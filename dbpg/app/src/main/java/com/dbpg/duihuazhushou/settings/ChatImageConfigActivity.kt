package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话生图配置（与标签生图分开）。
 *
 * 管的是"聊天里点 🎨 快速出图"用什么：带多少条上文、用不用对话模型把上文
 * 转成英文标签（用哪个模型）、哪家生图运营商、哪套风格、步数/CFG/采样器。
 *
 * 数据在 web image store（自动配图也走这套配置），经 __dhpApi 读写：
 *   getImageConfig()  → apis / current / auto(quickContextWindow…) / llmConfigs
 *   getImageStyle()   → styles / current style 的采样参数
 *   imgSaveAuto / imgSaveSampling / imgUseApi / imgUseStyle
 */
class ChatImageConfigActivity : Activity() {

    private lateinit var ctxValue: TextView
    private lateinit var useLlm: Switch
    private lateinit var llmSelect: TextView
    private lateinit var apiSelect: TextView
    private lateinit var styleSelect: TextView
    private lateinit var steps: EditText
    private lateinit var cfg: EditText
    private lateinit var samplerSelect: TextView

    private var ctxWindow = 5
    private var llmUid = ""
    private var llmLabel = "跟随当前模型"
    private var apiUid = ""
    private var styleUid = ""
    private var sampler = "k_euler_ancestral"

    private var providers: List<JSONObject> = emptyList()
    private var styles: List<JSONObject> = emptyList()
    private var llms: List<JSONObject> = emptyList()

    private val listener = NativeBridge.ApiResultListener { json ->
        try {
            val o = JSONObject(json)
            val ok = o.optBoolean("ok")
            if (!ok) {
                toast("保存失败：${o.optString("error")}")
                return@ApiResultListener
            }
            when (o.optString("kind")) {
                "imgUseApi", "imgUseStyle" -> load()
                "imgSaveAuto", "imgSaveSampling" -> toast("已保存")
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_image_config)
        Insets.applyTop(this)

        ctxValue = findViewById(R.id.cimg_ctx_value)
        useLlm = findViewById(R.id.cimg_use_llm)
        llmSelect = findViewById(R.id.cimg_llm_select)
        apiSelect = findViewById(R.id.cimg_api_select)
        styleSelect = findViewById(R.id.cimg_style_select)
        steps = findViewById(R.id.cimg_steps)
        cfg = findViewById(R.id.cimg_cfg)
        samplerSelect = findViewById(R.id.cimg_sampler_select)

        findViewById<TextView>(R.id.cimg_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.cimg_reload).setOnClickListener { load() }
        findViewById<TextView>(R.id.cimg_ctx_minus).setOnClickListener {
            ctxWindow = (ctxWindow - 1).coerceAtLeast(1)
            renderCtx()
        }
        findViewById<TextView>(R.id.cimg_ctx_plus).setOnClickListener {
            ctxWindow = (ctxWindow + 1).coerceAtMost(20)
            renderCtx()
        }
        llmSelect.setOnClickListener { pickLlm() }
        samplerSelect.setOnClickListener { pickSampler() }
        findViewById<TextView>(R.id.cimg_save).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(listener)
        load()
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    // ------------------------------------------------------------ 读取

    private fun load() {
        EngineBridge.fetch(this, "getImageConfig") { obj ->
            if (obj == null) {
                toast("引擎还没就绪，稍后点「刷新」")
                return@fetch
            }
            val cur = obj.optJSONObject("current") ?: JSONObject()
            val auto = obj.optJSONObject("auto") ?: JSONObject()
            apiUid = cur.optString("uid")
            ctxWindow = auto.optInt("quickContextWindow", 5).coerceIn(1, 20)
            useLlm.isChecked = cur.optBoolean("useLlmOptimization")
            llmUid = cur.optString("llmConfigId")
            renderCtx()

            val arr = obj.optJSONArray("providers") ?: JSONArray()
            providers = (0 until arr.length()).map { arr.getJSONObject(it) }
            apiSelect.text = buildString {
                append("运营商：")
                append(cur.optString("apiName").ifBlank { "未选" })
                val m = cur.optString("model")
                if (m.isNotBlank()) append(" · ").append(m)
            }
            apiSelect.setOnClickListener { pickProviderThenModel() }

            val la = obj.optJSONArray("llmConfigs") ?: JSONArray()
            llms = (0 until la.length()).map { la.getJSONObject(it) }
            val hit = llms.find { it.optString("uid") == llmUid }
            llmLabel = hit?.optString("name")?.ifBlank { hit.optString("model") } ?: "跟随当前模型"
            llmSelect.text = "提示词模型：$llmLabel"
        }
        EngineBridge.fetch(this, "getImageStyle") { obj ->
            if (obj == null) return@fetch
            val arr = obj.optJSONArray("styles") ?: JSONArray()
            styles = (0 until arr.length()).map { arr.getJSONObject(it) }
            styleUid = obj.optString("styleUid")
            styleSelect.text = "风格：" + obj.optString("styleName").ifBlank { "默认" }
            styleSelect.setOnClickListener { pickStyle() }
            steps.setText(obj.optInt("steps", 28).toString())
            cfg.setText(obj.optDouble("cfgScale", 6.0).toString())
            sampler = obj.optString("sampler").ifBlank { "k_euler_ancestral" }
            samplerSelect.text = "采样器：$sampler"
        }
    }

    private fun renderCtx() {
        ctxValue.text = "$ctxWindow 条"
    }

    // ------------------------------------------------------------ 选择器

    /** 两级选择：运营商（地址） → 该家下的模型。 */
    private fun pickProviderThenModel() {
        if (providers.isEmpty()) return toast("还没有生图运营商")
        val names = providers.map { it.optString("name").ifBlank { it.optString("baseUrl") } }
        AlertDialog.Builder(this)
            .setTitle("选择生图运营商")
            .setItems(names.toTypedArray()) { _, i ->
                val models = providers[i].optJSONArray("models") ?: JSONArray()
                if (models.length() == 0) return@setItems toast("这家还没添加模型")
                if (models.length() == 1) {
                    val uid = models.getJSONObject(0).optString("uid")
                    EngineBridge.command(JSONObject().put("cmd", "imgUseApi").put("uid", uid))
                    return@setItems
                }
                val labels = (0 until models.length()).map { j ->
                    models.getJSONObject(j).optString("model").ifBlank { models.getJSONObject(j).optString("name") }
                }
                AlertDialog.Builder(this)
                    .setTitle("选择模型")
                    .setItems(labels.toTypedArray()) { _, j ->
                        val uid = models.getJSONObject(j).optString("uid")
                        EngineBridge.command(JSONObject().put("cmd", "imgUseApi").put("uid", uid))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickStyle() {
        if (styles.isEmpty()) return toast("还没有风格")
        val names = styles.map { it.optString("name") }
        AlertDialog.Builder(this)
            .setTitle("选择出图风格")
            .setItems(names.toTypedArray()) { _, i ->
                val uid = styles[i].optString("uid")
                EngineBridge.command(JSONObject().put("cmd", "imgUseStyle").put("uid", uid))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickLlm() {
        val names = mutableListOf("跟随当前模型")
        for (l in llms) names.add(l.optString("name").ifBlank { l.optString("model") })
        AlertDialog.Builder(this)
            .setTitle("提示词由哪个模型生成")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) {
                    llmUid = ""
                    llmLabel = "跟随当前模型"
                } else {
                    val l = llms[i - 1]
                    llmUid = l.optString("uid")
                    llmLabel = l.optString("name").ifBlank { l.optString("model") }
                }
                llmSelect.text = "提示词模型：$llmLabel"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickSampler() {
        val options = arrayOf(
            "k_euler_ancestral", "k_euler", "k_dpmpp_2m", "k_dpmpp_sde", "ddim",
        )
        AlertDialog.Builder(this)
            .setTitle("采样器")
            .setItems(options) { _, i ->
                sampler = options[i]
                samplerSelect.text = "采样器：$sampler"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 保存

    private fun save() {
        EngineBridge.command(
            JSONObject().put("cmd", "imgSaveAuto").put("uid", apiUid).put(
                "auto",
                JSONObject()
                    .put("quickContextWindow", ctxWindow)
                    .put("useLlmOptimization", useLlm.isChecked)
                    .put("llmConfigId", llmUid),
            ),
        )
        EngineBridge.command(
            JSONObject().put("cmd", "imgSaveSampling").put("uid", styleUid).put(
                "sampling",
                JSONObject()
                    .put("steps", steps.text?.toString()?.toIntOrNull() ?: 28)
                    .put("cfgScale", cfg.text?.toString()?.toDoubleOrNull() ?: 6.0)
                    .put("sampler", sampler),
            ),
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
