package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var modelChip: TextView
    private lateinit var jumpBottomBtn: TextView

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
        val close = findViewById<TextView>(R.id.chat_close)
        val more = findViewById<TextView>(R.id.chat_more)

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
        val hasGlobalBg = ChatBackground.hasGlobal(this)

        val sessionItems = mutableListOf(
            MenuEntry("✏️", "重命名会话", "给这次冒险起个好记的名字") { promptRename() },
            MenuEntry("🔍", "搜索消息", "搜关键词定位剧情，方便改写") { showSearch() },
            MenuEntry("🖼️", "换本会话背景", "只对当前会话生效，不影响其它会话") { pickBackground() },
        )
        if (hasSessionBg) {
            sessionItems.add(
                MenuEntry("🧹", "清除本会话背景", if (hasGlobalBg) "回到全局背景" else "恢复纯色背景") {
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
        val full = listOf(quickStyleArtist, quickStyleQuality, tags)
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
