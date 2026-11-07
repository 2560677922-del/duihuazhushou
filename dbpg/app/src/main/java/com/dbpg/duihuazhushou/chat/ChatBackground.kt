package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import java.io.File

/**
 * 聊天背景图：**两级**，全局一张 + 每个会话可选一张。
 *
 * 为什么不塞进会话 JSON：
 *   1. base64 会把会话文件撑到几 MB，列表页读索引时就慢了；
 *   2. 背景是"观看偏好"，不是聊天内容，清缓存/迁移时处理策略不一样；
 *   3. 用文件后，以后要加"背景模糊 / 暗色遮罩强度 / 轮换"都只改这一处。
 *
 * 约定（简单到不用记）：
 *   - 全局背景：`files/chat_bg/global.jpg`
 *   - 会话背景：`files/chat_bg/s_<sessionId>.jpg`
 *   - 会话背景存在就用它，否则回落全局；都没有则用纯色页面背景。
 *
 * 显示时会在图上面盖一层**半透明底色**（[dim]），否则深色照片上的
 * 深色正文会看不清；遮罩浓度由外观设置控制。
 */
object ChatBackground {

    private const val DIR = "chat_bg"
    private const val MAX_WIDTH = 1080
    private const val QUALITY = 85

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun globalFile(ctx: Context) = File(dir(ctx), "global.jpg")

    private fun sessionFile(ctx: Context, sessionId: String): File =
        File(dir(ctx), "s_" + sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun hasGlobal(ctx: Context) = globalFile(ctx).length() > 0

    fun hasSession(ctx: Context, sessionId: String) =
        sessionId.isNotBlank() && sessionFile(ctx, sessionId).length() > 0

    /** 会话优先、全局兜底的"当前该显示哪张图"。 */
    fun resolve(ctx: Context, sessionId: String): Bitmap? {
        val session = if (sessionId.isNotBlank()) loadFile(sessionFile(ctx, sessionId)) else null
        return session ?: loadFile(globalFile(ctx))
    }

    fun loadGlobal(ctx: Context): Bitmap? = loadFile(globalFile(ctx))

    fun loadSession(ctx: Context, sessionId: String): Bitmap? =
        if (sessionId.isBlank()) null else loadFile(sessionFile(ctx, sessionId))

    /**
     * 存图。`sessionId == null` 表示全局背景。
     * 自动按宽度缩到 [MAX_WIDTH]：再宽对手机屏没意义，只会吃内存。
     */
    fun save(ctx: Context, sessionId: String?, src: Bitmap): Boolean = runCatching {
        val target = if (sessionId == null) globalFile(ctx) else sessionFile(ctx, sessionId)
        val scaled = scaleToWidth(src, MAX_WIDTH)
        target.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }
        true
    }.getOrDefault(false)

    fun clear(ctx: Context, sessionId: String?) {
        val f = if (sessionId == null) globalFile(ctx) else sessionFile(ctx, sessionId)
        runCatching { f.delete() }
    }

    /**
     * 把背景画到页面根布局上。
     *
     * 用 [LayerDrawable] 而不是在布局里加 ImageView：少一层视图、不会挡住点击，
     * 而且换图时只要重设 background 就行，不用管层级顺序。
     *
     * @param dim 0–100：遮罩浓度。0 = 不盖（原图），100 = 完全盖住（等于纯色）。
     */
    fun applyTo(view: View, bitmap: Bitmap?, dim: Int = 55) {
        if (bitmap == null) {
            view.background = ColorDrawable(BASE_COLOR)
            return
        }
        val overlay = ColorDrawable(withAlpha(BASE_COLOR, (dim.coerceIn(0, 95) / 100f * 255).toInt()))
        // 索引 0 先绘制（图片），索引 1 在上面（遮罩）
        view.background = LayerDrawable(arrayOf<Drawable>(BitmapDrawable(view.resources, bitmap), overlay))
    }

    /** 页面底色，和 bg 主题里的 #F4F6FA 保持一致。 */
    private const val BASE_COLOR = 0xFFF4F6FA.toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun loadFile(f: File): Bitmap? {
        if (!f.exists() || f.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun scaleToWidth(src: Bitmap, maxW: Int): Bitmap {
        if (src.width <= maxW) return src
        val h = (src.height.toFloat() / src.width * maxW).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, h, true)
    }
}
