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
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.GroupChatActivity
import com.dbpg.duihuazhushou.NativeChatActivity
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ChatBackground
import com.dbpg.duihuazhushou.chat.GenBridge
import com.dbpg.duihuazhushou.data.MigrationActivity
import com.dbpg.stcore.session.SessionEditor
import org.json.JSONObject

/**
 * 「AI」tab 的落地内容：冒险（单人会话）/ 群聊两种模式。
 *
 * 为什么单独抽出来，而不是直接写在 MainActivity 里：
 *   MainActivity 是 Java（历史的 WebView 宿主），而列表绑定要碰
 *   SessionRepo / SessionAdapter 这些 Kotlin 类型。Java 调 Kotlin 的
 *   函数类型很别扭，与其在 Java 里硬拼，不如把这坨逻辑收成一个 Kotlin
 *   帮助类，Java 侧只留一行调用。
 *
 * 模式条放在标题下：冒险仍是默认第一项，群聊并列。以后加跑团 / 剧本
 * 也只是往这里多一个模式，不需要再开一个"导航页"。
 *
 * 返回 [Refresher]：MainActivity 在 onResume 里调一次即可保持数据新鲜。
 */
object HomeBinder {

    private const val MODE_ADVENTURE = 0
    private const val MODE_GROUP = 1

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
        val modeAdventure = view.findViewById<TextView>(R.id.home_mode_adventure)
        val modeGroup = view.findViewById<TextView>(R.id.home_mode_group)

        var mode = MODE_ADVENTURE
        // reload 在下面才赋值；长按回调通过闭包晚绑定，所以先声明再赋。
        lateinit var reload: Refresher

        val adapter = SessionAdapter(
            onClick = { item ->
                if (mode == MODE_GROUP) openGroup(activity, item.id) else openChat(activity, item.id)
            },
            onLongClick = { item ->
                if (mode == MODE_GROUP) showGroupMenu(activity, item) { reload.refresh() }
                else showSessionMenu(activity, item) { reload.refresh() }
            },
        )
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter

        fun paintModes() {
            val on = activity.getDrawable(R.drawable.bg_pill_accent)
            val off = activity.getDrawable(R.drawable.bg_pill_soft)
            modeAdventure.background = if (mode == MODE_ADVENTURE) on else off
            modeAdventure.setTextColor(
                if (mode == MODE_ADVENTURE) 0xFF2F66D8.toInt() else 0xFF5A6B85.toInt(),
            )
            modeAdventure.setTypeface(null, if (mode == MODE_ADVENTURE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            modeGroup.background = if (mode == MODE_GROUP) on else off
            modeGroup.setTextColor(
                if (mode == MODE_GROUP) 0xFF2F66D8.toInt() else 0xFF5A6B85.toInt(),
            )
            modeGroup.setTypeface(null, if (mode == MODE_GROUP) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        reload = Refresher {
            if (mode == MODE_GROUP) {
                val groups = GroupStore.list(activity)
                val items = groups.map {
                    SessionSummary(
                        id = it.id,
                        title = it.title,
                        messageCount = it.members.size,
                        updatedAt = it.updatedAt,
                        current = false,
                        detail = "${it.members.size} 位成员 · ${it.publicWorldBookIds.size} 本公共书",
                    )
                }
                adapter.submit(items)
                empty.text = "还没有群聊\n\n点右上角「新建」，选几张角色卡就是一个群。\n公共区所有人可见，每个角色还有一个私聊频道"
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } else {
                SessionRepo.list { entries ->
                    if (activity.isFinishing || activity.isDestroyed) return@list
                    // 群聊频道不进冒险列表：它们是群的一部分，不该单独立在首页
                    val items = entries.filter { it.groupId.isEmpty() }.map {
                        SessionSummary(
                            id = it.id,
                            title = it.title,
                            messageCount = it.count,
                            updatedAt = it.updatedAt,
                            current = false,
                        )
                    }
                    adapter.submit(items)
                    empty.text = "还没有会话\n\n点右上角「新建」开一段新的，\n或「导入」把网页里已有的会话搬过来"
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        view.findViewById<TextView>(R.id.home_migrate).apply {
            setOnClickListener { activity.startActivity(Intent(activity, MigrationActivity::class.java)) }
            // 导入只对冒险有意义；群聊模式下藏起来，避免点了没反应
            visibility = View.VISIBLE
        }

        view.findViewById<TextView>(R.id.home_new).setOnClickListener {
            if (mode == MODE_GROUP) {
                createGroup(activity) { reload.refresh() }
            } else {
                val doc = SessionRepo.create("新冒险")
                // 先落盘再进聊天：聊天页是按 id 读文件的，读不到会另建一份，
                // 那样刚新建的这条就"消失"了。
                SessionRepo.save(doc) { ok ->
                    if (!ok) return@save
                    activity.runOnUiThread { openChat(activity, doc.id) }
                }
            }
        }

        // 模式切换时同步「导入」的可见性
        fun syncChrome() {
            view.findViewById<TextView>(R.id.home_migrate).visibility =
                if (mode == MODE_GROUP) View.GONE else View.VISIBLE
        }
        modeAdventure.setOnClickListener {
            if (mode == MODE_ADVENTURE) return@setOnClickListener
            mode = MODE_ADVENTURE
            paintModes()
            syncChrome()
            reload.refresh()
        }
        modeGroup.setOnClickListener {
            if (mode == MODE_GROUP) return@setOnClickListener
            mode = MODE_GROUP
            paintModes()
            syncChrome()
            reload.refresh()
        }

        paintModes()
        syncChrome()
        reload.refresh()
        return reload
    }

    // ------------------------------------------------------------ 群聊

    /**
     * 新建群：先选角色卡，再起名，最后一次性建好 N+1 个会话。
     *
     * 角色列表来自引擎（资产页同一份数据），不在这里复制一份 ——
     * 资产页导入的新卡立刻可选。
     */
    private fun createGroup(activity: Activity, onDone: () -> Unit) {
        GenBridge.fetchAssetCatalog { json ->
            if (activity.isFinishing) return@fetchAssetCatalog
            val chars = runCatching {
                JSONObject(json ?: "{}").optJSONArray("characters")
            }.getOrNull()
            if (chars == null || chars.length() == 0) {
                toast(activity, "角色库是空的，先去资产页导入角色卡")
                return@fetchAssetCatalog
            }
            val ids = ArrayList<String>()
            val names = ArrayList<String>()
            for (i in 0 until chars.length()) {
                val o = chars.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                ids.add(id)
                names.add(o.optString("name", "未命名"))
            }
            val checked = BooleanArray(ids.size)
            AlertDialog.Builder(activity)
                .setTitle("选成员（可多选）")
                .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("下一步") { _, _ ->
                    val picked = ids.indices.filter { checked[it] }.map { ids[it] to names[it] }
                    if (picked.isEmpty()) {
                        toast(activity, "至少选一个角色")
                        return@setPositiveButton
                    }
                    promptGroupName(activity, picked, onDone)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun promptGroupName(
        activity: Activity,
        members: List<Pair<String, String>>,
        onDone: () -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(members.firstOrNull()?.second.orEmpty() + "的群")
            setSelection(text.length)
            textSize = 15f
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(36, 26, 36, 26)
        }
        val wrap = LinearLayout(activity).apply {
            setPadding(48, 12, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("群名")
            .setView(wrap)
            .setPositiveButton("创建") { _, _ ->
                val title = input.text?.toString().orEmpty().trim().ifBlank { "新群聊" }
                toast(activity, "正在建频道…")
                Thread {
                    val g = GroupStore.create(activity.applicationContext, title, members)
                    activity.runOnUiThread {
                        openGroup(activity, g.id)
                        onDone()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGroupMenu(activity: Activity, item: SessionSummary, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(item.displayTitle)
            .setItems(arrayOf("删除群聊")) { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle("删除群聊")
                    .setMessage("「${item.displayTitle}」和它的全部频道消息都会被删除，此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        GroupStore.delete(activity, item.id)
                        onDone()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openGroup(activity: Activity, groupId: String) {
        activity.startActivity(
            Intent(activity, GroupChatActivity::class.java)
                .putExtra(GroupChatActivity.EXTRA_GROUP_ID, groupId),
        )
    }

    // ------------------------------------------------------------ 单人会话

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

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
