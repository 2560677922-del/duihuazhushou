package com.dbpg.duihuazhushou.chat

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.appearance.BubbleStyle
import com.dbpg.duihuazhushou.appearance.ThemePalette
import com.dbpg.duihuazhushou.chat.render.CardBitmaps
import com.dbpg.duihuazhushou.richtext.RichTextRenderer
import com.dbpg.stcore.richtext.RichTextParser

/**
 * 聊天列表适配器（RecyclerView）。
 *
 * 只做渲染 + 把点击回调出去：文本 → RichTextRenderer（Spannable），
 * 图片 → 本地路径解码，版本条/操作入口 → 回调给 Activity。
 * 不碰网络、不碰会话逻辑，数据全部来自 [ChatItem]。
 *
 * 顺滑三要点：
 *   1. ListAdapter + DiffUtil：只更新变化的条目，流式刷新不整列表重绘
 *   2. ViewHolder 复用：气泡背景每次重建（避免共享 Drawable 被染色）
 *   3. 文本宽度用 TextView.maxWidth 封顶（LinearLayout 的 maxWidth 不生效）
 */
class ChatAdapter(
    private var config: AppearanceConfig,
    private val screenWidthPx: Int,
    private val listener: Listener,
) : ListAdapter<ChatItem, ChatAdapter.VH>(DIFF) {

    /** 一张/一组配图被点击 */
    interface Listener {
        fun onImageClick(item: ChatItem, path: String)
        fun onVariantSwitch(item: ChatItem, delta: Int)
        fun onActionMenu(item: ChatItem, anchor: View)
        /** 单条消息生图：把这条 + 前 N 条当上下文出图，挂回这条消息 */
        fun onImageGen(item: ChatItem)
        /**
         * 这条是卡片但位图还没生成：请界面触发一次离屏渲染。
         *
         * 之所以由界面转给 controller，而不是 Adapter 自己调渲染器：
         * Adapter 只该"画"，渲染结果的落库/刷新要走 ChatController 的数据链。
         */
        fun onCardNeeded(item: ChatItem, widthPx: Int)
        /** 点卡片：打开全屏查看器（可选中文字、可跑脚本） */
        fun onCardOpen(item: ChatItem)

        /**
         * 点 AI 气泡：从这一条分支出新会话（复制到这条含）。
         *
         * 入口做成"点气泡本体"而不是埋在 ⋯ 菜单里，是因为这是高频操作：
         * 想走另一条线时，点一下那句话就该开新线，而不是先长按再选菜单。
         */
        fun onBranchFrom(item: ChatItem)
    }

    /** 气泡最大宽度：按屏宽百分比算，改设置不用重建 Adapter。 */
    private val bubbleMaxWidthPx: Int
        get() = (screenWidthPx * config.bubbleWidthPercent / 100).coerceAtLeast(screenWidthPx / 3)

    /**
     * 卡片渲染宽度 = 气泡宽度 - 左右 padding（10dp × 2）。
     * 必须和气泡内可用宽度一致，否则卡片里的表格/图片会溢出或留白。
     */
    private val bubbleContentWidthPx: Int
        get() = (bubbleMaxWidthPx - dpOf(20f)).coerceAtLeast(screenWidthPx / 3)

    private fun dpOf(v: Float): Int = (v * density).toInt()

    private val density: Float get() = android.content.res.Resources.getSystem().displayMetrics.density

    /** 从设置页回来时调用：立刻套用新的字号/行距/宽度。 */
    fun updateConfig(cfg: AppearanceConfig) {
        config = cfg
        notifyDataSetChanged()
    }

    /**
     * 流式局部刷新：只改这一条的文字。
     *
     * 不走整表 submitList 之外的路径，是因为 ListAdapter 的内部列表不可变；
     * 但 DiffUtil 会算出"只有一条变了"，只会 rebind 那一个 holder ——
     * 比"整个会话重新渲染 + 全表 rebind"便宜两个数量级。
     */
    fun patchStreaming(actionId: String, text: String) {
        if (actionId.isEmpty()) return
        val cur = currentList
        val i = cur.indexOfFirst { it.actionId == actionId }
        if (i < 0) return
        if (cur[i].content == text && cur[i].streaming) return
        val next = ArrayList(cur)
        next[i] = cur[i].copy(content = text, streaming = true)
        submitList(next)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem == newItem
        }
        private const val COLOR_USER = 0xFFDCE9FF.toInt()
        private const val COLOR_AI = 0xFFE8EEF8.toInt()
        private const val COLOR_SYS = 0xFFEEF1F6.toInt()
        private const val COLOR_DIM = 0xFF7F8DA6.toInt()
    }

    class VH(
        root: FrameLayout,
        val bubble: LinearLayout,
        val text: TextView,
        val card: ImageView,
        val images: LinearLayout,
        val meta: LinearLayout,
        val verOlder: TextView,
        val verNo: TextView,
        val verNewer: TextView,
        val more: TextView,
        val imgGen: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        /*
         * 气泡整棵子树退出全局染色（UiBackdrop 见到这个 tag 只套字体）。
         *
         * 气泡底色/文字色由本 Adapter 按 BubbleStyle 与「字体颜色」算，
         * 被全局规则再映射一次会把用户选的"酒红/纯白"改回默认墨黑。
         * 聊天页的顶栏、输入栏、菜单不在这棵树里，照常跟随底板设置。
         */
        v.tag = "dhp:keep-color"
        return VH(
            v as FrameLayout,
            v.findViewById(R.id.bubble),
            v.findViewById(R.id.msg_text),
            v.findViewById(R.id.msg_card),
            v.findViewById(R.id.msg_images),
            v.findViewById(R.id.msg_meta),
            v.findViewById(R.id.msg_ver_older),
            v.findViewById(R.id.msg_ver_no),
            v.findViewById(R.id.msg_ver_newer),
            v.findViewById(R.id.msg_more),
            v.findViewById(R.id.msg_img_gen),
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, getItem(position))
    }

    private fun bind(holder: VH, item: ChatItem) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val isUser = item.role == "user"
        val isSystem = item.role == "system"

        // 1) 对齐 + 气泡配色
        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when {
            isUser -> Gravity.END
            isSystem -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        holder.bubble.layoutParams = lp
        // 气泡款式（圆润/柔和/方角/极简）：圆角、描边、配色、投影都跟着款式走。
        // 每次 bind 重建 Drawable —— 共享实例会被 RecyclerView 复用染色（经典坑）。
        val style = BubbleStyle.byId(config.bubbleStyle)
        holder.bubble.background = bubbleBackground(style, isUser, isSystem, density)
        holder.bubble.elevation = if (style.elevation && !isSystem) 1.2f * density else 0f

        /*
         * 2) 卡片：显示离屏渲染好的位图。
         *
         * 位图还没生成时**先用纯文本顶上**，同时异步请求渲染 —— 不能为了等图
         * 留个空白气泡（用户会以为消息丢了）。渲染完成后 controller 会刷新
         * 这一条，位图"接替"文字，高度按已知值占位，不会跳。
         */
        val cardPath = item.cardPath?.takeIf { java.io.File(it).exists() }
        val wantCard = item.html != null && config.htmlRender && !item.streaming
        val showCard = wantCard && cardPath != null
        if (showCard) {
            holder.text.visibility = View.GONE
            holder.card.visibility = View.VISIBLE
            val h = if (item.cardHeightPx > 0) item.cardHeightPx
            else FrameLayout.LayoutParams.WRAP_CONTENT
            holder.card.layoutParams = (holder.card.layoutParams).also { it.height = h }
            CardBitmaps.load(holder.card, cardPath!!, bubbleContentWidthPx)
            holder.card.setOnClickListener { listener.onCardOpen(item) }
        } else {
            holder.card.visibility = View.GONE
            holder.card.setImageDrawable(null)
            holder.text.visibility = View.VISIBLE
            // 卡片型内容但位图未就绪：先显示纯文本 + 请求渲染
            if (wantCard) listener.onCardNeeded(item, bubbleContentWidthPx)
        }

        // 3) 文本：maxWidth 封顶 + Spannable 渲染（字号/行距/字体/颜色跟随外观设置）
        holder.text.maxWidth = bubbleMaxWidthPx
        holder.text.typeface = ThemePalette.fontTypeface(config.fontFamily)
        holder.text.setTextColor(ThemePalette.fontColorInt(config))
        holder.text.textSize = config.textSizeSp
        holder.text.setLineSpacing(0f, config.lineSpacing)
        val content = if (item.streaming) item.content + "▍" else item.content
        if (showCard) {
            // 已由位图渲染，TextView 留空不占位
            holder.text.text = ""
        } else if (content.isBlank()) {
            holder.text.visibility = View.GONE
        } else {
            holder.text.visibility = View.VISIBLE
            RichTextRenderer.applyTo(holder.text, RichTextParser.parse(content))
        }

        // 3) 图片：复用前先清空，再按本地路径加载（LruCache 命中秒开）。
        //    文件已被删掉的（图库/查看器里删过）直接跳过 —— 否则留个空壳
        //    ImageView，看着像"缩略图还在"，点开又是"图片不存在"。
        holder.images.removeAllViews()
        val livePaths = item.imagePaths.filter { java.io.File(it).exists() }
        if (livePaths.isEmpty()) {
            holder.images.visibility = View.GONE
        } else {
            holder.images.visibility = View.VISIBLE
            for (path in livePaths) {
                val iv = ImageView(ctx)
                val lpp = LinearLayout.LayoutParams(bubbleMaxWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                lpp.topMargin = (4 * density).toInt()
                iv.layoutParams = lpp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.adjustViewBounds = true
                ImageLoader.load(path, bubbleMaxWidthPx)?.let { iv.setImageBitmap(it) }
                // 点图放大：以前这里没有监听，图片只能"看着"，点不动
                iv.setOnClickListener { listener.onImageClick(item, path) }
                holder.images.addView(iv)
            }
        }

        // 4) 版本条 / 操作入口
        val hasVariants = !isUser && item.variantCount > 1
        val canOperate = item.actionId.isNotEmpty() && !isSystem
        if (!hasVariants && !canOperate) {
            holder.meta.visibility = View.GONE
        } else {
            holder.meta.visibility = View.VISIBLE
            holder.verOlder.visibility = if (hasVariants) View.VISIBLE else View.GONE
            holder.verNewer.visibility = if (hasVariants) View.VISIBLE else View.GONE
            holder.verNo.visibility = if (hasVariants) View.VISIBLE else View.GONE
            if (hasVariants) {
                // 显示成「第 n / 共 m 版」，1 是最新
                holder.verNo.text = "${item.variantIndex + 1}/${item.variantCount}"
                holder.verOlder.alpha = if (item.variantIndex < item.variantCount - 1) 1f else 0.3f
                holder.verNewer.alpha = if (item.variantIndex > 0) 1f else 0.3f
                holder.verOlder.setOnClickListener {
                    if (item.variantIndex < item.variantCount - 1) listener.onVariantSwitch(item, +1)
                }
                holder.verNewer.setOnClickListener {
                    if (item.variantIndex > 0) listener.onVariantSwitch(item, -1)
                }
            }
            // 单条生图：会话消息都能给这条补图（挂回这条，不动别的）
            holder.imgGen.visibility = if (canOperate) View.VISIBLE else View.GONE
            holder.imgGen.setOnClickListener { listener.onImageGen(item) }
            holder.more.visibility = if (canOperate) View.VISIBLE else View.GONE
            holder.more.setTextColor(COLOR_DIM)
            holder.more.setOnClickListener { listener.onActionMenu(item, it) }
        }

        /*
         * 点 AI 气泡 = 从这条分支。
         *
         * 每次 bind 都要显式重设：ViewHolder 复用时旧的 listener 会捕获上一条
         * item，切到用户消息后忘了清，就会出现"点自己的发言也分支"。
         * 用户 / 系统 / 流式中 / 只读条目一律不绑。
         */
        val canBranch = !isUser && !isSystem && item.actionId.isNotEmpty() && !item.streaming
        val branchClick = if (canBranch) View.OnClickListener { listener.onBranchFrom(item) } else null
        holder.text.setOnClickListener(branchClick)
        holder.bubble.setOnClickListener(branchClick)
    }

    /**
     * 按款式生成气泡背景。
     *
     * 系统消息（分隔提示）永远用小圆角灰底，不参与款式选择——
     * 它不是"对话气泡"，跟着换色会显得很乱。
     *
     * 用户气泡在自定义主题色时改用「淡主题色」：整体配色跟着主题走；
     * 默认蓝时保持原款配色，视觉零变化。
     */
    private fun bubbleBackground(
        style: BubbleStyle,
        isUser: Boolean,
        isSystem: Boolean,
        density: Float,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = (if (isSystem) 10f else style.cornerDp) * density
        setColor(
            when {
                isSystem -> COLOR_SYS
                isUser -> paleUserColor(style.userColor)
                else -> style.aiColor
            },
        )
        if (style.stroke && !isSystem) {
            setStroke((1 * density).toInt(), 0xFFE4E9F2.toInt())
        }
    }

    /** 自定义主题色 → 用户气泡用淡主题色；默认蓝 → 原配色不动。 */
    private fun paleUserColor(fallback: Int): Int {
        val accentHex = config.accentColor
        if (accentHex.isBlank() || accentHex.equals("#2F66D8", ignoreCase = true)) return fallback
        val accent = com.dbpg.duihuazhushou.appearance.ThemePalette.parseHex(accentHex, "#2F66D8")
        // 18% 主题色压在白底上：淡而不脏，正文仍好读
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(accent) * 0.18 + 255 * 0.82).toInt(),
            (android.graphics.Color.green(accent) * 0.18 + 255 * 0.82).toInt(),
            (android.graphics.Color.blue(accent) * 0.18 + 255 * 0.82).toInt(),
        )
    }
}
