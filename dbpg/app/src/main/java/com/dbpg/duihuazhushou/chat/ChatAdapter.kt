package com.dbpg.duihuazhushou.chat

import android.graphics.Typeface
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
import com.dbpg.duihuazhushou.chat.render.HtmlMessageView
import com.dbpg.duihuazhushou.richtext.RichTextRenderer
import com.dbpg.stcore.richtext.FoldParser
import com.dbpg.stcore.richtext.MsgBlock
import com.dbpg.stcore.richtext.RichStyle
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
        webHeights.clear()
        notifyDataSetChanged()
    }

    /**
     * 每条富文本消息上次量到的高度（px）。
     *
     * 为什么记在这里：WebView 复用后要重新 layout，如果不给初始高度，
     * 会先塌成 0 再撑开 —— 滚动时整页抖一下。按消息 id 记住就稳了。
     */
    private val webHeights = HashMap<String, Int>()

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
        /** 纯文本 / 富文本两种 item 类型：分开才能分别限制回收池，避免 WebView 被"传染"给纯文本消息 */
        const val TYPE_TEXT = 0
        const val TYPE_HTML = 1

        /** 富文本 ViewHolder 的回收池上限。屏幕上同时可见的富消息通常 ≤2，留 2 个够用。 */
        const val MAX_WEB_POOL = 2

        /** 渲染页超过这个时间还没回传高度，就退回纯文本（防白气泡）。 */
        private const val WEB_FALLBACK_MS = 3000L

        /** 交给 WebView 的 HTML 长度上限，超了退回纯文本。 */
        private const val MAX_WEB_HTML = 200_000

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
        val blocks: LinearLayout,
        val webHost: FrameLayout,
        val card: ImageView,
        val images: LinearLayout,
        val meta: LinearLayout,
        val verOlder: TextView,
        val verNo: TextView,
        val verNewer: TextView,
        val more: TextView,
        val imgGen: TextView,
        /** 只有 TYPE_HTML 的 ViewHolder 才有；其余为 null */
        var web: HtmlMessageView? = null,
        /** 当前绑定的消息 id：延迟回调要靠它判断"这个 holder 还是不是同一条" */
        var boundId: String = "",
    ) : RecyclerView.ViewHolder(root)

    /**
     * 池化：给「富文本」这一类单独设回收池上限。
     *
     * RecyclerView 默认每类缓存 5 个 ViewHolder —— 对纯文本无所谓，对
     * WebView 是灾难：每个实例十几 MB + 独立 JS 上下文，一屏滚过去能攒出
     * 十几个。上限收到 2（屏幕上同时可见的富消息通常 ≤2），超出即销毁。
     *
     * 懒加载不用另做：onBindViewHolder 只在条目进入可见区（或 GapWorker
     * 预取即将可见的）时才调用，WebView 在那一刻才收到内容。
     */
    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        rv.recycledViewPool.setMaxRecycledViews(TYPE_HTML, MAX_WEB_POOL)
    }

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
        val holder = VH(
            v as FrameLayout,
            v.findViewById(R.id.bubble),
            v.findViewById(R.id.msg_text),
            v.findViewById(R.id.msg_blocks),
            v.findViewById(R.id.msg_web),
            v.findViewById(R.id.msg_card),
            v.findViewById(R.id.msg_images),
            v.findViewById(R.id.msg_meta),
            v.findViewById(R.id.msg_ver_older),
            v.findViewById(R.id.msg_ver_no),
            v.findViewById(R.id.msg_ver_newer),
            v.findViewById(R.id.msg_more),
            v.findViewById(R.id.msg_img_gen),
        )
        if (viewType == TYPE_HTML) holder.web = createWeb(holder)
        return holder
    }

    /**
     * 给一个 ViewHolder 造 WebView。
     *
     * 高度必须显式给：WebView 不支持 wrap_content，等页面回传真实高度
     * 之前的占位高度先用「上次量到的值」（[webHeights]），没有再估算 ——
     * 否则会出现"先塌成一条线、再猛地撑开"的跳动。
     */
    private fun createWeb(holder: VH): HtmlMessageView {
        val web = HtmlMessageView(holder.itemView.context)
        holder.webHost.addView(
            web,
            FrameLayout.LayoutParams(
                bubbleContentWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        web.heightListener = null
        return web
    }

    override fun getItemViewType(position: Int): Int =
        if (useWeb(getItem(position))) TYPE_HTML else TYPE_TEXT

    /** 只有「含标记 + 已生成完 + 开了富渲染」的消息才值得用 WebView。 */
    private fun useWeb(item: ChatItem): Boolean {
        val html = item.html ?: return false
        if (!config.htmlRender || item.streaming) return false
        /*
         * 超长消息（> 20 万字符）退回纯文本：内容要经 evaluateJavascript
         * 整段送进 WebView，再走 marked 解析，十万字级别的消息会把主线程按住
         * 好几百毫秒。这类消息通常是导入的旧日志，用纯文本读反而更快。
         */
        return html.length <= MAX_WEB_HTML
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // 不清空的话，滚动时旧内容会先闪一下再到新内容（同一个 WebView 复用）
        holder.web?.reset()
        holder.webHost.visibility = View.GONE
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, getItem(position))
    }

    /**
     * 把一条富文本消息交给 WebView。
     *
     * 宽度每次 bind 都对一遍（用户可能刚在设置里改了气泡宽度），
     * 高度先用上次量到的值占位，页面加载完会用真实高度覆盖。
     */
    private fun bindWeb(holder: VH, web: HtmlMessageView, item: ChatItem) {
        val w = bubbleContentWidthPx
        /*
         * 容器宽度要显式给：XML 里是 match_parent，而气泡是 wrap_content，
         * 子 View 取 match_parent 会一路取到"屏幕可用宽度"，气泡就被撑满了。
         */
        (web.parent as? View)?.let { host ->
            val hlp = host.layoutParams
            if (hlp != null && hlp.width != w) {
                hlp.width = w
                host.layoutParams = hlp
            }
        }
        // 高度按「消息 + 版本」记：切版本时内容完全不同，高度当然也不同
        val hKey = item.id + "@" + item.variantIndex
        val known = webHeights[hKey] ?: item.cardHeightPx
        val h = if (known > 0) known else estimateWebHeight(item.content, w)
        val lp = web.layoutParams
        if (lp == null || lp.width != w || lp.height != h) {
            web.layoutParams = FrameLayout.LayoutParams(w, h)
        }
        web.heightListener = HtmlMessageView.HeightListener { px ->
            webHeights[hKey] = px
            val cur = web.layoutParams
            if (cur != null && cur.height != px) {
                cur.height = px
                web.layoutParams = cur
            }
        }
        val html = item.html ?: return
        web.bind(html, HtmlMessageView.cssOf(config, fontScaleOf(web)))

        /*
         * 兜底：3 秒还没回传任何高度，说明 assets 里的渲染页没跑起来
         * （文件缺失 / WebView 被系统禁用 / 厂商 ROM 阉割）。
         * 这时退回纯文本 —— 宁可难看，也不能给用户留一个白气泡。
         */
        web.postDelayed({
            if (holder.web !== web || holder.boundId != item.id) return@postDelayed
            if ((webHeights[hKey] ?: 0) > 0) return@postDelayed
            holder.webHost.visibility = View.GONE
            holder.text.visibility = View.VISIBLE
            styleText(holder.text)
            RichTextRenderer.applyTo(holder.text, RichTextParser.parse(item.content))
        }, WEB_FALLBACK_MS)
    }

    /** 系统字体缩放：TextView 用 sp 会跟着变，WebView 的 CSS px 不会，要手动对齐。 */
    private fun fontScaleOf(view: View): Float {
        val dm = view.resources.displayMetrics
        return if (dm.density > 0f) dm.scaledDensity / dm.density else 1f
    }

    /**
     * 首帧高度估算（只在"从没量过这条"时用）。
     *
     * 中文按 0.62em 估字宽：比精确排版偏大一点没关系 —— 宁可第一帧多留
     * 一点白，也不要先塌后撑（那个更显眼）。
     */
    private fun estimateWebHeight(plain: String, widthPx: Int): Int {
        val lineH = (config.textSizeSp * density * config.lineSpacing).coerceAtLeast(1f)
        val perLine = (widthPx / (config.textSizeSp * density * 0.62f)).coerceAtLeast(6f)
        val lines = plain.length / perLine + plain.count { it == '\n' } + 1
        return (lines * lineH).toInt().coerceIn(lineH.toInt(), 30000)
    }

    private fun bind(holder: VH, item: ChatItem) {
        holder.boundId = item.id
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
         * 2) 富文本：WebView 真渲染。
         *
         * 判定只有一条：这条消息「含标记」且不是流式中间态。CSS、Markdown、
         * <details>、表格、内联 <style> 全部交给 WebView —— 预设的正则写法
         * 千奇百怪（div/table/summary/自定义 class），原生侧不可能逐种适配，
         * 这也是 Tavo / Wivern / SillyClient 走的同一条路。
         *
         * 位图卡片与原生折叠块保留为降级路径：WebView 不可用、或这条没有
         * ViewHolder（理论上不会）时还能把内容读出来。
         */
        val wantWeb = useWeb(item)
        val web = holder.web
        if (wantWeb && web != null) {
            holder.card.visibility = View.GONE
            holder.card.setImageDrawable(null)
            holder.blocks.visibility = View.GONE
            holder.blocks.removeAllViews()
            holder.text.visibility = View.GONE
            holder.text.text = ""
            holder.webHost.visibility = View.VISIBLE
            bindWeb(holder, web, item)
        } else {
            holder.webHost.visibility = View.GONE
            web?.reset()

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

            // 3) 文本：含 <details> 时按块渲染，其余走单个 TextView
            styleText(holder.text)
            val fold = item.foldHtml
            val useBlocks = fold != null && !item.streaming && !showCard
            if (useBlocks) {
                holder.text.text = ""
                holder.text.visibility = View.GONE
                holder.blocks.visibility = View.VISIBLE
                renderFoldBlocks(holder.itemView.context, holder.blocks, item, fold!!)
            } else {
                holder.blocks.visibility = View.GONE
                holder.blocks.removeAllViews()
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
            }
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
    }

    /**
     * 正文 TextView 的统一排版（字号/行距/字体/颜色/最大宽度）。
     * 折叠块里的每段文字也走这一套，否则同一气泡里会出现两种字号。
     */
    private fun styleText(tv: TextView) {
        tv.maxWidth = bubbleMaxWidthPx
        tv.typeface = ThemePalette.fontTypeface(config.fontFamily)
        tv.setTextColor(ThemePalette.fontColorInt(config))
        tv.textSize = config.textSizeSp
        tv.setLineSpacing(0f, config.lineSpacing)
    }

    /**
     * 折叠栏展开状态：key = "itemId#块序号"。
     *
     * 存在 Adapter 而不是 View 上 —— RecyclerView 复用会让"展开的第 3 条"
     * 跑到别的消息上去，必须按消息 id 记住。
     */
    private val foldState = HashMap<String, Boolean>()

    /**
     * 按块渲染：正文段落用 TextView，`<details>` 用可点击的折叠卡片。
     *
     * 卡片宽度跟随内容（wrap），但正文 TextView 有 maxWidth 封顶，
     * 所以长段落不会被撑出气泡。
     */
    private fun renderFoldBlocks(
        ctx: android.content.Context,
        box: LinearLayout,
        item: ChatItem,
        html: String,
    ) {
        box.removeAllViews()
        for ((i, b) in FoldParser.split(html).withIndex()) {
            when (b) {
                is MsgBlock.Text -> {
                    val tv = TextView(ctx)
                    styleText(tv)
                    RichTextRenderer.applyTo(tv, RichTextParser.parse(b.html))
                    box.addView(tv)
                }
                is MsgBlock.Fold -> box.addView(foldCard(ctx, item, i, b))
            }
        }
    }

    /**
     * 折叠卡片：左侧主题色竖条 + 标题 + 箭头，点击展开正文。
     *
     * 颜色全部从主题 accent 派生（6% 底 / 20% 描边 / 纯色竖条），
     * 换主题时不会出现"气泡变了、折叠栏还是蓝色"的错位。
     */
    private fun foldCard(
        ctx: android.content.Context,
        item: ChatItem,
        index: Int,
        block: MsgBlock.Fold,
    ): View {
        val accent = ThemePalette.parseHex(config.accentColor, "#2F66D8")

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpOf(12f).toFloat()
                setColor(mixWithWhite(accent, 0.06f))
                setStroke(dpOf(1f).coerceAtLeast(1), mixWithWhite(accent, 0.22f))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpOf(6f)
                bottomMargin = dpOf(4f)
            }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpOf(12f), dpOf(9f), dpOf(10f), dpOf(9f))
            isClickable = true
        }
        head.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpOf(2f).toFloat()
                setColor(accent)
            }
        }, LinearLayout.LayoutParams(dpOf(3f), dpOf(14f)).apply { marginEnd = dpOf(8f) })

        val title = TextView(ctx).apply {
            // 标题里的反引号（`标题`）在 ST 里会变成行内代码底色；折叠卡片本身
            // 已经是淡色底，再叠一层会脏。标题只保留粗体/斜体，去掉 code 底。
            val richTitle = RichTextParser.parse(block.title)
            text = RichTextRenderer.toSpannable(
                richTitle.copy(spans = richTitle.spans.filter { it.style != RichStyle.CODE }),
            )
            setTextColor(accent)
            textSize = (config.textSizeSp - 2f).coerceAtLeast(12f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val arrow = TextView(ctx).apply {
            text = "▾"
            setTextColor(mixWithWhite(accent, 0.72f))
            textSize = 13f
            setPadding(dpOf(6f), 0, 0, 0)
        }
        head.addView(arrow)
        card.addView(head)

        val body = TextView(ctx).apply {
            setPadding(dpOf(14f), 0, dpOf(12f), dpOf(10f))
        }
        styleText(body)
        RichTextRenderer.applyTo(body, RichTextParser.parse(block.body))
        card.addView(body)

        val key = "${item.id}#$index"
        var open = foldState[key] ?: block.open
        fun applyState(animate: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            if (animate) {
                arrow.animate().rotation(if (open) 0f else -90f).setDuration(160).start()
            } else {
                arrow.rotation = if (open) 0f else -90f
            }
        }
        head.setOnClickListener {
            open = !open
            foldState[key] = open
            applyState(true)
        }
        applyState(false)
        return card
    }

    /** 与白色按比例混合：ratio 越小越淡，避免半透明叠在气泡上出现脏色。 */
    private fun mixWithWhite(color: Int, ratio: Float): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(color) * ratio + 255 * (1 - ratio)).toInt(),
        (android.graphics.Color.green(color) * ratio + 255 * (1 - ratio)).toInt(),
        (android.graphics.Color.blue(color) * ratio + 255 * (1 - ratio)).toInt(),
    )

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
