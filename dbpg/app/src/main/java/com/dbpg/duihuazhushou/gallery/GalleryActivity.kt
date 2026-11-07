package com.dbpg.duihuazhushou.gallery

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

/**
 * 原生图片库。
 *
 * 只做三件事：扫目录、铺网格、点开看大图。
 * 数据和 UI 分得很开：目录扫描在 [GalleryRepository]，缩略图解码在 ImageLoader。
 */
class GalleryActivity : Activity() {

    private lateinit var repo: GalleryRepository
    private lateinit var adapter: GalleryAdapter
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        Insets.applyTop(this)
        repo = GalleryRepository(this)

        countView = findViewById(R.id.gallery_count)
        emptyView = findViewById(R.id.gallery_empty)
        findViewById<TextView>(R.id.gallery_back).setOnClickListener { finish() }

        adapter = GalleryAdapter { openViewer(it) }
        // 3 列；每格解码宽度取屏宽/3，够清晰又不浪费内存
        adapter.targetWidthPx = resources.displayMetrics.widthPixels / 3

        findViewById<RecyclerView>(R.id.gallery_grid).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }
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
            }
        }.start()
    }

    private fun openViewer(item: GalleryImage) {
        startActivity(
            Intent(this, ImageViewerActivity::class.java)
                .putExtra(ImageViewerActivity.EXTRA_PATH, item.file.absolutePath)
        )
    }
}
