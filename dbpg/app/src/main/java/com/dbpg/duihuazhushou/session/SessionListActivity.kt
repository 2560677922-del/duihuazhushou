package com.dbpg.duihuazhushou.session

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

/**
 * 原生会话列表。
 *
 * 定位：底部「聊天」tab 的落地页 —— 先看有哪些会话，点一个直接进原生聊天。
 * 会话的增删改仍归网页，这里只做"看得见、点得进去"。
 */
class SessionListActivity : Activity() {

    private lateinit var bridge: SessionBridge
    private lateinit var adapter: SessionAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)
        bridge = SessionBridge()

        emptyView = findViewById(R.id.session_empty)
        adapter = SessionAdapter { item ->
            bridge.open(item.id)
            // 原生聊天由网页拉起；本页退出，避免用户按返回时回到一个过期列表
            finish()
        }

        findViewById<TextView>(R.id.session_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.session_refresh).setOnClickListener { reload() }

        findViewById<RecyclerView>(R.id.session_list).apply {
            layoutManager = LinearLayoutManager(this@SessionListActivity)
            adapter = this@SessionListActivity.adapter
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        // 从聊天页返回会新增消息，刷新一下消息数/时间
        reload()
    }

    private fun reload() {
        bridge.load { list ->
            if (isFinishing) return@load
            adapter.submit(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
