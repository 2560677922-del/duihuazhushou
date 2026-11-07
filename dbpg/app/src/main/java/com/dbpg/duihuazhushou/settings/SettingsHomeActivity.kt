package com.dbpg.duihuazhushou.settings

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.dbpg.duihuazhushou.R

/**
 * 设置首页：LLM 运营商 / 生图运营商 / 生图风格 / 显示 / UI 风格 / 图片库 / 迁移。
 * 资产类（预设/世界书/角色卡/正则）在底部「资产」页，这里只留提示。
 */
class SettingsHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_home)
        Insets.applyTop(this)

        findViewById<View>(R.id.shome_back).setOnClickListener { finish() }

        findViewById<View>(R.id.shome_api).setOnClickListener {
            startActivity(Intent(this, ApiConfigActivity::class.java))
        }
        findViewById<View>(R.id.shome_imgapi).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.imagegen.ImageGenConfigActivity::class.java),
            )
        }
        findViewById<View>(R.id.shome_style).setOnClickListener {
            startActivity(Intent(this, ImageStyleActivity::class.java))
        }
        findViewById<View>(R.id.shome_chatimg).setOnClickListener {
            startActivity(Intent(this, ChatImageConfigActivity::class.java))
        }
        findViewById<View>(R.id.shome_display).setOnClickListener {
            startActivity(Intent(this, DisplayActivity::class.java))
        }
        findViewById<View>(R.id.shome_appearance).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.appearance.SettingsActivity::class.java),
            )
        }
        findViewById<View>(R.id.shome_gallery).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.gallery.GalleryActivity::class.java),
            )
        }
        findViewById<View>(R.id.shome_migrate).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.data.MigrationActivity::class.java),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        EngineBridge.fetch(this, "getConfig") { obj ->
            val v = findViewById<TextView>(R.id.shome_api_status) ?: return@fetch
            if (obj == null) {
                v.text = "当前模型读取中…"
                return@fetch
            }
            val model = obj.optString("model")
            v.text = if (model.isBlank()) "未连接：先填 API 地址和密钥" else "当前模型：$model"
        }
    }
}
