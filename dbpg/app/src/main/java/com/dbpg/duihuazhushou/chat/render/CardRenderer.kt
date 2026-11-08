package com.dbpg.duihuazhushou.chat.render

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.appearance.ThemePalette
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

/**
 * 卡片渲染器：把一条"美化 HTML"画成位图，列表里只放 ImageView。
 *
 * ★ 为什么不再用"每条消息一个 WebView"：
 *   列表每个 item 都 inflate 一个 WebView，同屏 10 个 ViewHolder 就是 10 个
 *   独立渲染上下文（每个 15~40MB），滚动时还要重新 loadData + 量高度。
 *   实测这是聊天页掉帧和内存飙高的头号原因。
 *
 * 做法：全局只留 **1 个** 离屏 WebView，串行把待渲染的卡片画成位图落盘；
 * 列表侧显示位图。代价是卡片内文字不能直接选中 —— 长按菜单提供
 * 「复制原文」和「查看卡片」（全屏单例 WebView）来解决。
 *
 * ★ 为什么要挂到窗口：部分机型对未 attach 的 WebView 不做 layout，
 *   draw 出来是白图。这里复刻引擎 WebView 的做法，挂 1x1 到 decor 上，
 *   再把 WebView 移到屏幕外（translationX），既参与布局又不干扰视觉/触摸。
 */
object CardRenderer {

    private const val TAG = "DHP_CARD"
    private const val RENDER_TIMEOUT = 6000L

    /** 渲染参数（跟外观设置走，改了要重新生成位图）。 */
    data class Style(
        val textSizeSp: Float,
        val lineSpacing: Float,
        val fontFamily: String,
        val fontColor: String,
        val bgBaseColor: String,
        val allowScript: Boolean,
    ) {
        companion object {
            fun of(cfg: AppearanceConfig) = Style(
                textSizeSp = cfg.textSizeSp,
                lineSpacing = cfg.lineSpacing,
                fontFamily = cfg.fontFamily,
                fontColor = cfg.fontColor,
                bgBaseColor = cfg.bgBaseColor,
                allowScript = cfg.allowScript,
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var host: FrameLayout? = null
    private var owner: Activity? = null
    private var busy = false

    private class Job(
        val key: String,
        val html: String,
        val widthPx: Int,
        val style: Style,
        val onDone: (path: String?, heightPx: Int) -> Unit,
    )

    private val queue = ArrayDeque<Job>()
    private val queuedKeys = HashSet<String>()

    /** 在 Activity.onResume 调用：确保离屏 WebView 挂在当前窗口上。 */
    fun attach(activity: Activity) {
        if (activity.isFinishing) return
        if (owner === activity && web != null) return
        releaseWeb()

        val decor = activity.window?.decorView as? ViewGroup ?: return
        val host = FrameLayout(activity).apply {
            tag = "dhp:cardhost"
            clipChildren = false
            clipToPadding = false
        }
        decor.addView(host, FrameLayout.LayoutParams(1, 1))

        val w = WebView(activity).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true      // 只为量高度
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = false
            settings.loadsImagesAutomatically = true
            settings.setSupportZoom(false)
        }
        host.addView(w, FrameLayout.LayoutParams(1, 1))
        // 挪到屏幕外：仍然参与 layout、仍然绘制，但用户看不见也点不到
        w.translationX = -30000f

        this.host = host
        this.web = w
        this.owner = activity
    }

    /** 在 Activity.onDestroy 调用：WebView 属于这个 Activity 的 context，必须销毁。 */
    fun detach(activity: Activity) {
        if (owner !== activity) return
        releaseWeb()
    }

    private fun releaseWeb() {
        queue.clear()
        queuedKeys.clear()
        busy = false
        main.removeCallbacksAndMessages(null)
        host?.let { h -> runCatching { (h.parent as? ViewGroup)?.removeView(h) } }
        runCatching { web?.destroy() }
        web = null
        host = null
        owner = null
    }

    /**
     * 请求渲染一条卡片。
     *
     * @param key 缓存键（一般用 RenderKey.stable() 的哈希），相同 key 只渲染一次
     * @param widthPx 气泡内容宽度（CSS px = 设备 px / density）
     * @param onDone 主线程回调：位图路径 + 高度；失败为 (null, 0)
     */
    fun render(
        context: Context,
        key: String,
        html: String,
        widthPx: Int,
        style: Style,
        onDone: (String?, Int) -> Unit,
    ) {
        val cached = CardStore.existing(context, key)
        if (cached != null) {
            onDone(cached.first, cached.second)
            return
        }
        if (key in queuedKeys) {
            // 同一条已经在排队：不重复排，等它画完界面会自己刷新
            return
        }
        queuedKeys.add(key)
        queue.addLast(Job(key, html, widthPx, style, onDone))
        pump()
    }

    private fun pump() {
        if (busy) return
        val job = queue.pollFirst() ?: return
        busy = true
        val w = web
        if (w == null) {
            // 没有宿主（用户还没进聊天页）：直接失败，界面先显示纯文本
            finish(job, null, 0)
            return
        }

        val lp = w.layoutParams
        lp.width = job.widthPx
        lp.height = 10
        w.layoutParams = lp
        w.measure(
            View.MeasureSpec.makeMeasureSpec(job.widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.AT_MOST),
        )
        w.layout(0, 0, job.widthPx, 10)

        val density = w.resources.displayMetrics.density
        var done = false

        val timeout = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "渲染超时，降级为纯文本: ${job.key.take(12)}")
                finish(job, null, 0)
            }
        }
        main.postDelayed(timeout, RENDER_TIMEOUT)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                // 图片/字体可能还没占位，量两次（和原 bindHtml 的教训一致）
                view.postDelayed({
                    measureAndCapture(view, job, density) { path, h ->
                        if (done) return@measureAndCapture
                        done = true
                        main.removeCallbacks(timeout)
                        finish(job, path, h)
                    }
                }, 180L)
            }
        }

        val safe = if (job.style.allowScript) job.html else sanitize(job.html)
        w.loadDataWithBaseURL(null, wrap(safe, job.style), "text/html", "utf-8", null)
    }

    private fun measureAndCapture(
        view: WebView,
        job: Job,
        density: Float,
        onResult: (String?, Int) -> Unit,
    ) {
        view.evaluateJavascript(
            "(function(){var m=document.getElementById('dhp-m');" +
                "if(!m)return Math.ceil(document.body.scrollHeight);" +
                "return Math.ceil(m.getBoundingClientRect().height);})()",
        ) { raw ->
            val cssPx = raw?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
            val px = (cssPx * density).toInt()
            if (px <= 0) {
                onResult(null, 0)
                return@evaluateJavascript
            }
            val w = job.widthPx
            view.layoutParams = (view.layoutParams).apply { height = px }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, px)

            val path = runCatching {
                val bmp = Bitmap.createBitmap(w, px, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                // 透明底：气泡自己的颜色透出来，卡片只用画自己的内容
                view.draw(canvas)
                CardStore.save(view.context, job.key, bmp, px)
            }.getOrNull()
            if (path == null) onResult(null, 0) else onResult(path, px)
        }
    }

    private fun finish(job: Job, path: String?, heightPx: Int) {
        queuedKeys.remove(job.key)
        busy = false
        main.post {
            job.onDone(path, heightPx)
            pump()
        }
    }

    // ------------------------------------------------------------ HTML 包装

    private fun sanitize(html: String): String {
        var out = html
        out = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE).replace(out, "")
        out = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE).replace(out, "")
        out = Regex("\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE).replace(out, "")
        return out
    }

    private fun wrap(html: String, style: Style): String {
        val color = String.format(
            "#%06X",
            0xFFFFFF and ThemePalette.fontColorOn(style.fontColor, style.bgBaseColor),
        )
        return """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:transparent;}
              body{color:$color;font-family:${ThemePalette.fontCssFamily(style.fontFamily)};
                   font-size:${style.textSizeSp}px;line-height:${style.lineSpacing};
                   word-break:break-word;overflow-wrap:anywhere;}
              img,video,canvas{max-width:100%;height:auto;}
              table{max-width:100%;border-collapse:collapse;}
              pre{white-space:pre-wrap;word-break:break-word;overflow-x:auto;}
              a{color:#2F66D8;}
            </style></head><body><div id="dhp-m">$html</div></body></html>
        """.trimIndent()
    }
}

/**
 * 卡片位图磁盘缓存。
 *
 * 目录：files/data/cards/<key>.webp
 * 上限 128MB，超了按修改时间删最旧的。
 *
 * 和 RenderCache 一样：**都是可重建的派生物**，删了只会慢一次。
 */
object CardStore {

    private const val MAX_BYTES = 128L * 1024 * 1024

    private fun dir(ctx: Context): File =
        File(File(ctx.applicationContext.filesDir, "data"), "cards").apply { if (!exists()) mkdirs() }

    private fun fileOf(ctx: Context, key: String): File = File(dir(ctx), key.webFileName())

    /** key 里可能有 `:`/`|`，换成安全字符，避免文件名非法。 */
    private fun String.webFileName(): String {
        val sb = StringBuilder(length)
        for (c in this) sb.append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
        return sb.append(".webp").toString()
    }

    /** 已存在则直接返回（路径, 高度）；高度从文件名旁边的小 sidecar 读。 */
    fun existing(ctx: Context, key: String): Pair<String, Int>? {
        val f = fileOf(ctx, key)
        if (!f.exists() || f.length() == 0L) return null
        val h = heightSidecar(ctx, key).takeIf { it > 0 } ?: 0
        return f.absolutePath to h
    }

    fun save(ctx: Context, key: String, bmp: Bitmap, heightPx: Int): String? {
        val f = fileOf(ctx, key)
        val ok = runCatching {
            FileOutputStream(f).use { out ->
                // WEBP 有损压缩：卡片位图上文字边缘仍然清晰，体积比 PNG 小一个数量级
                bmp.compress(Bitmap.CompressFormat.WEBP, 92, out)
                out.flush()
            }
        }.isSuccess
        if (!ok) return null
        writeHeight(ctx, key, heightPx)
        trim(ctx)
        return f.absolutePath
    }

    /** 高度单独存一行小文件：避免为了拿高度去解码整张图。 */
    fun heightSidecar(ctx: Context, key: String): Int {
        val f = File(dir(ctx), key.webFileName() + ".h")
        return runCatching { f.readText().trim().toInt() }.getOrDefault(0)
    }

    fun writeHeight(ctx: Context, key: String, px: Int) {
        runCatching { File(dir(ctx), key.webFileName() + ".h").writeText(px.toString()) }
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun sizeBytes(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_BYTES) return
            total -= f.length()
            runCatching { f.delete() }
        }
    }
}

/**
 * 卡片位图的解码缓存。
 *
 * RecyclerView 滚动时同一条会反复 bind，每次都 decodeFile 会明显掉帧；
 * 这里按字节数做 LruCache（32MB，约 20~40 张卡片），并把解码放到后台线程。
 *
 * 复用安全：给 ImageView 打 tag=路径，异步回来时校验 tag 还是不是自己 ——
 * 否则快速滚动会把 A 的图贴到 B 的气泡上（经典错位）。
 */
object CardBitmaps {

    private val io = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dhp-card-bitmap").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val cache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(view: android.widget.ImageView, path: String, widthPx: Int) {
        cache.get(path)?.let {
            view.tag = path
            view.setImageBitmap(it)
            return
        }
        if (view.tag == path && view.drawable != null) return
        view.tag = path
        view.setImageDrawable(null)
        io.execute {
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(path)
            }.getOrNull()
            if (bmp == null) return@execute
            cache.put(path, bmp)
            main.post {
                if (view.tag == path) view.setImageBitmap(bmp)
            }
        }
    }

    fun clear() = cache.evictAll()
}
