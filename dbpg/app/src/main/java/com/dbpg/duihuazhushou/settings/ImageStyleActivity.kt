package com.dbpg.duihuazhushou.settings

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONObject

/**
 * 生图风格页：画师串 / 质量串 / 负面 / LLM 转标签模板 + 尺寸。
 * 编辑的是当前风格（image store 的 styleConfigs[currentStyleId]），
 * 经 window.__dhpApi 读写（数据与引擎同源）。
 */
class ImageStyleActivity : Activity() {

    private lateinit var artist: EditText
    private lateinit var quality: EditText
    private lateinit var negative: EditText
    private lateinit var template: EditText
    private lateinit var width: EditText
    private lateinit var height: EditText

    private val apiListener = NativeBridge.ApiResultListener { json ->
        try {
            val obj = JSONObject(json)
            if (obj.optString("kind") == "imgSaveParams") {
                toast(if (obj.optBoolean("ok")) "已保存" else "保存失败：${obj.optString("error")}")
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_style)
        Insets.applyTop(this)

        artist = findViewById(R.id.style_artist)
        quality = findViewById(R.id.style_quality)
        negative = findViewById(R.id.style_negative)
        template = findViewById(R.id.style_template)
        width = findViewById(R.id.style_width)
        height = findViewById(R.id.style_height)

        findViewById<TextView>(R.id.style_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.style_reload).setOnClickListener { load() }
        findViewById<TextView>(R.id.style_save).setOnClickListener { save() }
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
        EngineBridge.fetch(this, "getImageStyle") { obj ->
            if (obj == null) {
                toast("引擎还没就绪，稍后点「刷新」")
                return@fetch
            }
            findViewById<TextView>(R.id.style_title).text =
                "生图风格 · ${obj.optString("styleName").ifBlank { "默认" }}"
            artist.setText(obj.optString("artistTags"))
            quality.setText(obj.optString("qualityTags"))
            negative.setText(obj.optString("negativePrompt"))
            template.setText(obj.optString("promptTemplate"))
            width.setText(obj.optInt("width", 832).toString())
            height.setText(obj.optInt("height", 1216).toString())
        }
    }

    private fun save() {
        val params = JSONObject()
            .put("artistTags", artist.text?.toString() ?: "")
            .put("qualityTags", quality.text?.toString() ?: "")
            .put("negativePrompt", negative.text?.toString() ?: "")
            .put("promptTemplate", template.text?.toString() ?: "")
            .put("width", width.text?.toString()?.toIntOrNull() ?: 832)
            .put("height", height.text?.toString()?.toIntOrNull() ?: 1216)
        EngineBridge.command(JSONObject().put("cmd", "imgSaveParams").put("params", params))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
