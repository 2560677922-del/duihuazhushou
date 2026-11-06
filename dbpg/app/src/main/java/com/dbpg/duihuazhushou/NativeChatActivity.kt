package com.dbpg.duihuazhushou

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.chat.ChatAdapter
import com.dbpg.duihuazhushou.chat.ChatItem
import com.dbpg.duihuazhushou.chat.ChatLogLoader
import com.dbpg.duihuazhushou.chat.ImageFileCache
import com.dbpg.stcore.ContentBlock
import com.dbpg.stcore.MacroContext
import com.dbpg.stcore.NativeImage
import com.dbpg.stcore.RawScript
import com.dbpg.stcore.VariableTables
import com.dbpg.stcore.buildScript
import com.dbpg.stcore.transformForDisplay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生聊天界面（原生外壳 + RecyclerView 气泡）。
 *
 * 分工（尽量解耦，避免层层嵌套）：
 *   - 本类：只做「取快照 / 解析配置 / 跑 ST 显示变换 / 组装 ChatItem / 发消息」这几件事
 *   - ChatAdapter：纯渲染（富文本 + 图片 + 对齐）
 *   - RichTextRenderer：把 stcore 的样式数据映射成 Spannable（渲染细节）
 *   - ChatLogLoader / ImageFileCache：数据来源与图片落盘
 *
 * 数据流：
 *   打开：网页 AndroidNative.openNativeChat(JSON) 带快照进来。
 *   刷新：前台时每 800ms 让 WebView 执行 window.__dhpNative.getSnapshot() 拉最新（导入 jsonl 时暂停）。
 *   发送：输入框内容交给 window.__dhpApp.sendUserMessage，复用网页既有生成链路。
 */
class NativeChatActivity : Activity() {

    companion object {
        const val EXTRA_SNAPSHOT = "snapshot"
        private const val REQ_JSONL = 1001
        private const val POLL_MS = 800L
    }

    /** 与网页 displayText 一一对应的显示配置。 */
    private data class DisplayConfig(
        val hideReasoning: Boolean,
        val reasoningCutMarker: String,
        val scripts: List<RawScript>,
        val variables: VariableTables,
        val macroContext: MacroContext,
    )

    private lateinit var list: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var imageCache: ImageFileCache

    private val handler = Handler(Looper.getMainLooper())
    private var lastRaw: String? = null
    private var renderSeq = 0
    private var alive = false

    /** 导入 jsonl 后暂停快照刷新，避免两边互相覆盖。 */
    private var viewingLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_chat)

        list = findViewById(R.id.chat_list)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
        val close = findViewById<TextView>(R.id.chat_close)
        val send = findViewById<TextView>(R.id.chat_send)
        val importBtn = findViewById<TextView>(R.id.chat_import)

        imageCache = ImageFileCache(this)
        val bubbleMax = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        adapter = ChatAdapter(bubbleMax)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        applySnapshot(intent.getStringExtra(EXTRA_SNAPSHOT), fromPoll = false)

        close.setOnClickListener { finish() }
        send.setOnClickListener { sendMessage() }
        importBtn.setOnClickListener { pickJsonl() }
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

    // ------------------------------------------------------------ 轮询网页快照
    private fun startPolling() {
        val runnable = object : Runnable {
            override fun run() {
                if (!alive) return
                if (!viewingLog) {
                    val wv = MainActivity.getWebView()
                    if (wv != null) {
                        val seq = ++renderSeq
                        wv.evaluateJavascript(
                            "window.__dhpNative && window.__dhpNative.getSnapshot ? window.__dhpNative.getSnapshot() : null",
                        ) { js ->
                            if (alive && seq == renderSeq && js != null && js != "null") {
                                applySnapshot(js, fromPoll = true)
                            }
                        }
                    }
                }
                if (alive) handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(runnable, POLL_MS)
    }

    // ------------------------------------------------------------ 解析快照
    private fun applySnapshot(raw: String?, fromPoll: Boolean) {
        val text = raw?.trim()
        if (text.isNullOrEmpty() || text == "{}" || text == "null") {
            if (!fromPoll) submit(emptyList(), scrollToBottom = false)
            return
        }
        if (fromPoll && text == lastRaw) return
        lastRaw = text
        try {
            val root = JSONObject(text)
            val config = parseConfig(root.optJSONObject("config"))
            val items = buildItems(root.optJSONArray("messages"), config)
            val title = root.optJSONObject("config")?.optString("title").orEmpty()
            if (title.isNotEmpty()) titleView.text = title
            submit(items, scrollToBottom = false)
        } catch (e: Exception) {
            Log.w("DHP", "解析原生聊天快照失败", e)
        }
    }

    /** 把快照消息转换成 UI 列表项：AI 消息跑 ST 显示变换，图片转本地路径。 */
    private fun buildItems(arr: JSONArray?, config: DisplayConfig): List<ChatItem> {
        if (arr == null || arr.length() == 0) return emptyList()
        val n = arr.length()
        val out = ArrayList<ChatItem>(n)
        for (i in 0 until n) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "assistant")
            val content = o.optString("content", "")
            val streaming = o.optBoolean("streaming", false)
            val images = readImages(o.optJSONArray("images"))

            val displayText: String
            val displayImages: List<NativeImage>
            if (role == "user") {
                // 用户消息原样显示（display 链路只针对 AI 输出）
                displayText = content
                displayImages = images
            } else {
                val blocks = transformForDisplay(
                    text = content,
                    attachments = images,
                    scripts = config.scripts,
                    vars = config.variables,
                    ctx = config.macroContext,
                    hideReasoning = config.hideReasoning,
                    reasoningCutMarker = config.reasoningCutMarker.ifEmpty { null },
                    depthOverride = n - 1 - i,
                )
                displayText = joinText(blocks)
                displayImages = blocks.filterIsInstance<ContentBlock.Image>().mapNotNull { it.image }
            }

            val paths = displayImages.mapNotNull { imageCache.pathFor(it.dataUrl) }
            out.add(ChatItem(id = "snap:$i", role = role, content = displayText, imagePaths = paths, streaming = streaming))
        }
        return out
    }

    private fun readImages(arr: JSONArray?): List<NativeImage> {
        if (arr == null) return emptyList()
        val out = ArrayList<NativeImage>(arr.length())
        for (j in 0 until arr.length()) {
            val io = arr.optJSONObject(j) ?: continue
            out.add(
                NativeImage(
                    id = io.optString("id", ""),
                    dataUrl = io.optString("dataUrl", "").ifEmpty { null },
                    description = io.optString("description", ""),
                    markdownIndex = io.optInt("markdownIndex", 0),
                ),
            )
        }
        return out
    }

    private fun joinText(blocks: List<ContentBlock>): String {
        val sb = StringBuilder()
        for (b in blocks) if (b is ContentBlock.Text) {
            val t = b.text.trim()
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(t)
            }
        }
        return sb.toString()
    }

    private fun parseConfig(o: JSONObject?): DisplayConfig {
        if (o == null) return DisplayConfig(false, "", emptyList(), VariableTables(), MacroContext())
        val scripts = mutableListOf<RawScript>()
        o.optJSONArray("scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                runCatching { scripts.add(buildScript(jsonToMap(s))) }
            }
        }
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
            variables = parseVariables(o.optJSONObject("variables")),
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
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optString(k, "")
        }
    }

    /** org.json 递归转 Map，喂给 stcore.buildScript。 */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
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

    // ------------------------------------------------------------ 列表更新
    private fun submit(items: List<ChatItem>, scrollToBottom: Boolean) {
        val shouldScroll = scrollToBottom || !list.canScrollVertically(1)
        adapter.submitList(items) {
            if (shouldScroll && items.isNotEmpty()) {
                list.scrollToPosition(items.size - 1)
            }
        }
    }

    // ------------------------------------------------------------ 导入 jsonl
    private fun pickJsonl() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_JSONL)
    }

    @Deprecated("Activity#onActivityResult 仍可用；避免为单一入口再引入 ActivityResult 依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_JSONL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val items = ChatLogLoader.load(this, uri)
        if (items.isEmpty()) {
            Toast.makeText(this, "没读到消息（可能不是 .jsonl 记录）", Toast.LENGTH_SHORT).show()
            return
        }
        viewingLog = true
        titleView.text = "聊天记录（${items.size} 条）"
        submit(items, scrollToBottom = true)
        Toast.makeText(this, "已加载 ${items.size} 条", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 发送消息
    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val wv: WebView = MainActivity.getWebView() ?: return
        val escaped = JSONObject.quote(text)
        wv.evaluateJavascript(
            "window.__dhpApp && window.__dhpApp.sendUserMessage ? window.__dhpApp.sendUserMessage($escaped) : null",
        ) {}
    }
}
