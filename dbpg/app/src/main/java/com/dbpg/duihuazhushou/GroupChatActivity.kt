package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.chat.ChatAdapter
import com.dbpg.duihuazhushou.chat.ChatController
import com.dbpg.duihuazhushou.chat.ChatItem
import com.dbpg.duihuazhushou.chat.ImageFileCache
import com.dbpg.duihuazhushou.gallery.ImageViewerActivity
import com.dbpg.duihuazhushou.session.GroupStore
import com.dbpg.duihuazhushou.session.SessionRepo
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.chat.GenBridge
import org.json.JSONObject

/**
 * 群聊页：一个群 + N+1 个会话。
 *
 * ## 为什么每个频道一个 ChatController
 * 用户要求"切走以后后台继续生成"。一个 controller 只有一个 genId/doc，
 * 切频道时复用它就会把上一频道的生成状态顶掉；而每个频道一个 controller，
 * 生成、渲染、落盘各自独立，切回来只是把它的内容重新画一遍。
 *
 * 代价是每个频道一个渲染线程。群聊成员通常个位数，可以接受；
 * 频道第一次被点开时才创建，不用的不占资源。
 *
 * ## 频道切换不是"过滤消息"
 * 切频道 = 打开另一个 sessionId。私聊频道的消息天然只含该角色，
 * 公共区才用 speakerId 标发言人。这样私聊可以直接复用单人聊天的
 * 分支 / 变体 / 摘要，不需要第二套消息模型。
 */
class GroupChatActivity : Activity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private lateinit var group: GroupStore.Group
    private val controllers = HashMap<String, ChatController>()
    private var activeSessionId = ""

    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var channelBar: LinearLayout
    private lateinit var channelScroll: android.widget.HorizontalScrollView
    private lateinit var titleView: TextView
    private lateinit var emptyView: TextView
    private lateinit var nextBtn: TextView
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView

    private lateinit var imageCache: ImageFileCache
    private var stickToBottom = true
    /** 公共区「续写」的下一位；只是一个轮转指针，不落盘 */
    private var nextSpeaker = 0
    /** 资产清单（角色/世界书/预设）；打开页面时拉一次，邀请成员与设置都用它 */
    private var catalog: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_chat)
        Insets.applyAll(this)
        SessionRepo.init(this)

        group = GroupStore.get(this, intent.getStringExtra(EXTRA_GROUP_ID).orEmpty())
            ?: run {
                finish()
                return
            }
        imageCache = ImageFileCache(this)

        titleView = findViewById(R.id.gc_title)
        channelBar = findViewById(R.id.gc_channels)
        channelScroll = findViewById(R.id.gc_channel_scroll)
        list = findViewById(R.id.gc_list)
        input = findViewById(R.id.gc_input)
        emptyView = findViewById(R.id.gc_empty)
        nextBtn = findViewById(R.id.gc_next)
        sendBtn = findViewById(R.id.gc_send)
        stopBtn = findViewById(R.id.gc_stop)

        titleView.text = group.title
        titleView.setOnClickListener { renameGroup() }

        adapter = ChatAdapter(Appearance.load(this), resources.displayMetrics.widthPixels, listener)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.recycledViewPool.setMaxRecycledViews(ChatAdapter.TYPE_HTML, ChatAdapter.MAX_WEB_POOL)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                stickToBottom = !rv.canScrollVertically(1)
            }
        })

        findViewById<View>(R.id.gc_back).setOnClickListener { finish() }
        findViewById<View>(R.id.gc_more).setOnClickListener { showGroupMenu() }
        findViewById<View>(R.id.gc_add_member).setOnClickListener { inviteMembers() }

        sendBtn.setOnClickListener { submit() }
        stopBtn.setOnClickListener { controllerFor(activeSessionId).stop() }
        nextBtn.setOnClickListener { continueNext() }

        paintChannels()
        selectChannel(group.publicSessionId, force = true)
        loadCatalog()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 每个频道各自收尾：正在生成的正文先落盘，再释放渲染线程
        controllers.values.forEach { runCatching { it.shutdown() } }
        controllers.clear()
        com.dbpg.duihuazhushou.chat.render.HtmlMessageView.releaseAll()
    }

    // ------------------------------------------------------------ 频道

    private fun paintChannels() {
        channelBar.removeAllViews()
        channelBar.addView(
            channelItem(group.publicSessionId, "公", "公共", activeSessionId == group.publicSessionId),
        )
        for (m in group.members) {
            channelBar.addView(
                channelItem(m.sessionId, m.name.take(1), m.name, activeSessionId == m.sessionId),
            )
        }
        // 选中的那个滚到可见：成员多的时候新加的频道默认在屏幕外
        channelBar.post {
            val idx = channelBar.indexOfChild(
                channelBar.findViewWithTag<View>("ch:$activeSessionId"),
            )
            if (idx >= 0) channelScroll.smoothScrollTo(channelBar.getChildAt(idx).left - dp(12), 0)
        }
    }

    private fun channelItem(
        sessionId: String,
        letter: String,
        name: String,
        selected: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(12), dp(4))
            background = getDrawable(if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill_soft)
            setOnClickListener { selectChannel(sessionId) }
            tag = "ch:$sessionId"
        }
        row.addView(TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (selected) 0xFF2F66D8.toInt() else 0xFF5A6B85.toInt())
            background = getDrawable(R.drawable.bg_circle_btn)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        row.addView(TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (selected) 0xFF2F66D8.toInt() else 0xFF5A6B85.toInt())
            setPadding(dp(6), 0, 0, 0)
        })
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.marginEnd = dp(6)
        row.layoutParams = lp
        return row
    }

    private fun selectChannel(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (!force && sessionId == activeSessionId) return
        activeSessionId = sessionId
        paintChannels()

        val isPublic = sessionId == group.publicSessionId
        nextBtn.visibility = if (isPublic) View.VISIBLE else View.GONE
        input.hint = if (isPublic) "在公共区说点什么…" else {
            val m = group.members.firstOrNull { it.sessionId == sessionId }
            if (m == null) "说点什么…" else "对${m.name}说…"
        }

        val c = controllerFor(sessionId)
        c.refreshView()
        // 切回来时按这个频道自己的状态还原按钮：后台可能还在生成
        val busy = c.isGenerating()
        sendBtn.visibility = if (busy) View.GONE else View.VISIBLE
        stopBtn.visibility = if (busy) View.VISIBLE else View.GONE
        input.isEnabled = !busy
        showEmpty(false)
    }

    private fun controllerFor(sessionId: String): ChatController =
        controllers.getOrPut(sessionId) {
            ChatController(applicationContext, imageCache, ChannelSink(sessionId)).also { it.open(sessionId) }
        }

    /**
     * 每个频道独立的接收者。
     *
     * 只有当前频道的回调能碰 UI；后台频道的流式/完成事件直接丢弃 ——
     * 数据已经在 controller 里落盘了，切回来 refreshView 就能看到。
     */
    private inner class ChannelSink(private val sid: String) : ChatController.Sink {
        override fun onItems(items: List<ChatItem>, scrollToBottom: Boolean) {
            if (sid != activeSessionId) return
            adapter.submitList(items) {
                if (scrollToBottom) {
                    stickToBottom = true
                    scrollToBottom()
                }
            }
            showEmpty(items.isEmpty())
        }

        override fun onStreamingText(actionId: String, text: String) {
            if (sid != activeSessionId) return
            adapter.patchStreaming(actionId, text)
            if (stickToBottom) scrollToBottom()
        }

        override fun onTitle(title: String) {
            // 群名以群配置为准；这里不跟随单个频道的标题
        }

        override fun onGenerating(active: Boolean) {
            if (sid != activeSessionId) return
            sendBtn.visibility = if (active) View.GONE else View.VISIBLE
            stopBtn.visibility = if (active) View.VISIBLE else View.GONE
            input.isEnabled = !active
        }

        override fun onToast(message: String) = toast(message)

        override fun onBranchCreated(sessionId: String, title: String) {
            toast("群聊频道里不支持分支出新会话")
        }
    }

    // ------------------------------------------------------------ 发送

    private fun submit() {
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        if (activeSessionId.isBlank()) return
        input.setText("")
        controllerFor(activeSessionId).send(text)
    }

    /** 公共区：按成员顺序叫下一位。顺序就是频道栏顺序，切一次轮一次。 */
    private fun continueNext() {
        if (activeSessionId != group.publicSessionId) return
        if (group.members.isEmpty()) {
            toast("先邀请成员")
            return
        }
        val m = group.members[nextSpeaker % group.members.size]
        nextSpeaker++
        controllerFor(activeSessionId).sendAs(m.characterId)
    }

    // ------------------------------------------------------------ 群设置

    private fun showGroupMenu() {
        val items = arrayOf("公共世界书", "群预设", "邀请成员", "重命名", "删除群聊")
        AlertDialog.Builder(this)
            .setTitle(group.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickPublicBooks()
                    1 -> pickGroupPreset()
                    2 -> inviteMembers()
                    3 -> renameGroup()
                    4 -> confirmDeleteGroup()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameGroup() {
        val edit = EditText(this).apply {
            setText(group.title)
            setSelection(text.length)
            textSize = 15f
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("群名")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = edit.text?.toString().orEmpty().trim()
                if (name.isNotEmpty()) {
                    group = GroupStore.update(this, group.copy(title = name))
                    titleView.text = group.title
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteGroup() {
        AlertDialog.Builder(this)
            .setTitle("删除群聊")
            .setMessage("「${group.title}」和它的全部频道消息都会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                GroupStore.delete(this, group.id)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickPublicBooks() {
        val books = catalog?.optJSONArray("worldBooks") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val n = books.length()
        val names = Array(n) { books.optJSONObject(it)?.optString("name").orEmpty() }
        val ids = Array(n) { books.optJSONObject(it)?.optString("id").orEmpty() }
        val checked = BooleanArray(n) { ids[it] in group.publicWorldBookIds }
        AlertDialog.Builder(this)
            .setTitle("公共世界书（全员生效）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val picked = ids.filterIndexed { i, _ -> checked[i] }
                group = GroupStore.update(this, group.copy(publicWorldBookIds = picked))
                refreshControllers()
                toast("已保存，下一轮生成生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickGroupPreset() {
        val presets = catalog?.optJSONArray("presets") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val n = presets.length()
        val names = ArrayList<String>(n + 1).apply {
            add("跟随全局")
            for (i in 0 until n) add(presets.optJSONObject(i)?.optString("name").orEmpty())
        }
        val ids = ArrayList<String>(n + 1).apply {
            add("")
            for (i in 0 until n) add(presets.optJSONObject(i)?.optString("id").orEmpty())
        }
        val current = ids.indexOf(group.presetId.orEmpty())
        AlertDialog.Builder(this)
            .setTitle("群预设")
            .setSingleChoiceItems(names.toTypedArray(), current.coerceAtLeast(0)) { d, which ->
                val picked = ids[which]
                group = GroupStore.update(this, group.copy(presetId = picked.ifBlank { null }))
                refreshControllers()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun inviteMembers() {
        val chars = catalog?.optJSONArray("characters") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val have = group.members.map { it.characterId }.toSet()
        val candidates = ArrayList<Pair<String, String>>()
        for (i in 0 until chars.length()) {
            val o = chars.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val name = o.optString("name", "")
            if (id.isNotBlank() && id !in have) candidates.add(id to name)
        }
        if (candidates.isEmpty()) {
            toast("角色库里没有可邀请的角色")
            return
        }
        val names = candidates.map { it.second }.toTypedArray()
        val checked = BooleanArray(candidates.size)
        AlertDialog.Builder(this)
            .setTitle("邀请成员")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("加入") { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) return@setPositiveButton
                toast("正在建频道…")
                Thread {
                    val updated = GroupStore.addMembers(applicationContext, group, picked)
                    runOnUiThread {
                        group = updated
                        paintChannels()
                        toast("已加入 ${picked.size} 位")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 群级配置改了：已打开的频道要重新读群信息（书/预设都从 GroupStore 现取）。 */
    private fun refreshControllers() {
        controllers.values.forEach { it.refreshConfig() }
    }

    private fun loadCatalog() {
        GenBridge.fetchAssetCatalog { json ->
            if (json.isNullOrBlank()) return@fetchAssetCatalog
            catalog = runCatching { JSONObject(json) }.getOrNull()
        }
    }

    // ------------------------------------------------------------ 列表回调

    private val listener = object : ChatAdapter.Listener {
        override fun onImageClick(item: ChatItem, path: String) {
            startActivity(
                Intent(this@GroupChatActivity, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, path),
            )
        }

        override fun onVariantSwitch(item: ChatItem, delta: Int) {
            controllerFor(activeSessionId).switchVariant(item.actionId, delta)
        }

        override fun onActionMenu(item: ChatItem, anchor: View) {
            val c = controllerFor(activeSessionId)
            val options = if (item.role == "user") {
                arrayOf("重新发送", "删除这句")
            } else {
                arrayOf("重新生成", "删除回复")
            }
            AlertDialog.Builder(this@GroupChatActivity)
                .setTitle("这条消息")
                .setItems(options) { _, which ->
                    when {
                        item.role == "user" && which == 0 -> c.resend(item.actionId)
                        item.role == "user" -> c.deleteUser(item.actionId)
                        which == 0 -> c.regenerate(item.actionId)
                        else -> c.deleteAi(item.actionId)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        override fun onImageGen(item: ChatItem) {
            toast("群聊里暂不支持单条生图")
        }

        override fun onCardNeeded(item: ChatItem, widthPx: Int) {
            controllerFor(activeSessionId).ensureCard(item.actionId, widthPx)
        }

        override fun onCardOpen(item: ChatItem) {
            val html = item.html ?: return
            startActivity(
                Intent(this@GroupChatActivity, com.dbpg.duihuazhushou.chat.render.CardViewerActivity::class.java)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_HTML, html)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_TEXT, item.content)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_TITLE, group.title),
            )
        }

        override fun onMessageInput(text: String) {
            input.setText(text)
            input.setSelection(input.text.length)
        }

        override fun onMessageSend(text: String) {
            input.setText(text)
            submit()
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun showEmpty(show: Boolean) {
        emptyView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun scrollToBottom() {
        if (adapter.itemCount == 0) return
        list.scrollToPosition(adapter.itemCount - 1)
        list.post { list.scrollBy(0, dp(400)) }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
