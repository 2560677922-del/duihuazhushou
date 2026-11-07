package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 生图风格「参考图」的本地文件缓存。
 *
 * 为什么要有这一层：
 * 参考图原本存在引擎（web image store）里，原生每次显示都要发一条
 * `getStyleThumb` 命令、由 web 把 base64 回传。旧图（网页端存的 blob 引用）
 * 走这条路没问题，但**新图是原生选的原图**，几 MB 的 base64 经
 * `evaluateJavascript` 往返时会被截断/丢弃 —— 用户看到的现象就是
 * 「以前的能读到，新加的读不到，点了保存也不行」。
 *
 * 所以改成：**显示只认本地文件**，桥只在「首次遇到旧图」时兜底一次。
 *   - 选图 → 缩放编码 → 立刻写 files/style_thumbs/<uid>.jpg → 立即显示；
 *   - 旧图（本地没有文件、但 store 里有 thumb）→ 命令取一次 → 写本地；
 *   - 之后无论是否在 App、是否断网、桥是否就绪，都能秒开。
 *
 * 只存 JPEG（参考图只做展示，不需要透明通道），一张图大约 20–60 KB。
 */
object StyleThumbStore {

    private const val DIR = "style_thumbs"
    /** 参考图不需要大图：384px 足够看清画风，还能把存储压到几十 KB。 */
    private const val MAX_EDGE = 384
    private const val QUALITY = 80

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** uid 里只有字母数字和连字符；仍做一次白名单过滤，避免路径穿越。 */
    private fun safe(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "unknown" }

    fun fileOf(ctx: Context, uid: String): File = File(dir(ctx), "${safe(uid)}.jpg")

    fun has(ctx: Context, uid: String): Boolean = fileOf(ctx, uid).length() > 0

    /** 读本地缩略图；没有或损坏返回 null。 */
    fun load(ctx: Context, uid: String): Bitmap? {
        val f = fileOf(ctx, uid)
        if (!f.exists() || f.length() <= 0) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /**
     * 把一张图存成本地缩略图（自动等比缩到 [MAX_EDGE]）。
     * 返回实际落盘的 Bitmap（已缩放），调用方可直接拿去显示。
     */
    fun saveScaled(ctx: Context, uid: String, src: Bitmap): Bitmap? {
        val scaled = scaleDown(src, MAX_EDGE)
        return runCatching {
            fileOf(ctx, uid).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            scaled
        }.getOrNull()
    }

    fun clear(ctx: Context, uid: String) {
        runCatching { fileOf(ctx, uid).delete() }
    }

    private fun scaleDown(src: Bitmap, max: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= max) return src
        val ratio = max.toFloat() / edge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
