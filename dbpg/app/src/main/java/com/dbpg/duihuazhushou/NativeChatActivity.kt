package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
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
import com.dbpg.duihuazhushou.chat.ChatBackground
import com.dbpg.duihuazhushou.chat.ImageFileCache
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import com.dbpg.stcore.imagegen.ImageParams
import com.dbpg.duihuazhushou.gallery.ImageViewerActivity
import com.dbpg.duihuazhushou.session.SessionRepo
import com.dbpg.duihuazhushou.settings.ApiConfigActivity
import org.json.JSONArray
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
        /** 换本会话背景 */
        private const val REQ_BG = 2001
        /** 全屏看图（删图后要把结果带回，摘掉消息里的引用） */
        private const val REQ_VIEW_IMG = 3001
        /** ⋯ 菜单「出图参考图」选图 */
        private const val REQ_GEN_REF = 3102

        private const val M_EDIT = 1
        private const val M_REGEN = 2
        private const val M_DELETE = 3
        private const val M_BRANCH = 4
    }

    private lateinit var list: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var quickImgBtn: View
    private lateinit var modelChip: TextView
    private lateinit var jumpBottomBtn: TextView
    private lateinit var summaryBtn: View

    /** 列表内容是否"贴着最新"。往上翻历史时为 false，新消息不强行拽回底部。 */
    private var stickToBottom = true
    /** 最近一次渲染出的消息（搜索用；onItems 里维护）。 */
    private var lastItems: List<ChatItem> = emptyList()

    /** 快捷生图是否在跑；以及"等模型回标签"时暂存的动作 id。 */
    private var quickBusy = false
    private var quickActionId: String = ""
    private var quickStyleArtist = ""
    private var quickStyleQuality = ""
    private var quickStyleNegative = ""
    /**
     * 出图参考图（发给生图模型、锁人物形象的那张）：存 image.genRefs.chat，
     * 聊天 ⋯ 菜单里随时换。风格页的参考图只是给用户看的风格示意，不进这里。
     */
    private var quickGenRefImage = ""
    private var quickGenRefStrength = 0.6
    private var quickGenRefInfo = 0.7
    /** ⋯ 菜单「出图参考图」弹窗里的预览控件（选完图要就地刷新）。 */
    private var genRefDialogThumb: ImageView? = null
    private var genRefDialogHint: TextView? = null
    private var quickDnaTags = ""
    private var quickSize = ""
    private var quickApiUrl = ""
    private var quickApiKey = ""
    private var quickApiModel = ""
    /** 快捷生图走与标签生图相同的渲染器，参数一个不少地带进请求体。 */
    private var quickEngine = ImageEngine.NAI_RELAY
    private var quickWidth = 832
    private var quickHeight = 1216
    private var quickSteps = 28
    private var quickCfg = 6.0
    private var quickSampler = "k_euler_ancestral"
    private var quickNoise = ""

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
        // 点标题改名：长按列表也能改，但聊天时最顺手的入口是在标题上
        titleView.setOnClickListener { promptRename() }
        quickImgBtn = findViewById(R.id.chat_quickimg)
        quickImgBtn.setOnClickListener { quickImage() }

        /*
         * 快捷换对话模型：chip 紧挨聊天名右边。
         * 两级弹窗 —— 先运营商、再该家保存的模型名，与 API 配置页同一份数据
         * （ConnectionStore 本地读写，点完立即生效，下一条消息就用新模型）。
         */
        modelChip = findViewById(R.id.chat_model_chip)
        modelChip.setOnClickListener { showModelPicker() }
        refreshModelChip()

        /*
         * 「回到最新」：往上翻历史后出现，点一下回底部。
         * scrollToPosition 只保证最后一条"可见"（会把它钉在列表顶部），
         * 所以到底后补一次 scrollBy 把视口压到真正的底 —— 否则短消息时
         * 下面会空出一大片（用户反馈的"下方一大片空的"就是它）。
         */
        jumpBottomBtn = findViewById(R.id.chat_jump_bottom)
        jumpBottomBtn.setOnClickListener {
            stickToBottom = true
            jumpBottomBtn.visibility = View.GONE
            scrollToBottom()
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val atBottom = !rv.canScrollVertically(1)
                stickToBottom = atBottom
                if (viewingLog) return
                jumpBottomBtn.visibility =
                    if (!atBottom && lastItems.isNotEmpty()) View.VISIBLE else View.GONE
            }
        })
        sendBtn = findViewById(R.id.chat_send)
        stopBtn = findViewById(R.id.chat_stop)
        val close = findViewById<View>(R.id.chat_close)
        val more = findViewById<TextView>(R.id.chat_more)
        summaryBtn = findViewById(R.id.chat_summary)
        summaryBtn.setOnClickListener { showSummaryDialog() }

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
        more.setOnClickListener { showMoreMenu() }
        sendBtn.setOnClickListener { submitInput() }
        stopBtn.setOnClickListener { controller.stop() }
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
        // 离屏卡片渲染器要挂在当前窗口上（部分机型不 attach 就不 layout，画出来是白图）
        com.dbpg.duihuazhushou.chat.render.CardRenderer.attach(this)
        applyBackground()
        // 网页设置里可能换了预设 / 正则 / 生图接口，回来跟上
        controller.refreshConfig()
        // 对话模型可能在设置页换过，chip 跟上
        refreshModelChip()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 正在生成就退出：已经吐出来的正文先存下来，别让用户白等
        controller.release()
        // 离屏 WebView 属于本 Activity 的 context，不销毁就是整页泄漏
        com.dbpg.duihuazhushou.chat.render.CardRenderer.detach(this)
    }

    // ------------------------------------------------------------ ChatController.Sink

    override fun onItems(items: List<ChatItem>, scrollToBottom: Boolean) {
        if (viewingLog) return
        lastItems = items
        // 贴底才跟着新内容走；用户正在往上翻历史时不打扰
        val shouldScroll = scrollToBottom || stickToBottom
        adapter.submitList(items) {
            if (shouldScroll && items.isNotEmpty()) {
                scrollToBottom()
            }
        }
    }

    /**
     * 滚到最新消息。
     *
     * 不能只调 [RecyclerView.scrollToPosition]：它保证的是"最后一条可见"，
     * 会把最后一条钉在列表顶部，消息短时下面空出一大片（滚动条看着像到底了）。
     * 先定位再补一次 scrollBy 压到真正的底。
     */
    private fun scrollToBottom() {
        val n = adapter.itemCount
        if (n == 0) return
        list.scrollToPosition(n - 1)
        list.post {
            val last = list.findViewHolderForAdapterPosition(n - 1)
            if (last != null) {
                val dy = last.itemView.bottom + list.paddingBottom - list.height
                if (dy > 0) list.scrollBy(0, dy)
            }
        }
    }

    /**
     * 流式增量：只改这一条的气泡，不重建整个列表。
     *
     * 贴底策略和整表刷新一致：用户往上翻历史时不抢滚动位置。
     */
    override fun onStreamingText(actionId: String, text: String) {
        if (viewingLog) return
        adapter.patchStreaming(actionId, text)
        if (stickToBottom) scrollToBottom()
    }

    override fun onTitle(title: String) {
        if (!viewingLog) titleView.text = title
        // 会话绑定完成的信号。背景要在拿到 sessionId 之后再贴一次：
        // onResume 时 controller.open 还异步跑着，那时 doc 为空，
        // 只能贴全局背景，会话自己的那张会被漏掉。
        applyBackground()
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

    /** 摘要跑起来时把入口淡一点：没有进度条，但至少能看出"正在忙"。 */
    override fun onSummaryState(active: Boolean) {
        summaryBtn.alpha = if (active) 0.35f else 1f
        summaryBtn.isEnabled = !active
        refreshSummaryBadge()
    }

    override fun onSummaryChanged() {
        refreshSummaryBadge()
    }

    /** 有摘要时图标用主题色，没摘要用普通灰 —— 一眼能看出这条会话压没压过。 */
    private fun refreshSummaryBadge() {
        val has = controller?.summaries()?.isNotEmpty() == true
        val color = if (has) 0xFF2F66D8.toInt() else 0xFF3A4353.toInt()
        (summaryBtn as? android.widget.ImageView)?.imageTintList =
            android.content.res.ColorStateList.valueOf(color)
    }

    // ------------------------------------------------------------ 列表交互

    private val adapterListener = object : ChatAdapter.Listener {
        override fun onImageClick(item: ChatItem, path: String) {
            // 用 forResult 打开：查看器里删图后要把消息里的引用一并摘掉
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this@NativeChatActivity, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, path),
                REQ_VIEW_IMG,
            )
        }

        override fun onVariantSwitch(item: ChatItem, delta: Int) {
            controller.switchVariant(item.actionId, delta)
        }

        override fun onActionMenu(item: ChatItem, anchor: View) {
            showActionMenu(item, anchor)
        }

        override fun onImageGen(item: ChatItem) {
            promptImageGenFor(item)
        }

        override fun onCardNeeded(item: ChatItem, widthPx: Int) {
            controller.ensureCard(item.actionId, widthPx)
        }

        override fun onCardOpen(item: ChatItem) {
            val html = item.html ?: return
            startActivity(
                Intent(this@NativeChatActivity, com.dbpg.duihuazhushou.chat.render.CardViewerActivity::class.java)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_HTML, html)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_TEXT, item.content)
                    .putExtra(com.dbpg.duihuazhushou.chat.render.CardViewerActivity.EXTRA_TITLE, titleView.text),
            )
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
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_BG) {
            handleBackgroundResult(data)
            return
        }
        if (requestCode == REQ_VIEW_IMG) {
            // 查看器里删了图：把消息里对它的引用摘掉，列表立刻不再显示
            val deleted = data?.getStringExtra(ImageViewerActivity.EXTRA_DELETED_PATH).orEmpty()
            if (deleted.isNotBlank()) controller.removeImageByPath(deleted)
            return
        }
        if (requestCode == REQ_GEN_REF) {
            handleGenRefPick(data)
            return
        }
        if (requestCode != REQ_JSONL) return
        val uri = data?.data ?: return
        val items = ChatLogLoader.load(this, uri)
        if (items.isEmpty()) {
            Toast.makeText(this, "没读到消息（可能不是 .jsonl 记录）", Toast.LENGTH_SHORT).show()
            return
        }
        viewingLog = true
        titleView.text = "聊天记录（${items.size} 条）"
        lastItems = items
        adapter.submitList(items) { list.scrollToPosition(items.size - 1) }
        Toast.makeText(this, "已加载 ${items.size} 条", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 顶栏菜单 / 背景

    /** 自定义菜单的一行。action 里不要持有 Dialog，统一由外层 dismiss。 */
    private data class MenuEntry(
        val icon: String,
        val title: String,
        val desc: String,
        val action: () -> Unit,
    )

    /**
     * ⋯ 菜单（卡片式）。
     *
     * 顶栏只留「返回 / 标题 / ⋯」，功能全收在这里。原来用系统
     * `AlertDialog.setItems()` 一排纯文字，选项多了以后既不好找也看不出
     * 每项做什么；现在按「会话 / 内容与资产 / 创作」分组，每行带图标和
     * 一句说明，白卡片圆角与首页风格一致。
     *
     * 顺手合并了原来「清除本会话背景」「使用全局背景」两个行为完全相同的
     * 选项（清除本会话图本来就会回落到全局图）。
     */
    private fun showMoreMenu() {
        val sid = controller?.doc?.id.orEmpty()
        val hasSessionBg = ChatBackground.hasSession(this, sid)

        val sessionItems = mutableListOf(
            MenuEntry("✏️", "重命名会话", "给这次冒险起个好记的名字") { promptRename() },
            MenuEntry("🔍", "搜索消息", "搜关键词定位剧情，方便改写") { showSearch() },
            MenuEntry("📚", "本对话资产", "世界书 / 预设 / 正则 / DNA，按对话单独配置") {
                com.dbpg.duihuazhushou.chat.ChatAssetsDialog.show(this, controller?.doc?.id.orEmpty())
            },
            MenuEntry("🎨", "出图参考图", "发给生图模型锁人物形象，随时换（跟风格参考图是两回事）") {
                showGenRefDialog()
            },
            MenuEntry("🖼️", "换本会话背景", "只对当前会话生效，不影响其它会话") { pickBackground() },
        )
        if (hasSessionBg) {
            sessionItems.add(
                MenuEntry("🧹", "清除本会话背景", "回到页面底色（可在设置里换）") {
                    ChatBackground.clear(this, sid)
                    applyBackground()
                },
            )
        }
        if (sid.isNotBlank()) {
            sessionItems.add(
                MenuEntry("🗑️", "删除会话", "连同全部消息一起删除，不可恢复") { confirmDeleteSession(sid) },
            )
        }
        val sections = listOf(
            "会话" to sessionItems,
            "内容与资产" to listOf(
                MenuEntry("📚", "资产（预设 / 世界书 / 正则）", "管理 SillyTavern 兼容层内容") {
                    startActivity(Intent(this, com.dbpg.duihuazhushou.assets.AssetsHomeActivity::class.java))
                },
                MenuEntry("📥", "导入聊天记录", "从 jsonl 载入一份只读预览") { pickJsonl() },
            ),
            // 聊天里的生图是「对话生图」：按上文生成、用 chat 场景配置。
            // 独立的标签生图页在首页「标签生图」入口，两套配置互不干扰。
            "创作" to listOf(
                MenuEntry("🎨", "对话生图", "按最近 N 条上文生成一张配图") { quickImage() },
                MenuEntry("⚙️", "对话生图设置", "上文条数 / 运营商 / 模型 / 尺寸") {
                    startActivity(
                        Intent(this, com.dbpg.duihuazhushou.settings.ChatImageConfigActivity::class.java),
                    )
                },
            ),
        )

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_chat_menu, null)
        dialog.setContentView(view)
        // 系统默认白底方角会盖住自定义圆角，必须显式设透明
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val title = controller?.doc?.title.orEmpty()
        view.findViewById<TextView>(R.id.menu_subtitle).text =
            if (title.isEmpty()) "当前会话"
            else if (title.length > 10) title.take(10) + "…" else title
        val box = view.findViewById<LinearLayout>(R.id.menu_container)
        for ((section, entries) in sections) {
            box.addView(TextView(this).apply {
                text = section
                textSize = 11f
                setTextColor(0xFF8A94A6.toInt())
                setPadding(dp(4), dp(8), 0, dp(2))
            })
            for (e in entries) {
                val row = LayoutInflater.from(this@NativeChatActivity)
                    .inflate(R.layout.item_chat_menu, box, false)
                row.findViewById<TextView>(R.id.menu_icon).text = e.icon
                row.findViewById<TextView>(R.id.menu_title).text = e.title
                row.findViewById<TextView>(R.id.menu_desc).text = e.desc
                row.setOnClickListener {
                    dialog.dismiss()
                    e.action()
                }
                box.addView(row)
            }
        }
        view.findViewById<TextView>(R.id.menu_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * 应用聊天背景：会话图优先，没有就用全局图；都没有回落纯色。
     * 遮罩浓度来自外观设置（bgDim），保证深色照片上正文仍可读。
     */
    private fun applyBackground() {
        val sid = controller?.doc?.id.orEmpty()
        val bitmap = ChatBackground.resolve(this, sid)
        ChatBackground.applyTo(findViewById(R.id.chat_root), bitmap, appearance.bgDim)
    }

    private fun pickBackground() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQ_BG)
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开图片选择器：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 选图回来：存成本会话背景并立即应用（结果分发在 onActivityResult 里）。 */
    private fun handleBackgroundResult(data: Intent?) {
        val uri = data?.data ?: return
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val sid = controller?.doc?.id.orEmpty()
            if (sid.isBlank()) {
                Toast.makeText(this, "会话还没加载好，稍后再试", Toast.LENGTH_SHORT).show()
                return
            }
            if (ChatBackground.save(this, sid, bmp)) {
                applyBackground()
                Toast.makeText(this, "背景已更换", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "保存失败（图片过大？）", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this, "读取图片失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ 改名

    // ------------------------------------------------------------ 出图参考图（发给模型的那张）

    /**
     * 出图参考图设置弹窗（chat 场景槽位）。
     *
     * 与风格页的「风格参考图」分工：那张只给用户看风格感觉；
     * 这张是真的发给生图模型（NAI 参考图 / Vibe Transfer）锁人物形象的，
     * 所以做成随时可换：⋯ 菜单点开 → 换图 / 清除 / 调强度与信息提取度。
     */
    private fun showGenRefDialog() {
        // 本地快照读当前槽位，不等引擎
        val st = com.dbpg.duihuazhushou.settings.ConnectionStore.snapshot("getImageStyle")
        val ref = st?.optJSONObject("genRefs")?.optJSONObject("chat") ?: JSONObject()
        quickGenRefImage = ref.optString("image")
        quickGenRefStrength = ref.optDouble("strength", 0.6)
        quickGenRefInfo = ref.optDouble("info", 0.7)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val hint = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A94A6.toInt())
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(140),
            ).also { it.topMargin = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEF1F6.toInt())
        }
        genRefDialogThumb = thumb
        genRefDialogHint = hint

        fun render() {
            if (quickGenRefImage.isBlank()) {
                thumb.visibility = View.GONE
                hint.text = "未设置 · 设置后对话生图 / 自动配图都会带上这张参考"
            } else {
                thumb.visibility = View.VISIBLE
                hint.text = "已设置 · 生图时发给模型维持形象（NAI 参考图）"
                runCatching {
                    val b = Base64.decode(quickGenRefImage.substringAfter("base64,"), Base64.DEFAULT)
                    thumb.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                }.onFailure { thumb.setImageBitmap(null) }
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(8) }
        }
        fun softBtn(text: String, color: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0xFFEEF1F6.toInt())
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { onClick() }
            }
        btnRow.addView(
            softBtn("换图", 0xFF2F66D8.toInt()) {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        },
                        REQ_GEN_REF,
                    )
                } catch (t: Throwable) {
                    toast("打不开图片选择器")
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = dp(6)
            },
        )
        btnRow.addView(
            softBtn("清除", 0xFF8A94A6.toInt()) {
                quickGenRefImage = ""
                pushGenRef(image = "")
                render()
                toast("已清除出图参考图")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(6)
            },
        )

        fun sliderRow(label: String, init: Double, onChange: (Double) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(6) }
            }
            val tv = TextView(this).apply {
                text = "$label %.2f".format(init)
                textSize = 12f
                setTextColor(0xFF3A4353.toInt())
            }
            val bar = SeekBar(this).apply {
                max = 100
                progress = (init * 100).toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        tv.text = "$label %.2f".format(p / 100.0)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        onChange((sb?.progress ?: 0) / 100.0)
                    }
                })
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0f).also {
                it.width = dp(110)
            })
            row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        box.addView(hint)
        box.addView(thumb)
        box.addView(btnRow)
        box.addView(sliderRow("参考强度", quickGenRefStrength) {
            quickGenRefStrength = it
            pushGenRef()
        })
        box.addView(sliderRow("信息提取", quickGenRefInfo) {
            quickGenRefInfo = it
            pushGenRef()
        })
        render()

        AlertDialog.Builder(this)
            .setTitle("出图参考图（发给模型）")
            .setView(box)
            .setPositiveButton("好了", null)
            .show()
    }

    /** 选图回来：压缩落库 + 就地刷新弹窗预览。 */
    private fun handleGenRefPick(data: Intent?) {
        val uri: Uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                toast("这张图解不开"); return
            }
            val m = maxOf(bmp.width, bmp.height)
            val scaled = if (m <= 512) bmp else {
                val r = 512f / m
                Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
            }
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            quickGenRefImage = "data:image/jpeg;base64," +
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            pushGenRef(image = quickGenRefImage)
            // 弹窗还开着就地刷新
            genRefDialogThumb?.let { iv ->
                iv.visibility = View.VISIBLE
                iv.setImageBitmap(scaled)
            }
            genRefDialogHint?.text = "已设置 · 生图时发给模型维持形象（NAI 参考图）"
            toast("出图参考图已设置，下次生图即生效")
        } catch (t: Throwable) {
            toast("读取图片失败：${t.message}")
        }
    }

    /** 写 chat 槽位（本地命令立即落盘）。image 传 "" 清图；不传只改参数。 */
    private fun pushGenRef(image: String? = null) {
        val o = JSONObject().put("cmd", "genRefSet").put("scene", "chat")
        image?.let { o.put("image", it) }
        o.put("strength", quickGenRefStrength)
        o.put("info", quickGenRefInfo)
        com.dbpg.duihuazhushou.settings.EngineBridge.command(o)
    }

    private fun promptRename() {
        val cur = controller?.doc?.title.orEmpty()
        val input = android.widget.EditText(this).apply {
            setText(cur)
            setHint("会话名")
            setTextSize(15f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSelection(text?.length ?: 0)
        }
        val wrap = android.widget.LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ -> controller?.rename(input.text?.toString().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「前情提要」入口（右上角）。
     *
     * 把模型真正会收到的那段提要原样摆出来，允许直接改 —— 这是"用户可
     * 查看并更改摘要"的落点。改完保存即生效（下一次发送就用新版）。
     */
    private fun showSummaryDialog() {
        val c = controller ?: return
        val current = c.summaries().joinToString("\n") { it.text.trim() }
        val covered = c.summarizedUpTo()
        val total = c.doc?.actions?.size ?: 0

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val tip = TextView(this).apply {
            setText(
                when {
                    current.isNotBlank() && covered > 0 ->
                        "已覆盖前 $covered 条（共 $total 条）。发消息时这段提要替代那几条原文，省下的上下文留给新剧情。可以直接改。"
                    current.isNotBlank() ->
                        "这段提要会替代已覆盖的原文发送给模型。可以直接改。"
                    else ->
                        "把早期对话压成一段提要，发消息时替代原文，长会话才跑得动。点「立即浓缩」压最早的一批；也可以在设置里开自动摘要。"
                },
            )
            setTextSize(12f)
            setTextColor(0xFF66707F.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val input = EditText(this).apply {
            setText(current)
            hint = "还没有摘要，点下面「立即浓缩」生成"
            setTextSize(14f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = android.view.Gravity.TOP
            minLines = 6
            maxLines = 12
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val runBtn = TextView(this).apply {
            setText("立即浓缩")
            setTextSize(13f)
            setTextColor(0xFF2F66D8.toInt())
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val clearBtn = TextView(this).apply {
            setText("清除摘要")
            setTextSize(13f)
            setTextColor(0xFFA33A3A.toInt())
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        box.addView(tip)
        box.addView(
            input,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(170),
            ).apply { topMargin = dp(8) },
        )
        row.addView(runBtn)
        row.addView(
            clearBtn,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        box.addView(row)

        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("前情提要")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("关闭", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = input.text?.toString().orEmpty().trim()
                if (t.isNotEmpty()) c.updateSummaryText(t)
                dlg.dismiss()
            }
        }
        runBtn.setOnClickListener {
            dlg.dismiss()
            c.summarizeNow()
        }
        clearBtn.setOnClickListener {
            if (current.isBlank()) {
                toast("还没有摘要")
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("清除摘要")
                .setMessage("清除后会把全文发给模型（原文一直没删，随时可以再压一次）。")
                .setPositiveButton("清除") { _, _ ->
                    c.clearSummaries()
                    dlg.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        dlg.show()
    }

    /**
     * 删除当前会话：先清掉它的专属背景图，再删会话文件与索引，最后退出聊天页。
     * 会话列表 onResume 会自己重新读一次索引，不需要额外广播。
     */
    private fun confirmDeleteSession(id: String) {
        val title = controller?.doc?.title.orEmpty().ifBlank { "当前会话" }
        android.app.AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("「$title」及其全部消息会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                ChatBackground.clear(this, id)
                com.dbpg.duihuazhushou.session.SessionRepo.delete(id) { ok ->
                    if (ok) {
                        toast("已删除")
                        finish()
                    } else {
                        toast("删除失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    // ------------------------------------------------------------ 快捷换对话模型

    /**
     * 顶栏模型 chip 的文案：跟当前对话模型走。
     *
     * 数据来自 [ConnectionStore] 的 getConfig 本地快照 —— 不等引擎、
     * 不显示地址/密钥，只有模型名。设置页换过模型后 onResume 会再刷一次。
     */
    private fun refreshModelChip() {
        EngineBridge.fetch(this, "getConfig") { obj ->
            if (obj == null || isFinishing) return@fetch
            val name = obj.optString("modelName").ifBlank { obj.optString("model") }
            modelChip.text = if (name.isBlank()) "模型 ▾" else "$name ▾"
        }
    }

    /**
     * 两级换模型：点 chip → 弹运营商列表 → 点运营商 → 弹这家保存的模型名。
     *
     * 只显示运营商名与模型名；地址和密钥留在「API 配置」页里，聊天里不露。
     * 选中即发 useModel（本地立即生效 + 镜像给引擎），下一条消息就走新模型。
     */
    private fun showModelPicker() {
        EngineBridge.fetch(this, "getConfig") { obj ->
            if (isFinishing) return@fetch
            if (obj == null) {
                toast("配置还没就绪，稍后再试")
                return@fetch
            }
            val providers = obj.optJSONArray("providers") ?: JSONArray()
            if (providers.length() == 0) {
                AlertDialog.Builder(this)
                    .setMessage("还没有对话运营商。先到「API 配置」添加运营商和模型。")
                    .setPositiveButton("去配置") { _, _ ->
                        startActivity(Intent(this, ApiConfigActivity::class.java))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@fetch
            }
            val names = Array(providers.length()) { i ->
                val p = providers.getJSONObject(i)
                val label = p.optString("note")
                    .ifBlank { p.optString("baseUrl") }
                    .ifBlank { "运营商 ${i + 1}" }
                val count = p.optJSONArray("models")?.length() ?: 0
                "$label（$count 个模型）"
            }
            AlertDialog.Builder(this)
                .setTitle("切换对话模型 · 先选运营商")
                .setItems(names) { _, i -> pickModelIn(providers.getJSONObject(i)) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 二级弹窗：这家运营商下保存的模型名，当前在用的打 ✓。 */
    private fun pickModelIn(provider: JSONObject) {
        val models = provider.optJSONArray("models") ?: JSONArray()
        if (models.length() == 0) {
            toast("这家还没保存模型")
            return
        }
        val labels = Array(models.length()) { j ->
            val m = models.getJSONObject(j)
            val name = m.optString("model").ifBlank { m.optString("name") }.ifBlank { "未命名" }
            if (m.optBoolean("current")) "✓ $name" else name
        }
        val providerName = provider.optString("note")
            .ifBlank { provider.optString("baseUrl") }
            .ifBlank { "运营商" }
        AlertDialog.Builder(this)
            .setTitle("$providerName · 选模型")
            .setItems(labels) { _, j ->
                val m = models.getJSONObject(j)
                val uid = m.optString("uid")
                if (uid.isBlank()) return@setItems
                val name = m.optString("model").ifBlank { m.optString("name") }
                EngineBridge.command(JSONObject().put("cmd", "useModel").put("uid", uid))
                modelChip.text = "$name ▾"
                toast("已切换到 $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 会话内搜索

    /**
     * 在本会话里搜消息，点结果跳到那一条。
     *
     * 改剧情时最常用：搜个角色名/关键词，直接定位到那段对话，
     * 再用消息的「⋯ 操作」编辑。只搜 content（已跑过显示清洗的纯文本）。
     */
    private fun showSearch() {
        if (lastItems.isEmpty()) {
            toast("还没有可搜索的消息")
            return
        }
        val box = EditText(this).apply {
            hint = "关键词，如角色名、地点…"
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_input)
        }
        val wrap = android.widget.LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索本会话")
            .setView(wrap)
            .setPositiveButton("搜索") { _, _ -> doSearch(box.text?.toString().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val hits = lastItems.mapIndexedNotNull { idx, item ->
            if (item.content.contains(q, ignoreCase = true)) idx to item else null
        }
        if (hits.isEmpty()) {
            toast("没找到「$q」")
            return
        }
        val labels = hits.map { (idx, item) ->
            val role = if (item.role == "user") "你" else if (item.role == "system") "系统" else "AI"
            val flat = item.content.replace('\n', ' ')
            val at = flat.indexOf(q, ignoreCase = true).coerceAtLeast(0)
            val start = (at - 10).coerceAtLeast(0)
            val end = (at + q.length + 18).coerceAtMost(flat.length)
            "第 ${idx + 1} 条 · $role：…${flat.substring(start, end)}…"
        }
        AlertDialog.Builder(this)
            .setTitle("找到 ${hits.size} 处")
            .setItems(labels.toTypedArray()) { _, i ->
                jumpToMessage(hits[i].first)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 跳到第 position 条消息（顶部对齐，方便接着往下读）。 */
    private fun jumpToMessage(position: Int) {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(position, 0)
    }

    // ------------------------------------------------------------ 单条消息生图

    /**
     * 给指定那条消息配图：选「带之前几条」当上下文，图挂回那条消息上。
     *
     * 与顶栏 🎨 的区别：🎨 取最新 N 条、挂最新回复；这里锚点是用户点的
     * 那一条，给旧剧情补图不会带错上下文。
     */
    private fun promptImageGenFor(item: ChatItem) {
        if (item.actionId.isBlank()) return
        if (quickBusy) {
            toast("还在出图，稍等一下")
            return
        }
        val options = arrayOf(
            "只这条", "这条 + 前 1 条", "这条 + 前 2 条",
            "这条 + 前 3 条", "这条 + 前 5 条", "这条 + 前 8 条",
        )
        val counts = intArrayOf(0, 1, 2, 3, 5, 8)
        AlertDialog.Builder(this)
            .setTitle("给这条消息配图")
            .setItems(options) { _, which ->
                quickImage(anchorActionId = item.actionId, beforeCount = counts[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 快捷生图

    /**
     * 按「对话生图」配置快速出图：
     *   读配置 → 取上文 → （可选）LLM 转标签 → 原生生图 → 挂到目标消息。
     * 配置来源与聊天自动配图同一套（web image store）。
     *
     * @param anchorActionId 图挂到哪条消息；空 = 最新 AI 回复（顶栏 🎨 的行为）
     * @param beforeCount    锚点之前再带几条当上下文；null = 用配置里的 quickContextWindow
     */
    private fun quickImage(anchorActionId: String? = null, beforeCount: Int? = null) {
        if (quickBusy) {
            toast("还在出图，稍等一下")
            return
        }
        val actionId = anchorActionId ?: controller?.latestActionId()
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
            // 读「对话生图」这一场景，不读全局 current：标签生图换运营商
            // 不会把聊天里的 🎨 带着改掉。
            val cur = cfg.optJSONObject("scenes")?.optJSONObject("chat")
                ?: cfg.optJSONObject("current") ?: JSONObject()
            val auto = cfg.optJSONObject("auto") ?: JSONObject()
            val n = beforeCount ?: auto.optInt("quickContextWindow", 5)
            val useLlm = cur.optBoolean("useLlmOptimization", true)
            val llmId = cur.optString("llmConfigId")

            quickApiUrl = cur.optString("baseUrl")
            quickApiKey = cur.optString("apiKey")
            quickApiModel = cur.optString("model")
            quickWidth = cur.optInt("width", 832)
            quickHeight = cur.optInt("height", 1216)
            quickEngine = ImageEngine.of(cur.optString("engine"))
            quickSize = "${quickWidth}x${quickHeight}"
            quickActionId = actionId

            EngineBridge.fetch(this, "getImageStyle") { st ->
                if (st == null) {
                    finishQuick("风格读取失败")
                    return@fetch
                }
                quickStyleArtist = st.optString("artistTags")
                quickStyleQuality = st.optString("qualityTags")
                quickStyleNegative = st.optString("negativePrompt")
                // 出图参考图走 chat 场景槽位（⋯ 菜单里设的那张），不跟风格
                val ref = st.optJSONObject("genRefs")?.optJSONObject("chat") ?: JSONObject()
                quickGenRefImage = ref.optString("image")
                quickGenRefStrength = ref.optDouble("strength", 0.6)
                quickGenRefInfo = ref.optDouble("info", 0.7)
                quickSteps = st.optInt("steps", 28)
                quickCfg = st.optDouble("cfgScale", 6.0)
                quickSampler = st.optString("sampler")
                    .ifBlank { ImageEngineSupport.defaultSamplerFor(quickApiModel) }
                quickNoise = st.optString("noiseSchedule")

                if (quickApiUrl.isBlank()) {
                    finishQuick("还没配置生图运营商")
                    return@fetch
                }

                // 锚点生图用「那条 + 前 N 条」；顶栏 🎨 用「最近 N 条」
                val context = if (anchorActionId != null) {
                    controller?.contextTextAt(actionId, n).orEmpty()
                } else {
                    controller?.contextText(n).orEmpty()
                }
                if (context.isBlank()) {
                    finishQuick("上下文为空")
                    return@fetch
                }
                // 角色 DNA：本对话勾选的英文外观标签，只在生图时生效（LLM 人物参考 + 手动模式直接拼）
                val dna = com.dbpg.duihuazhushou.settings.ConnectionStore.dnaTags(
                    com.dbpg.duihuazhushou.session.SessionBindingStore.load(
                        this, controller?.doc?.id.orEmpty(),
                    ).dnaIds,
                )
                quickDnaTags = dna
                val contextWithDna = if (dna.isBlank()) context
                else "人物外观参考（提示词中必须保留这些特征）：$dna\n\n$context"
                if (useLlm) {
                    toast("让模型生成画面标签…")
                    EngineBridge.command(
                        JSONObject().put("cmd", "optimizeImagePrompt")
                            .put("context", contextWithDna).put("llmConfigId", llmId),
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
                val act = c.doc?.actions?.find { it.id == quickActionId }
                    ?: c.doc?.actions?.lastOrNull { it.ai.isNotBlank() }
                act?.let { c.plainTextOf(it) }
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
        val full = listOf(quickStyleArtist, quickStyleQuality, quickDnaTags, tags)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        toast("出图中…")
        val model = quickApiModel.ifBlank { "nai-diffusion-4-5-full" }
        val params = ImageParams(
            engine = quickEngine,
            url = quickApiUrl,
            apiKey = quickApiKey,
            model = model,
            prompt = full,
            negativePrompt = quickStyleNegative,
            width = quickWidth,
            height = quickHeight,
            cfgScale = quickCfg,
            steps = quickSteps,
            sampler = quickSampler,
            noiseSchedule = quickNoise,
            refImageBase64 = quickGenRefImage.ifBlank { null },
            refStrength = quickGenRefStrength,
            refInfoExtracted = quickGenRefInfo,
        )
        val urls = ImageEngineSupport.candidateUrls(quickEngine, quickApiUrl)
        val req = ImageGenService.Request(
            url = urls.firstOrNull() ?: quickApiUrl,
            apiKey = quickApiKey,
            model = model,
            prompt = full,
            size = quickSize.ifBlank { null },
            responseFormat = "b64_json",
            params = params,
            endpointCandidates = urls.drop(1),
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
