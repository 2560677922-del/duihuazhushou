package com.dbpg.duihuazhushou

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dbpg.stcore.ContentBlock
import com.dbpg.stcore.MacroContext
import com.dbpg.stcore.NativeImage
import com.dbpg.stcore.RawScript
import com.dbpg.stcore.RegexEngineOptions
import com.dbpg.stcore.RegexPlacementFilter
import com.dbpg.stcore.Role
import com.dbpg.stcore.VariableTables
import com.dbpg.stcore.buildScript
import com.dbpg.stcore.transformForDisplay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生聊天界面（对应 tavo 那种"原生外壳 + 气泡内渲染"思路的落地版）。
 *
 * 为什么要有它：网页聊天区在老 Android WebView 上会因为 `overflow-x: clip`
 * 不被支持、加上美化正则注入的 HTML，出现"横向溢出 / 点不到输入框"的问题。
 * 这里整屏改用原生控件渲染会话，从根上绕开 WebView 的 CSS 兼容性坑。
 *
 * 内容变换复用网页同款的 ST 兼容引擎（:stcore 模块，已单测对齐 web）：
 * 快照里带来"显示配置"（正则脚本 / 变量表 / 宏上下文 / 思维链开关），
 * 原生端用 transformForDisplay 复刻网页的 displayText，保证两边看到的一致。
 *
 * 数据流：
 *   - 打开：网页 AndroidNative.openNativeChat(JSON) 把快照带进来。
 *   - 刷新：界面在前台时每 800ms 让 WebView 执行 window.__dhpNative.getSnapshot() 拉最新。
 *   - 发送：输入框内容交给 WebView 的 window.__dhpApp.sendUserMessage，复用网页既有生成逻辑。
 */
class NativeChatActivity : Activity() {

    companion object {
        const val EXTRA_SNAPSHOT = "snapshot"
    }

    /** 原生端渲染所需、与网页 displayText 一一对应的显示配置。 */
    private data class DisplayConfig(
        val hideReasoning: Boolean,
        val reasoningCutMarker: String,
        val scripts: List<RawScript>,
        val variables: VariableTables,
        val macroContext: MacroContext,
    )

    /** 一条 UI 消息（已解析，尚未做 ST 变换）。 */
    private data class UiMessage(
        val role: String,
        val content: String,
        val images: List<NativeImage>,
        val streaming: Boolean,
    )

    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** 上一次完整快照原文，变了才重渲染（流式期间会随草稿变化而触发）。 */
    private var lastRaw: String? = null
    /** 渲染序号：防止轮询回调乱序覆盖。 */
    private var renderSeq = 0
    private var alive = false

    /** 当前显示配置；首次解析后复用，避免每次轮询都重建 RawScript。 */
    private var cfg: DisplayConfig? = null
    /** 变换结果缓存：非流式消息内容稳定，命中即跳过 stcore 重算。 */
    private val blockCache = LinkedHashMap<String, List<ContentBlock>>(256)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_chat)

        scroll = findViewById(R.id.chat_scroll)
        container = findViewById(R.id.chat_container)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
        val close = findViewById<TextView>(R.id.chat_close)
        val send = findViewById<TextView>(R.id.chat_send)

        applySnapshot(intent.getStringExtra(EXTRA_SNAPSHOT) ?: "{}", fromPoll = false)

        close.setOnClickListener { finish() }
        send.setOnClickListener { sendMessage() }
    }

    override fun onResume() {
        super.onResume()
        alive = true
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        alive = false
        handler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------- 轮询网页快照
    private fun startPolling() {
        val runnable = object : Runnable {
            override fun run() {
                if (!alive) return
                val wv = MainActivity.getWebView()
                if (wv != null) {
                    val seq = ++renderSeq
                    // 让网页直接把"对象"返回：evaluateJavascript 会序列化成 JSON 文本（无外层引号），可直接解析。
                    wv.evaluateJavascript(
                        "window.__dhpNative && window.__dhpNative.getSnapshot ? window.__dhpNative.getSnapshot() : null",
                    ) { js ->
                        if (alive && seq == renderSeq && js != null && js != "null") {
                            applySnapshot(js, fromPoll = true)
                        }
                    }
                }
                if (alive) handler.postDelayed(this, 800)
            }
        }
        handler.postDelayed(runnable, 800)
    }

    // -------------------------------------------------------- 解析快照
    private fun applySnapshot(raw: String?, fromPoll: Boolean) {
        val text = raw?.trim()
        if (text.isNullOrEmpty() || text == "{}" || text == "null") {
            if (!fromPoll) renderEmpty()
            return
        }
        if (fromPoll && text == lastRaw) return
        lastRaw = text
        try {
            val root = JSONObject(text)
            cfg = parseConfig(root.optJSONObject("config"))
            val msgs = parseMessages(root.optJSONArray("messages"))
            renderMessages(msgs)
            val t = root.optJSONObject("config")?.optString("title")
            if (!t.isNullOrEmpty()) titleView.text = t
        } catch (e: Exception) {
            Log.w("DHP", "解析原生聊天快照失败", e)
        }
    }

    private fun renderEmpty() {
        container.removeAllViews()
        val tip = TextView(this)
        tip.text = "还没有对话内容"
        tip.setTextColor(Color.parseColor("#5C6678"))
        tip.textSize = 14f
        tip.gravity = Gravity.CENTER
        container.addView(tip)
    }

    private fun parseConfig(o: JSONObject?): DisplayConfig {
        if (o == null) return DisplayConfig(false, "", emptyList(), VariableTables(), MacroContext())
        val scripts = mutableListOf<RawScript>()
        val arr = o.optJSONArray("scripts")
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            runCatching { scripts.add(buildScript(jsonToMap(s))) }
        }
        val vars = parseVariables(o.optJSONObject("variables"))
        val mc = o.optJSONObject("macroContext")
        val macro = MacroContext(
            char = mc?.optString("char")?.notEmpty(),
            user = mc?.optString("user")?.notEmpty(),
            persona = mc?.optString("persona")?.notEmpty(),
            description = mc?.optString("description")?.notEmpty(),
            scenario = mc?.optString("scenario")?.notEmpty(),
            original = mc?.optString("original")?.notEmpty(),
            mesExample = mc?.optString("mes_example")?.notEmpty(),
            chat = mc?.optString("chat")?.notEmpty(),
            currentMessage = mc?.optString("currentMessage")?.notEmpty(),
            lastMessage = mc?.optString("lastMessage")?.notEmpty(),
        )
        return DisplayConfig(
            hideReasoning = o.optBoolean("hideReasoning", false),
            reasoningCutMarker = o.optString("reasoningCutMarker", ""),
            scripts = scripts,
            variables = vars,
            macroContext = macro,
        )
    }

    private fun String.notEmpty(): String? = if (isNotEmpty()) this else null

    private fun parseVariables(o: JSONObject?): VariableTables {
        val session = mutableMapOf<String, String>()
        val global = mutableMapOf<String, String>()
        o?.optJSONObject("session")?.let { fillVars(it, session) }
        o?.optJSONObject("global")?.let { fillVars(it, global) }
        return VariableTables(session, global)
    }

    private fun fillVars(o: JSONObject, out: MutableMap<String, String>) {
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = o.optString(k, "")
        }
    }

    private fun parseMessages(arr: JSONArray?): List<UiMessage> {
        val list = mutableListOf<UiMessage>()
        if (arr == null) return list
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "assistant")
            val content = o.optString("content", "")
            val streaming = o.optBoolean("streaming", false)
            val images = mutableListOf<NativeImage>()
            val im = o.optJSONArray("images")
            if (im != null) for (j in 0 until im.length()) {
                val io = im.optJSONObject(j) ?: continue
                val dataUrl = io.optString("dataUrl", "").ifEmpty { null }
                images.add(
                    NativeImage(
                        id = io.optString("id", ""),
                        dataUrl = dataUrl,
                        description = io.optString("description", ""),
                        markdownIndex = io.optInt("markdownIndex", 0),
                    ),
                )
            }
            list.add(UiMessage(role, content, images, streaming))
        }
        return list
    }

    /** org.json 对象递归转成 stcore.buildScript 能吃的 Map（数字取整，布尔/字符串原样）。 */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            map[k] = jsonToObj(o.get(k))
        }
        return map
    }

    private fun jsonToObj(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> List(v.length()) { i -> jsonToObj(v.get(i)) }
        is Int, is Boolean, is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toInt() else v
        else -> v?.toString()
    }

    // -------------------------------------------------------- 渲染消息
    private fun renderMessages(msgs: List<UiMessage>) {
        container.removeAllViews()
        if (msgs.isEmpty()) {
            renderEmpty()
            return
        }
        val config = cfg ?: return
        msgs.forEachIndexed { index, msg ->
            val depth = msgs.size - 1 - index
            val blocks = transformMessage(msg, config, depth)
            val bubble = buildBubble(msg.role, blocks)
            container.addView(bubble)
        }
        // 滚到最底部（最新一条）
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 对一条消息做 ST 兼容变换，得到"文本 / 图片"交替的内容块。 */
    private fun transformMessage(
        msg: UiMessage,
        config: DisplayConfig,
        depth: Int,
    ): List<ContentBlock> {
        // 流式草稿内容一直在变，不进缓存；其它稳定内容命中缓存省算力。
        val cacheKey = "${msg.role}:${msg.content}"
        if (!msg.streaming) {
            blockCache[cacheKey]?.let { return it }
        }
        val blocks = transformForDisplay(
            text = msg.content,
            attachments = msg.images,
            scripts = config.scripts,
            vars = config.variables,
            ctx = config.macroContext,
            hideReasoning = config.hideReasoning,
            reasoningCutMarker = config.reasoningCutMarker.ifEmpty { null },
            depthOverride = depth,
        )
        if (!msg.streaming) {
            // LinkedHashMap 自带容量上限淘汰
            while (blockCache.size >= 256) blockCache.remove(blockCache.keys.first())
            blockCache[cacheKey] = blocks
        }
        return blocks
    }

    /** 用 item_chat_message.xml 搭一个气泡：左（AI）/ 右（用户）对齐 + 配图。 */
    private fun buildBubble(role: String, blocks: List<ContentBlock>): View {
        val isUser = role == "user"
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_message, container, false)
        val bubble = view.findViewById<LinearLayout>(R.id.bubble)
        val textView = view.findViewById<TextView>(R.id.msg_text)
        val imagesBox = view.findViewById<LinearLayout>(R.id.msg_images)

        val sb = StringBuilder()
        var hasText = false
        for (b in blocks) when (b) {
            is ContentBlock.Text -> {
                val t = b.text.trim()
                if (t.isNotEmpty()) {
                    if (hasText) sb.append("\n\n")
                    sb.append(t)
                    hasText = true
                }
            }
            is ContentBlock.Image -> {
                val bm = decodeDataUrl(b.image?.dataUrl)
                val iv = ImageView(this)
                val w = (220 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.gravity = if (isUser) Gravity.END else Gravity.START
                lp.topMargin = (6 * resources.displayMetrics.density).toInt()
                iv.layoutParams = lp
                iv.adjustViewBounds = true
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                if (bm != null) iv.setImageBitmap(bm)
                else {
                    iv.setBackgroundColor(Color.parseColor("#1B2230"))
                    iv.minimumHeight = (80 * resources.displayMetrics.density).toInt()
                }
                imagesBox.addView(iv)
            }
        }
        if (hasText) {
            textView.visibility = View.VISIBLE
            textView.text = renderText(sb.toString())
        } else {
            textView.visibility = View.GONE
        }

        // 对齐与配色
        val frame = view as android.widget.FrameLayout
        val flp = frame.layoutParams as LinearLayout.LayoutParams
        flp.gravity = if (isUser) Gravity.END else Gravity.START
        frame.layoutParams = flp
        (bubble.background as? GradientDrawable)?.setColor(
            if (isUser) Color.parseColor("#2A4B7C") else Color.parseColor("#232A3A"),
        )
        return view
    }

    /** 轻量 markdown → HTML，再交给 Html.fromHtml 渲染（**加粗 / *斜体* / `代码`）。 */
    private fun renderText(src: String): CharSequence {
        val html = src
            .replace("\\*\\*([^*]+)\\*\\*".toRegex()) { "<b>${it.groupValues[1]}</b>" }
            .replace("(?<!\\*)\\*([^*]+)\\*(?!\\*)".toRegex()) { "<i>${it.groupValues[1]}</i>" }
            .replace("`([^`]+)`".toRegex()) { "<tt>${it.groupValues[1]}</tt>" }
            .replace("\n", "<br>")
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
    }

    private fun decodeDataUrl(dataUrl: String?): Bitmap? {
        if (dataUrl.isNullOrEmpty() || !dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        return runCatching {
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    // -------------------------------------------------------- 发送消息
    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val wv = MainActivity.getWebView()
        if (wv != null) {
            // JSONObject.quote 给出正确转义的 JS 字符串字面量
            val escaped = JSONObject.quote(text)
            wv.evaluateJavascript(
                "window.__dhpApp && window.__dhpApp.sendUserMessage ? window.__dhpApp.sendUserMessage($escaped) : null",
            ) {}
        }
    }
}
