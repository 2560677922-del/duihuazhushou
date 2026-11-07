package com.dbpg.duihuazhushou.gallery

import android.content.Context
import android.os.Environment
import java.io.File

/** 一张本地图片（只描述文件，不解码，解码交给 ImageLoader）。 */
data class GalleryImage(
    val file: File,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * 图片库数据源：只扫 App 自己的两个目录，不做递归、不碰外部存储。
 *
 *  - `getExternalFilesDir(PICTURES)`：生图落盘位置（见 ImageStore）
 *  - `cacheDir/chat_img`：聊天里 [[IMG: ...]] 转存下来的图片
 *
 * 这样用户能看到"我生成过的 / 聊天里出现过的"图，而不用申请任何存储权限。
 */
class GalleryRepository(private val context: Context) {

    fun list(): List<GalleryImage> {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            File(context.filesDir, "images"),
            File(context.cacheDir, "chat_img"),
        )
        val out = ArrayList<GalleryImage>()
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile && isImage(f.name)) {
                    out.add(GalleryImage(f, f.length(), f.lastModified()))
                }
            }
        }
        // 新的在前：用户刚生成的图应该第一眼看到
        return out.sortedByDescending { it.modifiedAt }
    }

    /**
     * 删除一张图。文件删除后必须把 [ImageLoader] 的内存缩略图也清掉 ——
     * 否则聊天/图库里旧图还会显示（LruCache 只认路径，不知道文件已经没了）。
     * chat_img 里的转存图再打个删除标记，防止下次渲染从 dataUrl 复活。
     */
    fun delete(file: File): Boolean {
        val ok = runCatching { file.delete() }.getOrDefault(false)
        if (ok) {
            com.dbpg.duihuazhushou.chat.ImageLoader.evict(file.absolutePath)
            com.dbpg.duihuazhushou.chat.ImageFileCache.markDeleted(file)
        }
        return ok
    }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".webp") || n.endsWith(".gif")
    }
}
