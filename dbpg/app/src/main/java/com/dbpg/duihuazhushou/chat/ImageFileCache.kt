package com.dbpg.duihuazhushou.chat

import android.content.Context
import com.dbpg.stcore.imagegen.ImageFormat
import com.dbpg.stcore.imagegen.ImagePayloadExtractor
import java.io.File

/**
 * 把网页快照里的 dataUrl 图片落到本地缓存文件，只向 UI 暴露【文件路径】。
 *
 * 为什么要有它：网页桥过来的快照里，图片是 base64 的 dataUrl。
 * 若直接把 base64 塞进列表，等于把大块字符串带进 UI 层（内存翻倍、列表变慢）。
 * 这里统一转存为缓存文件，聊天列表只认路径 —— 与「禁止 base64 进 UI」一致。
 *
 * 以 dataUrl 的 hashCode 命名，天然去重、重复轮询不会重复写盘。
 */
class ImageFileCache(context: Context) {

    private val dir: File = File(context.cacheDir, "chat_img").apply { mkdirs() }
    private val memo = HashMap<Int, String>()

    /** dataUrl → 本地文件绝对路径；解析不出来返回 null。 */
    fun pathFor(dataUrl: String?): String? {
        if (dataUrl.isNullOrBlank()) return null
        val key = dataUrl.hashCode()
        memo[key]?.let { p -> if (File(p).length() > 0) return p }

        val inline = ImagePayloadExtractor.decodeDataUrl(dataUrl) ?: return null
        val file = File(dir, "$key.${ImageFormat.extFor(inline.mime)}")
        return try {
            if (file.length() == 0L) file.writeBytes(inline.bytes)
            memo[key] = file.absolutePath
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
