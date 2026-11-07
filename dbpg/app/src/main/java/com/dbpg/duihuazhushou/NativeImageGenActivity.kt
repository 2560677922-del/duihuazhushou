package com.dbpg.duihuazhushou

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.chat.ImageLoader
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.imagegen.ImageStore
import com.dbpg.duihuazhushou.imagegen.TagCategories
import com.dbpg.duihuazhushou.imagegen.TagLibrary
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.settings.SizePresetChips
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import com.dbpg.stcore.imagegen.ImageParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生「标签生图」页。
 *
 * ## 2026-11-07 重构要点
 *
 * 1. **不再显示地址 / 密钥 / 模型输入框**：用户已经在「生图运营商」里配好
 *    「砂糖」这类中转，界面上只需要选「哪家 → 哪个模型」，连接细节留在
 *    [com.dbpg.duihuazhushou.settings.ConnectionStore] 内部发请求时用。
 * 2. **与对话生图分离**：本页所有切换都带 `scope=tag`，只写
 *    `image.sceneApiIds.tag`；聊天里的 🎨 读 `sceneApiIds.chat`，互不干扰。
 * 3. **渲染器分流**：按 [ImageEngine] 构造请求体，NovelAI 官方格式会带上
 *    `sampler / steps / scale / noise_schedule / negative_prompt`，本地部署
 *    会带上 `size / cfg_scale / steps`。之前一律发 OpenAI 最小请求体，
 *    这些参数选了等于没选。
 * 4. **小图尺寸快捷档位**：只给 832×1216 / 1216×832 / 512×512 这类小尺寸，
 *    点一下即写入配置，风格页与这里共用。
 * 5. **标签库是完整 21097 条**：APK 里带的 `assets/www/tags/novelai-tags.json`
 *    一直是全量，之前 UI 无筛选只取 12 条还把 12 当总数显示，看着像「只有十几个标签」。
 *    现在状态行分别显示库总量 / 筛选命中量 / 本页显示量，并可「加载更多」。
 * 6. **中文展示、英文发送**：已选标签在界面上以中文 chip 呈现（可单个移除 / 清空），
 *    但拼进生图请求体的只有英文 tag。生成按钮与结果图放在标签列表**之前**，
 *    这样标签列表再怎么展开也不会把出图结果顶出屏幕。
 */
class NativeImageGenActivity : Activity() {

    private lateinit var apiSelect: TextView
    private lateinit var engineSelect: TextView
    private lateinit var styleSelect: TextView
    private lateinit var sizeValue: TextView
    private lateinit var sizeChips: LinearLayout
    private lateinit var stepsInput: EditText
    private lateinit var cfgInput: EditText
    private lateinit var samplerSelect: TextView
    private lateinit var promptInput: EditText
    private lateinit var selectedBox: LinearLayout
    private lateinit var runBtn: TextView
    private lateinit var statusView: TextView
    private lateinit var results: LinearLayout

    private var service: ImageGenService? = null

    /** 标签一级/二级分类筛选（空 = 全部）。 */
    private var tagGroup = ""
    private var tagSub = ""

    /**
     * 已选标签：界面只显示中文（zh），发请求时只取英文 tag。
     * 用户之前记不住自己选过什么，就是因为没有这份「看得懂」的清单。
     */
    private val selectedTags = mutableListOf<TagLibrary.Tag>()

    /** 标签结果分页：默认铺开 30 条，点「加载更多」再加 60 条。 */
    private var tagLimit = 30
    private var tagQuery = ""

    /** 当前风格提示词（出图时拼进提示词/负面）。 */
    private var styleArtist = ""
    private var styleQuality = ""
    private var styleNegative = ""
    private var styleUid = ""

    /** 选择器数据。 */
    private var providerList: List<JSONObject> = emptyList()
    private var styleList: List<JSONObject> = emptyList()

    /** 本场景（tag）选中的接口快照，含内部用的 baseUrl / apiKey，但界面不展示。 */
    private var scene = JSONObject()
    private var curWidth = 832
    private var curHeight = 1216
    private var curEngine = ImageEngine.NAI_RELAY
    private var curSampler = "k_euler_ancestral"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_image_gen)
        Insets.applyTop(this)

        apiSelect = findViewById(R.id.gen_api_select)
        engineSelect = findViewById(R.id.gen_engine_select)
        styleSelect = findViewById(R.id.gen_style_select)
        sizeValue = findViewById(R.id.gen_size_value)
        sizeChips = findViewById(R.id.gen_size_chips)
        stepsInput = findViewById(R.id.gen_steps)
        cfgInput = findViewById(R.id.gen_cfg)
        samplerSelect = findViewById(R.id.gen_sampler_select)
        promptInput = findViewById(R.id.gen_prompt)
        selectedBox = findViewById(R.id.gen_selected_tags)
        runBtn = findViewById(R.id.gen_run)
        statusView = findViewById(R.id.gen_status)
        results = findViewById(R.id.gen_results)

        findViewById<TextView>(R.id.gen_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.gen_config).setOnClickListener {
            startActivity(Intent(this, com.dbpg.duihuazhushou.imagegen.ImageGenConfigActivity::class.java))
        }
        findViewById<TextView>(R.id.gen_gallery).setOnClickListener {
            startActivity(Intent(this, com.dbpg.duihuazhushou.gallery.GalleryActivity::class.java))
        }
        runBtn.setOnClickListener { run() }

        // 快捷标签：纯原生读 APK 内置标签库，不走桥
        val tagSearch = findViewById<EditText>(R.id.gen_tag_search)
        val tagBox = findViewById<LinearLayout>(R.id.gen_tags)
        val tagStatus = findViewById<TextView>(R.id.gen_tag_status)
        TagLibrary.load(this)
        var tagDebounce: Runnable? = null
        tagSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                tagDebounce?.let { tagSearch.removeCallbacks(it) }
                tagLimit = 30 // 换了关键词就重新从第一页开始
                val r = Runnable { renderTags(tagBox, tagStatus, s?.toString() ?: "") }
                tagDebounce = r
                tagSearch.postDelayed(r, 300)
            }
        })
        buildGroupTabs()
        loadSelectors()
        renderSelectedTags()
        renderTags(tagBox, tagStatus, "")
    }

    /**
     * 标签搜索结果：每行两个 chip，点一下加入「已选标签」。
     *
     * 之前无筛选时只取 12 条、并把这 12 条当「匹配 12 条」显示，
     * 用户以为整个标签库只有十几个（实际打包的是 21097 条）。
     * 现在状态行分三个数：库总量 / 当前筛选命中量 / 本页显示量，
     * 显示完还能继续「加载更多」。
     */
    private fun renderTags(box: LinearLayout, status: TextView, query: String) {
        box.removeAllViews()
        tagQuery = query
        val total = TagLibrary.count(query, tagGroup, tagSub)
        val hits = TagLibrary.search(query, tagGroup, tagSub, tagLimit)
        status.text = buildString {
            append("标签库 ").append(TagLibrary.size()).append(" 条")
            if (tagGroup.isNotBlank()) {
                append(" · ").append(tagGroup)
                if (tagSub.isNotBlank()) append(" · ").append(TagCategories.subLabel(tagSub))
            }
            append(" · 命中 ").append(total).append(" 条 · 显示 ").append(hits.size).append(" 条")
        }
        var row: LinearLayout? = null
        for ((i, t) in hits.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row)
            }
            row?.addView(TextView(this).apply {
                text = "${t.zh}（${t.tag}）"
                textSize = 12f
                setTextColor(0xFF3A4353.toInt())
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                    topMargin = 4
                }
                setOnClickListener { addTag(t) }
            })
        }
        if (hits.size < total) {
            box.addView(TextView(this).apply {
                text = "加载更多（还有 ${total - hits.size} 条）"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFF2F66D8.toInt())
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    tagLimit += 60
                    renderTags(box, status, tagQuery)
                }
            })
        }
        if (total == 0) {
            box.addView(TextView(this).apply {
                text = "没有匹配的标签，换个关键词或分类试试"
                textSize = 12f
                setTextColor(0xFF8A94A6.toInt())
                setPadding(0, dp(10), 0, dp(10))
            })
        }
    }

    /** 选中一个标签：只记对象，界面用中文，出图时才取英文 tag。 */
    private fun addTag(t: TagLibrary.Tag) {
        if (selectedTags.any { it.tag == t.tag }) {
            toast("「${t.zh}」已经选过了")
            return
        }
        selectedTags.add(t)
        renderSelectedTags()
    }

    /** 已选清单：中文 chip + × 移除 + 一键清空。 */
    private fun renderSelectedTags() {
        selectedBox.removeAllViews()
        selectedBox.addView(TextView(this).apply {
            text = if (selectedTags.isEmpty()) {
                "已选标签：还没选（点下方标签，界面显示中文，出图只发英文）"
            } else {
                "已选 ${selectedTags.size} 个标签 · 显示中文，出图只发英文 tag"
            }
            textSize = 11f
            setTextColor(0xFF5A6B85.toInt())
            setPadding(dp(2), dp(2), 0, dp(4))
        })
        if (selectedTags.isEmpty()) return

        var row: LinearLayout? = null
        for ((i, t) in selectedTags.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                selectedBox.addView(row)
            }
            row?.addView(TextView(this).apply {
                text = "${t.zh}  ×"
                textSize = 12f
                setTextColor(0xFF2F66D8.toInt())
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                    topMargin = dp(4)
                }
                setOnClickListener {
                    selectedTags.remove(t)
                    renderSelectedTags()
                }
            })
        }
        selectedBox.addView(TextView(this).apply {
            text = "清空已选"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF8A94A6.toInt())
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener {
                selectedTags.clear()
                renderSelectedTags()
            }
        })
    }

    /** 出图：按选定渲染器构造参数，端点候选逐个试。 */
    private fun run() {
        // 已选标签只把英文 tag 发给模型；中文只存在于界面上。
        // 手写的补充提示词仍然保留，方便老用户直接粘英文 tag 串。
        val typed = promptInput.text.toString().trim().trimEnd(',', '，').trim()
        val tagPart = selectedTags.joinToString(", ") { it.tag }
        val prompt = listOf(tagPart, typed).filter { it.isNotEmpty() }.joinToString(", ")
        if (prompt.isEmpty()) { statusView.text = "请点选标签或填写提示词"; return }
        val baseUrl = scene.optString("baseUrl")
        if (baseUrl.isBlank()) {
            statusView.text = "还没配置生图运营商，点右上「运营商」新建"
            return
        }

        val steps = stepsInput.text?.toString()?.toIntOrNull() ?: 28
        val cfg = cfgInput.text?.toString()?.toDoubleOrNull() ?: 6.0
        // 采样参数本来挂在风格上，本页改了也写回去，下次进来保持一致
        if (styleUid.isNotBlank()) {
            EngineBridge.command(
                JSONObject().put("cmd", "imgSaveSampling").put("uid", styleUid).put(
                    "sampling",
                    JSONObject().put("steps", steps).put("cfgScale", cfg).put("sampler", curSampler),
                ),
            )
        }

        val fullPrompt = listOf(styleArtist, styleQuality, prompt)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        val params = ImageParams(
            engine = curEngine,
            url = baseUrl,
            apiKey = scene.optString("apiKey"),
            model = scene.optString("model"),
            prompt = fullPrompt,
            negativePrompt = styleNegative,
            width = curWidth,
            height = curHeight,
            cfgScale = cfg,
            steps = steps,
            sampler = curSampler,
            noiseSchedule = "karras",
            qualityToggle = true,
        )
        val urls = ImageEngineSupport.candidateUrls(curEngine, baseUrl)
        val req = ImageGenService.Request(
            url = urls.firstOrNull() ?: baseUrl,
            apiKey = scene.optString("apiKey"),
            model = scene.optString("model"),
            prompt = fullPrompt,
            params = params,
            endpointCandidates = urls.drop(1),
        )

        statusView.text = "生成中…（${curEngine.label} · ${curWidth}×${curHeight}，生图较慢）"
        runBtn.isEnabled = false

        val svc = ImageGenService(applicationContext)
        service = svc
        svc.generate(req, object : ImageGenService.Callback {
            override fun onSuccess(images: List<ImageStore.StoredImage>) {
                runBtn.isEnabled = true
                statusView.text = "完成，共 ${images.size} 张（已保存到本地）"
                showResults(images)
                svc.shutdown()
                if (service === svc) service = null
            }

            override fun onError(message: String) {
                runBtn.isEnabled = true
                statusView.text = "失败：$message"
                svc.shutdown()
                if (service === svc) service = null
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 从「运营商」页返回时可能新增了模型，刷新选择器
        runCatching { loadSelectors() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    // ------------------------------------------------------------ 分类与选择器

    /** 一级分类 chip 行（全部 + 各类），切换后重建二级行与标签。 */
    private fun buildGroupTabs() {
        val box = findViewById<LinearLayout>(R.id.gen_group_tabs)
        val tabBox = findViewById<LinearLayout>(R.id.gen_tags)
        val status = findViewById<TextView>(R.id.gen_tag_status)
        val search = findViewById<EditText>(R.id.gen_tag_search)
        box.removeAllViews()
        val groups = listOf("") + TagLibrary.groups()
        for (g in groups) {
            val active = g == tagGroup
            box.addView(TextView(this).apply {
                text = if (g.isEmpty()) "全部" else g
                textSize = 12f
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF5A6B85.toInt())
                background = getDrawable(if (active) R.drawable.bg_chip_active else R.drawable.bg_btn_soft)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(6)
                setOnClickListener {
                    if (tagGroup == g) return@setOnClickListener
                    tagGroup = g
                    tagSub = ""
                    tagLimit = 30
                    buildGroupTabs()
                    renderTags(tabBox, status, search.text?.toString() ?: "")
                }
            })
        }
        buildSubTabs()
    }

    /** 二级分类 chip 行（该一级分类下实际存在的子类）。 */
    private fun buildSubTabs() {
        val box = findViewById<LinearLayout>(R.id.gen_sub_tabs)
        box.removeAllViews()
        val subs = TagLibrary.subsOf(tagGroup)
        if (subs.isEmpty()) return
        val tabBox = findViewById<LinearLayout>(R.id.gen_tags)
        val status = findViewById<TextView>(R.id.gen_tag_status)
        val search = findViewById<EditText>(R.id.gen_tag_search)
        for (s0 in listOf("") + subs) {
            val active = s0 == tagSub
            val label = if (s0.isEmpty()) "全部" else TagCategories.subLabel(s0)
            box.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF8A94A6.toInt())
                background = getDrawable(if (active) R.drawable.bg_chip_active else R.drawable.bg_btn_soft)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(6)
                setOnClickListener {
                    if (tagSub == s0) return@setOnClickListener
                    tagSub = s0
                    tagLimit = 30
                    buildSubTabs()
                    renderTags(tabBox, status, search.text?.toString() ?: "")
                }
            })
        }
    }

    /** 读本场景配置 + 风格 + 采样参数，填充所有选择器。全部走本地 ConnectionStore。 */
    private fun loadSelectors() {
        EngineBridge.fetch(this, "getImageConfig") { obj ->
            if (obj == null) return@fetch
            val arr = obj.optJSONArray("providers") ?: JSONArray()
            providerList = (0 until arr.length()).map { arr.getJSONObject(it) }
            scene = obj.optJSONObject("scenes")?.optJSONObject("tag")
                ?: obj.optJSONObject("current") ?: JSONObject()
            curWidth = scene.optInt("width", 832)
            curHeight = scene.optInt("height", 1216)
            curEngine = ImageEngine.of(scene.optString("engine"))

            val model = scene.optString("model").ifBlank { "未选模型" }
            // 只显示「运营商 · 模型」，绝不把 baseUrl / apiKey 摊在界面上
            apiSelect.text = "${scene.optString("apiName").ifBlank { "未选运营商" }} · $model"
            apiSelect.setOnClickListener { pickModelQuick() }
            engineSelect.text = "渲染器：${curEngine.label}"
            engineSelect.setOnClickListener { pickEngine() }
            renderSizeValue()
            buildSizeChips()
        }
        EngineBridge.fetch(this, "getImageStyle") { obj ->
            if (obj == null) return@fetch
            val arr = obj.optJSONArray("styles") ?: JSONArray()
            styleList = (0 until arr.length()).map { arr.getJSONObject(it) }
            styleUid = obj.optString("styleUid")
            styleSelect.text = "风格：" + obj.optString("styleName").ifBlank { "默认" }
            styleArtist = obj.optString("artistTags")
            styleQuality = obj.optString("qualityTags")
            styleNegative = obj.optString("negativePrompt")
            stepsInput.setText(obj.optInt("steps", 28).toString())
            cfgInput.setText(obj.optDouble("cfgScale", 6.0).toString())
            curSampler = obj.optString("sampler").ifBlank { "k_euler_ancestral" }
            samplerSelect.text = "采样器：$curSampler"
            styleSelect.setOnClickListener { pickStyle() }
            samplerSelect.setOnClickListener { pickSampler() }
        }
    }

    /**
     * 一步切模型：已经选了「砂糖」这类运营商，最常用的动作是换它下面的模型，
     * 不该每次都先再选一遍运营商。列表末尾保留「换一家运营商」。
     */
    private fun pickModelQuick() {
        if (providerList.isEmpty()) {
            toast("还没有生图运营商，点右上「运营商」新建")
            return
        }
        val curUid = scene.optString("uid")
        val prov = providerList.firstOrNull { p ->
            val models = p.optJSONArray("models") ?: JSONArray()
            (0 until models.length()).any { models.getJSONObject(it).optString("uid") == curUid }
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

    /** 两级选择：先选运营商，再选它下面的模型。 */
    private fun pickProviderThenModel() {
        if (providerList.isEmpty()) {
            toast("还没有生图运营商，点右上「运营商」新建")
            return
        }
        val names = providerList.map { it.optString("name").ifBlank { "(未命名)" } }
        AlertDialog.Builder(this)
            .setTitle("选择生图运营商")
            .setItems(names.toTypedArray()) { _, i ->
                val models = providerList[i].optJSONArray("models") ?: JSONArray()
                if (models.length() == 0) {
                    toast("这家还没添加模型，去「运营商」里拉取")
                    return@setItems
                }
                if (models.length() == 1) {
                    useApi(models.getJSONObject(0).optString("uid"))
                    return@setItems
                }
                val labels = (0 until models.length()).map { j ->
                    models.getJSONObject(j).optString("model").ifBlank { models.getJSONObject(j).optString("name") }
                }
                AlertDialog.Builder(this)
                    .setTitle("选择模型")
                    .setItems(labels.toTypedArray()) { _, j -> useApi(models.getJSONObject(j).optString("uid")) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** scope=tag：只改标签生图这一套，不动对话生图与网页自动配图。 */
    private fun useApi(uid: String) {
        EngineBridge.command(
            JSONObject().put("cmd", "imgUseApi").put("uid", uid).put("scope", "tag"),
        )
        loadSelectors()
    }

    /** 渲染器选择：NovelAI 官方/中转、本地部署、通用 OpenAI、DALL·E。 */
    private fun pickEngine() {
        val engines = ImageEngine.entries
        val labels = engines.map { "${it.label} — ${it.hint}" }.toTypedArray()
        val checked = engines.indexOf(curEngine)
        AlertDialog.Builder(this)
            .setTitle("选择渲染器")
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                EngineBridge.command(
                    JSONObject().put("cmd", "imgSetEngine")
                        .put("uid", scene.optString("uid"))
                        .put("engine", engines[which].id),
                )
                dlg.dismiss()
                loadSelectors()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickStyle() {
        if (styleList.isEmpty()) { toast("还没有风格，去设置里建一套"); return }
        val names = styleList.map { it.optString("name") }
        AlertDialog.Builder(this)
            .setTitle("选择出图风格")
            .setItems(names.toTypedArray()) { _, which ->
                EngineBridge.command(
                    JSONObject().put("cmd", "imgUseStyle").put("uid", styleList[which].optString("uid")),
                )
                loadSelectors()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickSampler() {
        val list = ImageEngineSupport.NAI_SAMPLERS
        AlertDialog.Builder(this)
            .setTitle("采样器（${curEngine.label}）")
            .setItems(list.toTypedArray()) { _, i ->
                curSampler = list[i]
                samplerSelect.text = "采样器：$curSampler"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 小图快捷档位：点一下即写入配置，风格页与对话生图设置页同步。 */
    private fun buildSizeChips() {
        SizePresetChips.render(this, sizeChips, curWidth, curHeight) { w, h ->
            curWidth = w
            curHeight = h
            EngineBridge.command(
                JSONObject().put("cmd", "imgSaveSize").put("width", w).put("height", h),
            )
            renderSizeValue()
            buildSizeChips()
        }
    }

    private fun renderSizeValue() {
        sizeValue.text = "$curWidth × $curHeight"
    }

    /** 选择器/风格变更后刷新（放在 onResume 与动作完成后调用）。 */
    private fun refreshSelectors() = loadSelectors()

    private fun showResults(images: List<ImageStore.StoredImage>) {
        results.removeAllViews()
        val width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        for (img in images) {
            val iv = ImageView(this)
            iv.layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.adjustViewBounds = true
            ImageLoader.load(img.path, width)?.let { iv.setImageBitmap(it) }
            results.addView(iv)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service?.shutdown()
        service = null
    }
}
