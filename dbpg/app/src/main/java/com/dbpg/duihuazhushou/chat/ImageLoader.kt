package com.dbpg.duihuazhushou.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 极简图片加载器：按目标宽度做降采样解码，并用内存 LruCache 缓存。
 *
 * 目的只有一个 —— 让聊天列表滑动顺滑：
 *   - 不把整张大图读进内存（inSampleSize 降采样）
 *   - 同一张图只解码一次（LruCache）
 * 没有引入任何第三方图片库，保持原生、可控。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun maxCacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun load(path: String?, targetWidthPx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        // 文件可能已被用户删掉（图库/查看器里删的）：缓存里的旧位图不能再顶上，
        // 否则缩略图还在、点开却说"图片不存在"。文件不在就当没有这张图。
        if (!File(path).exists()) {
            cache.remove(path)
            return null
        }
        cache.get(path)?.let { return it }
        val bmp = decodeSampled(path, targetWidthPx) ?: return null
        cache.put(path, bmp)
        return bmp
    }

    /** 删除文件后调用：把内存里残留的缩略图一并清掉。 */
    fun evict(path: String) {
        cache.remove(path)
    }

    fun clear() = cache.evictAll()

    private fun decodeSampled(path: String, targetWidth: Int): Bitmap? {
        if (!File(path).exists()) return null
        // 先只读边界，算出采样率，避免整图解到内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, targetWidth)
            inPreferredConfig = Bitmap.Config.RGB_565   // 聊天配图不需要 alpha，省一半内存
        }
        return try {
            BitmapFactory.decodeFile(path, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** 计算 2 的幂采样率，使解码宽度不超过目标宽度。 */
    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) return 1
        var size = 1
        var w = sourceWidth
        while (w / 2 >= targetWidth) {
            w /= 2
            size *= 2
        }
        return size
    }
}
