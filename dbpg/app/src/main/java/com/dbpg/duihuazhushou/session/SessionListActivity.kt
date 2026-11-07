package com.dbpg.duihuazhushou.session

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.NativeChatActivity
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.data.MigrationActivity
import com.dbpg.stcore.session.SessionEditor

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
        // 命名参数：SessionAdapter 现在有两个回调，尾随 lambda 会绑到最后一个（长按）
        adapter = SessionAdapter(
            onClick = { item ->
                startActivity(
                    Intent(this, NativeChatActivity::class.java)
                        .putExtra(NativeChatActivity.EXTRA_SESSION_ID, item.id),
                )
            },
            onLongClick = { item -> showItemMenu(item) },
        )

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

    // ------------------------------------------------------------ 长按操作

    /**
     * 列表长按：重命名 / 删除。
     * 删除走 [SessionRepo.delete]（同时删 `<id>.json` 并重建索引），
     * 不碰 web 快照，也不留「索引里还挂着、点进去是空的」的残影。
     */
    private fun showItemMenu(item: SessionSummary) {
        AlertDialog.Builder(this)
            .setTitle(item.displayTitle)
            .setItems(arrayOf("重命名", "删除会话")) { _, which ->
                when (which) {
                    0 -> promptRename(item)
                    1 -> confirmDelete(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptRename(item: SessionSummary) {
        val input = EditText(this).apply {
            setText(item.displayTitle)
            setSelection(text.length)
            textSize = 15f
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val title = input.text?.toString().orEmpty()
                SessionRepo.load(item.id) { doc ->
                    if (doc == null) return@load
                    SessionRepo.save(SessionEditor.rename(doc, title)) { reload() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(item: SessionSummary) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("「${item.displayTitle}」及其全部消息会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                SessionRepo.delete(item.id) { ok ->
                    if (ok) reload()
                    else android.widget.Toast.makeText(this, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
