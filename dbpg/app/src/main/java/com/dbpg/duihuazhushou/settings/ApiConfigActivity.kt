package com.dbpg.duihuazhushou.settings

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM 运营商配置页（2026-11-11 重构）。
 *
 * ## 界面
 * 「提供商列表 → 详情」两级，详情再分 配置 / 模型 / 高级设置 三个 Tab。
 * 地址与密钥不再挂在全局顶部，而是收进每一家的「配置」Tab，密钥默认掩码。
 * 参考成熟客户端的信息架构，但保留我们自己的批量删除模型、全选添加等能力。
 *
 * ## 数据
 * 全部走原生 ConnectionStore（权威源），页面只发命令、按回包刷新：
 *
 *   getConfig()                    同步返回当前配置 + 运营商/模型全量
 *   llmSaveProviderConn           按 key 保存这一家的名称/地址/密钥（组内同步）
 *   llmAddModelsToProvider        按 key 往这一家批量加模型
 *   llmSetProviderEnabled         按 key 启用/停用（会真的挪走 currentConfigId）
 *   llmAddProvider / llmRenameProvider / llmRemoveProvider / llmRemoveModels
 *   useModel / removeModel / saveParams
 *   fetchModels(json)             原生 OkHttp 拉列表 → onApiResult(kind=models)
 *
 * 早期版本的命令都隐式作用于「当前运营商」（currentConfigId）。页面一旦允许
 * 直接编辑非当前运营商，保存/加模型就会串台 —— 这也是「拉取模型永远拉第一家」
 * 的同源问题。现在所有编辑命令都带 key，UI 与数据层的语义终于对齐。
 *
 * ## 换肤
 * 静态骨架由 UiBackdrop 全局染色；运行时生成的卡片走 [ProviderUi]（只读
 * ThemeTokens）。两者共用同一套色板，换主题时整页一起变。
 */
class ApiConfigActivity : Activity() {

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
    private lateinit var cfgEnabled: Switch
    private lateinit var cfgState: TextView
    private lateinit var cfgName: EditText
    private lateinit var cfgKey: EditText
    private lateinit var cfgBase: EditText
    private lateinit var cfgKeyEye: ImageView
    private lateinit var cfgRefetch: ImageView
    private lateinit var cfgPath: TextView
    private lateinit var cfgInfo: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var modelBatch: TextView
    private lateinit var modelCount: TextView
    private lateinit var paramTemp: EditText
    private lateinit var paramMax: EditText
    private lateinit var paramCtx: EditText
    private lateinit var paramLook: EditText
    private lateinit var floatLeft: TextView
    private lateinit var floatRight: TextView

    // ── 状态 ──
    /** 最近一次 getConfig 的运营商快照。 */
    private var providers: JSONArray = JSONArray()
    /** 当前详情页对应的运营商 key；null = 列表态。 */
    private var currentKey: String? = null
    private var tab = 0
    private var search = ""
    private var keyVisible = false
    private var lastModels: List<String> = emptyList()

    /** 用户是否动过详情表单：刷新时别把正在打的字冲掉。 */
    private var userEditedConn = false
    private var userEditedParams = false
    /** 程序化回填期间抑制 watcher / Switch 监听，避免误标脏或误发命令。 */
    private var fillingConn = false
    /** 拉模型前会先静默保存一次连接，那次不要弹 toast、也不要打断流程。 */
    private var pendingFetch = false

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_config)
        Insets.applyTop(this)
        bindViews()
        wireEvents()
        paintTabs()
    }

    private fun bindViews() {
        listRoot = findViewById(R.id.pv_list)
        detailRoot = findViewById(R.id.pv_detail)
        listBox = findViewById(R.id.pv_list_box)
        listStatus = findViewById(R.id.pv_list_status)
        detailStatus = findViewById(R.id.pv_detail_status)
        searchEdit = findViewById(R.id.pv_search)
        detailAvatar = findViewById(R.id.pv_detail_avatar)
        detailTitle = findViewById(R.id.pv_detail_title)
        tabViews = listOf(
            findViewById(R.id.pv_tab_config),
            findViewById(R.id.pv_tab_models),
            findViewById(R.id.pv_tab_advanced),
        )
        panes = listOf(
            findViewById(R.id.pv_pane_config),
            findViewById(R.id.pv_pane_models),
            findViewById(R.id.pv_pane_advanced),
        )
        cfgEnabled = findViewById(R.id.pv_cfg_enabled)
        cfgState = findViewById(R.id.pv_cfg_state)
        cfgName = findViewById(R.id.pv_cfg_name)
        cfgKey = findViewById(R.id.pv_cfg_key)
        cfgBase = findViewById(R.id.pv_cfg_base)
        cfgKeyEye = findViewById(R.id.pv_cfg_key_eye)
        cfgRefetch = findViewById(R.id.pv_cfg_refetch)
        cfgPath = findViewById(R.id.pv_cfg_path)
        cfgInfo = findViewById(R.id.pv_cfg_info)
        modelBox = findViewById(R.id.pv_model_box)
        modelBatch = findViewById(R.id.pv_model_batch)
        modelCount = findViewById(R.id.pv_model_count)
        paramTemp = findViewById(R.id.pv_param_temp)
        paramMax = findViewById(R.id.pv_param_max)
        paramCtx = findViewById(R.id.pv_param_ctx)
        paramLook = findViewById(R.id.pv_param_look)
        floatLeft = findViewById(R.id.pv_float_left)
        floatRight = findViewById(R.id.pv_float_right)
    }

    private fun wireEvents() {
        findViewById<View>(R.id.pv_back_list).setOnClickListener { finish() }
        findViewById<View>(R.id.pv_reload).setOnClickListener { loadConfig() }
        findViewById<View>(R.id.pv_add).setOnClickListener { promptNewProvider() }
        findViewById<View>(R.id.pv_back_detail).setOnClickListener { showList() }
        findViewById<View>(R.id.pv_detail_more).setOnClickListener {
            val p = currentProvider() ?: return@setOnClickListener
            showProviderMenu(p)
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                search = s?.toString()?.trim().orEmpty()
                renderList()
            }
        })

        // 详情表单：用户动过就别在刷新时覆盖
        val connWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!fillingConn) userEditedConn = true
            }
        }
        cfgName.addTextChangedListener(connWatcher)
        cfgKey.addTextChangedListener(connWatcher)
        cfgBase.addTextChangedListener(connWatcher)

        val paramWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!fillingConn) userEditedParams = true
            }
        }
        paramTemp.addTextChangedListener(paramWatcher)
        paramMax.addTextChangedListener(paramWatcher)
        paramCtx.addTextChangedListener(paramWatcher)
        paramLook.addTextChangedListener(paramWatcher)

        tabViews.forEachIndexed { i, v -> v.setOnClickListener { switchTab(i) } }

        cfgEnabled.setOnCheckedChangeListener { _, checked ->
            if (fillingConn) return@setOnCheckedChangeListener
            val key = currentKey ?: return@setOnCheckedChangeListener
            command("llmSetProviderEnabled", JSONObject().put("key", key).put("enabled", checked))
        }
        cfgKeyEye.setOnClickListener {
            keyVisible = !keyVisible
            applyKeyVisibility()
        }
        cfgRefetch.setOnClickListener { fetchModels() }
        modelBatch.setOnClickListener {
            val p = currentProvider() ?: return@setOnClickListener
            batchDeleteModels(p.optJSONArray("models") ?: JSONArray())
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
        EngineBridge.fetch(this, "getConfig") { obj ->
            if (obj == null) {
                setStatus("引擎还没就绪，稍等几秒点「刷新」")
                return@fetch
            }
            applyConfig(obj)
        }
    }

    /** 操作成功后的刷新：current 可能变了，表单要跟上。 */
    private fun reloadAfterOp() {
        userEditedConn = false
        userEditedParams = false
        loadConfig()
    }

    private fun applyConfig(obj: JSONObject) {
        if (listStatus.text?.toString().orEmpty().startsWith("引擎") ||
            detailStatus.text?.toString().orEmpty().startsWith("引擎")
        ) setStatus("")
        try {
            providers = obj.optJSONArray("providers") ?: JSONArray()
            if (!userEditedParams) {
                val params = obj.optJSONObject("params") ?: JSONObject()
                fillingConn = true
                setIfDiffers(paramTemp, num(params, "temperature"))
                setIfDiffers(paramMax, num(params, "maxTokens"))
                setIfDiffers(paramCtx, num(params, "contextWindow"))
                setIfDiffers(paramLook, num(params, "userLookbackTokens"))
                fillingConn = false
            }
            val key = currentKey
            if (key != null && findProvider(key) == null) {
                // 这家被删了（可能从别的入口），退回列表
                showList()
            } else {
                paintTabs()
                if (key != null) fillDetail(findProvider(key)!!)
            }
            renderList()
        } catch (t: Throwable) {
            setStatus("配置读取失败：${t.message}")
        }
    }

    private fun num(o: JSONObject, key: String): String {
        val v = o.optDouble(key, Double.NaN)
        return if (v.isNaN()) "" else if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }

    private fun setIfDiffers(e: EditText, text: String) {
        if (e.text?.toString() != text) e.setText(text)
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
        when (index) {
            0 -> {
                floatLeft.text = "删除运营商"
                floatRight.text = "保存"
            }
            1 -> {
                floatLeft.text = "批量删除"
                floatRight.text = "＋ 添加新模型"
            }
            else -> {
                floatLeft.text = "刷新"
                floatRight.text = "保存参数"
            }
        }
    }

    /** Tab 是静态 XML 控件，这里用当前色板重画并打 keep-color，避免二次染色。 */
    private fun paintTabs() {
        val ui = ProviderUi(this)
        val labels = listOf("配置", "模型", "高级设置")
        tabViews.forEachIndexed { i, v ->
            val selected = i == tab
            v.text = labels[i]
            v.setTextColor(if (selected) ui.tk.onAccent else ui.tk.textDim)
            v.setTypeface(v.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            v.background = ui.shape(if (selected) ui.tk.accent else ui.tk.panelSoft, 999f)
            v.tag = "dhp:keep-color"
        }
    }

    /** 悬浮条左右按钮按当前 Tab 分发。 */
    private fun onFloatLeft() = when (tab) {
        0 -> {
            val p = currentProvider() ?: return
            confirmDeleteProvider(p)
        }
        1 -> {
            val p = currentProvider() ?: return
            batchDeleteModels(p.optJSONArray("models") ?: JSONArray())
        }
        else -> loadConfig()
    }

    private fun onFloatRight() = when (tab) {
        0 -> saveProviderConn()
        1 -> fetchModels()
        else -> saveParams()
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

        val enabled = p.optBoolean("enabled", true)
        fillingConn = true
        cfgEnabled.isChecked = enabled
        fillingConn = false
        cfgEnabled.thumbTintList = ColorStateList.valueOf(ui.tk.onAccent)
        cfgEnabled.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.tk.accent, ui.tk.hairline),
        )
        cfgState.text = if (enabled) "启用后可用于生成" else "已停用：当前模型会挪到其它启用的运营商"

        if (!userEditedConn) {
            fillingConn = true
            setIfDiffers(cfgName, p.optString("note"))
            setIfDiffers(cfgKey, p.optString("apiKey"))
            setIfDiffers(cfgBase, p.optString("baseUrl"))
            fillingConn = false
        }
        val base = p.optString("baseUrl").trim().trimEnd('/')
        cfgPath.text = if (base.isBlank()) "/chat/completions" else "$base/chat/completions"

        val models = p.optJSONArray("models") ?: JSONArray()
        var current = ""
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optBoolean("current")) current = m.optString("model").ifBlank { m.optString("name") }
        }
        cfgInfo.text = buildString {
            append("共 ${models.length()} 个模型\n")
            append("当前模型：").append(current.ifBlank { "未选择" }).append('\n')
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
        p.optString("note").ifBlank { hostOf(p.optString("baseUrl")).ifBlank { "未命名运营商" } }

    private fun initialOf(name: String, url: String): String {
        val s = name.trim().ifBlank { hostOf(url) }
        return if (s.isEmpty()) "?" else s.substring(0, 1).uppercase()
    }

    private fun hostOf(url: String): String = runCatching {
        val s = url.trim()
        if (s.isEmpty()) return ""
        java.net.URI(s).host ?: ""
    }.getOrDefault("")

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
            card.addView(ui.text(if (providers.length() == 0) "还没有运营商" else "没有匹配的运营商", 15f, ui.tk.textDim))
            card.addView(ui.text("点右上角 ＋ 新建，或改一下搜索词", 12f, ui.tk.textFaint))
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
            if (m.optString("model").contains(q, true) || m.optString("name").contains(q, true)) return true
        }
        return false
    }

    private fun providerCard(ui: ProviderUi, p: JSONObject): View {
        val key = p.optString("key")
        val name = displayName(p)
        val models = p.optJSONArray("models") ?: JSONArray()
        val enabled = p.optBoolean("enabled", true)
        val hasKey = p.optBoolean("hasKey")
        val inUse = (0 until models.length()).any {
            models.optJSONObject(it)?.optBoolean("current") == true
        }

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
        badges.addView(ui.badge(if (inUse) "使用中" else if (enabled) "启用" else "已停用",
            if (inUse || enabled) ProviderUi.Badge.ON else ProviderUi.Badge.OFF))
        val countBadge = ui.badge("${models.length()} 个模型", ProviderUi.Badge.NEUTRAL)
        countBadge.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = ui.dp(6) }
        badges.addView(countBadge)
        if (!hasKey) {
            val warn = ui.badge("未填密钥", ProviderUi.Badge.WARN)
            warn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = ui.dp(6) }
            badges.addView(warn)
        }
        mid.addView(badges)
        row.addView(mid)

        val more = ui.icon(R.drawable.ic_more_vert, 22).apply {
            setOnClickListener { showProviderMenu(p) }
        }
        row.addView(more)
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
            card.addView(ui.text("这家还没有模型", 15f, ui.tk.textDim))
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
                command("useModel", JSONObject().put("uid", uid))
            }
        }
        val row = ui.horizontal()
        val icon = ui.icon(R.drawable.ic_llm, 30)
        icon.setColorFilter(if (current) ui.tk.accent else ui.tk.icon)
        row.addView(icon)

        val mid = ui.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(ui.dp(12), 0, ui.dp(6), 0)
        }
        val title = ui.text(label, 14f, if (current) ui.tk.accent else ui.tk.text, bold = current)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        mid.addView(title)

        val badges = ui.horizontal().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.dp(7) }
        }
        badges.addView(ui.badge(if (current) "使用中" else "点击使用",
            if (current) ProviderUi.Badge.ON else ProviderUi.Badge.NEUTRAL))
        val tempBadge = ui.badge("温度 ${m.optDouble("temperature", 0.8)}", ProviderUi.Badge.NEUTRAL)
        tempBadge.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = ui.dp(6) }
        badges.addView(tempBadge)
        val maxBadge = ui.badge("上限 ${m.optInt("maxTokens", 8192)}", ProviderUi.Badge.NEUTRAL)
        maxBadge.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = ui.dp(6) }
        badges.addView(maxBadge)
        mid.addView(badges)
        row.addView(mid)

        val more = ui.icon(R.drawable.ic_more_vert, 20).apply {
            setOnClickListener { showModelMenu(m) }
        }
        row.addView(more)
        card.addView(row)
        return card
    }

    private fun showModelMenu(m: JSONObject) {
        val uid = m.optString("uid")
        val label = m.optString("model").ifBlank { m.optString("name") }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("使用这个模型", "删除模型")) { _, which ->
                when (which) {
                    0 -> command("useModel", JSONObject().put("uid", uid))
                    1 -> AlertDialog.Builder(this)
                        .setTitle("删除模型")
                        .setMessage(label)
                        .setPositiveButton("删除") { _, _ ->
                            command("removeModel", JSONObject().put("uid", uid))
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
        val key = p.optString("key")
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
        if (key.isEmpty()) toast("这条运营商数据缺少 key，请刷新后重试")
    }

    /** 多选删除：一条 llmRemoveModels 命令搞定，删前二次确认。 */
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
                        command("llmRemoveModels", JSONObject().put("uids", uids))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("全选") { _, _ -> batchDeleteModels(models, true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteProvider(p: JSONObject) {
        val key = p.optString("key")
        AlertDialog.Builder(this)
            .setTitle("删除运营商")
            .setMessage("「${displayName(p)}」及其下所有模型配置会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                command("llmRemoveProvider", JSONObject().put("key", key))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 自定义表单弹窗：用 ProviderUi 构造，连弹窗底板都走主题色板，
     * 深色皮肤下不会冒出一块系统白。
     */
    private fun showFormDialog(
        title: String,
        fields: List<Pair<String, EditText>>,
        positive: String,
        onOk: () -> Unit,
    ) {
        val ui = ProviderUi(this)
        val box = ui.card(20f, 18)
        box.addView(ui.text(title, 17f, ui.tk.text, bold = true))
        for ((label, input) in fields) {
            val tv = ui.text(label, 12f, ui.tk.textDim)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.dp(14) }
            box.addView(tv)
            box.addView(input)
        }
        val row = ui.horizontal().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ui.dp(18) }
        }
        val cancel = ui.soft("取消")
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val ok = ui.primary(positive)
        row.addView(cancel)
        row.addView(spacer)
        row.addView(ok)
        box.addView(row)

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            resources.displayMetrics.widthPixels - ui.dp(48),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener { onOk(); d.dismiss() }
        d.show()
    }

    private fun promptNewProvider() {
        val ui = ProviderUi(this)
        val name = ui.input("如「玄猫中转」").also { it.inputType = InputType.TYPE_CLASS_TEXT }
        val url = ui.input("https://api.example.com/v1")
        val key = ui.input("sk-…", password = true)
        showFormDialog(
            "新建运营商",
            listOf("名称（可留空，用域名）" to name, "请求地址（Base URL）" to url, "API 密钥" to key),
            "创建",
        ) {
            val u = url.text?.toString()?.trim().orEmpty()
            if (u.isEmpty()) {
                setStatus("地址不能为空")
                return@showFormDialog
            }
            command(
                "llmAddProvider",
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
            it.setText(p.optString("note"))
            it.setSelection(it.text?.length ?: 0)
        }
        showFormDialog("重命名运营商", listOf("名称" to name), "保存") {
            val value = name.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                toast("名字不能为空")
                return@showFormDialog
            }
            command("llmRenameProvider", JSONObject().put("key", p.optString("key")).put("name", value))
        }
    }

    // ───────────────────────────── 命令 ─────────────────────────────

    private fun command(cmd: String, extra: JSONObject = JSONObject()) {
        val body = JSONObject().put("cmd", cmd)
        for (k in extra.keys()) body.put(k, extra.get(k))
        EngineBridge.command(body)
    }

    private fun connBody(): JSONObject = JSONObject()
        .put("key", currentKey.orEmpty())
        .put("name", cfgName.text?.toString()?.trim().orEmpty())
        .put("baseUrl", cfgBase.text?.toString()?.trim().orEmpty())
        .put("apiKey", cfgKey.text?.toString()?.trim().orEmpty())

    private fun saveProviderConn() {
        val key = currentKey ?: return
        val base = cfgBase.text?.toString()?.trim().orEmpty()
        if (base.isEmpty()) {
            setStatus("请先填写请求地址（Base URL）")
            return
        }
        setStatus("保存中…")
        command("llmSaveProviderConn", connBody().put("key", key))
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
        // 先把连接信息落到这一家（静默，不弹 toast），再拉列表 —— 两件事互不阻塞
        pendingFetch = true
        command("llmSaveProviderConn", JSONObject()
            .put("key", key)
            .put("baseUrl", base)
            .put("apiKey", apiKey))
        EngineBridge.fetchModels(base, apiKey)
    }

    private fun saveParams() {
        val temp = paramTemp.text?.toString()?.toDoubleOrNull()
        if (temp == null) {
            setStatus("温度要填数字（如 0.8）")
            return
        }
        val params = JSONObject()
            .put("temperature", temp)
            .put("maxTokens", paramMax.text?.toString()?.toLongOrNull() ?: 8192L)
            .put("contextWindow", paramCtx.text?.toString()?.toLongOrNull() ?: 1000000L)
            .put("userLookbackTokens", paramLook.text?.toString()?.toLongOrNull() ?: 200000L)
        setStatus("保存参数中…")
        command("saveParams", JSONObject().put("params", params))
    }

    // ───────────────────────────── 结果回调 ─────────────────────────────

    private fun onApiResult(json: String) {
        try {
            val obj = JSONObject(json)
            val kind = obj.optString("kind")
            val ok = obj.optBoolean("ok")
            val error = obj.optString("error")
            when (kind) {
                "models" -> {
                    if (ok) {
                        val arr = obj.optJSONArray("models") ?: JSONArray()
                        val models = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) {
                            val m = arr.optString(i)
                            if (m.isNotBlank()) models.add(m)
                        }
                        lastModels = models
                        when {
                            models.isEmpty() -> setStatus("该地址返回了空模型列表")
                            else -> {
                                setStatus("拉到 ${models.size} 个模型，勾选后添加")
                                showModelMultiPicker(models)
                            }
                        }
                    } else {
                        setStatus("拉取失败：$error")
                    }
                }
                "llmSaveProviderConn" -> {
                    if (error.isNotEmpty()) {
                        pendingFetch = false
                        setStatus("保存失败：$error")
                    } else if (pendingFetch) {
                        pendingFetch = false
                        // 拉模型前的静默保存，不打断流程
                    } else {
                        setStatus("已保存，连接信息同步到这家全部模型")
                        toast("已保存")
                        reloadAfterOp()
                    }
                }
                "llmAddModelsToProvider" -> {
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
                "llmSetProviderEnabled" -> {
                    if (error.isNotEmpty()) setStatus("操作失败：$error") else loadConfig()
                }
                "useModel" -> {
                    if (ok) {
                        toast("已切换")
                        reloadAfterOp()
                    } else setStatus("切换失败：$error")
                }
                "removeModel" -> {
                    if (ok) {
                        toast("已删除")
                        reloadAfterOp()
                    } else setStatus("删除失败：$error")
                }
                "llmRenameProvider" -> {
                    if (ok) reloadAfterOp() else toast("改名失败：$error")
                }
                "llmRemoveModels" -> {
                    if (ok) {
                        toast("已删除 ${obj.optInt("removed", 0)} 项")
                        reloadAfterOp()
                    } else setStatus("删除失败：$error")
                }
                "llmRemoveProvider" -> {
                    if (ok) {
                        toast("运营商已删除")
                        showList()
                        loadConfig()
                    } else setStatus("删除失败：$error")
                }
                "llmAddProvider" -> {
                    if (ok) {
                        setStatus("已创建运营商，接着添加模型即可")
                        reloadAfterOp()
                    } else setStatus("创建失败：$error")
                }
                "saveParams" -> {
                    if (ok) {
                        setStatus("参数已保存（上限/上下文已同步全部模型）")
                        toast("参数已保存")
                        reloadAfterOp()
                    } else setStatus("保存失败：$error")
                }
                /*
                 * 兜底：新增命令即使忘了写分支，也不至于「点了没反应」。
                 */
                else -> {
                    if (ok) loadConfig() else setStatus("操作失败：$error")
                }
            }
        } catch (t: Throwable) {
            setStatus("结果解析失败：${t.message}")
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
        command("llmAddModelsToProvider", JSONObject().put("key", key).put("models", arr))
    }
}
