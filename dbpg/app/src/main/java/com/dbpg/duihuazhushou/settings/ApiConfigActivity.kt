package com.dbpg.duihuazhushou.settings

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生 API 接口配置页。
 *
 * 交互对齐网页版：
 *   · 填地址 + 密钥 → 拉取模型 → **勾选多个一次加入这家**；
 *   · 已保存的运营商是可点的卡片：点开 → 点模型即「使用」（快捷切换），
 *     长按模型删除；
 *   · 生成参数跟随当前模型编辑。
 *
 * 数据层在 web 引擎（apiConfig store），本页通过引擎 WebView 上的
 * window.__dhpApi 读写：
 *
 *   getConfig()          同步返回 JSON 串（当前配置 + 运营商/模型全量）
 *   fetchModels(json)    异步拉模型列表 → onApiResult(kind=models)
 *   command(json)        异步命令：saveConn / addModels / useModel /
 *                        removeModel / saveParams → onApiResult(kind=命令名)
 */
class ApiConfigActivity : Activity() {

    private lateinit var baseEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var status: TextView
    private lateinit var providersBox: LinearLayout
    private lateinit var tempEdit: EditText
    private lateinit var maxEdit: EditText
    private lateinit var ctxEdit: EditText
    private lateinit var lookEdit: EditText

    /** 最近一次拉到的模型列表（供多选对话框用）。 */
    private var lastModels: List<String> = emptyList()

    /** 用户正在哪个运营商的展开态（key → 是否展开）。刷新后保持。 */
    private val expanded = mutableSetOf<String>()

    /** 输入框是否由用户动过：刷新时别把人家正在打的字冲掉。 */
    private var userEditedConn = false

    /**
     * setIfDiffers 正在程序化回填：回填用的 setText 也会触发 TextWatcher，
     * 不压制的话首次加载就会把 userEditedConn 误标成 true，之后切到哪个
     * 运营商输入框都冻结在第一个的地址上（2026-11-09 用户实报
     * 「拉取模型永远拉第一个」的根因）。
     */
    private var fillingConn = false

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_config)
        Insets.applyTop(this)

        baseEdit = findViewById(R.id.api_base)
        keyEdit = findViewById(R.id.api_key)
        status = findViewById(R.id.api_status)
        providersBox = findViewById(R.id.api_providers_box)
        tempEdit = findViewById(R.id.api_param_temp)
        maxEdit = findViewById(R.id.api_param_max)
        ctxEdit = findViewById(R.id.api_param_ctx)
        lookEdit = findViewById(R.id.api_param_look)

        findViewById<TextView>(R.id.api_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.api_reload).setOnClickListener {
            userEditedConn = false
            loadConfig()
        }
        findViewById<TextView>(R.id.api_fetch).setOnClickListener { fetchModels() }
        findViewById<TextView>(R.id.api_param_save).setOnClickListener { saveParams() }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // 程序化回填不算「用户编辑过」，别把 userEditedConn 污染成 true
                if (!fillingConn) userEditedConn = true
            }
        }
        baseEdit.addTextChangedListener(watcher)
        keyEdit.addTextChangedListener(watcher)
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

    // ------------------------------------------------------------ 读配置

    private fun engineReady(): Boolean = MainActivity.getWebView() != null

    private fun loadConfig() {
        // 走共用入口：引擎冷启动没就绪时它会自动重试，不用用户手动点「刷新」。
        EngineBridge.fetch(this, "getConfig") { obj ->
            if (obj == null) {
                status.text = "引擎还没就绪（网页还在加载），稍等几秒点「刷新」"
                return@fetch
            }
            applyConfig(obj)
        }
    }

    /**
     * 操作成功后的刷新：current 可能已经变了（切换 / 新建 / 删掉当前项回退），
     * 输入框要跟上，否则下一轮「拉取模型」会拿旧地址打到旧运营商。
     */
    private fun reloadAfterOp() {
        userEditedConn = false
        loadConfig()
    }

    /** 成功拿到配置时清掉「加载中」提示，但别抹掉「已创建运营商」这类操作反馈。 */
    private fun applyConfig(obj: JSONObject) {
        if (status.text?.toString().orEmpty().startsWith("引擎")) status.text = ""
        try {
            if (!userEditedConn) {
                setIfDiffers(baseEdit, obj.optString("baseUrl"))
                setIfDiffers(keyEdit, obj.optString("apiKey"))
            }

            val params = obj.optJSONObject("params") ?: JSONObject()
            setIfDiffers(tempEdit, num(params, "temperature"))
            setIfDiffers(maxEdit, num(params, "maxTokens"))
            setIfDiffers(ctxEdit, num(params, "contextWindow"))
            setIfDiffers(lookEdit, num(params, "userLookbackTokens"))

            renderProviders(obj.optJSONArray("providers") ?: JSONArray())
        } catch (t: Throwable) {
            status.text = "配置读取失败：${t.message}"
        }
    }

    private fun num(o: JSONObject, key: String): String {
        val v = o.optDouble(key, Double.NaN)
        return if (v.isNaN()) "" else if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }

    private fun setIfDiffers(e: EditText, text: String) {
        if (e.text?.toString() != text) {
            fillingConn = true
            e.setText(text)
            fillingConn = false
        }
    }

    // ------------------------------------------------------------ 运营商与模型（动态行）

    private fun renderProviders(arr: JSONArray) {
        providersBox.removeAllViews()

        // 新建运营商：用户反馈「只能切换以前添加过的」，原生必须能自己加
        providersBox.addView(TextView(this).apply {
            text = "＋ 新建运营商"
            setTextColor(0xFF2F66D8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { promptNewProvider() }
        })
        if (arr.length() == 0) {
            providersBox.addView(simpleCard("还没有运营商", "填好地址和密钥，拉取模型后自动建立"))
            return
        }

        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val key = p.optString("key")
            val note = p.optString("note")
            val baseUrl = p.optString("baseUrl")
            val hasKey = p.optBoolean("hasKey")
            val models = p.optJSONArray("models") ?: JSONArray()
            val isCurrent = (0 until models.length()).any { models.getJSONObject(i2(it)).optBoolean("current") }

            // ── 运营商卡片头：可点展开/收起 ──
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                if (i > 0) (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                setOnClickListener { if (expanded.contains(key)) expanded.remove(key) else expanded.add(key); loadConfig() }
                // 长按运营商：重命名 / 批量删模型 / 删除整个运营商
                setOnLongClickListener { showProviderMenu(key, note, models); true }
            }
            val title = TextView(this).apply {
                text = buildString {
                    append(note.ifBlank { baseUrl.ifBlank { "(未填地址)" } })
                    if (isCurrent) append("  ●")
                }
                setTextColor(if (isCurrent) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
                textSize = 14f
                if (isCurrent) setTypeface(typeface, Typeface.BOLD)
            }
            head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = "${models.length()} 个模型" + if (!hasKey) " · 未填密钥" else ""
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
            })
            head.addView(TextView(this).apply {
                text = if (expanded.contains(key)) "▾" else "▸"
                setTextColor(0xFF8A94A6.toInt())
                textSize = 14f
                setPadding(dp(8), 0, 0, 0)
            })
            providersBox.addView(head)

            // ── 展开的模型列表 ──
            if (expanded.contains(key)) {
                if (baseUrl.isNotBlank()) {
                    providersBox.addView(simpleCard("", baseUrl, small = true))
                }
                // 批量管理入口：以前只能长按模型一个个删，几十个模型要按几十次
                val ops = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(2))
                }
                ops.addView(actionChip("☐ 批量删除模型") { batchDeleteModels(models) })
                ops.addView(actionChip("🗑️ 删除运营商") { confirmDeleteProvider(key, note) })
                providersBox.addView(ops)
                for (j in 0 until models.length()) {
                    val m = models.getJSONObject(j)
                    val uid = m.optString("uid")
                    val model = m.optString("model")
                    val name = m.optString("name")
                    val current = m.optBoolean("current")

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = getDrawable(R.drawable.bg_btn_soft)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
                    }
                    row.addView(TextView(this).apply {
                        text = if (current) "✓ " else ""
                        setTextColor(0xFF2F66D8.toInt())
                        textSize = 13f
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    row.addView(TextView(this).apply {
                        text = model.ifBlank { name }
                        setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF3A4353.toInt())
                        textSize = 13f
                        ellipsize = null
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(this).apply {
                        text = if (current) "使用中" else "使用"
                        setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF66707F.toInt())
                        textSize = 12f
                    })
                    row.setOnClickListener {
                        if (current) return@setOnClickListener
                        status.text = "切换到 ${model.ifBlank { name }} …"
                        command("useModel", JSONObject().put("uid", uid))
                    }
                    row.setOnLongClickListener {
                        AlertDialog.Builder(this)
                            .setTitle("删除模型")
                            .setMessage(model.ifBlank { name })
                            .setPositiveButton("删除") { _, _ ->
                                command("removeModel", JSONObject().put("uid", uid))
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                    providersBox.addView(row)
                }
            }
        }
    }

    /** 展开区里的操作 chip，两个各占一半宽度。 */
    private fun actionChip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF2F66D8.toInt())
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    /** 长按运营商卡片：重命名 / 批量删模型 / 删除整个运营商。 */
    private fun showProviderMenu(key: String, note: String, models: JSONArray) {
        AlertDialog.Builder(this)
            .setTitle(note.ifBlank { "运营商" })
            .setItems(
                arrayOf("重命名", "批量删除模型（${models.length()} 个）", "删除整个运营商"),
            ) { _, which ->
                when (which) {
                    0 -> promptRenameProvider(key, note)
                    1 -> batchDeleteModels(models)
                    2 -> confirmDeleteProvider(key, note)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun confirmDeleteProvider(key: String, note: String) {
        AlertDialog.Builder(this)
            .setTitle("删除运营商")
            .setMessage("「${note.ifBlank { "未命名运营商" }}」及其下所有模型配置会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                command("llmRemoveProvider", JSONObject().put("key", key))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按运营商卡片：改显示名。 */
    private fun promptRenameProvider(key: String, current: String) {
        val input = EditText(this).apply {
            setText(current)
            hint = "如「玄猫中转」"
            setTextSize(14f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSelection(text?.length ?: 0)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名运营商")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast("名字不能为空")
                    return@setPositiveButton
                }
                command("llmRenameProvider", JSONObject().put("key", key).put("name", name))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建 LLM 运营商 = 名称 + 地址 + 密钥；创建后自动切为当前。 */
    private fun promptNewProvider() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun addLabel(t: String) = box.addView(TextView(this).apply {
            text = t
            setTextColor(0xFF5A6B85.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
        fun addInput(hint: String, uri: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setTextSize(13f)
            setTextColor(0xFF1A2230.toInt())
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (uri) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            else android.text.InputType.TYPE_CLASS_TEXT
        }.also { box.addView(it) }

        addLabel("名称（可留空，用域名）")
        val name = addInput("如「玄猫中转」")
        addLabel("请求地址（Base URL）")
        val url = addInput("https://api.example.com/v1", uri = true)
        addLabel("API 密钥")
        val key = addInput("sk-…")

        AlertDialog.Builder(this)
            .setTitle("新建运营商")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val u = url.text?.toString()?.trim().orEmpty()
                if (u.isEmpty()) {
                    status.text = "地址不能为空"
                    return@setPositiveButton
                }
                command(
                    "llmAddProvider",
                    JSONObject()
                        .put("name", name.text?.toString()?.trim() ?: "")
                        .put("baseUrl", u)
                        .put("apiKey", key.text?.toString()?.trim() ?: ""),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun i2(v: Int): Int = v

    private fun simpleCard(title: String, sub: String, small: Boolean = false): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        if (title.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = title
                setTextColor(0xFF1A2230.toInt())
                textSize = if (small) 12f else 14f
            })
        }
        if (sub.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = sub
                setTextColor(0xFF66707F.toInt())
                textSize = if (small) 11f else 12f
                if (title.isNotBlank()) (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(2)
            })
        }
        return box
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    // ------------------------------------------------------------ 拉取 / 命令

    /** 连接信息 JSON（带 cmd 前缀的命令体）。 */
    private fun connJson(): JSONObject = JSONObject().put("baseUrl", baseEdit.text?.toString()?.trim() ?: "").put("apiKey", keyEdit.text?.toString()?.trim() ?: "")

    /**
     * 发一条配置命令。
     *
     * 2026-11-07 起全部走 [EngineBridge]：配置命令在原生就地执行、立即落盘，
     * 不再需要 WebView 活着，也不会出现「引擎还在加载 → 点了没反应」。
     * `connJson()` 带上输入框里的地址/密钥，保存前顺带把连接信息对齐。
     */
    private fun command(cmd: String, extra: JSONObject = JSONObject()) {
        val body = connJson().put("cmd", cmd)
        for (k in extra.keys()) body.put(k, extra.get(k))
        EngineBridge.command(body)
    }

    private fun fetchModels() {
        val baseUrl = baseEdit.text?.toString()?.trim().orEmpty()
        val apiKey = keyEdit.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            status.text = "请先填写请求地址（Base URL）"
            return
        }
        status.text = "拉取中…"
        // 先把地址/密钥落到原生权威源（运营商级同步），再拉列表 —— 两件事互不阻塞
        EngineBridge.command(
            JSONObject().put("cmd", "saveConn")
                .put("baseUrl", baseUrl).put("apiKey", apiKey),
        )
        // 网络请求也在原生（OkHttp），不再借 WebView 的 fetch
        EngineBridge.fetchModels(baseUrl, apiKey)
    }

    private fun saveParams() {
        val temp = tempEdit.text?.toString()?.toDoubleOrNull()
        if (temp == null) {
            status.text = "温度要填数字（如 0.8）"
            return
        }
        val params = JSONObject()
            .put("temperature", temp)
            .put("maxTokens", maxEdit.text?.toString()?.toLongOrNull() ?: 2000L)
            .put("contextWindow", ctxEdit.text?.toString()?.toLongOrNull() ?: 1000000L)
            .put("userLookbackTokens", lookEdit.text?.toString()?.toLongOrNull() ?: 200000L)
        status.text = "保存参数中…"
        command("saveParams", JSONObject().put("params", params))
    }

    // ------------------------------------------------------------ 结果回调

    private fun onApiResult(json: String) {
        try {
            val obj = JSONObject(json)
            val kind = obj.optString("kind")
            val ok = obj.optBoolean("ok")
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
                            models.isEmpty() -> status.text = "该地址返回了空模型列表"
                            else -> {
                                status.text = "拉到 ${models.size} 个模型，勾选后添加"
                                showModelMultiPicker(models)
                            }
                        }
                    } else {
                        status.text = "拉取失败：${obj.optString("error")}"
                    }
                }
                "addModels" -> {
                    if (ok) {
                        val added = obj.optInt("added", 0)
                        val skipped = obj.optInt("skipped", 0)
                        status.text = "已添加 $added 个" + if (skipped > 0) "（跳过重复 $skipped 个）" else ""
                        Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
                        reloadAfterOp()
                    } else {
                        status.text = "添加失败：${obj.optString("error")}"
                    }
                }
                "useModel" -> {
                    if (ok) {
                        Toast.makeText(this, "已切换", Toast.LENGTH_SHORT).show()
                        reloadAfterOp()
                    } else {
                        status.text = "切换失败：${obj.optString("error")}"
                    }
                }
                "removeModel" -> {
                    if (ok) {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        reloadAfterOp()
                    } else {
                        status.text = "删除失败：${obj.optString("error")}"
                    }
                }
                "llmRenameProvider" -> {
                    if (ok) loadConfig() else toast("改名失败：${obj.optString("error")}")
                }
                "llmRemoveModels", "llmRemoveProvider" -> {
                    if (ok) {
                        toast("已删除 ${obj.optInt("removed", 0)} 项")
                        reloadAfterOp()
                    } else {
                        status.text = "删除失败：${obj.optString("error")}"
                    }
                }
                "llmAddProvider" -> {
                    if (ok) {
                        status.text = "已创建运营商，接着拉取模型即可"
                        reloadAfterOp()
                    } else {
                        status.text = "创建失败：${obj.optString("error")}"
                    }
                }
                "saveConn" -> if (!ok) status.text = "保存失败：${obj.optString("error")}"
                "saveParams" -> {
                    if (ok) {
                        status.text = "参数已保存"
                        Toast.makeText(this, "参数已保存", Toast.LENGTH_SHORT).show()
                        loadConfig()
                    } else {
                        status.text = "保存失败：${obj.optString("error")}"
                    }
                }
                /*
                 * 兜底：新增命令即使忘了在上面写分支，也不至于「点了没反应」。
                 * 以前每个命令都要在这里登记一次，漏了就静默失败 —— 这是
                 * 本页面最容易出的 bug，而且编译期完全看不出来。
                 */
                else -> {
                    if (ok) loadConfig() else status.text = "操作失败：${obj.optString("error")}"
                }
            }
        } catch (t: Throwable) {
            status.text = "结果解析失败：${t.message}"
        }
    }

    /** 拉到的模型多选对话框：勾选若干 → 一次加入这家（对齐网页版）。 */
    private fun showModelMultiPicker(models: List<String>) {
        val names = models.toTypedArray()
        val checked = BooleanArray(models.size)
        // 已经在这家的模型默认勾上也行，但跳过重复由 store 处理，这里默认全不勾
        AlertDialog.Builder(this)
            .setTitle("选择要添加的模型（${models.size} 个）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("添加所选") { _, _ ->
                val picked = models.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) {
                    status.text = "没有勾选任何模型"
                    return@setPositiveButton
                }
                status.text = "添加 ${picked.size} 个模型中…"
                val arr = JSONArray()
                for (m in picked) arr.put(m)
                command("addModels", JSONObject().put("models", arr))
            }
            .setNeutralButton("全选") { d, _ ->
                // 全选 = 重新弹一次并全部勾上（简单可靠，不做原地刷新）
                d.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("选择要添加的模型（${models.size} 个）")
                    .setMultiChoiceItems(names, BooleanArray(models.size) { true }) { _, _, _ -> }
                    .setPositiveButton("添加全部") { _, _ ->
                        status.text = "添加 ${models.size} 个模型中…"
                        val arr = JSONArray()
                        for (m in models) arr.put(m)
                        command("addModels", JSONObject().put("models", arr))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
