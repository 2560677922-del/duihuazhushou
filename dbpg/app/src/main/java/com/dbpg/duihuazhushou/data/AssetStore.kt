package com.dbpg.duihuazhushou.data

import android.content.Context
import com.dbpg.stcore.imagegen.Base64Codec
import com.dbpg.stcore.imagegen.ImageFormat
import org.json.JSONObject
import java.io.File

/**
 * 图片资产库（原生）。
 *
 * 只存"图片字节 + 一张 id→文件名 的索引"，不存任何业务含义 ——
 * 会话里的配图、生图产物都只是引用这里的 id。
 *
 * 为什么按 id 命名：迁移可以重复跑。同一个 id 再导一次就覆盖同一张文件，
 * 不会像按时间戳命名那样越迁越多、越积越大。
 *
 * 线程：纯文件 IO，调用方负责放后台线程。
 */
class AssetStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "data/assets").apply { if (!exists()) mkdirs() }

    private val indexFile: File
        get() = File(dir, "index.json")

    /** id → 文件名。第一次访问时从 index.json 读，之后常驻。 */
    private var map: MutableMap<String, String>? = null

    /** 落盘结果（给迁移统计用）。 */
    data class Saved(val id: String, val path: String, val bytes: Int)

    /**
     * 保存一张 data URL（`data:image/png;base64,....`）。
     *
     * @return 成功返回文件；data URL 不合法/解码失败返回 null（不抛异常，
     *   迁移时宁可少一张图也不要中断整个流程）。
     */
    fun saveDataUrl(id: String, dataUrl: String): Saved? {
        if (id.isEmpty() || dataUrl.isEmpty()) return null
        val (mime, payload) = splitDataUrl(dataUrl) ?: return null
        val bytes = runCatching { Base64Codec.decode(payload) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return saveBytes(id, bytes, mime)
    }

    fun saveBytes(id: String, bytes: ByteArray, mime: String): Saved? {
        val safe = safeId(id) ?: return null
        if (bytes.isEmpty()) return null
        val ext = ImageFormat.extFor(mime)
        val name = "$safe.$ext"
        val file = File(dir, name)
        return try {
            file.writeBytes(bytes)
            val m = ensureMap()
            // 如果这个 id 以前存过别的扩展名，把旧文件删掉，避免同 id 两份
            m[safe]?.let { old -> if (old != name) runCatching { File(dir, old).delete() } }
            m[safe] = name
            writeIndex(m)
            Saved(safe, file.absolutePath, bytes.size)
        } catch (t: Throwable) {
            null
        }
    }

    /** 按 id 找文件；找不到返回 null。 */
    fun fileOf(id: String): File? {
        val m = ensureMap()
        val name = m[safeId(id) ?: return null] ?: return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    fun count(): Int = ensureMap().size

    // ------------------------------------------------------------ 内部

    private fun splitDataUrl(dataUrl: String): Pair<String, String>? {
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return null
        val head = dataUrl.substring(0, comma)
        if (!head.startsWith("data:")) return null
        val payload = dataUrl.substring(comma + 1).trim()
        if (payload.isEmpty()) return null
        val mime = head.removePrefix("data:").substringBefore(';').ifEmpty { "image/png" }
        return mime to payload
    }

    private fun ensureMap(): MutableMap<String, String> {
        map?.let { return it }
        val m = mutableMapOf<String, String>()
        if (indexFile.exists()) {
            runCatching {
                val o = JSONObject(indexFile.readText())
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = o.optString(k, "")
                }
            }
        }
        map = m
        return m
    }

    private fun writeIndex(m: Map<String, String>) {
        runCatching {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, v) }
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(o.toString())
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    /** id 会进文件名，必须挡住路径穿越。 */
    private fun safeId(id: String): String? {
        if (id.isEmpty() || id.length > 128) return null
        return if (SAFE.matches(id)) id else null
    }

    private companion object {
        val SAFE = Regex("^[A-Za-z0-9_.\\-]+$")
    }
}
