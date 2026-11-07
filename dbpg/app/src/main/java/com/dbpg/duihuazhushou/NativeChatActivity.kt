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
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var appearance: AppearanceConfig
    private lateinit var controller: ChatController

    /** 导入 jsonl 后进入只读预览：此时不显示会话操作条。 */
    private var viewingLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_chat)
        SessionRepo.init(this)

        list = findViewById(R.id.chat_list)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
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
}
