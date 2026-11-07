package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生图风格页：多套风格存储、随用随切，含"风格参考图"（只作展示，不参与出图）。
 *
 * 数据在 web image store（生成走引擎，配置必须同源），经 __dhpApi 读写：
 *   getImageStyle()                       风格列表 + 当前详情（含参考图 dataURL）
 *   imgAddStyle / imgUseStyle / imgRemoveStyle / imgRenameStyle
 *   imgSaveStyle{uid, style{...}}         保存某套风格的提示词与参考图
 *   imgSaveParams{params{width,height}}   尺寸（挂在接口上）
 */
class ImageStyleActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var artist: EditText
    private lateinit var quality: EditText
    private lateinit var negative: EditText
    private lateinit var template: EditText
    private lateinit var width: EditText
    private lateinit var height: EditText
    private lateinit var thumbView: ImageView

    /** 当前编辑的风格 uid（跟随 store 的 currentStyleId）。 */
    private var currentUid = ""

    /** 风格 uid → (缩略图 ImageView, 占位字)；异步取参考图后回填。 */
    private val thumbViews = mutableMapOf<String, Pair<ImageView, TextView>>()

    /** 待保存的参考图 dataURL；null = 不改动，"" = 清除。 */
    private var pendingThumb: String? = null

    private val apiListener = NativeBridge.ApiResultListener { json ->
        try {
            val obj = JSONObject(json)
            val kind = obj.optString("kind")
            val ok = obj.optBoolean("ok")
            if (!ok) {
                toast("${kind} 失败：${obj.optString("error")}")
                return@ApiResultListener
            }
            when (kind) {
                "getStyleThumb" -> {
                    val uid = obj.optString("uid")
                    val dataUrl = obj.optString("thumb")
                    val pair = thumbViews[uid] ?: return@ApiResultListener
                    if (dataUrl.isNotBlank() && dataUrl.contains("base64,")) {
                        runCatching {
                            val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                pair.first.setImageBitmap(bmp)
                                pair.second.visibility = TextView.GONE
                            }
                        }
                    }
                    return@ApiResultListener
                }
                "imgAddStyle" -> {
                    toast("已新建风格")
                    load()
                }
                "imgUseStyle" -> load()
                "imgRemoveStyle" -> {
                    toast("已删除")
                    load()
                }
                "imgRenameStyle" -> load()
                "imgSaveStyle" -> {
                    toast("已保存")
                    load()
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_style)
        Insets.applyTop(this)

        listBox = findViewById(R.id.style_list)
        artist = findViewById(R.id.style_artist)
        quality = findViewById(R.id.style_quality)
        negative = findViewById(R.id.style_negative)
        template = findViewById(R.id.style_template)
        width = findViewById(R.id.style_width)
        height = findViewById(R.id.style_height)
        thumbView = findViewById(R.id.style_thumb)

        findViewById<TextView>(R.id.style_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.style_reload).setOnClickListener { load() }
        findViewById<TextView>(R.id.style_add).setOnClickListener { promptNewStyle() }
        findViewById<TextView>(R.id.style_thumb_pick).setOnClickListener { pickThumb() }
        findViewById<TextView>(R.id.style_thumb_clear).setOnClickListener {
            pendingThumb = ""
            thumbView.setImageBitmap(null)
            toast("已清除，点「保存到当前风格」生效")
        }
        findViewById<TextView>(R.id.style_save).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(apiListener)
        load()
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    // ------------------------------------------------------------ 读取与渲染

    private fun load() {
        EngineBridge.fetch(this, "getImageStyle") { obj ->
            if (obj == null) {
                toast("引擎还没就绪，稍后点「刷新」")
                return@fetch
            }
            currentUid = obj.optString("styleUid")
            pendingThumb = null
            findViewById<TextView>(R.id.style_title).text =
                "生图风格 · ${obj.optString("styleName").ifBlank { "默认" }}"
            artist.setText(obj.optString("artistTags"))
            quality.setText(obj.optString("qualityTags"))
            negative.setText(obj.optString("negativePrompt"))
            template.setText(obj.optString("promptTemplate"))
            width.setText(obj.optInt("width", 832).toString())
            height.setText(obj.optInt("height", 1216).toString())
            showThumb(obj.optString("thumb"))
            renderStyles(obj.optJSONArray("styles") ?: JSONArray())
        }
    }

    /**
     * 风格列表：方块卡片网格（两列）。
     *
     * 为什么是方块：风格的价值在"看起来长什么样"，参考图必须是正方形才能
     * 整齐地铺一排比较；文字列表看不出风格差异（用户反馈）。
     * 缩略图按需单张取（getStyleThumb），不把所有 base64 一次塞过来。
     */
    private fun renderStyles(arr: JSONArray) {
        listBox.removeAllViews()
        val cols = 2
        val gap = dp(8)
        val cellW = ((resources.displayMetrics.widthPixels - dp(12) * 2 - gap * (cols - 1)) / cols)

        var row: LinearLayout? = null
        for (i in 0 until arr.length()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = gap
                }
                listBox.addView(row)
            }
            val s = arr.getJSONObject(i)
            val uid = s.optString("uid")
            val name = s.optString("name").ifBlank { "未命名风格" }
            val current = s.optBoolean("current")

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = gap
                }
                setOnClickListener {
                    if (current) return@setOnClickListener
                    EngineBridge.command(JSONObject().put("cmd", "imgUseStyle").put("uid", uid))
                }
                setOnLongClickListener {
                    styleActions(uid, name); true
                }
            }

            // 方形缩略图（高度 = 宽度），无图时用首字占位
            val thumbBox = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(cellW - dp(16), cellW - dp(16))
                background = getDrawable(R.drawable.bg_input)
            }
            val iv = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            thumbBox.addView(iv)
            val initial = TextView(this).apply {
                text = name.take(1)
                setTextColor(0xFF2F66D8.toInt())
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            thumbBox.addView(initial)
            cell.addView(thumbBox)

            if (s.optBoolean("hasThumb")) {
                thumbViews[uid] = iv to initial
                // thumb 可能是 blob: 引用，交给 web 的 resolveCached 解析成 dataURL
                EngineBridge.command(JSONObject().put("cmd", "getStyleThumb").put("uid", uid))
            }

            cell.addView(TextView(this).apply {
                text = (if (current) "✓ " else "") + name
                setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, dp(2))
                if (current) setTypeface(typeface, Typeface.BOLD)
            })
            row?.addView(cell)
        }
    }

    /** 长按风格：重命名 / 删除。 */
    private fun styleActions(uid: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setNeutralButton("重命名") { _, _ ->
                val input = EditText(this).apply {
                    setText(name)
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                AlertDialog.Builder(this)
                    .setTitle("重命名风格")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        EngineBridge.command(
                            JSONObject().put("cmd", "imgRenameStyle").put("uid", uid)
                                .put("name", input.text?.toString() ?: name),
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("删除") { _, _ ->
                EngineBridge.command(JSONObject().put("cmd", "imgRemoveStyle").put("uid", uid))
            }
            .setPositiveButton("取消", null)
            .show()
    }

    private fun promptNewStyle() {
        val input = EditText(this).apply {
            hint = "风格名，如「清新日系」"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("新建风格")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                EngineBridge.command(
                    JSONObject().put("cmd", "imgAddStyle")
                        .put("name", input.text?.toString()?.ifBlank { "新风格" } ?: "新风格"),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 参考图

    private fun pickThumb() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQ_THUMB)
        } catch (t: Throwable) {
            toast("打不开图片选择器：${t.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_THUMB || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            // 缩一点再存，参考图只做展示，不需要原图尺寸
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                toast("这张图解不开"); return
            }
            val scaled = scaleDown(bmp, 512)
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            pendingThumb = dataUrl
            thumbView.setImageBitmap(scaled)
            toast("已选择，点「保存到当前风格」生效")
        } catch (t: Throwable) {
            toast("读取图片失败：${t.message}")
        }
    }

    private fun scaleDown(src: Bitmap, max: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= max) return src
        val ratio = max.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }

    private fun showThumb(dataUrl: String) {
        if (dataUrl.isBlank() || !dataUrl.contains("base64,")) {
            thumbView.setImageBitmap(null)
            return
        }
        try {
            val b64 = dataUrl.substringAfter("base64,")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            thumbView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } catch (_: Throwable) {
            thumbView.setImageBitmap(null)
        }
    }

    // ------------------------------------------------------------ 保存

    private fun save() {
        if (currentUid.isBlank()) {
            toast("当前没有选中的风格")
            return
        }
        val style = JSONObject()
            .put("artistTags", artist.text?.toString() ?: "")
            .put("qualityTags", quality.text?.toString() ?: "")
            .put("negativePrompt", negative.text?.toString() ?: "")
            .put("promptTemplate", template.text?.toString() ?: "")
        pendingThumb?.let { style.put("thumb", it) }
        EngineBridge.command(
            JSONObject().put("cmd", "imgSaveStyle").put("uid", currentUid).put("style", style),
        )
        // 尺寸挂在接口上（与 web 的职责划分一致）
        EngineBridge.command(
            JSONObject().put("cmd", "imgSaveParams").put(
                "params",
                JSONObject()
                    .put("width", width.text?.toString()?.toIntOrNull() ?: 832)
                    .put("height", height.text?.toString()?.toIntOrNull() ?: 1216),
            ),
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        const val REQ_THUMB = 3001
    }
}
