package com.dbpg.duihuazhushou.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.dbpg.stcore.character.CharacterCardParser
import java.io.File

/**
 * 角色卡头像：聊天页与群聊频道栏共用。
 *
 * 只按「名字」找，不引入第二套 id：
 *   - 角色卡导入时文件名就是卡名（重名会加 " (1)"），所以先按文件名精确匹配；
 *   - 用户手动改过文件名的，再扫一遍卡里的 `name` 字段兜底。
 *
 * 解码统一压到 96px 并进 LRU：一张头像 512px 解出来约 1MB，
 * 一屏十几个气泡逐个解会直接顶到 OOM 线。
 */
object CharacterAvatar {

    private const val MAX_PX = 96

    private val cache = LruCache<String, Bitmap>(24)
    /** 名字 → 文件路径；找不到也缓存空串，避免每次都扫目录 */
    private val files = HashMap<String, String>()

    fun bitmap(ctx: Context, name: String): Bitmap? {
        val key = name.trim()
        if (key.isEmpty()) return null
        cache.get(key)?.let { return it }
        val file = findFile(ctx, key) ?: return null
        val bmp = decode(file) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 换过头像 / 重新导入后调用，下次取图重新扫。 */
    fun invalidate() {
        cache.evictAll()
        files.clear()
    }

    private fun findFile(ctx: Context, name: String): File? {
        files[name]?.let { return if (it.isEmpty()) null else File(it) }
        val dir = File(ctx.applicationContext.filesDir, "characters")
        val all = dir.listFiles()?.filter { it.isFile } ?: run {
            files[name] = ""
            return null
        }
        val exact = all.firstOrNull { it.nameWithoutExtension == name }
        val hit = exact ?: all.firstOrNull {
            Regex("^" + Regex.escape(name) + " \\(\\d+\\)$").matches(it.nameWithoutExtension)
        } ?: scanByCardName(all, name)
        files[name] = hit?.absolutePath.orEmpty()
        return hit
    }

    /** 文件名被改过：解析卡里的 name。解析失败/没头像的都跳过。 */
    private fun scanByCardName(all: List<File>, name: String): File? {
        for (f in all) {
            if (!f.name.endsWith(".png", ignoreCase = true)) continue
            val card = runCatching {
                CharacterCardParser.parse(f.readBytes(), f.nameWithoutExtension)
            }.getOrNull() ?: continue
            if (card.name.trim() == name) return f
        }
        return null
    }

    private fun decode(file: File): Bitmap? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // PNG 卡：头像就是整张图；JSON 卡没有图，返回 null 用占位
        val card = runCatching {
            CharacterCardParser.parse(bytes, file.nameWithoutExtension)
        }.getOrNull()
        val raw = card?.avatarBytes ?: bytes
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > MAX_PX * 2) sample *= 2
        return BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
