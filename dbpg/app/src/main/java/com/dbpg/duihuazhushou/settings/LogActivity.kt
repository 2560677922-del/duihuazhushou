package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.AppLog
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.R

/**
 * 运行日志页：看、复制、清空。
 *
 * 只做展示，不产生日志 —— 日志来源写在 [AppLog] 的注释里。这里刻意不引入
 * 过滤/搜索/导出文件：用户真正要的动作只有一个「整段复制发给开发者」，
 * 加筛选反而让这一段更难拿全。
 */
class LogActivity : Activity() {

    private lateinit var text: TextView
    private lateinit var meta: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        Insets.applyTop(this)

        text = findViewById(R.id.log_text)
        meta = findViewById(R.id.log_meta)
        scroll = findViewById(R.id.log_scroll)

        findViewById<View>(R.id.log_back).setOnClickListener { finish() }
        findViewById<View>(R.id.log_copy).setOnClickListener { copyAll() }
        findViewById<View>(R.id.log_clear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val body = AppLog.dump()
        val lines = body.lineSequence().count { it.isNotBlank() }
        meta.text = AppLog.header(this) + " · 共 $lines 行"
        text.text = body
        // 最新一条在最下面，进来直接看尾部。
        // 不用 fullScroll：textIsSelectable 会抢焦点，fullScroll 可能被拉回顶部。
        // 这里自己算最大偏移并 clamp，不依赖 View.scrollTo 的越界行为。
        scroll.post {
            val max = (text.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, max)
        }
    }

    private fun copyAll() {
        // 头部（含版本 + 机型）单起一行：粘贴到聊天里第一眼就能看出装的是哪版
        val body = AppLog.header(this) + "\n" + AppLog.dump()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("运行日志", body))
        Toast.makeText(this, "已复制 ${body.length} 字，粘给开发者就行", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空日志？")
            .setMessage("会删掉全部历史记录。下次出错前的内容就找不回来了。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                AppLog.clear()
                reload()
            }
            .show()
    }
}
