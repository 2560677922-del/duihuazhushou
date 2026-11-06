package com.dbpg.stcore.imagegen

/**
 * 图片格式小工具：MIME ⇄ 文件扩展名。
 * 生图落盘时要按 MIME 决定存成 .png 还是 .jpg，避免系统相册认不出。
 */
object ImageFormat {

    /** MIME → 扩展名（未知一律按 png）。 */
    fun extFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/heic", "image/heif" -> "heic"
        else -> "png"
    }

    fun isImage(mime: String?): Boolean = mime?.startsWith("image/", ignoreCase = true) == true
}
