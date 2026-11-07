package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.chat.ChatAdapter
import com.dbpg.duihuazhushou.chat.ChatController
import com.dbpg.duihuazhushou.chat.ChatItem
import com.dbpg.duihuazhushou.chat.ChatLogLoader
import com.dbpg.duihuazhushou.chat.ImageFileCache
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.gallery.ImageViewerActivity
import com.dbpg.duihuazhushou.session.SessionRepo
import org.json.JSONObject

/**
 * 原生聊天界面。
 *
 * 这里**只做界面**：把控件事件转给 [ChatController]，把它的回调画出来。
 * 会话怎么存、消息怎么改、生成怎么发，全都不在本类 —— 那些在
 * ChatController / SessionEditor / SessionHistory / GenBridge 里。
 *
 * 数据源已经换成原生 [com.dbpg.stcore.session.SessionDoc]（不再每 800ms
 * 轮询网页快照）；网页只在"组装提示词 + 发 API"这一步被调用。
 */
class NativeChatActivity : Activity(), ChatController.Sink {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        /** 兜底：本地还没有这份会话时用它构造一份。 */
        const val EXTRA_SNAPSHOT = "snapshot"

        private const val REQ_JSONL = 1001

        private const val M_EDIT = 1
        private const val M_REGEN = 2
        private const val M_DELETE = 3
        private const val M_BRANCH = 4
    }

    private lateinit var list: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var quickImgBtn: TextView

    /** 快捷生图是否在跑；以及"等模型回标签"时暂存的动作 id。 */
    private var quickBusy = false
    private var quickActionId: String = ""
    private var quickStyleArtist = ""
    private var quickStyleQuality = ""
    private var quickStyleNegative = ""
    private var quickSize = ""
    private var quickApiUrl = ""
    private var quickApiKey = ""
    private var quickApiModel = ""

    private val apiListener = com.dbpg.duihuazhushou.NativeBridge.ApiResultListener { json ->
        runOnUiThread { onQuickImageResult(json) }
    }
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var appearance: AppearanceConfig
    private lateinit var controller: ChatController

    /** 导入 jsonl 后进入只读预览：此时不显示会话操作条。 */
    private var viewingLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_chat)
        Insets.applyAll(this)
        SessionRepo.init(this)

        list = findViewById(R.id.chat_list)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
        quickImgBtn = findViewById(R.id.chat_quickimg)
        quickImgBtn.setOnClickListener { quickImage() }
        sendBtn = findViewById(R.id.chat_send)
        stopBtn = findViewById(R.id.chat_stop)
        val close = findViewById<TextView>(R.id.chat_close)
        val importBtn = findViewById<TextView>(R.id.chat_import)
        val genBtn = findViewById<TextView>(R.id.chat_gen)
        val engineBtn = findViewById<TextView>(R.id.chat_engine)

        appearance = Appearance.load(this)
        adapter = ChatAdapter(appearance, resources.displayMetrics.widthPixels, adapterListener)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        controller = ChatController(applicationContext, ImageFileCache(this), this)

        /*
         * sessionId 直接从 Intent 拿，不在这里解析快照。
         *
         * 快照里带着整段会话正文，主线程 JSONObject 化一次就可能按住第一帧
         * （黑屏卡死的直接原因）。解析交给 ChatController 的后台线程做。
         */
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().trim()
        val snapshot = intent.getStringExtra(EXTRA_SNAPSHOT).orEmpty()
        if (sessionId.isBlank() && snapshot.isBlank()) {
            Toast.makeText(this, "没有指定会话", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        controller.open(sessionId, snapshot)

        close.setOnClickListener { finish() }
        sendBtn.setOnClickListener { submitInput() }
        stopBtn.setOnClickListener { controller.stop() }
        importBtn.setOnClickListener { pickJsonl() }
        genBtn.setOnClickListener { startActivity(Intent(this, NativeImageGenActivity::class.java)) }
        engineBtn.setOnClickListener {
            startActivity(Intent(this, com.dbpg.duihuazhushou.engine.EngineSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        com.dbpg.duihuazhushou.NativeBridge.setApiResultListener(apiListener)
        val latest = Appearance.load(this)
        if (latest != appearance) {
            appearance = latest
            adapter.updateConfig(latest)
        }
        controller.applyAppearance(latest)
        // 网页设置里可能换了预设 / 正则 / 生图接口，回来跟上
        controller.refreshConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 正在生成就退出：已经吐出来的正文先存下来，别让用户白等
        controller.release()
    }

    // ------------------------------------------------------------ ChatController.Sink

    override fun onItems(items: List<ChatItem>, scrollToBottom: Boolean) {
        if (viewingLog) return
        val shouldScroll = scrollToBottom || !list.canScrollVertically(1)
        adapter.submitList(items) {
            if (shouldScroll && items.isNotEmpty()) {
                list.scrollToPosition(items.size - 1)
            }
        }
    }

    override fun onTitle(title: String) {
        if (!viewingLog) titleView.text = title
    }

    override fun onGenerating(active: Boolean) {
        sendBtn.visibility = if (active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (active) View.VISIBLE else View.GONE
        // 输入框不禁用：用户可以边等边把下一句打好
    }

    override fun onToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBranchCreated(sessionId: String, title: String) {
        Toast.makeText(this, "已分成新会话：$title", Toast.LENGTH_SHORT).show()
        controller.open(sessionId)
    }

    // ------------------------------------------------------------ 列表交互

    private val adapterListener = object : ChatAdapter.Listener {
        override fun onImageClick(item: ChatItem, path: String) {
            startActivity(
                Intent(this@NativeChatActivity, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, path),
            )
        }

        override fun onVariantSwitch(item: ChatItem, delta: Int) {
            controller.switchVariant(item.actionId, delta)
        }

        override fun onActionMenu(item: ChatItem, anchor: View) {
            showActionMenu(item, anchor)
        }
    }

    private fun showActionMenu(item: ChatItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (item.role == "user") {
            popup.menu.add(0, M_EDIT, 0, "编辑这句")
            popup.menu.add(0, M_DELETE, 1, "删除这句")
        } else {
            popup.menu.add(0, M_REGEN, 0, "重新生成")
            popup.menu.add(0, M_EDIT, 1, "编辑这条回复")
            popup.menu.add(0, M_BRANCH, 2, "从这里分支出新会话")
            popup.menu.add(0, M_DELETE, 3, "删除这条回复")
        }
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                M_EDIT -> if (item.role == "user") editText("编辑你的话", item.content) {
                    controller.editUser(item.actionId, it)
                } else editText("编辑这条回复", item.content) {
                    controller.editAi(item.actionId, it)
                }
                M_REGEN -> controller.regenerate(item.actionId)
                M_BRANCH -> controller.branchFrom(item.actionId)
                M_DELETE -> if (item.role == "user") {
                    controller.deleteUser(item.actionId)
                } else {
                    controller.deleteAi(item.actionId)
                }
            }
            true
        }
        popup.show()
    }

    /** 简单文本编辑弹窗（原生，不用 WebView 的 prompt）。 */
    private fun editText(title: String, initial: String, onOk: (String) -> Unit) {
        val box = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            minLines = 3
            maxLines = 12
            setPadding(36, 24, 36, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("保存") { _, _ -> onOk(box.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 发送

    private fun submitInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        controller.send(text)
    }

    // ------------------------------------------------------------ 导入 jsonl（只读预览）

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
        adapter.submitList(items) { list.scrollToPosition(items.size - 1) }
        Toast.makeText(this, "已加载 ${items.size} 条", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 快捷生图

    /**
     * 按「对话生图」配置快速出图：
     *   读配置 → 取最近 N 条上文 → （可选）LLM 转标签 → 原生生图 → 挂到最新 AI 消息。
     * 配置来源与聊天自动配图同一套（web image store）。
     */
    private fun quickImage() {
        if (quickBusy) {
            toast("还在出图，稍等一下")
            return
        }
        val actionId = controller?.latestActionId()
        if (actionId == null) {
            toast("还没有可配图的回复")
            return
        }
        quickBusy = true
        quickImgBtn.alpha = 0.4f
        toast("读取生图配置…")

        EngineBridge.fetch(this, "getImageConfig") { cfg ->
            if (cfg == null) {
                finishQuick("引擎还没就绪")
                return@fetch
            }
            val cur = cfg.optJSONObject("current") ?: JSONObject()
            val auto = cfg.optJSONObject("auto") ?: JSONObject()
            val n = auto.optInt("quickContextWindow", 5)
            val useLlm = cur.optBoolean("useLlmOptimization", true)
            val llmId = cur.optString("llmConfigId")

            quickApiUrl = cur.optString("baseUrl")
            quickApiKey = cur.optString("apiKey")
            quickApiModel = cur.optString("model")
            quickSize = "${cur.optInt("width", 832)}x${cur.optInt("height", 1216)}"
            quickActionId = actionId

            EngineBridge.fetch(this, "getImageStyle") { st ->
                if (st == null) {
                    finishQuick("风格读取失败")
                    return@fetch
                }
                quickStyleArtist = st.optString("artistTags")
                quickStyleQuality = st.optString("qualityTags")
                quickStyleNegative = st.optString("negativePrompt")

                if (quickApiUrl.isBlank()) {
                    finishQuick("还没配置生图运营商")
                    return@fetch
                }

                val context = controller?.contextText(n).orEmpty()
                if (context.isBlank()) {
                    finishQuick("上下文为空")
                    return@fetch
                }
                if (useLlm) {
                    toast("让模型生成画面标签…")
                    EngineBridge.command(
                        JSONObject().put("cmd", "optimizeImagePrompt")
                            .put("context", context).put("llmConfigId", llmId),
                    )
                } else {
                    // 不用模型：直接用这段上下文（截断），够用且零额外成本
                    generateQuickImage(context.take(600))
                }
            }
        }
    }

    private fun onQuickImageResult(json: String) {
        val o = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return
        }
        if (o.optString("kind") != "optimizeImagePrompt") return
        if (!o.optBoolean("ok")) {
            // 模型失败不阻塞出图：退回原文当标签，至少能出一张
            toast("标签生成失败（${o.optString("error")}），改用原文")
            val fallback = controller?.let { c ->
                c.doc?.actions?.lastOrNull { it.ai.isNotBlank() }?.let { c.plainTextOf(it) }
            }.orEmpty()
            generateQuickImage(fallback.take(600))
            return
        }
        generateQuickImage(o.optString("prompt"))
    }

    private fun generateQuickImage(tags: String) {
        if (tags.isBlank()) {
            finishQuick("提示词为空")
            return
        }
        val full = listOf(quickStyleArtist, quickStyleQuality, tags)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        toast("出图中…")
        val req = ImageGenService.Request(
            url = quickApiUrl,
            apiKey = quickApiKey,
            model = quickApiModel.ifBlank { "nai-diffusion-4-5-full" },
            prompt = full,
            size = quickSize.ifBlank { null },
            responseFormat = "b64_json",
            extraBody = if (quickStyleNegative.isBlank()) emptyMap()
            else mapOf("negative_prompt" to quickStyleNegative),
        )
        val svc = ImageGenService(applicationContext)
        svc.generate(req, object : ImageGenService.Callback {
            override fun onSuccess(images: List<com.dbpg.duihuazhushou.imagegen.ImageStore.StoredImage>) {
                svc.shutdown()
                val first = images.firstOrNull()
                if (first == null) {
                    finishQuick("没有解析到图片")
                    return
                }
                runOnUiThread {
                    controller?.attachImage(quickActionId, first.path, full)
                    finishQuick("已生成 1 张")
                }
            }

            override fun onError(message: String) {
                svc.shutdown()
                runOnUiThread { finishQuick("出图失败：$message") }
            }
        })
    }

    private fun finishQuick(msg: String) {
        quickBusy = false
        quickImgBtn.alpha = 1f
        toast(msg)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

}
