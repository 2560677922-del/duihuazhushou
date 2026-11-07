package com.dbpg.duihuazhushou.settings

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONObject

/**
 * 显示设置：思维链是否隐藏 + 发给后端的文本带不带正则（llmContextRaw）+
 * 导出用原文。数据在 web storageSettings store，经 __dhpApi 读写。
 */
class DisplayActivity : Activity() {

    private lateinit var hide: Switch
    private lateinit var llmRaw: Switch
    private lateinit var exportRaw: Switch
    private lateinit var marker: EditText

    private val apiListener = NativeBridge.ApiResultListener { json ->
        try {
            val obj = JSONObject(json)
            if (obj.optString("kind") == "saveDisplay") {
                toast(if (obj.optBoolean("ok")) "已保存" else "保存失败：${obj.optString("error")}")
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display)
        Insets.applyTop(this)

        hide = findViewById(R.id.disp_hide)
        llmRaw = findViewById(R.id.disp_llm_raw)
        exportRaw = findViewById(R.id.disp_export_raw)
        marker = findViewById(R.id.disp_marker)

        findViewById<TextView>(R.id.disp_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.disp_save).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(apiListener)
        EngineBridge.fetch(this, "getDisplaySettings") { obj ->
            if (obj == null) return@fetch
            hide.isChecked = obj.optBoolean("hideReasoning")
            llmRaw.isChecked = obj.optBoolean("llmContextRaw")
            exportRaw.isChecked = obj.optBoolean("exportRaw")
            marker.setText(obj.optString("reasoningCutMarker"))
        }
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    private fun save() {
        EngineBridge.command(
            JSONObject()
                .put("cmd", "saveDisplay")
                .put(
                    "settings",
                    JSONObject()
                        .put("hideReasoning", hide.isChecked)
                        .put("llmContextRaw", llmRaw.isChecked)
                        .put("exportRaw", exportRaw.isChecked)
                        .put("reasoningCutMarker", marker.text?.toString() ?: ""),
                ),
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
