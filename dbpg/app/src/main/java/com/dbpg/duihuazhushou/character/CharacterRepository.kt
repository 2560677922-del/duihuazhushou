package com.dbpg.duihuazhushou.character

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dbpg.stcore.character.CharacterCard
import com.dbpg.stcore.character.CharacterCardParser
import java.io.File

/** 一张已落盘的角色卡：文件句柄 + 解析结果（解析失败时 card 为 null）。 */
data class CharacterFile(
    val file: File,
    val card: CharacterCard?,
) {
    val displayName: String
        get() = card?.name?.ifBlank { null }
            ?: file.nameWithoutExtension
}

/**
 * 角色卡仓库：只做文件层的事（扫描 / 导入 / 删除），不碰 UI。
 *
 * 存放位置固定为 `filesDir/characters/`，和 ST 的 PNG/JSON 卡一一对应，
 * 不改字段、不改图片，原样保留 —— 保证导出回网页版还能用。
 */
class CharacterRepository(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "characters").apply { if (!exists()) mkdirs() }

    /** 扫描目录。按名字排序，坏卡也保留（用户可以看到并删掉）。 */
    fun list(): List<CharacterFile> = dir.listFiles()
        ?.filter { it.isFile && isSupported(it.name) }
        ?.map { f -> CharacterFile(f, runCatching { CharacterCardParser.parse(f.readBytes(), f.nameWithoutExtension) }.getOrNull()) }
        ?.sortedWith(compareBy({ (it.card?.name ?: it.file.name).lowercase() }))
        ?: emptyList()

    /**
     * 从系统文件选择器导入。返回导入后的条目；失败抛出可读的中文原因。
     *
     * 支持 `.png`（图里带卡）和 `.json`；`.charx` 是压缩包，这里不解，
     * 明确报错而不是静默失败。
     */
    fun import(uri: Uri): CharacterFile {
        val name = queryDisplayName(uri) ?: "character-${System.currentTimeMillis()}.png"
        val lower = name.lowercase()
        if (lower.endsWith(".charx") || lower.endsWith(".zip")) {
            throw IllegalArgumentException("暂不支持 .charx / .zip，请解压后导入里面的 .png 或 .json")
        }
        if (!isSupported(name)) throw IllegalArgumentException("只支持 .png 或 .json 角色卡")

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
        if (bytes.isEmpty()) throw IllegalStateException("文件是空的")
        if (bytes.size > 32 * 1024 * 1024) throw IllegalStateException("文件超过 32MB，不像角色卡")

        val card = CharacterCardParser.parse(bytes, name.substringBeforeLast('.'))
            ?: throw IllegalStateException("解析失败：不是有效的 SillyTavern 角色卡")

        // 重名不覆盖，加序号，避免用户导入两张同名卡丢一张
        val target = uniqueFile(sanitize(card.name.ifBlank { "character" }), extensionOf(name))
        target.writeBytes(bytes)
        return CharacterFile(target, card)
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 读原始字节，用于把卡片原样交给网页侧导入。 */
    fun readRaw(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    private fun isSupported(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".json")
    }

    private fun extensionOf(name: String): String =
        if (name.lowercase().endsWith(".json")) "json" else "png"

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(48)
        return cleaned.ifBlank { "character" }
    }

    private fun uniqueFile(base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i).$ext")
            i++
        }
        return f
    }
}
