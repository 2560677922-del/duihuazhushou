package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.dbpg.stcore.imagegen.ImageFormat
import java.io.File
import java.security.MessageDigest

/**
 * 生图落盘：把图片字节写进 App 私有目录，返回【本地路径 + Uri】。
 *
 * 为什么不进相册 / 不申请存储权限：
 *   私有目录（getExternalFilesDir）在所有 Android 版本都免权限，卸载即清理，
 *   对"聊天配图"这种临时素材最合适；需要给别的 App 用时再走 FileProvider。
 *
 * 绝不 base64 返回：UI 层拿到的只有 path / uri。
 *
 * 2026-11-09 改动：文件名改用「内容 SHA-256 哈希」，而不是时间戳+随机数。
 *   原因（用户要求"一张即可，不要反复存"）：
 *   - 同一张图（同样字节）只落一次盘，再生图/再引用不会再写一份副本；
 *   - 聊天、图库都只按路径引用这一个文件，不会因"引用一次"就复制一份；
 *   - 哈希名天然去重，也顺带避免 base64 满天飞导致的体积爆炸。
 *   画廊按 modifiedAt 排序，文件名是否带时间戳不影响"最新生成排前面"。
 */
class ImageStore(private val context: Context) {

    data class StoredImage(val path: String, val uri: Uri, val mime: String)

    fun save(bytes: ByteArray, mime: String): StoredImage? {
        if (bytes.isEmpty()) return null
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()

        // 内容哈希命名：相同字节永远指向同一个文件，绝不重复落盘。
        val hash = sha256Hex(bytes)
        val file = File(dir, "$hash.${ImageFormat.extFor(mime)}")
        return try {
            // 已存在说明之前生成过完全相同的图，直接复用，不再写一次。
            if (file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            StoredImage(file.absolutePath, Uri.fromFile(file), mime)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
        return sb.toString()
    }
}
