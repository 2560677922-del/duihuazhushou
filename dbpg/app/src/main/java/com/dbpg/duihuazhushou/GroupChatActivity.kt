package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.chat.ChatAdapter
import com.dbpg.duihuazhushou.chat.ChatController
import com.dbpg.duihuazhushou.chat.ChatItem
import com.dbpg.duihuazhushou.chat.GenBridge
import com.dbpg.duihuazhushou.chat.ImageFileCache
import com.dbpg.duihuazhushou.character.CharacterAvatar
import com.dbpg.duihuazhushou.gallery.ImageViewerActivity
import com.dbpg.duihuazhushou.session.GroupStore
import com.dbpg.duihuazhushou.session.SessionBindingStore
import com.dbpg.duihuazhushou.session.SessionRepo
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.ProviderUi
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
 *
 * ## 配置分层
 *   群（公共书 / 预设 / 运营商 / 正则）→ 成员（私有书 / 预设 / 运营商）→ 全局。
 * 成员没单独选的项一律"跟随群"，群没配再跟随全局 —— 数据上只存"显式选过"的。
 */
class GroupChatActivity : Activity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private val ui by lazy { ProviderUi(this) }

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
    /** 资产清单（角色/世界书/预设/正则）；打开页面时拉一次 */
    private var catalog: JSONObject? = null
    /** 当前频道最近一次渲染出的条目（搜索用） */
    private var currentItems: List<ChatItem> = emptyList()
    /** 搜索命中位置与游标（同一个词重复点「搜索」= 跳到下一条） */
    private var searchHits: List<Int> = emptyList()
    private var searchCursor = -1
    private var searchWord = ""

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
        findViewById<View>(R.id.gc_search).setOnClickListener { searchInChannel() }
        findViewById<View>(R.id.gc_summary).setOnClickListener { summarizeNow() }

        sendBtn.setOnClickListener { submit() }
        stopBtn.setOnClickListener { controllerFor(activeSessionId).stop() }
        nextBtn.setOnClickListener { continueNext() }
        // 长按「续写」= 指定谁说话，不用等轮转
        nextBtn.setOnLongClickListener {
            pickSpeaker()
            true
        }

        paintChannels()
        selectChannel(group.publicSessionId, force = true)
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // 群配置可能被别处改过（比如新建群时继承的书），回来重读一次
        GroupStore.get(this, group.id)?.let {
            if (it != group) {
                group = it
                paintChannels()
                refreshControllers()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 每个频道各自收尾：正在生成的正文先落盘，再释放渲染线程
        controllers.values.forEach { runCatching { it.shutdown() } }
        controllers.clear()
        com.dbpg.duihuazhushou.chat.render.HtmlMessageView.releaseAll()
    }

    // ------------------------------------------------------------ 频道栏

    private fun paintChannels() {
        channelBar.removeAllViews()
        channelBar.addView(publicChannelItem())
        for ((i, m) in group.members.withIndex()) {
            channelBar.addView(memberChannelItem(m, i + 1))
        }
        // 选中的那个滚到可见：成员多的时候新加的频道默认在屏幕外
        channelBar.post {
            val idx = channelBar.indexOfChild(
                channelBar.findViewWithTag<View>("ch:$activeSessionId"),
            )
            if (idx >= 0) channelScroll.smoothScrollTo(channelBar.getChildAt(idx).left - ui.dp(12), 0)
        }
    }

    private fun publicChannelItem(): View {
        val selected = activeSessionId == group.publicSessionId
        val row = channelRow(selected, group.publicSessionId)
        val icon = TextView(this).apply {
            text = "公"
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (selected) ui.tk.accent else ui.tk.textDim)
            background = ui.shape(if (selected) ui.tk.accentSoft else ui.tk.panelSoft, 999f)
            layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26))
        }
        row.addView(icon)
        row.addView(channelLabel("公共", selected))
        row.setOnClickListener { selectChannel(group.publicSessionId) }
        return row
    }

    /**
     * 成员频道。
     *
     * 未选中时收成"头像 + 顺序徽章"，选中才展开名字 —— 否则三个成员就能把
     * 300dp 的屏幕撑满，后面的成员要靠盲滑。
     */
    private fun memberChannelItem(m: GroupStore.Member, order: Int): View {
        val selected = activeSessionId == m.sessionId
        val row = channelRow(selected, m.sessionId)

        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28))
        }
        val avatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.shape(ui.tk.panelSoft, 999f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val bmp = CharacterAvatar.bitmap(this, m.name)
        if (bmp != null) {
            avatar.setImageBitmap(bmp)
        } else {
            avatar.visibility = View.GONE
            holder.addView(TextView(this).apply {
                text = m.name.take(1)
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) ui.tk.accent else ui.tk.textDim)
                background = ui.shape(if (selected) ui.tk.accentSoft else ui.tk.panelSoft, 999f)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }
        holder.addView(avatar)
        // 顺序徽章：点了「续写」就按这个数字轮
        holder.addView(TextView(this).apply {
            text = order.toString()
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(ui.tk.onAccent)
            background = ui.shape(ui.tk.accent, 999f)
            val s = ui.dp(15)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = -ui.dp(2)
                bottomMargin = -ui.dp(2)
            }
        })
        row.addView(holder)
        if (selected) row.addView(channelLabel(m.name, true))
        row.setOnClickListener { selectChannel(m.sessionId) }
        row.setOnLongClickListener {
            memberMenu(m)
            true
        }
        return row
    }

    private fun channelRow(selected: Boolean, sessionId: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(36)
            setPadding(ui.dp(5), ui.dp(4), ui.dp(if (selected) 12 else 5), ui.dp(4))
            background = if (selected) ui.shape(ui.tk.accentSoft, 999f) else null
            tag = "ch:$sessionId"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = ui.dp(6) }
        }

    private fun channelLabel(name: String, selected: Boolean): TextView = TextView(this).apply {
        text = name
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (selected) ui.tk.accent else ui.tk.textDim)
        setPadding(ui.dp(6), 0, 0, 0)
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
        // 换频道后旧的搜索结果作废
        searchHits = emptyList()
        searchCursor = -1
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
            currentItems = items
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

        override fun onSummaryState(active: Boolean) {
            if (sid != activeSessionId) return
            findViewById<View>(R.id.gc_summary).alpha = if (active) 0.35f else 1f
        }

        override fun onSummaryChanged() {
            if (sid != activeSessionId) return
            controllerFor(sid).refreshView()
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

    /** 长按「续写」：直接指定谁说。 */
    private fun pickSpeaker() {
        if (activeSessionId != group.publicSessionId) return
        if (group.members.isEmpty()) {
            toast("先邀请成员")
            return
        }
        val names = group.members.map { "${it.name} 先说" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("让谁说")
            .setItems(names) { _, i ->
                val m = group.members[i]
                // 指针停在他后面：下一次「续写」自然轮到下一位
                nextSpeaker = i + 1
                controllerFor(activeSessionId).sendAs(m.characterId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 成员

    /**
     * 长按频道头像的菜单。
     *
     * 排序没有做成"长按拖动"：频道栏在横向 ScrollView 里，拖动和滚动手势
     * 会互相抢，手指一抖就变成滚动。改成菜单 + 独立的顺序面板，
     * 在任何屏幕宽度、任何成员数量下都稳。
     */
    private fun memberMenu(m: GroupStore.Member) {
        val items = arrayOf("成员设置", "设为下一位发言", "移出群聊")
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> memberSettings(m)
                    1 -> {
                        val at = group.members.indexOfFirst { it.characterId == m.characterId }
                        if (at >= 0) nextSpeaker = at
                        selectChannel(group.publicSessionId)
                        toast("下一次「续写」由${m.name}说")
                    }
                    2 -> confirmRemoveMember(m)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun memberSettings(m: GroupStore.Member) {
        val b = SessionBindingStore.load(this, m.sessionId)
        showPanel("${m.name} · 频道设置") {
            addView(ui.text("这里的设置只管这个成员的私聊频道；公共区说话时带的是公共书", 11.5f, ui.tk.textFaint))
            addView(ui.gap(10))

            addView(detailRow("角色卡", m.name) { editMemberName(m) })
            addView(detailRow("私有世界书", booksSummary(b)) { v ->
                pickMemberBooks(m) { v.text = it }
            })
            addView(detailRow("预设", presetLabel(b.presetId, group.presetId)) { v ->
                pickMemberPreset(m) { v.text = it }
            })
            addView(detailRow("运营商", apiLabel(b.apiConfigId, group.apiConfigId)) { v ->
                pickMemberApi(m) { v.text = it }
            })

            addView(ui.gap(14))
            addView(ui.soft("移出群聊").apply {
                setTextColor(0xFFD64545.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    dismissPanel()
                    confirmRemoveMember(m)
                }
            })
            addView(ui.gap(6))
            addView(ui.text("移出后他的聊天记录不会删，会作为单人会话回到「冒险」列表", 11f, ui.tk.textFaint))
        }
    }

    /** 绑定里存的是 id；显示成名字要查清单。查不到（被删了）就说明按跟随处理。 */
    private fun presetLabel(own: String?, inherited: String?): String {
        if (own.isNullOrBlank()) return if (inherited.isNullOrBlank()) "跟随全局" else "跟随群"
        return catalogName("presets", own) ?: "已删除 · 跟随上层"
    }

    private fun apiLabel(own: String?, inherited: String?): String {
        if (own.isNullOrBlank()) return if (inherited.isNullOrBlank()) "跟随全局" else "跟随群"
        return ConnectionStore.llmOptions().firstOrNull { it.first == own }?.second
            ?: "已删除 · 跟随上层"
    }

    private fun catalogName(array: String, id: String): String? {
        val arr = catalog?.optJSONArray(array) ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("name")
        }
        return null
    }

    private fun booksSummary(b: SessionBindingStore.Binding): String {
        val ids = b.worldBookIds ?: return "跟随公共书"
        if (ids.isEmpty()) return "只用公共书"
        return "${ids.size} 本私有书"
    }

    private fun editMemberName(m: GroupStore.Member) {
        val edit = ui.input("名字").apply {
            setText(m.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("频道显示名")
            .setView(wrapForDialog(edit))
            .setPositiveButton("保存") { _, _ ->
                val name = edit.text?.toString().orEmpty().trim()
                if (name.isEmpty()) return@setPositiveButton
                group = GroupStore.update(
                    this,
                    group.copy(
                        members = group.members.map {
                            if (it.characterId == m.characterId) it.copy(name = name) else it
                        },
                    ),
                )
                // 只改显示名：历史消息按 speakerId 映射，跟着一起变
                SessionBindingStore.save(this, m.sessionId, SessionBindingStore.load(this, m.sessionId).copy(characterName = name))
                paintChannels()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickMemberBooks(m: GroupStore.Member, onPicked: (String) -> Unit) {
        val books = catalog?.optJSONArray("worldBooks") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val n = books.length()
        val names = Array(n) { books.optJSONObject(it)?.optString("name").orEmpty() }
        val ids = Array(n) { books.optJSONObject(it)?.optString("id").orEmpty() }
        // 现读：面板打开后可能已经改过别的项，别拿旧快照覆盖
        val b = SessionBindingStore.load(this, m.sessionId)
        val checked = BooleanArray(n) { ids[it] in b.worldBookIds.orEmpty() }
        AlertDialog.Builder(this)
            .setTitle("${m.name} 的私有世界书")
            .setMessage("公共书全员强制生效，这里只选额外的私有本")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val picked = ids.filterIndexed { i, _ -> checked[i] }
                val latest = SessionBindingStore.load(this, m.sessionId)
                SessionBindingStore.save(this, m.sessionId, latest.copy(worldBookIds = picked))
                refreshControllers()
                onPicked(if (picked.isEmpty()) "只用公共书" else "${picked.size} 本私有书")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickMemberPreset(m: GroupStore.Member, onPicked: (String) -> Unit) {
        val presets = catalog?.optJSONArray("presets") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val b = SessionBindingStore.load(this, m.sessionId)
        val names = ArrayList<String>(presets.length() + 1).apply {
            add(if (group.presetId.isNullOrBlank()) "跟随群（当前跟随全局）" else "跟随群")
            for (i in 0 until presets.length()) add(presets.optJSONObject(i)?.optString("name").orEmpty())
        }
        val ids = ArrayList<String>(presets.length() + 1).apply {
            add("")
            for (i in 0 until presets.length()) add(presets.optJSONObject(i)?.optString("id").orEmpty())
        }
        val cur = ids.indexOf(b.presetId.orEmpty()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${m.name} 的预设")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                val latest = SessionBindingStore.load(this, m.sessionId)
                SessionBindingStore.save(this, m.sessionId, latest.copy(presetId = ids[which].ifBlank { null }))
                refreshControllers()
                onPicked(presetLabel(ids[which].ifBlank { null }, group.presetId))
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickMemberApi(m: GroupStore.Member, onPicked: (String) -> Unit) {
        val options = ConnectionStore.llmOptions()
        if (options.isEmpty()) {
            toast("还没有配置运营商")
            return
        }
        val b = SessionBindingStore.load(this, m.sessionId)
        val names = ArrayList<String>(options.size + 1).apply {
            add(if (group.apiConfigId.isNullOrBlank()) "跟随群（当前跟随全局）" else "跟随群")
            options.forEach { add(it.second) }
        }
        val ids = ArrayList<String>(options.size + 1).apply {
            add("")
            options.forEach { add(it.first) }
        }
        val cur = ids.indexOf(b.apiConfigId.orEmpty()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${m.name} 的运营商")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                val latest = SessionBindingStore.load(this, m.sessionId)
                SessionBindingStore.save(this, m.sessionId, latest.copy(apiConfigId = ids[which].ifBlank { null }))
                refreshControllers()
                onPicked(apiLabel(ids[which].ifBlank { null }, group.apiConfigId))
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemoveMember(m: GroupStore.Member) {
        AlertDialog.Builder(this)
            .setTitle("移出「${m.name}」")
            .setMessage("他的频道会变成普通单人会话，消息保留在「冒险」列表里。")
            .setPositiveButton("移出") { _, _ -> removeMember(m) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeMember(m: GroupStore.Member) {
        group = GroupStore.update(
            this,
            group.copy(members = group.members.filterNot { it.characterId == m.characterId }),
        )
        controllers.remove(m.sessionId)?.shutdown()
        // 频道会话转为普通会话：清掉群归属，列表页才会重新显示它
        SessionRepo.load(m.sessionId) { d ->
            if (d != null && d.group != null) {
                SessionRepo.save(d.copy(group = null, title = "${d.title}（已退出群聊）"))
            }
        }
        if (activeSessionId == m.sessionId) selectChannel(group.publicSessionId, force = true)
        paintChannels()
        toast("已移出，记录保留在冒险列表")
    }

    /** 发言顺序：上移/下移即时写盘，频道栏立刻重排。 */
    private fun orderDialog() {
        if (group.members.size < 2) {
            toast("至少两个成员才需要排序")
            return
        }
        showPanel("发言顺序") {
            addView(ui.text("点「续写」按这个顺序轮；长按频道头像可指定谁先说", 11.5f, ui.tk.textFaint))
            addView(ui.gap(8))
            for ((i, m) in group.members.withIndex()) {
                val row = ui.horizontal()
                row.setPadding(ui.dp(2), ui.dp(7), ui.dp(2), ui.dp(7))
                row.addView(ui.avatar(m.name.take(1), 30))
                row.addView(ui.text("  ${m.name}", 13.5f, ui.tk.text).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(View(this@GroupChatActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                row.addView(orderButton("↑", i > 0) { moveMember(i, i - 1) })
                row.addView(orderButton("↓", i < group.members.size - 1) { moveMember(i, i + 1) })
                addView(row)
            }
        }
    }

    private fun orderButton(label: String, enabled: Boolean, onClick: () -> Unit): TextView =
        ui.text(label, 15f, if (enabled) ui.tk.accent else ui.tk.textFaint).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34))
            if (enabled) setOnClickListener { onClick() }
        }

    private fun moveMember(from: Int, to: Int) {
        if (from == to) return
        val list = group.members.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        group = GroupStore.update(this, group.copy(members = list))
        paintChannels()
        // 面板内容依赖顺序，重开一次最直接（成员数量是个位数）
        dismissPanel()
        orderDialog()
    }

    // ------------------------------------------------------------ 群设置

    private fun showGroupMenu() {
        val items = arrayOf(
            "公共世界书",
            "群预设",
            "群运营商",
            "群正则组",
            "发言顺序",
            "邀请成员",
            "重命名",
            "删除群聊",
        )
        AlertDialog.Builder(this)
            .setTitle(group.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickPublicBooks()
                    1 -> pickGroupPreset()
                    2 -> pickGroupApi()
                    3 -> pickGroupRegex()
                    4 -> orderDialog()
                    5 -> inviteMembers()
                    6 -> renameGroup()
                    7 -> confirmDeleteGroup()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameGroup() {
        val edit = ui.input("群名").apply {
            setText(group.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("群名")
            .setView(wrapForDialog(edit))
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

    private fun pickGroupApi() {
        val options = ConnectionStore.llmOptions()
        if (options.isEmpty()) {
            toast("还没有配置运营商")
            return
        }
        val names = ArrayList<String>(options.size + 1).apply {
            add("跟随全局")
            options.forEach { add(it.second) }
        }
        val ids = ArrayList<String>(options.size + 1).apply {
            add("")
            options.forEach { add(it.first) }
        }
        val cur = ids.indexOf(group.apiConfigId.orEmpty()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("群运营商")
            .setMessage("成员默认用它，个人界面可以单独改")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                group = GroupStore.update(this, group.copy(apiConfigId = ids[which].ifBlank { null }))
                refreshControllers()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickGroupRegex() {
        val groups = catalog?.optJSONArray("regexGroups") ?: run {
            toast("引擎还没就绪，稍后再试")
            return
        }
        val n = groups.length()
        val names = Array(n) { groups.optJSONObject(it)?.optString("name").orEmpty() }
        val ids = Array(n) { groups.optJSONObject(it)?.optString("id").orEmpty() }
        val checked = BooleanArray(n) { ids[it] in group.regexGroupIds.orEmpty() }
        val nullMeansGlobal = group.regexGroupIds == null
        AlertDialog.Builder(this)
            .setTitle("群正则组")
            .setMessage(
                if (nullMeansGlobal) "当前跟随全局。勾选后群内所有频道只用勾中的组。"
                else "成员单独选了正则的用自己的，其余用这套。",
            )
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val picked = ids.filterIndexed { i, _ -> checked[i] }
                group = GroupStore.update(this, group.copy(regexGroupIds = picked))
                refreshControllers()
                toast("已保存")
            }
            .setNeutralButton("恢复跟随全局") { _, _ ->
                group = GroupStore.update(this, group.copy(regexGroupIds = null))
                refreshControllers()
                toast("已恢复跟随全局")
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

    /** 群级配置改了：已打开的频道要重新读群信息（书/预设/API 都从 GroupStore 现取）。 */
    private fun refreshControllers() {
        controllers.values.forEach { it.refreshConfig() }
    }

    private fun loadCatalog() {
        GenBridge.fetchAssetCatalog { json ->
            if (json.isNullOrBlank()) return@fetchAssetCatalog
            catalog = runCatching { JSONObject(json) }.getOrNull()
        }
    }

    // ------------------------------------------------------------ 搜索 / 摘要

    /**
     * 在当前频道里找关键词。
     *
     * 同一个词再点一次就跳到下一条 —— 这是在手机上最省事的"下一个"。
     * 只搜当前频道：跨频道搜索的落点还没想好（命中后要自动切频道？），
     * 先不做半成品。
     */
    private fun searchInChannel() {
        if (currentItems.isEmpty()) {
            toast("这个频道还没有消息")
            return
        }
        if (searchHits.isNotEmpty() && searchCursor >= 0) {
            searchCursor = (searchCursor + 1) % searchHits.size
            list.scrollToPosition(searchHits[searchCursor])
            toast("第 ${searchCursor + 1}/${searchHits.size} 条")
            return
        }
        val edit = ui.input("关键词").apply { setText(searchWord) }
        AlertDialog.Builder(this)
            .setTitle("搜索当前频道")
            .setView(wrapForDialog(edit))
            .setPositiveButton("搜索") { _, _ ->
                val word = edit.text?.toString().orEmpty().trim()
                if (word.isEmpty()) return@setPositiveButton
                searchWord = word
                searchHits = currentItems.mapIndexedNotNull { i, item ->
                    if (item.content.contains(word, ignoreCase = true)) i else null
                }
                if (searchHits.isEmpty()) {
                    toast("没找到「$word」")
                    return@setPositiveButton
                }
                searchCursor = 0
                list.scrollToPosition(searchHits[0])
                toast("命中 ${searchHits.size} 条，再点搜索看下一条")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun summarizeNow() {
        if (activeSessionId.isBlank()) return
        val c = controllerFor(activeSessionId)
        val name = group.members.firstOrNull { it.sessionId == activeSessionId }?.name
        if (name != null) {
            AlertDialog.Builder(this)
                .setTitle("压缩这页的历史")
                .setMessage("只压缩「$name」这个频道，公共区和其他成员的摘要不受影响。")
                .setPositiveButton("开始") { _, _ -> c.summarizeNow() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            c.summarizeNow()
        }
    }

    // ------------------------------------------------------------ 面板

    private var panel: Dialog? = null

    /** token 化的底部面板：深色皮肤下不会冒出系统白底。 */
    private fun showPanel(title: String, body: LinearLayout.() -> Unit) {
        dismissPanel()
        val box = ui.card(20f, 18)
        box.addView(ui.text(title, 16.5f, ui.tk.text, bold = true))
        val inner = ui.vertical().apply {
            setPadding(0, ui.dp(4), 0, ui.dp(8))
            body()
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(inner)
        }
        box.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = resources.displayMetrics.widthPixels - ui.dp(28)
        d.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setGravity(Gravity.BOTTOM)
        d.show()
        panel = d

        /*
         * 内容多时封顶并滚动。
         *
         * 不能一开始就把窗口设成固定高度：只有两行设置的面板会变成一块
         * 空荡荡的大白板。所以先按内容自然撑开，量完再决定要不要压。
         */
        val maxH = (resources.displayMetrics.heightPixels * 0.68f).toInt()
        inner.post {
            if (inner.height > maxH) {
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = maxH
                }
            }
        }
    }

    private fun dismissPanel() {
        runCatching { panel?.dismiss() }
        panel = null
    }

    /**
     * 设置面板里的一行：「标签 …… 当前值 ›」。
     *
     * 回调把 value 那个 TextView 交出去：选完新的值就地改文字，
     * 不用关掉面板再重开（重开会闪，连续改两项很难受）。
     */
    private fun detailRow(
        label: String,
        value: String,
        onClick: (TextView) -> Unit,
    ): LinearLayout {
        val row = ui.horizontal()
        row.setPadding(ui.dp(2), ui.dp(13), ui.dp(2), ui.dp(13))
        row.addView(ui.text(label, 13f, ui.tk.textDim))
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val valueView = ui.text(value, 13f, ui.tk.text)
        row.addView(valueView)
        row.addView(ui.text(" ›", 14f, ui.tk.textFaint))
        row.isClickable = true
        row.setOnClickListener { onClick(valueView) }
        return row
    }

    private fun wrapForDialog(v: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0)
        addView(
            v,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
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
        list.post { list.scrollBy(0, ui.dp(400)) }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
