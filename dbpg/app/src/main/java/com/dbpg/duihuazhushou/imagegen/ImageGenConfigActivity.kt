package com.dbpg.duihuazhushou.imagegen

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.settings.EngineBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生生图接口配置页：交互与 LLM 运营商配置一致。
 *
 * 数据在 web 的 image store（生图走引擎管线，配置必须同源），
 * 经 window.__dhpApi.getImageConfig() 读、command() 写：
 *   imgSaveConn / imgAddModels / imgUseApi / imgRemoveApi
 */
class ImageGenConfigActivity : Activity() {

    private lateinit var baseEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var status: TextView
    private lateinit var apisBox: LinearLayout

    private var userEditedConn = false

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_config)
        Insets.applyTop(this)

        baseEdit = findViewById(R.id.imgconf_base)
        keyEdit = findViewById(R.id.imgconf_key)
        status = findViewById(R.id.imgconf_status)
        apisBox = findViewById(R.id.imgconf_apis_box)

        findViewById<TextView>(R.id.imgconf_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.imgconf_reload).setOnClickListener {
            userEditedConn = false
            load()
        }
        findViewById<TextView>(R.id.imgconf_fetch).setOnClickListener { fetchModels() }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                userEditedConn = true
            }
        }
        baseEdit.addTextChangedListener(watcher)
        keyEdit.addTextChangedListener(watcher)
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(apiListener)
        load()
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    private fun load() {
        EngineBridge.fetch(this, "getImageConfig") { obj ->
            if (obj == null) {
                status.text = "引擎还没就绪，稍等几秒点「刷新」"
                return@fetch
            }
            apply(obj)
        }
    }

    private fun apply(obj: JSONObject) {
        val cur = obj.optJSONObject("current") ?: JSONObject()
        if (!userEditedConn) {
            setIfDiffers(baseEdit, cur.optString("baseUrl"))
            setIfDiffers(keyEdit, cur.optString("apiKey"))
        }
        renderProviders(obj.optJSONArray("providers") ?: JSONArray())
    }

    private fun setIfDiffers(e: EditText, text: String) {
        if (e.text?.toString() != text) e.setText(text)
    }

    // ------------------------------------------------------------ 运营商列表

    private val openProviders = mutableSetOf<String>()

    /**
     * 运营商卡片：同一地址（baseUrl）下的所有模型归为一家。
     * 点卡片展开看模型，点模型即切换使用；长按删除模型。
     * （用户反馈：以前把每个模型都当成一家运营商，列表一长串全是模型名）
     */
    private fun renderProviders(arr: JSONArray) {
        apisBox.removeAllViews()

        apisBox.addView(TextView(this).apply {
            text = "＋ 新建运营商"
            setTextColor(0xFF2F66D8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { promptNewProvider() }
        })

        if (arr.length() == 0) {
            apisBox.addView(simpleCard("还没有运营商", "点「新建运营商」填地址和密钥，再拉取模型"))
            return
        }

        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val key = p.optString("key")
            val name = p.optString("name").ifBlank { p.optString("baseUrl").ifBlank { "(未填地址)" } }
            val baseUrl = p.optString("baseUrl")
            val hasKey = p.optBoolean("hasKey")
            val isCurrent = p.optBoolean("current")
            val models = p.optJSONArray("models") ?: JSONArray()
            val open = openProviders.contains(key)

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                setOnClickListener {
                    if (open) openProviders.remove(key) else openProviders.add(key)
                    load()
                }
                // 长按改运营商名（整组条目一起改，换模型时名字不会跳回去）
                setOnLongClickListener { promptRenameProvider(key, name); true }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = (if (isCurrent) "✓ " else "") + name
                setTextColor(if (isCurrent) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
                textSize = 14f
                if (isCurrent) setTypeface(typeface, Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = buildString {
                    append(baseUrl.ifBlank { "(未填地址)" })
                    if (!hasKey) append(" · 未填密钥")
                }
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            head.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = "${models.length()} 个模型"
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
            })
            head.addView(TextView(this).apply {
                text = if (open) "▾" else "▸"
                setTextColor(0xFF8A94A6.toInt())
                textSize = 14f
                setPadding(dp(8), 0, 0, 0)
            })
            apisBox.addView(head)

            if (!open) continue
            for (j in 0 until models.length()) {
                val m = models.getJSONObject(j)
                val uid = m.optString("uid")
                val model = m.optString("model")
                val cur = m.optBoolean("current")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = getDrawable(R.drawable.bg_btn_soft)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
                }
                row.addView(TextView(this).apply {
                    text = if (cur) "✓ " else ""
                    setTextColor(0xFF2F66D8.toInt())
                    textSize = 13f
                })
                row.addView(TextView(this).apply {
                    text = model.ifBlank { m.optString("name") }
                    setTextColor(if (cur) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (cur) "使用中" else "使用"
                    setTextColor(if (cur) 0xFF2F66D8.toInt() else 0xFF8A94A6.toInt())
                    textSize = 12f
                })
                row.setOnClickListener {
                    if (cur) return@setOnClickListener
                    EngineBridge.command(JSONObject().put("cmd", "imgUseApi").put("uid", uid))
                }
                row.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("删除模型")
                        .setMessage(model.ifBlank { m.optString("name") })
                        .setPositiveButton("删除") { _, _ ->
                            EngineBridge.command(JSONObject().put("cmd", "imgRemoveApi").put("uid", uid))
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                apisBox.addView(row)
            }
        }
    }

    /** 长按运营商卡片：改显示名。 */
    private fun promptRenameProvider(key: String, current: String) {
        val input = EditText(this).apply {
            setText(current)
            hint = "如「玄猫中转」"
            setTextSize(14f)
            setTextColor(0xFF1A2230.toInt())
            background = getDrawable(R.drawable.bg_input)
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
                EngineBridge.command(JSONObject().put("cmd", "imgRenameProvider").put("key", key).put("name", name))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建运营商：名称（可空）+ 地址 + 密钥。 */
    private fun promptNewProvider() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = box.addView(TextView(this).apply {
            text = t
            setTextColor(0xFF5A6B85.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
        fun input(hint: String, uri: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setTextSize(13f)
            setTextColor(0xFF1A2230.toInt())
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (uri) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            else android.text.InputType.TYPE_CLASS_TEXT
        }.also { box.addView(it) }

        label("名称（可留空，用域名）")
        val name = input("如「玄猫中转」")
        label("请求地址（Base URL）")
        val url = input("https://api.example.com/v1", uri = true)
        label("API 密钥")
        val key = input("sk-…")

        AlertDialog.Builder(this)
            .setTitle("新建运营商")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val u = url.text?.toString()?.trim().orEmpty()
                if (u.isEmpty()) {
                    toast("地址不能为空")
                    return@setPositiveButton
                }
                EngineBridge.command(
                    JSONObject().put("cmd", "imgAddProvider")
                        .put("name", name.text?.toString()?.trim() ?: "")
                        .put("baseUrl", u)
                        .put("apiKey", key.text?.toString()?.trim() ?: ""),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun simpleCard(title: String, sub: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF5A6B85.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF9AA4B2.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        return box
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    // ------------------------------------------------------------ 拉取 / 命令

    private fun fetchModels() {
        if (!EngineBridge.ready()) {
            status.text = "引擎还没启动，稍后重试"
            return
        }
        if (baseEdit.text?.toString()?.trim().isNullOrEmpty()) {
            status.text = "请先填写请求地址（Base URL）"
            return
        }
        status.text = "拉取中…"
        EngineBridge.command(
            JSONObject()
                .put("cmd", "imgSaveConn")
                .put("baseUrl", baseEdit.text?.toString()?.trim() ?: "")
                .put("apiKey", keyEdit.text?.toString()?.trim() ?: ""),
        )
        val w = com.dbpg.duihuazhushou.MainActivity.getWebView() ?: return
        val payload = org.json.JSONObject.quote(
            JSONObject()
                .put("baseUrl", baseEdit.text?.toString()?.trim() ?: "")
                .put("apiKey", keyEdit.text?.toString()?.trim() ?: "")
                .toString(),
        )
        w.evaluateJavascript("window.__dhpApi && window.__dhpApi.fetchModels($payload)") {}
    }

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
                "imgAddModels" -> {
                    if (ok) {
                        status.text = "已添加 ${obj.optInt("added", 0)} 个" +
                            (if (obj.optInt("skipped", 0) > 0) "（跳过重复 ${obj.optInt("skipped")} 个）" else "")
                        toast(status.text.toString())
                        load()
                    } else {
                        status.text = "添加失败：${obj.optString("error")}"
                    }
                }
                "imgRenameProvider" -> {
                    if (ok) {
                        toast("已改名")
                        load()
                    } else {
                        status.text = "改名失败：${obj.optString("error")}"
                    }
                }
                "imgAddProvider" -> {
                    if (ok) {
                        toast("已创建，接着拉取模型即可")
                        load()
                    } else {
                        status.text = "创建失败：${obj.optString("error")}"
                    }
                }
                "imgUseApi" -> {
                    if (ok) {
                        toast("已切换")
                        load()
                    } else {
                        status.text = "切换失败：${obj.optString("error")}"
                    }
                }
                "imgRemoveApi" -> {
                    if (ok) {
                        toast("已删除")
                        load()
                    } else {
                        status.text = "删除失败：${obj.optString("error")}"
                    }
                }
                "imgSaveConn" -> if (!ok) status.text = "保存失败：${obj.optString("error")}"
            }
        } catch (t: Throwable) {
            status.text = "结果解析失败：${t.message}"
        }
    }

    private fun showModelMultiPicker(models: List<String>) {
        val names = models.toTypedArray()
        val checked = BooleanArray(models.size)
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
                EngineBridge.command(
                    JSONObject()
                        .put("cmd", "imgAddModels")
                        .put("baseUrl", baseEdit.text?.toString()?.trim() ?: "")
                        .put("apiKey", keyEdit.text?.toString()?.trim() ?: "")
                        .put("models", arr),
                )
            }
            .setNeutralButton("全选") { d, _ ->
                d.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("选择要添加的模型（${models.size} 个）")
                    .setMultiChoiceItems(names, BooleanArray(models.size) { true }) { _, _, _ -> }
                    .setPositiveButton("添加全部") { _, _ ->
                        status.text = "添加 ${models.size} 个模型中…"
                        val arr = JSONArray()
                        for (m in models) arr.put(m)
                        EngineBridge.command(
                            JSONObject()
                                .put("cmd", "imgAddModels")
                                .put("baseUrl", baseEdit.text?.toString()?.trim() ?: "")
                                .put("apiKey", keyEdit.text?.toString()?.trim() ?: "")
                                .put("models", arr),
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
