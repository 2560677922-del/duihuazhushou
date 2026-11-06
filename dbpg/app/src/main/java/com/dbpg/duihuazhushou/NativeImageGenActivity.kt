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

        urlInput = findViewById(R.id.gen_url)
        keyInput = findViewById(R.id.gen_key)
        modelInput = findViewById(R.id.gen_model)
        sizeInput = findViewById(R.id.gen_size)
        promptInput = findViewById(R.id.gen_prompt)
        runBtn = findViewById(R.id.gen_run)
        statusView = findViewById(R.id.gen_status)
        results = findViewById(R.id.gen_results)

        findViewById<TextView>(R.id.gen_close).setOnClickListener { finish() }
        runBtn.setOnClickListener { run() }

        // 若网页桥曾把上次的配置放到 Intent 里（可选），这里回填
        intent.getStringExtra("url")?.let { urlInput.setText(it) }
        intent.getStringExtra("model")?.let { modelInput.setText(it) }
        sizeInput.setText(intent.getStringExtra("size") ?: "1024x1024")
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
