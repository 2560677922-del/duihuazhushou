package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.appearance.BackdropDrawable
import com.dbpg.duihuazhushou.appearance.ThemePalette
import java.io.File

/**
 * 背景图：**两级互不干扰** —— 界面背景（操作界面）+ 会话背景（聊天）。
 *
 * 为什么拆开：用户在设置里换的「全局背景」本意是给首页/设置这些**操作界面**
 * 用的，可它以前会兜底进聊天里，两个诉求互相打架。现在：
 *   - `ui.jpg`            界面背景 —— 首页/设置/资产/生图等一切非聊天界面
 *   - `s_<sessionId>.jpg` 会话背景 —— 只在对应聊天里显示，聊天不再兜底界面图
 *
 * 兼容：老的 `global.jpg` 读盘时自动改名成 `ui.jpg`（用户存过的图不丢）。
 *
 * 为什么不塞进会话 JSON：
 *   1. base64 会把会话文件撑到几 MB，列表页读索引时就慢了；
 *   2. 背景是"观看偏好"，不是聊天内容，清缓存/迁移时处理策略不一样；
 *   3. 用文件后，以后要加"背景模糊 / 暗色遮罩强度 / 轮换"都只改这一处。
 *
 * 显示统一走 [BackdropDrawable]：底色 + 图（**等比不拉伸**）+ 遮罩 + 装饰。
 */
object ChatBackground {

    private const val DIR = "chat_bg"
    private const val MAX_WIDTH = 1080
    private const val QUALITY = 85

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 界面背景（操作界面用）。老版本叫 global，读盘时迁移。 */
    private fun uiFile(ctx: Context): File {
        val f = File(dir(ctx), "ui.jpg")
        val legacy = File(dir(ctx), "global.jpg")
        if (!f.exists() && legacy.exists()) runCatching { legacy.renameTo(f) }
        return f
    }

    private fun sessionFile(ctx: Context, sessionId: String): File =
        File(dir(ctx), "s_" + sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun hasUi(ctx: Context) = uiFile(ctx).length() > 0

    fun hasSession(ctx: Context, sessionId: String) =
        sessionId.isNotBlank() && sessionFile(ctx, sessionId).length() > 0

    /** 界面背景图（非聊天界面用）。 */
    fun loadUi(ctx: Context): Bitmap? = loadFile(uiFile(ctx))

    fun loadSession(ctx: Context, sessionId: String): Bitmap? =
        if (sessionId.isBlank()) null else loadFile(sessionFile(ctx, sessionId))

    /**
     * 聊天页当前该显示哪张图：**只认会话背景**。
     * 没有就返回 null（调用方走纯色底 + 装饰），不再兜底界面背景。
     */
    fun resolve(ctx: Context, sessionId: String): Bitmap? = loadSession(ctx, sessionId)

    /**
     * 存图。`sessionId == null` 表示界面背景。
     * 自动按宽度缩到 [MAX_WIDTH]：再宽对手机屏没意义，只会吃内存。
     */
    fun save(ctx: Context, sessionId: String?, src: Bitmap): Boolean = runCatching {
        val target = if (sessionId == null) uiFile(ctx) else sessionFile(ctx, sessionId)
        val scaled = scaleToWidth(src, MAX_WIDTH)
        target.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }
        true
    }.getOrDefault(false)

    fun clear(ctx: Context, sessionId: String?) {
        val f = if (sessionId == null) uiFile(ctx) else sessionFile(ctx, sessionId)
        runCatching { f.delete() }
    }

    /**
     * 把背景画到页面根布局上。
     *
     * 用 [BackdropDrawable] 一层画完（底色 → 等比图 → 遮罩 → 装饰），
     * 而不是在布局里加 ImageView：少一层视图、不会挡住点击，换图时
     * 只要重设 background 就行。
     *
     * 图**不拉伸**：等比缩放居中，没盖到的边条由页面底色填满 ——
     * 底色就是外观设置里的「页面底色」，用户可自己换。
     *
     * @param dim 0–100：遮罩浓度。0 = 不盖（原图），100 = 完全盖住（等于纯色）。
     */
    fun applyTo(view: View, bitmap: Bitmap?, dim: Int = 55) {
        val cfg = Appearance.load(view.context)
        view.background = BackdropDrawable(
            baseColor = ThemePalette.base(cfg),
            bitmap = bitmap,
            dim = dim,
            decoration = cfg.decoration,
        )
    }

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
