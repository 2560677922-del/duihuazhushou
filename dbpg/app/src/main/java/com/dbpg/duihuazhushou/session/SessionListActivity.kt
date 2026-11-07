package com.dbpg.duihuazhushou.session

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.NativeChatActivity
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.data.MigrationActivity

/**
 * 原生会话列表（底部「聊天」tab 的落地页）。
 *
 * 数据来源已经换成原生文件（[SessionRepo] → `files/data/sessions`），
 * 不再问网页要摘要 —— 会话是原生的，列表当然也该读原生。
 *
 * 没数据时提供一个「导入」，走一次性迁移通道把网页里的旧会话搬过来；
 * 导入完成后这里立刻就能看到。
 */
class SessionListActivity : Activity() {

    private lateinit var adapter: SessionAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)
        Insets.applyTop(this)
        SessionRepo.init(this)

        emptyView = findViewById(R.id.session_empty)
        adapter = SessionAdapter { item ->
            startActivity(
                Intent(this, NativeChatActivity::class.java)
                    .putExtra(NativeChatActivity.EXTRA_SESSION_ID, item.id),
            )
        }

        findViewById<TextView>(R.id.session_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.session_refresh).setOnClickListener { reload() }
        findViewById<TextView>(R.id.session_import).setOnClickListener {
            startActivity(Intent(this, MigrationActivity::class.java))
        }

        findViewById<RecyclerView>(R.id.session_list).apply {
            layoutManager = LinearLayoutManager(this@SessionListActivity)
            adapter = this@SessionListActivity.adapter
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        // 从聊天页返回会新增消息，刷新一下条数/时间
        reload()
    }

    private fun reload() {
        SessionRepo.list { entries ->
            if (isFinishing) return@list
            val list = entries.map {
                SessionSummary(
                    id = it.id,
                    title = it.title,
                    messageCount = it.count,
                    updatedAt = it.updatedAt,
                    current = false,
                )
            }
            adapter.submit(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
