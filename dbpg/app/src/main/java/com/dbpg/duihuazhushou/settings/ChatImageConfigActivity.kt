package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话生图配置（与标签生图**完全分开**）。
 *
 * 管的是「聊天里点 🎨 快速出图」用什么：带多少条上文、用不用对话模型把上文
 * 转成英文标签（用哪个模型）、哪家运营商、哪个模型、哪套风格、步数/CFG/采样器、
 * 以及图片大小。
 *
 * ## 2026-11-07 与标签生图解耦
 *
 * 两处都读同一份 [ConnectionStore]，但各自有独立的选中接口
 * （`sceneApiIds.chat` / `sceneApiIds.tag`）：在聊天里换模型不会影响标签页，
 * 反之亦然。所有 `imgUseApi` 都带 `scope=chat`。
 *
 * 界面上不再出现地址与密钥 —— 那是在「生图运营商」里配一次的底层信息，
 * 这里只显示「运营商 · 模型」，点一下直接列出当前运营商下的所有模型可切换。
 */
class ChatImageConfigActivity : Activity() {

    private lateinit var ctxValue: TextView
    private lateinit var useLlm: Switch
    private lateinit var llmSelect: TextView
    private lateinit var apiSelect: TextView
    private lateinit var engineSelect: TextView
    private lateinit var styleSelect: TextView
    private lateinit var sizeChips: LinearLayout
    private lateinit var steps: EditText
    private lateinit var cfg: EditText
    private lateinit var samplerSelect: TextView

    private var ctxWindow = 5
    private var llmUid = ""
    private var llmLabel = "跟随当前模型"
    private var apiUid = ""
    private var styleUid = ""
    private var sampler = "k_euler_ancestral"
    private var curWidth = 832
    private var curHeight = 1216
    private var curEngine = ImageEngine.NAI_RELAY

    /** 本场景（chat）的接口快照。地址/密钥只在内部发请求时用，界面不显示。 */
    private var scene = JSONObject()
    private var providers: List<JSONObject> = emptyList()
    private var styles: List<JSONObject> = emptyList()
    private var llms: List<JSONObject> = emptyList()

    /** 只负责把失败提示出来；成功后的刷新由各动作显式调用 load()。 */
    private val listener = NativeBridge.ApiResultListener { json ->
        try {
            val o = JSONObject(json)
            if (!o.optBoolean("ok")) toast("保存失败：${o.optString("error")}")
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
        engineSelect = findViewById(R.id.cimg_engine_select)
        styleSelect = findViewById(R.id.cimg_style_select)
        sizeChips = findViewById(R.id.cimg_size_chips)
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
                toast("读取配置失败")
                return@fetch
            }
            scene = obj.optJSONObject("scenes")?.optJSONObject("chat")
                ?: obj.optJSONObject("current") ?: JSONObject()
            apiUid = scene.optString("uid")
            val auto = obj.optJSONObject("auto") ?: JSONObject()
            ctxWindow = auto.optInt("quickContextWindow", 5).coerceIn(1, 20)
            useLlm.isChecked = scene.optBoolean("useLlmOptimization", true)
            llmUid = scene.optString("llmConfigId")
            curWidth = scene.optInt("width", 832)
            curHeight = scene.optInt("height", 1216)
            curEngine = ImageEngine.of(scene.optString("engine"))
            renderCtx()

            val arr = obj.optJSONArray("providers") ?: JSONArray()
            providers = (0 until arr.length()).map { arr.getJSONObject(it) }
            val model = scene.optString("model").ifBlank { "未选模型" }
            apiSelect.text = "${scene.optString("apiName").ifBlank { "未选运营商" }} · $model"
            apiSelect.setOnClickListener { pickModelQuick() }
            engineSelect.text = "渲染器：${curEngine.label}"
            engineSelect.setOnClickListener { pickEngine() }
            buildSizeChips()

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

    /**
     * 一步切模型：已经选好了运营商（如「砂糖」），最常用的是在它下面换模型，
     * 不该每次先重选一遍运营商。列表末尾留「换一家运营商」。
     */
    private fun pickModelQuick() {
        if (providers.isEmpty()) return toast("还没有生图运营商")
        val prov = providers.firstOrNull { p ->
            val models = p.optJSONArray("models") ?: JSONArray()
            (0 until models.length()).any { models.getJSONObject(it).optString("uid") == apiUid }
        }
        if (prov == null) { pickProviderThenModel(); return }
        val models = prov.optJSONArray("models") ?: JSONArray()
        val labels = (0 until models.length()).map { j ->
            models.getJSONObject(j).optString("model").ifBlank { models.getJSONObject(j).optString("name") }
        } + "换一家运营商…"
        AlertDialog.Builder(this)
            .setTitle("${prov.optString("name")} · 切换模型")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.size - 1) { pickProviderThenModel(); return@setItems }
                useApi(models.getJSONObject(which).optString("uid"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 两级选择：运营商 → 该家下的模型。 */
    private fun pickProviderThenModel() {
        if (providers.isEmpty()) return toast("还没有生图运营商")
        val names = providers.map { it.optString("name").ifBlank { "(未命名)" } }
        AlertDialog.Builder(this)
            .setTitle("选择生图运营商")
            .setItems(names.toTypedArray()) { _, i ->
                val models = providers[i].optJSONArray("models") ?: JSONArray()
                if (models.length() == 0) return@setItems toast("这家还没添加模型")
                if (models.length() == 1) {
                    useApi(models.getJSONObject(0).optString("uid"))
                    return@setItems
                }
                val labels = (0 until models.length()).map { j ->
                    models.getJSONObject(j).optString("model").ifBlank { models.getJSONObject(j).optString("name") }
                }
                AlertDialog.Builder(this)
                    .setTitle("选择模型")
                    .setItems(labels.toTypedArray()) { _, j ->
                        useApi(models.getJSONObject(j).optString("uid"))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** scope=chat：同步写 currentApiId（网页自动配图也认它），不影响标签生图。 */
    private fun useApi(uid: String) {
        EngineBridge.command(
            JSONObject().put("cmd", "imgUseApi").put("uid", uid).put("scope", "chat"),
        )
        load()
    }

    private fun pickEngine() {
        val engines = ImageEngine.entries
        val labels = engines.map { "${it.label} — ${it.hint}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择渲染器")
            .setSingleChoiceItems(labels, engines.indexOf(curEngine)) { dlg, which ->
                EngineBridge.command(
                    JSONObject().put("cmd", "imgSetEngine")
                        .put("uid", apiUid).put("engine", engines[which].id),
                )
                dlg.dismiss()
                load()
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
                EngineBridge.command(
                    JSONObject().put("cmd", "imgUseStyle").put("uid", styles[i].optString("uid")),
                )
                load()
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
        val options = ImageEngineSupport.NAI_SAMPLERS
        AlertDialog.Builder(this)
            .setTitle("采样器（${curEngine.label}）")
            .setItems(options.toTypedArray()) { _, i ->
                sampler = options[i]
                samplerSelect.text = "采样器：$sampler"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 小图快捷档位，与标签生图页、风格页共用同一份配置。 */
    private fun buildSizeChips() {
        SizePresetChips.render(this, sizeChips, curWidth, curHeight) { w, h ->
            curWidth = w
            curHeight = h
            EngineBridge.command(
                JSONObject().put("cmd", "imgSaveSize").put("width", w).put("height", h),
            )
            buildSizeChips()
        }
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
        EngineBridge.command(
            JSONObject().put("cmd", "imgSaveSize").put("width", curWidth).put("height", curHeight),
        )
        toast("已保存")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
