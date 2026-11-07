package com.dbpg.duihuazhushou

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.chat.ImageLoader
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.imagegen.ImageStore
import com.dbpg.duihuazhushou.imagegen.TagLibrary

/**
 * 原生「标签生图」试验页（把网页 Tags 页的生图动作搬到原生）。
 *
 * 只做三件事：收参数 → 调 [ImageGenService]（纯 Kotlin：OkHttp + ZIP 解压 + 落盘）→ 用本地路径显示结果。
 * 不含任何 base64；标签选择器可在此基础上前置接入标签库。
 */
class NativeImageGenActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var sizeInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var runBtn: TextView
    private lateinit var statusView: TextView
    private lateinit var results: LinearLayout

    private var service: ImageGenService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_image_gen)
        Insets.applyTop(this)

        urlInput = findViewById(R.id.gen_url)
        keyInput = findViewById(R.id.gen_key)
        modelInput = findViewById(R.id.gen_model)
        sizeInput = findViewById(R.id.gen_size)
        promptInput = findViewById(R.id.gen_prompt)
        runBtn = findViewById(R.id.gen_run)
        statusView = findViewById(R.id.gen_status)
        results = findViewById(R.id.gen_results)

        findViewById<TextView>(R.id.gen_close).setOnClickListener { finish() }
        // 接口配置：地址/密钥/拉模型/多选添加/切换 —— 与 LLM 运营商同一套交互
        findViewById<TextView>(R.id.gen_config).setOnClickListener {
            startActivity(
                android.content.Intent(
                    this,
                    com.dbpg.duihuazhushou.imagegen.ImageGenConfigActivity::class.java,
                ),
            )
        }
        // 生成完想回看历史图：直接进原生图片库，不用绕网页
        findViewById<TextView>(R.id.gen_gallery).setOnClickListener {
            startActivity(android.content.Intent(this, com.dbpg.duihuazhushou.gallery.GalleryActivity::class.java))
        }
        runBtn.setOnClickListener { run() }

        // 若网页桥曾把上次的配置放到 Intent 里（可选），这里回填
        intent.getStringExtra("url")?.let { urlInput.setText(it) }
        intent.getStringExtra("model")?.let { modelInput.setText(it) }
        sizeInput.setText(intent.getStringExtra("size") ?: "1024x1024")

        // 快捷标签：纯原生读 APK 内置标签库，不走桥
        val tagSearch = findViewById<EditText>(R.id.gen_tag_search)
        val tagBox = findViewById<LinearLayout>(R.id.gen_tags)
        val tagStatus = findViewById<TextView>(R.id.gen_tag_status)
        TagLibrary.load(this)
        tagStatus.text = if (TagLibrary.size() > 0) "标签库 ${TagLibrary.size()} 条" else "标签库未随包发布（web 构建产物缺失）"
        // 边输入边搜（对齐网页 Tags 页的即时筛选），300ms 防抖
        var tagDebounce: Runnable? = null
        tagSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                tagDebounce?.let { tagSearch.removeCallbacks(it) }
                val r = Runnable { renderTags(tagBox, tagStatus, s?.toString() ?: "") }
                tagDebounce = r
                tagSearch.postDelayed(r, 300)
            }
        })
        // 首次给一批常用标签，不用打字也能点
        renderTags(tagBox, tagStatus, "")

        // 从引擎配置回填（用户在生图接口配置页存的地址/密钥/模型）
        com.dbpg.duihuazhushou.settings.EngineBridge.fetch(this, "getImageConfig") { obj ->
            if (obj == null) return@fetch
            val cur = obj.optJSONObject("current") ?: return@fetch
            if (urlInput.text.isNullOrBlank()) urlInput.setText(cur.optString("baseUrl"))
            if (keyInput.text.isNullOrBlank()) keyInput.setText(cur.optString("apiKey"))
            if (modelInput.text.isNullOrBlank()) modelInput.setText(cur.optString("model"))
        }
    }

    /** 标签搜索结果：每行两个 chip，点一下追加进提示词（纯原生数据）。 */
    private fun renderTags(box: LinearLayout, status: TextView, query: String) {
        box.removeAllViews()
        val hits = TagLibrary.search(query, if (query.isBlank()) 12 else 40)
        status.text = if (hits.isEmpty()) "没有匹配的标签" else "匹配 ${hits.size} 条"
        var row: LinearLayout? = null
        for ((i, t) in hits.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4
                }
                box.addView(row)
            }
            val chip = TextView(this).apply {
                text = "${t.zh}（${t.tag}）"
                textSize = 12f
                setTextColor(0xFF3A4353.toInt())
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                    topMargin = 4
                }
                setOnClickListener {
                    // 追加到提示词末尾（不能把原文本的最后一个字符重复一遍 —— 老实现有这个 bug）
                    val cur = promptInput.text?.toString()?.trimEnd()
                        ?.trimEnd(',', '，')?.trimEnd().orEmpty()
                    val next = if (cur.isEmpty()) "${t.tag}, " else "$cur, ${t.tag}, "
                    promptInput.setText(next)
                    promptInput.setSelection(next.length)
                }
            }
            row?.addView(chip)
        }
    }

    private fun run() {
        val url = urlInput.text.toString().trim()
        val prompt = promptInput.text.toString().trim()
        if (url.isEmpty()) { statusView.text = "请填写中转站地址"; return }
        if (prompt.isEmpty()) { statusView.text = "请填写标签 / 提示词"; return }

        val req = ImageGenService.Request(
            url = url,
            apiKey = keyInput.text.toString().trim(),
            model = modelInput.text.toString().trim().ifEmpty { "flux" },
            prompt = prompt,
            size = sizeInput.text.toString().trim().ifEmpty { null },
            n = 1,
            responseFormat = "b64_json",
        )

        statusView.text = "生成中…（生图较慢，请稍候）"
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
