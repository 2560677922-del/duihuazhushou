package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.dbpg.duihuazhushou.R

/**
 * 设置首页：只做入口列表，不承载业务。
 *
 * 为什么不再是原来那个只有外观的 SettingsActivity：
 *   用户要求设置页和网页版一致 —— 接口配置（地址/密钥/拉模型）放第一位，
 *   外观、引擎、图片库、数据迁移依次排列。原外观页保留为子页。
 */
class SettingsHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_home)

        findViewById<TextView>(R.id.shome_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.shome_api).setOnClickListener {
            startActivity(Intent(this, ApiConfigActivity::class.java))
        }
        findViewById<TextView>(R.id.shome_appearance).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.appearance.SettingsActivity::class.java),
            )
        }
        findViewById<TextView>(R.id.shome_engine).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.engine.EngineSettingsActivity::class.java),
            )
        }
        findViewById<TextView>(R.id.shome_gallery).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.gallery.GalleryActivity::class.java),
            )
        }
        findViewById<TextView>(R.id.shome_migrate).setOnClickListener {
            startActivity(
                Intent(this, com.dbpg.duihuazhushou.data.MigrationActivity::class.java),
            )
        }
    }
}
