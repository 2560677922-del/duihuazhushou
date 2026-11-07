package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 原生 API 接口配置页：地址 + 密钥 + 拉取模型 + 选中即用。
 *
 * 数据层在 web 引擎（apiConfig store），本页通过引擎 WebView 上的
 * window.__dhpApi 读写：
 *
 *   getConfig()              同步返回 JSON 串（当前地址/密钥/模型 + 运营商概览）
 *   saveConfig(json)         异步保存 → 完成后回调 AndroidNative.onApiResult
 *   fetchModels(json)        异步拉模型 → 成功/失败都回调 onApiResult
 *
 * 为什么不把配置存到原生侧：生成请求是 web 组装发出的，配置必须同源。
 * 原生存一份、web 读一份，早晚打架。
 */
class ApiConfigActivity : Activity() {

    private lateinit var baseEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var providers: TextView

    /** 最近一次拉到的模型列表（对话框被旋转/遮挡时还要用）。 */
    private var lastModels: List<String> = emptyList()

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_config)

        baseEdit = findViewById(R.id.api_base)
        keyEdit = findViewById(R.id.api_key)
        status = findViewById(R.id.api_status)
        current = findViewById(R.id.api_current)
        providers = findViewById(R.id.api_providers)

        findViewById<TextView>(R.id.api_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.api_fetch).setOnClickListener { fetchModels() }
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

    private fun loadConfig() {
        val w = MainActivity.getWebView()
        if (w == null) {
            current.text = "引擎还没启动，稍后重试"
            return
        }
        w.evaluateJavascript(
            "window.__dhpApi ? window.__dhpApi.getConfig() : null",
        ) { r ->
            runOnUiThread { applyConfig(r) }
        }
    }

    /** evaluateJavascript 返回值是「JSON 化的 JS 值」：字符串会再包一层引号，用 JSONTokener 统一拆。 */
    private fun applyConfig(raw: String?) {
        if (raw.isNullOrEmpty() || raw == "null") {
            current.text = "引擎还没就绪（网页还在加载），稍等几秒重进"
            return
        }
        try {
            val value = JSONTokener(raw).nextValue()
            val obj = JSONObject(value.toString())
            baseEdit.setText(obj.optString("baseUrl"))
            keyEdit.setText(obj.optString("apiKey"))
            val model = obj.optString("model")
            val name = obj.optString("modelName")
            current.text =
                if (model.isBlank()) "未选择（先填地址和密钥，再拉取模型）" else "$name\n$model"

            val arr = obj.optJSONArray("providers")
            providers.text = if (arr == null || arr.length() == 0) {
                "还没有运营商（填好地址拉取模型后自动建立）"
            } else {
                buildString {
                    append("已保存的运营商：")
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        append('\n')
                        append("· ")
                        append(p.optString("note").ifBlank { p.optString("baseUrl").ifBlank { "(未填地址)" } })
                        append(" · ")
                        append(p.optInt("modelCount"))
                        append(" 个模型")
                    }
                }
            }
        } catch (t: Throwable) {
            current.text = "配置读取失败：${t.message}"
        }
    }

    // ------------------------------------------------------------ 拉取 / 保存

    /** 当前输入框里的连接信息，序列化成 __dhpApi 的参数。 */
    private fun payload(model: String? = null): String {
        val o = JSONObject()
        o.put("baseUrl", baseEdit.text?.toString()?.trim() ?: "")
        o.put("apiKey", keyEdit.text?.toString()?.trim() ?: "")
        if (model != null) o.put("model", model)
        return o.toString()
    }

    private fun fetchModels() {
        val w = MainActivity.getWebView()
        if (w == null) {
            status.text = "引擎还没启动，稍后重试"
            return
        }
        val baseUrl = baseEdit.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            status.text = "请先填写请求地址（Base URL）"
            return
        }
        status.text = "拉取中…"
        // 先把地址/密钥落库（运营商级同步），再拉列表 —— 两件事互不阻塞
        w.evaluateJavascript("window.__dhpApi && window.__dhpApi.saveConfig(${jsArg(payload())})") { }
        w.evaluateJavascript("window.__dhpApi && window.__dhpApi.fetchModels(${jsArg(payload())})") { }
    }

    /**
     * web 侧参数必须是 JSON **字符串**（__dhpApi 内部会 JSON.parse），
     * 所以这里把 JSON 包成带引号的 JS 字符串字面量再内联。
     * 用 JSONObject.quote 生成，引号/换行/反斜杠都会被正确转义。
     */
    private fun jsArg(json: String): String = JSONObject.quote(json)

    // ------------------------------------------------------------ 结果回调

    private fun onApiResult(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("kind")) {
                "models" -> {
                    if (obj.optBoolean("ok")) {
                        val arr = obj.optJSONArray("models") ?: JSONArray()
                        val models = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) {
                            val m = arr.optString(i)
                            if (m.isNotBlank()) models.add(m)
                        }
                        lastModels = models
                        status.text = "拉到 ${models.size} 个模型，点选择一个"
                        if (models.isEmpty()) {
                            status.text = "该地址返回了空模型列表"
                        } else {
                            showModelPicker(models)
                        }
                    } else {
                        status.text = "拉取失败：${obj.optString("error")}"
                    }
                }
                "save" -> {
                    if (obj.optBoolean("ok")) {
                        loadConfig() // 保存成功 → 刷新当前模型/运营商展示
                    } else {
                        status.text = "保存失败：${obj.optString("error")}"
                    }
                }
            }
        } catch (t: Throwable) {
            status.text = "结果解析失败：${t.message}"
        }
    }

    private fun showModelPicker(models: List<String>) {
        val names = models.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择模型（${models.size} 个）")
            .setItems(names) { _, which ->
                val model = models[which]
                status.text = "切换到 $model …"
                MainActivity.getWebView()?.evaluateJavascript(
                    "window.__dhpApi && window.__dhpApi.saveConfig(${jsArg(payload(model))})",
                ) { }
                Toast.makeText(this, "已选择 $model", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
