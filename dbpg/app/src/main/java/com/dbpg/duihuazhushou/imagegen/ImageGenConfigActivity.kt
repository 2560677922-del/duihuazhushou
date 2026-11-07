package com.dbpg.duihuazhushou.imagegen

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
        renderApis(obj.optJSONArray("apis") ?: JSONArray())
    }

    private fun setIfDiffers(e: EditText, text: String) {
        if (e.text?.toString() != text) e.setText(text)
    }

    // ------------------------------------------------------------ 接口列表

    private fun renderApis(arr: JSONArray) {
        apisBox.removeAllViews()
        if (arr.length() == 0) {
            apisBox.addView(simpleCard("还没有接口", "填好地址和密钥，拉取模型后自动建立"))
            return
        }
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val uid = a.optString("uid")
            val model = a.optString("model")
            val name = a.optString("name")
            val baseUrl = a.optString("baseUrl")
            val current = a.optBoolean("current")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            }
            row.addView(TextView(this).apply {
                text = if (current) "✓ " else ""
                setTextColor(0xFF7FB2FF.toInt())
                textSize = 13f
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ImageGenConfigActivity).apply {
                    text = model.ifBlank { name.ifBlank { "(未选模型)" } }
                    setTextColor(if (current) 0xFF7FB2FF.toInt() else 0xFFE6EAF2.toInt())
                    textSize = 13.5f
                    if (current) setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@ImageGenConfigActivity).apply {
                    text = buildString {
                        append(baseUrl.ifBlank { "(未填地址)" })
                        if (!a.optBoolean("hasKey")) append(" · 未填密钥")
                    }
                    setTextColor(0xFF6C7A90.toInt())
                    textSize = 11f
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (current) "使用中" else "使用"
                setTextColor(if (current) 0xFF7FB2FF.toInt() else 0xFF6C7A90.toInt())
                textSize = 12f
            })
            row.setOnClickListener {
                if (current) return@setOnClickListener
                status.text = "切换到 ${model.ifBlank { name }} …"
                EngineBridge.command(JSONObject().put("cmd", "imgUseApi").put("uid", uid))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("删除接口")
                    .setMessage(model.ifBlank { name })
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

    private fun simpleCard(title: String, sub: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF8FA6C8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF4E5A6B.toInt())
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
