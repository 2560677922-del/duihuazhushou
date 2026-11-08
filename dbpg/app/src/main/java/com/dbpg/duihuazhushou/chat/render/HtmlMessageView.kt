package com.dbpg.duihuazhushou.chat.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.appearance.ThemePalette
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 消息正文的 WebView 渲染器（ST 一档）。
 *
 * ★ 为什么改回 WebView
 *   上一版走的是「原生 TextView / 原生折叠块」，只认得 `<details>` 一种写法。
 *   真实预设的正则是千奇百怪的：`<div class="card">`、`<table>`、内联 `<style>`、
 *   自定义 class —— 原生侧永远追不完，追一个漏一个，看起来就是"渲染没上去"。
 *   渲染这件事只有 WebView 能做对（Tavo / Wivern / SillyClient 全是这条路）。
 *
 * ★ 为什么不是每条消息一个 WebView 的朴素写法
 *   WebView 实例很贵（每个十几 MB + 独立 JS 上下文），RecyclerView 一屏
 *   七八个就是内存爆点，滚动还要重新量高度。所以这里做了三件事：
 *     1. 只有「含标记的消息」才创建（纯文本仍走 TextView，绝大多数消息是这种）
 *     2. 池化：由 Adapter 限制这类 ViewHolder 的回收池大小（最多留 2 个）
 *     3. 页面壳只加载一次，换内容走 evaluateJavascript，不重建文档
 *
 * ★ 高度
 *   WebView 不支持 wrap_content，必须由页面回传真实高度（render.js 里
 *   用 ResizeObserver + toggle 监听），原生据此设置 layoutParams.height。
 *   在第一次回传之前用估算高度占位，避免"先塌成 0 再撑开"的跳动。
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class HtmlMessageView(context: Context) : WebView(context) {

    /** 页面回传的内容高度（物理 px）。 */
    fun interface HeightListener {
        fun onContentHeight(px: Int)
    }

    var heightListener: HeightListener? = null

    /** CSS 变量（跟外观设置走；变了要重新 theme + render）。 */
    data class Css(
        val fg: String,
        val accent: String,
        val soft: String,
        val line: String,
        val code: String,
        val size: Float,
        val lh: Float,
        val font: String,
    ) {
        fun stable(): String = "$fg|$accent|$size|$lh|$font"

        fun json(): JSONObject = JSONObject()
            .put("fg", fg)
            .put("accent", accent)
            .put("soft", soft)
            .put("line", line)
            .put("code", code)
            .put("size", size.toDouble())
            .put("lh", lh.toDouble())
            .put("font", font)
    }

    companion object {
        private const val TAG = "DHP_HTML"
        private const val SHELL = "file:///android_asset/msg/render.html"

        /**
         * 这些实例必须在 Activity 销毁时 destroy()：
         * WebView 持有 Activity 的 context，不销毁就是整页泄漏
         * （RecyclerView 的回收池不负责这件事）。
         */
        private val live = mutableListOf<WeakReference<HtmlMessageView>>()

        fun releaseAll() {
            val snapshot = live.toList()
            live.clear()
            for (ref in snapshot) {
                val v = ref.get() ?: continue
                runCatching {
                    v.heightListener = null
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.destroy()
                }
            }
        }

        /**
         * 由外观配置算出 CSS 变量。
         *
         * @param fontScale 系统字体缩放（`scaledDensity / density`）。
         *   TextView 用的是 sp，会跟着系统"字体大小"设置放大；WebView 的 CSS px
         *   恒等于 dp，不会。不乘这个系数的话，用户把系统字体调大之后，
         *   纯文本消息变大、富文本消息不变 —— 一屏两种字号。
         *   （`textZoom` 已经锁 100，所以这里自己算，避免被二次缩放。）
         */
        fun cssOf(cfg: AppearanceConfig, fontScale: Float = 1f): Css {
            val fg = ThemePalette.fontColorOn(cfg.fontColor, cfg.bgBaseColor)
            val accent = ThemePalette.accent(cfg)
            val scale = fontScale.coerceIn(0.8f, 1.6f)
            return Css(
                fg = hex(fg),
                accent = hex(accent),
                // 卡片底/描边/代码底都从正文色派生：
                // 用户换了深色气泡或浅色文字，这些都是自适应，不用再单独配一套
                soft = rgba(fg, 0.06f),
                line = rgba(fg, 0.18f),
                code = rgba(fg, 0.07f),
                size = cfg.textSizeSp * scale,
                lh = cfg.lineSpacing,
                font = ThemePalette.fontCssFamily(cfg.fontFamily),
            )
        }

        private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

        private fun rgba(color: Int, alpha: Float): String =
            "rgba(${Color.red(color)},${Color.green(color)},${Color.blue(color)},${"%.3f".format(alpha)})"
    }

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var destroyed = false
    private var pending: Pair<String, Css>? = null
    private var lastKey = ""
    private var downY = 0f
    private var downX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        live.removeAll { it.get() == null }
        live.add(WeakReference(this))

        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        settings.apply {
            javaScriptEnabled = true          // marked + 高度回传，必需
            domStorageEnabled = false
            allowFileAccess = true            // 只为读 assets/msg/render.html
            allowContentAccess = false
            // 消息是模型输出，不允许它再去读别的本地文件
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = false         // 角色卡可能引用图床图片
            loadsImagesAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100                    // 字号由 CSS 控制，别叠系统缩放
        }

        addJavascriptInterface(Bridge(), "DHPBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (destroyed || ready) return
                ready = true
                pending?.let { (html, css) -> apply(css, html) }
                pending = null
            }
        }

        loadUrl(SHELL)
    }

    /**
     * 换一条消息的内容。
     *
     * @param html 跑完正则/变量替换的富 HTML（Markdown 未转，交给 marked）
     * @param css  外观变量；与上次相同且内容未变时直接返回，避免无谓重排
     */
    fun bind(html: String, css: Css) {
        if (destroyed) return
        // 用「长度 + 哈希」做内容指纹，不把整段 HTML 留在字段里：
        // 一条消息动辄几万字，几十条就是几 MB 的常驻字符串
        val key = css.stable() + '\u0001' + html.length + ':' + html.hashCode()
        if (key == lastKey && ready) return
        lastKey = key
        if (!ready) {
            pending = html to css
            return
        }
        apply(css, html)
    }

    /** 恢复到"空壳"状态：ViewHolder 被回收时调用，防旧内容闪到新消息上。 */
    fun reset() {
        if (destroyed || !ready) return
        lastKey = ""
        evaluateJavascript("DHP.clear();", null)
    }

    private fun apply(css: Css, html: String) {
        val js = "DHP.theme(${css.json()});DHP.render(${JSONObject.quote(html)});"
        evaluateJavascript(js, null)
    }

    /**
     * 纵向滑动交还给父列表。
     *
     * WebView 一旦判定为滚动就会 requestDisallowInterceptTouchEvent(true)，
     * 父 RecyclerView 再也拦不到 —— 表现是"手指划在消息上，整页不动"。
     * 这里在明显纵向拖动时主动放弃，点击（<details> 展开）不受影响。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = abs(event.y - downY)
                val dx = abs(event.x - downX)
                if (dy > touchSlop && dy > dx) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun destroy() {
        destroyed = true
        ready = false
        heightListener = null
        runCatching { super.destroy() }
    }

    private inner class Bridge {
        /** render.js 回传内容高度（物理 px）。JS 线程 → 主线程。 */
        @JavascriptInterface
        fun height(px: Int) {
            if (destroyed) return
            main.post {
                if (!destroyed) heightListener?.onContentHeight(px)
            }
        }

        /** 页面内脚本报错时留个日志，方便排查是哪张卡的问题。 */
        @JavascriptInterface
        fun warn(msg: String?) {
            Log.w(TAG, "页面告警: $msg")
        }
    }
}
