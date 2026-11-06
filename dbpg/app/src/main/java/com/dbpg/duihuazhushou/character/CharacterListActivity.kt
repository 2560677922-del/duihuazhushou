package com.dbpg.duihuazhushou.character

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.R
import org.json.JSONObject

/**
 * 原生角色卡库。
 *
 * 语言上它只是"卡片的浏览器/导入器"：真正的会话与生成仍在网页侧，
 * 点「用它开聊」时把标准 V2 JSON 回传给网页导入 + 建会话，
 * 再交给网页拉起原生聊天（复用既有快照通道）。
 *
 * 这样角色卡格式完全沿用 SillyTavern，原生端不发明第二套存储。
 */
class CharacterListActivity : Activity() {

    private companion object {
        const val REQ_IMPORT = 2001
        const val BG = 0xFF0F1115.toInt()
        const val PANEL = 0xFF171A21.toInt()
        const val TEXT = 0xFFE6EAF2.toInt()
        const val MUTED = 0xFF8FA6C8.toInt()
    }

    private lateinit var repo: CharacterRepository
    private lateinit var adapter: CharacterAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_list)
        repo = CharacterRepository(this)

        findViewById<TextView>(R.id.char_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.char_import).setOnClickListener { pickFile() }

        emptyView = findViewById(R.id.char_empty)
        adapter = CharacterAdapter(::showDetail)

        findViewById<RecyclerView>(R.id.char_list).apply {
            layoutManager = LinearLayoutManager(this@CharacterListActivity)
            adapter = this@CharacterListActivity.adapter
            setBackgroundColor(BG)
            setPadding(0, dp(6), 0, dp(24))
            clipToPadding = false
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        // 从文件管理器返回后可能新增了卡，重新扫一遍
        reload()
    }

    // ------------------------------------------------------------ 列表加载

    private fun reload() {
        Thread {
            val list = repo.list()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                adapter.submit(list)
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // ------------------------------------------------------------ 导入

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/json", "text/plain"))
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("系统文件选择器只有这一条回调路径")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { repo.import(uri) }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "已导入：${it.displayName}", Toast.LENGTH_SHORT).show()
                    reload()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "导入失败", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ 详情弹窗

    private fun showDetail(item: CharacterFile) {
        val card = item.card
        if (card == null) {
            confirmDelete(item, "这张卡解析失败，可能不是角色卡。要删除它吗？")
            return
        }

        val body = buildString {
            append(card.description.ifBlank { "（没有描述）" })
            if (card.scenario.isNotBlank()) append("\n\n场景：").append(card.scenario)
            if (card.tags.isNotEmpty()) append("\n\n标签：").append(card.tags.joinToString(" "))
            val extra = card.alternateGreetings.size
            if (extra > 0) append("\n\n备用开场白：").append(extra).append(" 条")
            append("\n\n开场白：\n").append(card.firstMessage.ifBlank { "（没有开场白）" })
        }

        val text = TextView(this).apply {
            setText(body)
            setTextColor(TEXT)
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(PANEL)
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(card.name)
            .setView(scroll)
            .setPositiveButton("用它开聊") { _, _ -> startChat(card) }
            .setNeutralButton("删除") { _, _ -> confirmDelete(item, "删除后本地文件就没了，确定删除《${card.name}》？") }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDelete(item: CharacterFile, message: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                val ok = repo.delete(item.file)
                Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 交给网页

    /**
     * 把卡回传给网页：导入 → 建档 → 建一个新会话 → 网页会自己拉起原生聊天。
     * 网页里这一步由 `window.__dhpApp.useCharacter(json)` 实现（见 nativeBridge.ts）。
     */
    private fun startChat(card: com.dbpg.stcore.character.CharacterCard) {
        val web = MainActivity.getWebView()
        if (web == null) {
            Toast.makeText(this, "网页还在启动，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val json = JSONObject.quote(CharacterJson.toV2(card))
        web.evaluateJavascript("window.__dhpApp && window.__dhpApp.useCharacter($json);", null)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
