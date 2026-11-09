package com.dbpg.duihuazhushou.character

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

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
        const val REQ_EDIT = 2002
        const val BG = 0xFFF4F6FA.toInt()
    }

    private lateinit var repo: CharacterRepository
    private lateinit var adapter: CharacterAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_list)
        Insets.applyTop(this)
        repo = CharacterRepository(this)

        findViewById<TextView>(R.id.char_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.char_import).setOnClickListener { pickFile() }
        findViewById<TextView>(R.id.char_new).setOnClickListener { newCharacter() }

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

    /** 打开空白编辑器新建一张卡；保存后回到这里刷新列表。 */
    private fun newCharacter() {
        startActivityForResult(
            Intent(this, CharacterEditActivity::class.java),
            REQ_EDIT,
        )
    }

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
        // 编辑器保存完会带着 RESULT_OK 回来，列表页重新扫一遍即可
        if (requestCode == REQ_EDIT) {
            if (resultCode == RESULT_OK) reload()
            return
        }
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

    // ------------------------------------------------------------ 详情页

    /** 解析成功 → 进全屏详情页；坏卡只给删除选项，避免详情页再崩一次。 */
    private fun showDetail(item: CharacterFile) {
        if (item.card == null) {
            confirmDelete(item, "这张卡解析失败，可能不是角色卡。要删除它吗？")
            return
        }
        startActivity(
            Intent(this, CharacterDetailActivity::class.java)
                .putExtra(CharacterDetailActivity.EXTRA_PATH, item.file.absolutePath)
        )
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
