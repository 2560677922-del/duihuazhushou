package com.dbpg.duihuazhushou.data

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.dbpg.duihuazhushou.R

/**
 * 数据迁移页（薄壳）。
 *
 * 这一层只做三件事：点按钮、显示进度、防重复点击。
 * 真正的驱动逻辑在 [MigrationCoordinator]，落盘在 [com.dbpg.stcore.session.SessionStore]
 * 和 [AssetStore] —— 界面换掉（以后合并进设置页）都不用改它们。
 */
class MigrationActivity : Activity() {

    private lateinit var progress: TextView
    private lateinit var startBtn: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_migration)

        progress = findViewById(R.id.mig_progress)
        startBtn = findViewById(R.id.mig_start)

        findViewById<TextView>(R.id.mig_back).setOnClickListener { finish() }
        startBtn.setOnClickListener { start() }

        // 页面被重建（旋转/内存回收）时，如果迁移还在跑，把界面接回去
        if (MigrationCoordinator.isRunning()) {
            running = true
            renderRunning()
        }
    }

    private fun start() {
        if (running) return
        running = true
        renderRunning()
        MigrationCoordinator.start(this) { p ->
            if (isFinishing || isDestroyed) return@start
            progress.text = buildText(p.message, p.done, p.total)
            if (p.finished) {
                running = false
                startBtn.text = if (p.failed) "重试" else "再迁移一次"
                startBtn.isEnabled = true
                startBtn.alpha = 1f
                // 展示原生侧实际落盘的数量，让"迁移成功"可被验证，而不是只看一句提示
                progress.text = buildText(p.message, p.done, p.total) + "\n\n" + nativeCounts()
            }
        }
    }

    private fun renderRunning() {
        startBtn.isEnabled = false
        startBtn.alpha = 0.5f
        startBtn.text = "迁移中…"
        if (progress.text == "尚未开始") progress.text = "正在准备…"
    }

    private fun buildText(message: String, done: Int, total: Int): String {
        if (total <= 0) return message
        val pct = (done * 100 / total).coerceIn(0, 100)
        return "$message\n进度 $done/$total（$pct%）"
    }

    /** 原生存储里现在到底有多少东西（读的是刚写完的目录，不是缓存）。 */
    private fun nativeCounts(): String {
        return try {
            val dir = java.io.File(java.io.File(filesDir, "data"), "sessions")
            val n = com.dbpg.stcore.session.SessionStore(dir).rebuildIndex().size
            val imgs = AssetStore(this).count()
            "原生存储现有：$n 个会话、$imgs 张图片"
        } catch (t: Throwable) {
            "统计失败：${t.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 界面没了就断开监听；迁移本身由 Coordinator 继续跑（写盘不依赖界面）
        MigrationCoordinator.cancel()
    }
}
