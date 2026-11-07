package com.dbpg.duihuazhushou.session

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.NativeChatActivity
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ChatBackground
import com.dbpg.duihuazhushou.data.MigrationActivity
import com.dbpg.stcore.session.SessionEditor

/**
 * 「冒险」tab 的落地内容（会话列表）。
 *
 * 为什么单独抽出来，而不是直接写在 MainActivity 里：
 *   MainActivity 是 Java（历史的 WebView 宿主），而列表绑定要碰
 *   SessionRepo / SessionAdapter 这些 Kotlin 类型。Java 调 Kotlin 的
 *   函数类型很别扭，与其在 Java 里硬拼，不如把这坨逻辑收成一个 Kotlin
 *   帮助类，Java 侧只留一行调用。
 *
 * 返回 [Refresher]：MainActivity 在 onResume 里调一次即可保持数据新鲜
 * （从聊天页返回、从迁移页返回都会走到）。
 */
object HomeBinder {

    fun interface Refresher {
        fun refresh()
    }

    fun bind(activity: Activity, container: ViewGroup): Refresher {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.activity_home, container, false)
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        SessionRepo.init(activity)

        val list = view.findViewById<RecyclerView>(R.id.home_session_list)
        val empty = view.findViewById<TextView>(R.id.home_empty)
        // reload 在下面才赋值；长按回调通过闭包晚绑定，所以先声明再赋。
        lateinit var reload: Refresher
        val adapter = SessionAdapter(
            onClick = { item -> openChat(activity, item.id) },
            onLongClick = { item -> showSessionMenu(activity, item) { reload.refresh() } },
        )
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter

        reload = Refresher {
            SessionRepo.list { entries ->
                if (activity.isFinishing || activity.isDestroyed) return@list
                val items = entries.map {
                    SessionSummary(
                        id = it.id,
                        title = it.title,
                        messageCount = it.count,
                        updatedAt = it.updatedAt,
                        current = false,
                    )
                }
                adapter.submit(items)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        view.findViewById<TextView>(R.id.home_migrate).setOnClickListener {
            activity.startActivity(Intent(activity, MigrationActivity::class.java))
        }

        view.findViewById<TextView>(R.id.home_new).setOnClickListener {
            val doc = SessionRepo.create("新冒险")
            // 先落盘再进聊天：聊天页是按 id 读文件的，读不到会另建一份，
            // 那样刚新建的这条就"消失"了。
            SessionRepo.save(doc) { ok ->
                if (!ok) return@save
                activity.runOnUiThread { openChat(activity, doc.id) }
            }
        }

        reload.refresh()
        return reload
    }

    /** 长按会话：重命名 / 删除。首页这套列表用得最多，删除入口必须在这里。 */
    private fun showSessionMenu(activity: Activity, item: SessionSummary, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(item.displayTitle)
            .setItems(arrayOf("重命名", "删除会话")) { _, which ->
                when (which) {
                    0 -> promptRename(activity, item, onDone)
                    1 -> confirmDelete(activity, item, onDone)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除会话：文件 + 索引一起走 [SessionRepo.delete]，顺带清掉该会话的
     * 专属背景图，避免留下一张永远没人引用的孤儿图。
     */
    private fun confirmDelete(activity: Activity, item: SessionSummary, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("删除会话")
            .setMessage("「${item.displayTitle}」及其全部消息会被删除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                ChatBackground.clear(activity, item.id)
                SessionRepo.delete(item.id) { ok ->
                    if (ok) onDone()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按会话 → 改名字。
     *
     * 先 load 再 save，而不是直接改索引：索引是派生数据（由会话文件重建），
     * 只改索引会在下次重建时被打回原形。
     */
    private fun promptRename(activity: Activity, item: SessionSummary, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setText(item.title)
            setHint("会话名")
            setTextSize(15f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(36, 26, 36, 26)
            setSelection(text?.length ?: 0)
        }
        val wrap = LinearLayout(activity).apply {
            setPadding(48, 12, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("重命名会话")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text?.toString().orEmpty()
                SessionRepo.load(item.id) { doc ->
                    if (doc == null) return@load
                    SessionRepo.save(SessionEditor.rename(doc, name)) { ok ->
                        if (ok) onDone()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openChat(activity: Activity, sessionId: String) {
        activity.startActivity(
            Intent(activity, NativeChatActivity::class.java)
                .putExtra(NativeChatActivity.EXTRA_SESSION_ID, sessionId),
        )
    }
}
