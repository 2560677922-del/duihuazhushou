package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.dbpg.stcore.imagegen.ImageFormat
import java.io.File

/**
 * 生图落盘：把图片字节写进 App 私有目录，返回【本地路径 + Uri】。
 *
 * 为什么不进相册 / 不申请存储权限：
 *   私有目录（getExternalFilesDir）在所有 Android 版本都免权限，卸载即清理，
 *   对"聊天配图"这种临时素材最合适；需要给别的 App 用时再走 FileProvider。
 *
 * 绝不 base64 返回：UI 层拿到的只有 path / uri。
 */
class ImageStore(private val context: Context) {

    data class StoredImage(val path: String, val uri: Uri, val mime: String)

    fun save(bytes: ByteArray, mime: String): StoredImage? {
        if (bytes.isEmpty()) return null
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()

        val name = "gen_${System.currentTimeMillis()}_${(0..9999).random()}.${ImageFormat.extFor(mime)}"
        val file = File(dir, name)
        return try {
            file.writeBytes(bytes)
            StoredImage(file.absolutePath, Uri.fromFile(file), mime)
        } catch (_: Exception) {
            null
        }
    }
}
