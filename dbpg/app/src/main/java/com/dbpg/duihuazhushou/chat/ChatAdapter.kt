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
    }

    /** 气泡最大宽度：按屏宽百分比算，改设置不用重建 Adapter。 */
    private val bubbleMaxWidthPx: Int
        get() = (screenWidthPx * config.bubbleWidthPercent / 100).coerceAtLeast(screenWidthPx / 3)

    /** 从设置页回来时调用：立刻套用新的字号/行距/宽度。 */
    fun updateConfig(cfg: AppearanceConfig) {
        config = cfg
        notifyDataSetChanged()
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
        val html: android.webkit.WebView,
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
        return VH(
            v as FrameLayout,
            v.findViewById(R.id.bubble),
            v.findViewById(R.id.msg_text),
            v.findViewById(R.id.msg_html),
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

        // 2) 美化 HTML：交给内嵌 WebView 渲染；普通消息仍用 TextView
        val richHtml = item.html
        if (richHtml != null) {
            holder.text.visibility = View.GONE
            holder.html.visibility = View.VISIBLE
            bindHtml(holder, richHtml, config)
        } else {
            holder.html.visibility = View.GONE
            holder.html.tag = null
            holder.text.visibility = View.VISIBLE
        }

        // 3) 文本：maxWidth 封顶 + Spannable 渲染（字号/行距跟随外观设置）
        holder.text.maxWidth = bubbleMaxWidthPx
        holder.text.setTextColor(0xFF1A2230.toInt())
        holder.text.textSize = config.textSizeSp
        holder.text.setLineSpacing(0f, config.lineSpacing)
        val content = if (item.streaming) item.content + "▍" else item.content
        if (richHtml != null) {
            // 已由 WebView 渲染，TextView 留空不占位
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
    }

    /**
     * 把美化 HTML 渲染到气泡里的 WebView。
     *
     * 安全边界：默认**剥离 <script> 与 on* 事件属性**再加载 —— 内容来自模型，
     * 不可信；只有用户在设置里显式打开「允许脚本」才执行。
     * JS 引擎本身保持开启：只为量内容高度（否则 WebView 高度算不出来）。
     */
    private fun bindHtml(holder: VH, html: String, cfg: com.dbpg.duihuazhushou.appearance.AppearanceConfig) {
        val web = holder.html
        if (web.tag == html + "|" + cfg.allowScript + "|" + cfg.textSizeSp) return
        web.tag = html + "|" + cfg.allowScript + "|" + cfg.textSizeSp

        val safe = if (cfg.allowScript) html else sanitizeHtml(html)
        val doc = wrapHtml(safe, cfg.textSizeSp, cfg.lineSpacing)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.loadsImagesAutomatically = true
        s.blockNetworkLoads = false
        s.setSupportZoom(false)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = true // 气泡里的链接不跳转，避免把聊天页变成浏览器

            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                // 量出真实内容高度，把 WebView 撑开（内部不再滚动，整页跟着列表滚）。
                //
                // 量的是包裹层 #dhp-m 的盒子高度，**不是** scrollHeight：
                // RecyclerView 回收复用时 WebView 还带着上一条的固定高度，
                // scrollHeight 至少要撑满"视口"（= 那个旧高度），短内容就量出
                // 一个大高度 —— 气泡下方一大片空白就是这么来的。
                // 包裹层高度只跟内容有关，回收多少次都准。
                //
                // 量两次：首次 paint 前 web font/图片还没占位，只量一次会把
                // 内容截断（实测：卡片下方的段落与提示条被裁掉）。
                fun measure() {
                    view.evaluateJavascript(
                        "(function(){var m=document.getElementById('dhp-m');" +
                            "if(!m)return Math.ceil(document.body.scrollHeight);" +
                            "return Math.ceil(m.getBoundingClientRect().height);})()",
                    ) { raw ->
                        // scrollHeight 是 CSS px；Android 布局要设备 px，必须乘 density
                        val cssPx = raw?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
                        val px = (cssPx * view.resources.displayMetrics.density).toInt()
                        if (px > 0) {
                            val lp = view.layoutParams
                            if (lp.height != px) {
                                lp.height = px
                                view.layoutParams = lp
                            }
                        }
                    }
                }
                measure()
                view.postDelayed({ measure() }, 320L)
            }
        }
        web.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
    }

    /** 剥脚本与事件属性（allowScript=false 时）。 */
    private fun sanitizeHtml(html: String): String {
        var out = html
        out = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE).replace(out, "")
        out = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE).replace(out, "")
        out = Regex("\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE).replace(out, "")
        return out
    }

    /** 统一包一层：视口、透明底、正文字号/行距跟随外观设置，图片不溢出。
     *  正文包在 #dhp-m 里 —— 量高度只量它，回收复用时不会被旧视口撑大。 */
    private fun wrapHtml(html: String, textSizeSp: Float, lineSpacing: Float): String = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:transparent;}
          body{color:#1A2230;font-size:${textSizeSp}px;line-height:${lineSpacing};
               word-break:break-word;overflow-wrap:anywhere;}
          img,video,canvas{max-width:100%;height:auto;}
          table{max-width:100%;border-collapse:collapse;}
          pre{white-space:pre-wrap;word-break:break-word;overflow-x:auto;}
          a{color:#2F66D8;}
        </style></head><body><div id="dhp-m">$html</div></body></html>
    """.trimIndent()

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
