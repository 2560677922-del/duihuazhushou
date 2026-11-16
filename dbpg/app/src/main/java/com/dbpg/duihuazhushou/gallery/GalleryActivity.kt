package com.dbpg.duihuazhushou.gallery

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

/**
 * 原生图片库。
 *
 * 扫目录、铺网格、点开看大图，外带**批量删除**：
 * 长按任意一格进入多选，勾几张一起删 —— 一次生成一屏图、挑剩下的
 * 要一张张点进去删，是用户最常抱怨的一步。
 *
 * 数据和 UI 分得很开：目录扫描/删除在 [GalleryRepository]，
 * 缩略图解码在 ImageLoader。
 */
class GalleryActivity : Activity() {

    private lateinit var repo: GalleryRepository
    private lateinit var adapter: GalleryAdapter
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var selectBtn: TextView
    private lateinit var bar: LinearLayout
    private lateinit var selHint: TextView
    private lateinit var selAll: TextView
    private lateinit var selDelete: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        Insets.applyTop(this)
        repo = GalleryRepository(this)

        countView = findViewById(R.id.gallery_count)
        emptyView = findViewById(R.id.gallery_empty)
        findViewById<TextView>(R.id.gallery_back).setOnClickListener {
            // 多选时返回键先退出多选，避免误退出整个图库
            if (adapter.selecting) exitSelect() else finish()
        }
        selectBtn = findViewById(R.id.gallery_select)
        bar = findViewById(R.id.gallery_bar)
        selHint = findViewById(R.id.gallery_sel_hint)
        selAll = findViewById(R.id.gallery_sel_all)
        selDelete = findViewById(R.id.gallery_sel_delete)

        adapter = GalleryAdapter(
            onClick = { openViewer(it) },
            onLongClick = { enterSelect(it) },
        )
        // 3 列；每格解码宽度取屏宽/3，够清晰又不浪费内存
        adapter.targetWidthPx = resources.displayMetrics.widthPixels / 3

        findViewById<RecyclerView>(R.id.gallery_grid).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }

        selectBtn.setOnClickListener { if (adapter.selecting) exitSelect() else enterSelect(null) }
        findViewById<TextView>(R.id.gallery_sel_cancel).setOnClickListener { exitSelect() }
        selAll.setOnClickListener {
            adapter.selectAll(!adapter.allSelected())
            renderBar()
        }
        selDelete.setOnClickListener { confirmDeleteSelected() }

        reload()
    }

    override fun onResume() {
        super.onResume()
        // 从大图页删掉图片后返回，需要重新扫
        reload()
    }

    private fun reload() {
        Thread {
            val list = repo.list()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                adapter.submit(list)
                countView.text = if (list.isEmpty()) "" else "${list.size} 张"
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (adapter.selecting) renderBar()
            }
        }.start()
    }

    // ------------------------------------------------------------ 多选

    private fun enterSelect(first: GalleryImage?) {
        if (!adapter.selecting) {
            adapter.setSelecting(true)
            if (first != null) adapter.toggle(first)
            adapter.refreshChecks()
            renderBar()
        }
    }

    private fun exitSelect() {
        adapter.setSelecting(false)
        renderBar()
    }

    private fun renderBar() {
        val on = adapter.selecting
        bar.visibility = if (on) View.VISIBLE else View.GONE
        selectBtn.text = if (on) "完成" else "选择"
        val n = adapter.selectionCount()
        selHint.text = if (n == 0) "未选择" else "已选 $n 张"
        selAll.text = if (adapter.allSelected()) "取消全选" else "全选"
        selDelete.isEnabled = n > 0
        selDelete.alpha = if (n > 0) 1f else 0.35f
        // 多选时不显示总数，避免和"已选 n 张"抢注意力
        countView.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteSelected() {
        val items = adapter.selectedItems()
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除 ${items.size} 张图片？")
            .setMessage("删除后本地文件就没了，聊天里引用它们的消息也不会再显示这几张。")
            .setPositiveButton("删除") { _, _ -> deleteAll(items) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteAll(items: List<GalleryImage>) {
        Thread {
            var ok = 0
            for (it in items) {
                if (repo.delete(it.file)) ok++
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, "已删除 $ok 张", Toast.LENGTH_SHORT).show()
                // 删完留在多选模式没意义：退出并重新扫目录
                exitSelect()
                reload()
            }
        }.start()
    }

    // ------------------------------------------------------------ 看图

    private fun openViewer(item: GalleryImage) {
        startActivity(
            Intent(this, ImageViewerActivity::class.java)
                .putExtra(ImageViewerActivity.EXTRA_PATH, item.file.absolutePath)
        )
    }
}
