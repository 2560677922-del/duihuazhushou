package com.dbpg.duihuazhushou.imagegen

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.settings.ProviderUi
import com.dbpg.stcore.imagegen.ImageEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生图运营商配置页（2026-11-11 重构，与 LLM 运营商页同一套信息架构）。
 *
 * ## 界面
 * 「运营商列表 → 详情（配置 / 模型）」。地址与密钥不再挂在页面顶部，
 * 而是收进每一家的「配置」Tab；密钥默认掩码，可点眼睛显隐。
 * 引擎与场景绑定也在配置 Tab —— 它们本来就是「这一家怎么用」的属性。
 *
 * ## 数据
 * 读写全部走原生 [com.dbpg.duihuazhushou.settings.ConnectionStore]，页面只发命令：
 *
 *   getImageConfig               当前配置 + 运营商全量（含 apiKey / engine / usedBy）
 *   imgSaveProviderConn          按 key 保存这一家的名称 / 地址 / 密钥（组内同步）
 *   imgAddModelsToProvider       按 key 往这一家批量加模型
 *   imgUseApi                    选中模型；带 scope=chat/tag 时绑定场景
 *   imgRemoveApi / imgRemoveApis / imgRemoveProvider
 *   imgAddProvider / imgRenameProvider / imgSetEngine
 *   fetchModels(json)            原生 OkHttp 拉列表 → onApiResult(kind=models)
 *
 * 旧页面只有「按当前接口」的 imgSaveConn / imgAddModels，编辑非当前运营商
 * 会串台；现在这两个命令仍保留给网页侧，但页面一律走带 key 的新命令。
 *
 * ## 换肤
 * 静态骨架由 UiBackdrop 全局染色；动态卡片走 [ProviderUi]，只读 ThemeTokens。
 * 两者共用同一套色板，换主题时整页一起变。
 */
class ImageGenConfigActivity : Activity() {

    // ── 静态骨架 ──
    private lateinit var listRoot: View
    private lateinit var detailRoot: View
    private lateinit var listBox: LinearLayout
    private lateinit var listStatus: TextView
    private lateinit var detailStatus: TextView
    private lateinit var searchEdit: EditText
    private lateinit var detailAvatar: TextView
    private lateinit var detailTitle: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var panes: List<View>
    private lateinit var cfgName: EditText
    private lateinit var cfgKey: EditText
    private lateinit var cfgBase: EditText
    private lateinit var cfgKeyEye: ImageView
    private lateinit var cfgRefetch: ImageView
    private lateinit var cfgEngineBox: LinearLayout
    private lateinit var cfgSceneBox: LinearLayout
    private lateinit var cfgInfo: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var modelBatch: TextView
    private lateinit var modelCount: TextView
    private lateinit var floatLeft: TextView
    private lateinit var floatRight: TextView

    // ── 状态 ──
    private var providers: JSONArray = JSONArray()
    /** 当前详情页对应的运营商 key；null = 列表态。 */
    private var currentKey: String? = null
    private var tab = 0
    private var search = ""
    private var keyVisible = false
    /** 用户动过详情表单：刷新时别把正在打的字冲掉。 */
    private var userEditedConn = false
    /** 程序化回填期间抑制 TextWatcher，避免误标脏。 */
    private var fillingConn = false
    /** 拉模型前会先静默保存连接，那次不要弹 toast、也不要打断流程。 */
    private var pendingFetch = false

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_config)
        Insets.applyTop(this)
        bindViews()
        wireEvents()
        paintTabs()
    }

    private fun bindViews() {
        listRoot = findViewById(R.id.ig_list)
        detailRoot = findViewById(R.id.ig_detail)
        listBox = findViewById(R.id.ig_list_box)
        listStatus = findViewById(R.id.ig_list_status)
        detailStatus = findViewById(R.id.ig_detail_status)
        searchEdit = findViewById(R.id.ig_search)
        detailAvatar = findViewById(R.id.ig_detail_avatar)
        detailTitle = findViewById(R.id.ig_detail_title)
        tabViews = listOf(findViewById(R.id.ig_tab_config), findViewById(R.id.ig_tab_models))
        panes = listOf(findViewById(R.id.ig_pane_config), findViewById(R.id.ig_pane_models))
        cfgName = findViewById(R.id.ig_cfg_name)
        cfgKey = findViewById(R.id.ig_cfg_key)
        cfgBase = findViewById(R.id.ig_cfg_base)
        cfgKeyEye = findViewById(R.id.ig_cfg_key_eye)
        cfgRefetch = findViewById(R.id.ig_cfg_refetch)
        cfgEngineBox = findViewById(R.id.ig_cfg_engines)
        cfgSceneBox = findViewById(R.id.ig_cfg_scenes)
        cfgInfo = findViewById(R.id.ig_cfg_info)
        modelBox = findViewById(R.id.ig_model_box)
        modelBatch = findViewById(R.id.ig_model_batch)
        modelCount = findViewById(R.id.ig_model_count)
        floatLeft = findViewById(R.id.ig_float_left)
        floatRight = findViewById(R.id.ig_float_right)
    }

    private fun wireEvents() {
        findViewById<View>(R.id.ig_back_list).setOnClickListener { finish() }
        findViewById<View>(R.id.ig_reload).setOnClickListener { loadConfig() }
        findViewById<View>(R.id.ig_add).setOnClickListener { promptNewProvider() }
        findViewById<View>(R.id.ig_back_detail).setOnClickListener { showList() }
        findViewById<View>(R.id.ig_detail_more).setOnClickListener {
            currentProvider()?.let { showProviderMenu(it) }
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                search = s?.toString()?.trim().orEmpty()
                renderList()
            }
        })

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!fillingConn) userEditedConn = true
            }
        }
        cfgName.addTextChangedListener(watcher)
        cfgKey.addTextChangedListener(watcher)
        cfgBase.addTextChangedListener(watcher)

        tabViews.forEachIndexed { i, v -> v.setOnClickListener { switchTab(i) } }
        cfgKeyEye.setOnClickListener {
            keyVisible = !keyVisible
            applyKeyVisibility()
        }
        cfgRefetch.setOnClickListener { fetchModels() }
        modelBatch.setOnClickListener {
            currentProvider()?.let { batchDeleteModels(it.optJSONArray("models") ?: JSONArray()) }
        }
        floatLeft.setOnClickListener { onFloatLeft() }
        floatRight.setOnClickListener { onFloatRight() }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(apiListener)
        loadConfig()
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentKey != null) showList() else super.onBackPressed()
    }

    // ───────────────────────────── 读配置 ─────────────────────────────

    private fun loadConfig() {
        EngineBridge.fetch(this, "getImageConfig") { obj ->
            if (obj == null) {
                setStatus("引擎还没就绪，稍等几秒点「刷新」")
                return@fetch
            }
            applyConfig(obj)
        }
    }

    private fun reloadAfterOp() {
        userEditedConn = false
        loadConfig()
    }

    private fun applyConfig(obj: JSONObject) {
        providers = obj.optJSONArray("providers") ?: JSONArray()
        val key = currentKey
        if (key != null && findProvider(key) == null) {
            showList()
        } else {
            paintTabs()
            if (key != null) fillDetail(findProvider(key)!!)
        }
        renderList()
    }

    private fun setStatus(msg: String) {
        listStatus.text = if (currentKey == null) msg else ""
        detailStatus.text = if (currentKey != null) msg else ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ───────────────────────────── 两态切换 ─────────────────────────────

    private fun showList() {
        currentKey = null
        userEditedConn = false
        listRoot.visibility = View.VISIBLE
        detailRoot.visibility = View.GONE
        renderList()
    }

    private fun openDetail(key: String) {
        currentKey = key
        userEditedConn = false
        keyVisible = false
        listRoot.visibility = View.GONE
        detailRoot.visibility = View.VISIBLE
        applyKeyVisibility()
        switchTab(0)
        fillDetail(findProvider(key) ?: return)
        loadConfig()
    }

    private fun switchTab(index: Int) {
        tab = index
        paintTabs()
        panes.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        if (index == 0) {
            floatLeft.text = "删除运营商"
            floatRight.text = "保存"
        } else {
            floatLeft.text = "批量删除"
            floatRight.text = "＋ 添加新模型"
        }
    }

    private fun paintTabs() {
        val ui = ProviderUi(this)
        val labels = listOf("配置", "模型")
        tabViews.forEachIndexed { i, v ->
            val selected = i == tab
            v.text = labels[i]
            v.setTextColor(if (selected) ui.tk.onAccent else ui.tk.textDim)
            v.setTypeface(v.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            v.background = ui.shape(if (selected) ui.tk.accent else ui.tk.panelSoft, 999f)
            v.tag = "dhp:keep-color"
        }
    }

    private fun onFloatLeft() {
        val p = currentProvider() ?: return
        if (tab == 0) confirmDeleteProvider(p)
        else batchDeleteModels(p.optJSONArray("models") ?: JSONArray())
    }

    private fun onFloatRight() {
        if (tab == 0) saveProviderConn() else fetchModels()
    }

    // ───────────────────────────── 详情表单 ─────────────────────────────

    private fun findProvider(key: String): JSONObject? {
        for (i in 0 until providers.length()) {
            val p = providers.optJSONObject(i) ?: continue
            if (p.optString("key") == key) return p
        }
        return null
    }

    private fun currentProvider(): JSONObject? = currentKey?.let { findProvider(it) }

    private fun fillDetail(p: JSONObject) {
        val ui = ProviderUi(this)
        val name = displayName(p)
        detailTitle.text = name
        detailAvatar.text = initialOf(name, p.optString("baseUrl"))
        detailAvatar.setTextColor(ui.tk.accent)
        detailAvatar.background = ui.shape(ui.tk.accentSoft, 15f)
        detailAvatar.tag = "dhp:keep-color"

        if (!userEditedConn) {
            fillingConn = true
            setIfDiffers(cfgName, p.optString("name"))
            setIfDiffers(cfgKey, p.optString("apiKey"))
            setIfDiffers(cfgBase, p.optString("baseUrl"))
            fillingConn = false
        }

        renderEngines(ui, p)
        renderScenes(ui, p)

        val models = p.optJSONArray("models") ?: JSONArray()
        cfgInfo.text = buildString {
            append("共 ${models.length()} 个模型\n")
            append("引擎：").append(engineOf(p).label).append('\n')
            append("连接：").append(if (p.optBoolean("hasKey")) "已配置密钥" else "未填密钥")
        }
        renderModels(models)
    }

    private fun applyKeyVisibility() {
        val ui = ProviderUi(this)
        val pos = cfgKey.selectionStart
        cfgKey.inputType = if (keyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        cfgKey.setSelection(pos.coerceAtMost(cfgKey.text?.length ?: 0))
        cfgKeyEye.setColorFilter(if (keyVisible) ui.tk.accent else ui.tk.icon)
    }

    private fun displayName(p: JSONObject): String =
        p.optString("name").ifBlank { hostOf(p.optString("baseUrl")).ifBlank { "未命名运营商" } }

    private fun initialOf(name: String, url: String): String {
        val s = name.trim().ifBlank { hostOf(url) }
        return if (s.isEmpty()) "?" else s.substring(0, 1).uppercase()
    }

    private fun hostOf(url: String): String = android.net.Uri.parse(url.trim()).host.orEmpty()

    private fun engineOf(p: JSONObject): ImageEngine = ImageEngine.of(p.optString("engine"))

    /** 引擎选择：一列可点的行，选中用主题淡底，右侧「当前」胶囊。 */
    private fun renderEngines(ui: ProviderUi, p: JSONObject) {
        cfgEngineBox.removeAllViews()
        val selected = engineOf(p)
        for (e in ImageEngine.entries) {
            val on = e == selected
            val row = ui.vertical(12).apply {
                background = ui.shape(if (on) ui.tk.accentSoft else ui.tk.panelSoft, 14f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = ui.dp(8) }
                setOnClickListener {
                    if (on) return@setOnClickListener
                    command("imgSetEngine", JSONObject().put("uid", p.optString("uid")).put("engine", e.id))
                }
            }
            val head = ui.horizontal()
            head.addView(ui.text(e.label, 14f, if (on) ui.tk.accent else ui.tk.text, bold = on))
            if (on) {
                val badge = ui.badge("当前", ProviderUi.Badge.ON)
                badge.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = ui.dp(8) }
                head.addView(badge)
            }
            row.addView(head)
            row.addView(ui.text(e.hint, 11f, ui.tk.textDim))
            cfgEngineBox.addView(row)
        }
    }

    /** 场景绑定：对话配图 / 标签生图，各记各的。 */
    private fun renderScenes(ui: ProviderUi, p: JSONObject) {
        cfgSceneBox.removeAllViews()
        val used = p.optJSONArray("usedBy") ?: JSONArray()
        val models = p.optJSONArray("models") ?: JSONArray()
        fun usedBy(scene: String): Boolean {
            for (i in 0 until used.length()) if (used.optString(i) == scene) return true
            return false
        }
        val scenes = listOf("chat" to "对话配图", "tag" to "标签生图")
        for ((id, label) in scenes) {
            val on = usedBy(id)
            val row = ui.horizontal().apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                setOnClickListener {
                    if (on) return@setOnClickListener
                    val uid = models.optJSONObject(0)?.optString("uid").orEmpty()
                    if (uid.isEmpty()) {
                        toast("先给这家添加一个模型，再绑定场景")
                        return@setOnClickListener
                    }
                    command("imgUseApi", JSONObject().put("uid", uid).put("scope", id))
                }
            }
            val mid = ui.vertical().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            mid.addView(ui.text(label, 14f, ui.tk.text))
            mid.addView(ui.text(
                if (on) "这一家正在给该场景出图" else "点右侧切换成这一家",
                11f, ui.tk.textDim,
            ))
            row.addView(mid)
            if (on) row.addView(ui.badge("使用中", ProviderUi.Badge.ON))
            else row.addView(ui.soft("设为使用"))
            cfgSceneBox.addView(row)
        }
    }

    // ───────────────────────────── 列表渲染 ─────────────────────────────

    private fun renderList() {
        val ui = ProviderUi(this)
        listBox.removeAllViews()
        val q = search
        val matched = ArrayList<JSONObject>()
        for (i in 0 until providers.length()) {
            val p = providers.optJSONObject(i) ?: continue
            if (q.isEmpty() || matches(p, q)) matched.add(p)
        }
        if (matched.isEmpty()) {
            val card = ui.card(18f, 20).apply {
                layoutParams = ui.itemMargin()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            card.addView(ui.text(if (providers.length() == 0) "还没有生图运营商" else "没有匹配的运营商", 15f, ui.tk.textDim))
            card.addView(ui.text("点右上角 ＋ 新建，填地址和密钥后拉取模型", 12f, ui.tk.textFaint))
            val btn = ui.primary("＋ 新建运营商", R.drawable.ic_plus).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = ui.dp(14) }
                setOnClickListener { promptNewProvider() }
            }
            card.addView(btn)
            listBox.addView(card)
            return
        }
        for (p in matched) listBox.addView(providerCard(ui, p))
    }

    private fun matches(p: JSONObject, q: String): Boolean {
        if (displayName(p).contains(q, true)) return true
        if (p.optString("baseUrl").contains(q, true)) return true
        val models = p.optJSONArray("models") ?: return false
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optString("model").contains(q, true)) return true
        }
        return false
    }

    private fun providerCard(ui: ProviderUi, p: JSONObject): View {
        val key = p.optString("key")
        val name = displayName(p)
        val models = p.optJSONArray("models") ?: JSONArray()
        val used = p.optJSONArray("usedBy") ?: JSONArray()
        val inUse = p.optBoolean("current") || used.length() > 0

        val card = ui.card(18f, 14).apply {
            layoutParams = ui.itemMargin()
            setOnClickListener { openDetail(key) }
        }
        val row = ui.horizontal()
        row.addView(ui.avatar(initialOf(name, p.optString("baseUrl")), 42))

        val mid = ui.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(ui.dp(12), 0, ui.dp(6), 0)
        }
        mid.addView(ui.text(name, 16f, ui.tk.text, bold = true))

        val badges = ui.horizontal().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.dp(7) }
        }
        badges.addView(ui.badge(if (inUse) "使用中" else "未使用", if (inUse) ProviderUi.Badge.ON else ProviderUi.Badge.OFF))
        badges.addView(ui.badge("${models.length()} 个模型", ProviderUi.Badge.NEUTRAL).also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = ui.dp(6) }
        })
        if (!p.optBoolean("hasKey")) {
            badges.addView(ui.badge("未填密钥", ProviderUi.Badge.WARN).also {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = ui.dp(6) }
            })
        }
        mid.addView(badges)
        row.addView(mid)
        row.addView(ui.icon(R.drawable.ic_more_vert, 22).apply { setOnClickListener { showProviderMenu(p) } })
        card.addView(row)
        return card
    }

    // ───────────────────────────── 模型渲染 ─────────────────────────────

    private fun renderModels(models: JSONArray) {
        val ui = ProviderUi(this)
        modelBox.removeAllViews()
        modelCount.text = "${models.length()} 个模型"
        if (models.length() == 0) {
            val card = ui.card(18f, 20).apply { layoutParams = ui.itemMargin() }
            card.addView(ui.text("这家还没有生图模型", 15f, ui.tk.textDim))
            card.addView(ui.text("点右下角「添加新模型」，按地址拉取后勾选加入", 12f, ui.tk.textFaint))
            modelBox.addView(card)
            return
        }
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            modelBox.addView(modelCard(ui, m))
        }
    }

    private fun modelCard(ui: ProviderUi, m: JSONObject): View {
        val uid = m.optString("uid")
        val label = m.optString("model").ifBlank { m.optString("name") }
        val current = m.optBoolean("current")
        val card = ui.card(16f, 12).apply {
            layoutParams = ui.itemMargin()
            if (!current) setOnClickListener {
                setStatus("切换到 $label …")
                command("imgUseApi", JSONObject().put("uid", uid))
            }
        }
        val row = ui.horizontal()
        val icon = ui.icon(R.drawable.ic_nav_image, 30)
        icon.setColorFilter(if (current) ui.tk.accent else ui.tk.icon)
        row.addView(icon)

        val mid = ui.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(ui.dp(12), 0, ui.dp(6), 0)
        }
        val title = ui.text(label.ifBlank { "未命名模型" }, 14f, if (current) ui.tk.accent else ui.tk.text, bold = current)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        mid.addView(title)
        mid.addView(ui.badge(if (current) "使用中" else "点击使用",
            if (current) ProviderUi.Badge.ON else ProviderUi.Badge.NEUTRAL).also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.dp(7) }
        })
        row.addView(mid)
        row.addView(ui.icon(R.drawable.ic_more_vert, 20).apply { setOnClickListener { showModelMenu(m) } })
        card.addView(row)
        return card
    }

    private fun showModelMenu(m: JSONObject) {
        val uid = m.optString("uid")
        val label = m.optString("model").ifBlank { m.optString("name") }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("使用这个模型", "用于对话配图", "用于标签生图", "删除模型")) { _, which ->
                when (which) {
                    0 -> command("imgUseApi", JSONObject().put("uid", uid))
                    1 -> command("imgUseApi", JSONObject().put("uid", uid).put("scope", "chat"))
                    2 -> command("imgUseApi", JSONObject().put("uid", uid).put("scope", "tag"))
                    3 -> AlertDialog.Builder(this)
                        .setTitle("删除模型")
                        .setMessage(label)
                        .setPositiveButton("删除") { _, _ ->
                            command("imgRemoveApi", JSONObject().put("uid", uid))
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ───────────────────────────── 菜单 / 对话框 ─────────────────────────────

    private fun showProviderMenu(p: JSONObject) {
        val models = p.optJSONArray("models") ?: JSONArray()
        AlertDialog.Builder(this)
            .setTitle(displayName(p))
            .setItems(arrayOf("重命名", "批量删除模型（${models.length()} 个）", "删除整个运营商")) { _, which ->
                when (which) {
                    0 -> promptRenameProvider(p)
                    1 -> batchDeleteModels(models)
                    2 -> confirmDeleteProvider(p)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun batchDeleteModels(models: JSONArray, selectAll: Boolean = false) {
        if (models.length() == 0) {
            toast("这个运营商下还没有模型")
            return
        }
        val names = Array(models.length()) { j ->
            val m = models.optJSONObject(j)
            m?.optString("model").orEmpty().ifBlank { m?.optString("name").orEmpty().ifBlank { "未命名" } }
        }
        val checked = BooleanArray(names.size) { selectAll }
        AlertDialog.Builder(this)
            .setTitle("勾选要删除的模型（${names.size} 个）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("删除选中") { _, _ ->
                val uids = JSONArray()
                for (j in 0 until models.length()) {
                    if (checked[j]) uids.put(models.optJSONObject(j)?.optString("uid").orEmpty())
                }
                if (uids.length() == 0) {
                    toast("一个都没勾")
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("将删除 ${uids.length()} 个模型，此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        command("imgRemoveApis", JSONObject().put("uids", uids))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("全选") { _, _ -> batchDeleteModels(models, true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteProvider(p: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("删除运营商")
            .setMessage("「${displayName(p)}」及其下所有模型配置会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                command("imgRemoveProvider", JSONObject().put("key", p.optString("key")))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptNewProvider() {
        val ui = ProviderUi(this)
        val name = ui.input("如「玄猫中转」").also { it.inputType = InputType.TYPE_CLASS_TEXT }
        val url = ui.input("https://api.example.com/v1")
        val key = ui.input("sk-…", password = true)
        ui.formDialog(
            this,
            "新建生图运营商",
            listOf("名称（可留空，用域名）" to name, "请求地址（Base URL）" to url, "API 密钥" to key),
            "创建",
        ) {
            val u = url.text?.toString()?.trim().orEmpty()
            if (u.isEmpty()) {
                setStatus("地址不能为空")
                return@formDialog
            }
            command(
                "imgAddProvider",
                JSONObject()
                    .put("name", name.text?.toString()?.trim().orEmpty())
                    .put("baseUrl", u)
                    .put("apiKey", key.text?.toString()?.trim().orEmpty()),
            )
        }
    }

    private fun promptRenameProvider(p: JSONObject) {
        val ui = ProviderUi(this)
        val name = ui.input("给这家起个名字").also {
            it.inputType = InputType.TYPE_CLASS_TEXT
            it.setText(p.optString("name"))
            it.setSelection(it.text?.length ?: 0)
        }
        ui.formDialog(this, "重命名运营商", listOf("名称" to name), "保存") {
            val value = name.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                toast("名字不能为空")
                return@formDialog
            }
            command("imgRenameProvider", JSONObject().put("key", p.optString("key")).put("name", value))
        }
    }

    // ───────────────────────────── 命令 ─────────────────────────────

    private fun command(cmd: String, extra: JSONObject = JSONObject()) {
        val body = JSONObject().put("cmd", cmd)
        for (k in extra.keys()) body.put(k, extra.get(k))
        EngineBridge.command(body)
    }

    private fun saveProviderConn() {
        val key = currentKey ?: return
        val base = cfgBase.text?.toString()?.trim().orEmpty()
        if (base.isEmpty()) {
            setStatus("请先填写请求地址（Base URL）")
            return
        }
        setStatus("保存中…")
        command(
            "imgSaveProviderConn",
            JSONObject()
                .put("key", key)
                .put("name", cfgName.text?.toString()?.trim().orEmpty())
                .put("baseUrl", base)
                .put("apiKey", cfgKey.text?.toString()?.trim().orEmpty()),
        )
    }

    private fun fetchModels() {
        val key = currentKey ?: return
        val base = cfgBase.text?.toString()?.trim().orEmpty()
        val apiKey = cfgKey.text?.toString()?.trim().orEmpty()
        if (base.isEmpty()) {
            setStatus("请先填写请求地址（Base URL）")
            return
        }
        setStatus("拉取中…")
        // 先静默保存连接，再拉列表；两件事互不阻塞
        pendingFetch = true
        command(
            "imgSaveProviderConn",
            JSONObject().put("key", key).put("baseUrl", base).put("apiKey", apiKey),
        )
        EngineBridge.fetchModels(base, apiKey)
    }

    private fun setIfDiffers(e: EditText, text: String) {
        if (e.text?.toString() != text) e.setText(text)
    }

    // ───────────────────────────── 结果回调 ─────────────────────────────

    private fun onApiResult(json: String) {
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            setStatus("结果解析失败：${t.message}")
            return
        }
        val kind = obj.optString("kind")
        val ok = obj.optBoolean("ok")
        val error = obj.optString("error")
        when (kind) {
            "models" -> {
                if (!ok) {
                    setStatus("拉取失败：$error")
                    return
                }
                val arr = obj.optJSONArray("models") ?: JSONArray()
                val models = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val m = arr.optString(i)
                    if (m.isNotBlank()) models.add(m)
                }
                if (models.isEmpty()) {
                    setStatus("该地址返回了空模型列表")
                } else {
                    setStatus("拉到 ${models.size} 个模型，勾选后添加")
                    showModelMultiPicker(models)
                }
            }
            "imgSaveProviderConn" -> {
                // 地址改了分组 key 会变，跟到新 key，详情页才不会被误判「已删除」
                val newKey = obj.optString("key")
                if (newKey.isNotEmpty()) currentKey = newKey
                when {
                    error.isNotEmpty() -> {
                        pendingFetch = false
                        setStatus("保存失败：$error")
                    }
                    pendingFetch -> pendingFetch = false
                    else -> {
                        toast("已保存")
                        reloadAfterOp()
                    }
                }
            }
            "imgAddModelsToProvider" -> {
                if (error.isNotEmpty()) {
                    setStatus("添加失败：$error")
                } else {
                    val added = obj.optInt("added", 0)
                    val skipped = obj.optInt("skipped", 0)
                    setStatus("已添加 $added 个" + if (skipped > 0) "（跳过重复 $skipped 个）" else "")
                    toast(statusText())
                    reloadAfterOp()
                }
            }
            "imgAddProvider" -> {
                if (ok) {
                    setStatus("已创建，点进这一家拉取模型即可")
                    reloadAfterOp()
                } else setStatus("创建失败：$error")
            }
            "imgRenameProvider", "imgSetEngine", "imgUseApi" -> {
                if (ok) loadConfig() else setStatus("操作失败：$error")
            }
            "imgRemoveApi", "imgRemoveApis", "imgRemoveProvider" -> {
                if (ok) {
                    toast("已删除 ${obj.optInt("removed", 1)} 项")
                    reloadAfterOp()
                } else setStatus("删除失败：$error")
            }
            else -> {
                if (ok) loadConfig() else setStatus("操作失败：$error")
            }
        }
    }

    private fun statusText(): String =
        if (currentKey != null) detailStatus.text?.toString().orEmpty()
        else listStatus.text?.toString().orEmpty()

    /** 拉到的模型多选对话框：勾选若干 → 一次加入当前正在看的那家。 */
    private fun showModelMultiPicker(models: List<String>) {
        val names = models.toTypedArray()
        val checked = BooleanArray(models.size)
        val key = currentKey
        AlertDialog.Builder(this)
            .setTitle("选择要添加的模型（${models.size} 个）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("添加所选") { _, _ ->
                val picked = models.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) {
                    setStatus("没有勾选任何模型")
                    return@setPositiveButton
                }
                addPickedModels(key, picked)
            }
            .setNeutralButton("全选") { d, _ ->
                d.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("选择要添加的模型（${models.size} 个）")
                    .setMultiChoiceItems(names, BooleanArray(models.size) { true }) { _, _, _ -> }
                    .setPositiveButton("添加全部") { _, _ -> addPickedModels(key, models) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addPickedModels(key: String?, picked: List<String>) {
        if (key == null) {
            setStatus("请先选中一家运营商")
            return
        }
        setStatus("添加 ${picked.size} 个模型中…")
        val arr = JSONArray()
        for (m in picked) arr.put(m)
        command("imgAddModelsToProvider", JSONObject().put("key", key).put("models", arr))
    }
}
