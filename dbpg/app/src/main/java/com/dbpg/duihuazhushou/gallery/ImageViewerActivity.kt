package com.dbpg.duihuazhushou.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ImageLoader
import java.io.File

/**
 * 全屏看图。
 *
 * 手势全部用系统 API：
 *  - 双指缩放：ScaleGestureDetector（1x ~ 5x）
 *  - 单指拖动：GestureDetector.onScroll（放大后才有意义）
 *  - 双击：1x / 2.5x 切换
 *
 * 保存走 MediaStore，不申请 FileProvider；Android 10 以下才需要写外部存储权限。
 */
class ImageViewerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_WRITE = 9001
        private const val MAX_SCALE = 5f
    }

    private lateinit var image: ImageView
    private lateinit var file: File
    private var scale = 1f

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setScale(scale * detector.scaleFactor)
                return true
            }
        })
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1f) return false
                image.translationX -= dx
                image.translationY -= dy
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) {
                    setScale(1f)
                    image.translationX = 0f
                    image.translationY = 0f
                } else {
                    setScale(2.5f)
                }
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        image = findViewById(R.id.viewer_image)
        // 目标宽度给到屏宽 2 倍，放大后仍清晰，又不至于把原图整张读进内存
        val target = resources.displayMetrics.widthPixels * 2
        val bmp: Bitmap? = ImageLoader.load(path, target)
        if (bmp == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        image.setImageBitmap(bmp)
        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        findViewById<TextView>(R.id.viewer_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.viewer_save).setOnClickListener { saveToAlbum() }
        findViewById<TextView>(R.id.viewer_delete).setOnClickListener { confirmDelete() }
    }

    private fun setScale(target: Float) {
        scale = target.coerceIn(1f, MAX_SCALE)
        image.scaleX = scale
        image.scaleY = scale
    }

    // ------------------------------------------------------------ 保存相册

    private fun saveToAlbum() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        doSave()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WRITE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) doSave()
        else Toast.makeText(this, "没有存储权限，无法保存到相册", Toast.LENGTH_SHORT).show()
    }

    private fun doSave() {
        val uri = runCatching { writeToMediaStore() }.getOrNull()
        Toast.makeText(this, if (uri != null) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    /** 写 MediaStore：Q 及以上用 RELATIVE_PATH + IS_PENDING，低版本直接插。 */
    private fun writeToMediaStore(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "dhp_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/冒险模拟器")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    // ------------------------------------------------------------ 删除

    private fun confirmDelete() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("删除后本地文件就没了，确定删除这张图？")
            .setPositiveButton("删除") { _, _ ->
                val ok = GalleryRepository(this).delete(file)
                Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                if (ok) finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
