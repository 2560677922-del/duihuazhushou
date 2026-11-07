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

    companion object {
        /**
         * 用户删掉的转存图打个标记，防止下次渲染时 pathFor 从 dataUrl
         * 把文件又写回来（"图删了又自己长回来"）。标记文件名 <hash>.deleted，
         * 与转存文件同名换扩展名，天然去重、重启后仍有效。
         */
        fun markDeleted(file: File) {
            val dir = file.parentFile ?: return
            // 只有 chat_img 转存目录需要防复活；其它目录没有 pathFor 这条回写路径
            if (dir.name != "chat_img") return
            val stem = file.nameWithoutExtension
            if (stem.isEmpty()) return
            runCatching { File(dir, "$stem.deleted").createNewFile() }
        }
    }

    private val dir: File = File(context.cacheDir, "chat_img").apply { mkdirs() }
    private val memo = HashMap<Int, String>()

    /** dataUrl → 本地文件绝对路径；解析不出来返回 null。 */
    fun pathFor(dataUrl: String?): String? {
        if (dataUrl.isNullOrBlank()) return null
        val key = dataUrl.hashCode()
        memo[key]?.let { p -> if (File(p).length() > 0) return p }

        val inline = ImagePayloadExtractor.decodeDataUrl(dataUrl) ?: return null
        val file = File(dir, "$key.${ImageFormat.extFor(inline.mime)}")
        // 删过的不复活：用户在查看器/图库里删掉就是不想要了
        if (File(dir, "$key.deleted").exists()) return null
        return try {
            if (file.length() == 0L) file.writeBytes(inline.bytes)
            memo[key] = file.absolutePath
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
