package com.dbpg.stcore.imagegen

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 生图响应解析器（纯 Kotlin / 纯 JVM，可脱离 Android 单测）。
 *
 * 背景：第三方（中转）站的返回格式五花八门，最常见的坑有：
 *   1. 你以为它返回图片，其实返回的是一个 ZIP 压缩包（里面才是 png）；
 *      有的 Content-Type 还写着 application/json，但 body 其实是 ZIP。
 *   2. 返回 JSON：OpenAI 风格 `{"data":[{"b64_json":"..."}]}`、
 *      `{"data":[{"url":"https://..."}]}`，或自定义 `{"image":"data:image/png;base64,..."}`。
 *   3. 直接返回 data URL 文本 / 纯 base64 文本。
 *
 * 本类的策略：**以字节为准，不轻信 Content-Type**。
 *   先看文件头 magic bytes，是 ZIP 就解压；是图片就直接用；
 *   都不是再按文本解析（data url / json / 纯 base64）。
 *
 * 输出两类结果：
 *   - Inline：已经拿到图片字节（可直接落盘）
 *   - Remote：只拿到一个 http(s) 地址（需要调用方再下载一次）
 * 调用方（Android 层 OkHttp）负责把 Remote 下载成字节，再统一落盘。
 */
object ImagePayloadExtractor {

    /** 单张 / 总解压上限，防 ZIP 炸弹把内存撑爆。 */
    const val MAX_ENTRY_BYTES = 40 * 1024 * 1024      // 单个文件 40MB
    const val MAX_TOTAL_BYTES = 128 * 1024 * 1024     // 解压总量 128MB

    /** 一张图片的来源。 */
    sealed class Source {
        /** 已经拿到字节。 */
        data class Inline(val bytes: ByteArray, val mime: String, val origin: String = "") : Source()
        /** 只拿到地址，需调用方再下载。 */
        data class Remote(val url: String) : Source()
    }

    /** 解析结果。kind 用于日志与说明，message 用于把中转站的报错回显给用户。 */
    data class Result(
        val kind: String,
        val images: List<Source> = emptyList(),
        /** 原始文本片段（解析失败时给用户看，比如中转站返回的错误 JSON）。 */
        val rawText: String? = null,
    ) {
        val isEmpty: Boolean get() = images.isEmpty()
    }

    /**
     * 解析一次 HTTP 响应。
     * @param contentType 响应头 Content-Type（可为空，仅作辅助，不作唯一依据）
     * @param body        响应体原始字节
     */
    fun extract(contentType: String?, body: ByteArray): Result {
        if (body.isEmpty()) return Result("empty")

        // 1) ZIP：文件头优先（中转站常把 zip 标成 json / octet-stream）
        if (isZip(body) || looksLikeZip(contentType)) {
            val imgs = unzipImages(body)
            if (imgs.isNotEmpty()) return Result("zip", imgs)
            // 是 zip 但里面没图片 —— 继续往下走，也许会落到文本解析
        }

        // 2) 直接是图片字节
        sniffMime(body)?.let { mime ->
            return Result("image", listOf(Source.Inline(body, mime, "body")))
        }

        // 3) 按文本解析
        val text = tryDecodeUtf8(body) ?: return Result("unknown")
        val t = text.trim()
        if (t.isEmpty()) return Result("empty")

        // 3.1 data URL（data:image/png;base64,....）
        decodeDataUrl(t)?.let { return Result("dataurl", listOf(it)) }

        // 3.2 JSON
        if (t.startsWith("{") || t.startsWith("[")) {
            val jsonResult = parseJsonText(t)
            if (jsonResult != null && !jsonResult.isEmpty) return jsonResult
        }

        // 3.3 纯 base64 文本
        if (Base64Codec.isLikelyBase64(t, 64)) {
            runCatching { Base64Codec.decode(t) }.getOrNull()?.let { bytes ->
                sniffMime(bytes)?.let { mime ->
                    return Result("base64", listOf(Source.Inline(bytes, mime, "text-base64")))
                }
            }
        }

        // 3.4 都不是：多半是中转站返回的报错文本
        return Result("text", rawText = t.take(800))
    }

    // ----------------------------------------------------------- magic bytes

    /** 通过文件头判断图片 MIME；不是已知图片返回 null。 */
    fun sniffMime(b: ByteArray): String? {
        if (b.isEmpty()) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b.size >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 &&
            u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A
        ) return "image/png"
        // JPEG: FF D8 FF
        if (b.size >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return "image/jpeg"
        // GIF: "GIF8"
        if (b.size >= 4 && u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38) return "image/gif"
        // WEBP: "RIFF"...."WEBP"
        if (b.size >= 12 && u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) return "image/webp"
        // BMP: "BM"
        if (b.size >= 2 && u(0) == 0x42 && u(1) == 0x4D) return "image/bmp"
        // AVIF / HEIF: 第 4~7 字节 "ftyp"，brand 里含 avif/heic/mif1
        if (b.size >= 12 && u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) {
            val brand = String(b, 8, 4, Charsets.US_ASCII)
            if (brand == "avif" || brand == "avis") return "image/avif"
            if (brand == "heic" || brand == "heix" || brand == "mif1") return "image/heic"
        }
        return null
    }

    /** ZIP 文件头：PK\x03\x04（普通）/ PK\x05\x06（空包）/ PK\x07\x08（分卷）。 */
    fun isZip(b: ByteArray): Boolean {
        if (b.size < 4) return false
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (u(0) != 0x50 || u(1) != 0x4B) return false
        return (u(2) == 0x03 && u(3) == 0x04) ||
            (u(2) == 0x05 && u(3) == 0x06) ||
            (u(2) == 0x07 && u(3) == 0x08)
    }

    private fun looksLikeZip(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return ct.contains("zip") || ct.contains("x-compressed") || ct.contains("x-zip")
    }

    // ----------------------------------------------------------- ZIP 解压

    /**
     * 内存解压：把 zip 里所有「图片」条目读出来。
     * 判断一个条目是不是图片：先按 magic bytes 认，认不出再按扩展名兜底。
     */
    private fun unzipImages(body: ByteArray): List<Source> {
        val out = ArrayList<Source>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(body)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name ?: ""
                        val data = readLimited(zis, MAX_ENTRY_BYTES)
                        if (data != null && data.isNotEmpty()) {
                            val mime = sniffMime(data) ?: mimeFromName(name)
                            if (mime != null) {
                                total += data.size
                                if (total <= MAX_TOTAL_BYTES) {
                                    out.add(Source.Inline(data, mime, "zip:$name"))
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // 损坏的 zip：把已读到的返回，不整单失败
        }
        return out
    }

    /** 读一个 zip 条目，最多 max 字节；超限视为可疑（防 zip 炸弹）返回 null。 */
    private fun readLimited(zis: ZipInputStream, max: Int): ByteArray? {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = zis.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun mimeFromName(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".bmp") -> "image/bmp"
            n.endsWith(".avif") -> "image/avif"
            n.endsWith(".heic") -> "image/heic"
            else -> null
        }
    }

    // ----------------------------------------------------------- data url

    /** 解析 `data:image/png;base64,xxxx`（base64 形式）。不是 data url 返回 null。 */
    fun decodeDataUrl(s: String): Source.Inline? {
        val str = s.trim()
        if (!str.startsWith("data:", ignoreCase = true)) return null
        val comma = str.indexOf(',')
        if (comma < 0) return null
        val header = str.substring(5, comma)          // image/png;base64
        val payload = str.substring(comma + 1)
        val isBase64 = header.contains("base64", ignoreCase = true)
        if (!isBase64) return null                    // 非 base64 的 data url 不当图片处理
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val bytes = runCatching { Base64Codec.decode(payload) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val realMime = sniffMime(bytes) ?: mime
        return Source.Inline(bytes, realMime, "dataurl")
    }

    // ----------------------------------------------------------- JSON

    /**
     * 从 JSON 文本里挖图片。递归遍历对象/数组，识别：
     *   - 字符串是 data url            -> Inline
     *   - 字符串是 http(s) 地址        -> Remote
     *   - 长 base64 且解码后是图片      -> Inline
     * 命中 Inline 优先（省一次下载）。
     */
    private fun parseJsonText(text: String): Result? {
        val inline = ArrayList<Source>()
        val remote = ArrayList<Source>()
        try {
            val trimmed = text.trim()
            val node: Any? = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            walk(node, inline, remote)
        } catch (_: Exception) {
            return null
        }
        return when {
            inline.isNotEmpty() -> Result("json", inline)
            remote.isNotEmpty() -> Result("json-url", remote)
            else -> null
        }
    }

    private fun walk(node: Any?, inline: MutableList<Source>, remote: MutableList<Source>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walk(node.opt(k), inline, remote)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walk(node.opt(i), inline, remote)
            }
            is String -> collectString(node, inline, remote)
            else -> Unit
        }
    }

    private fun collectString(raw: String, inline: MutableList<Source>, remote: MutableList<Source>) {
        val s = raw.trim()
        if (s.length < 8) return
        if (s.startsWith("data:", ignoreCase = true)) {
            decodeDataUrl(s)?.let { inline.add(it) }
            return
        }
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            // 常见图片扩展名才当图片地址，避免把无关链接（比如文档地址）也收进来
            val lower = s.lowercase()
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.contains("image") || lower.contains("/img")
            ) {
                remote.add(Source.Remote(s))
            }
            return
        }
        // 纯 base64：必须真解出图片文件头才认（避免把普通长字符串误判）
        if (s.length >= 64 && Base64Codec.isLikelyBase64(s, 64)) {
            val bytes = runCatching { Base64Codec.decode(s) }.getOrNull()
            if (bytes != null && bytes.size > 16) {
                val mime = sniffMime(bytes)
                if (mime != null) inline.add(Source.Inline(bytes, mime, "json-b64"))
            }
        }
    }

    // ----------------------------------------------------------- utf8

    /** 尝试按 UTF-8 解码成文本；明显是二进制（含 NUL）返回 null。 */
    private fun tryDecodeUtf8(b: ByteArray): String? {
        // 含 NUL 的几乎都是二进制，别浪费时间去当文本解析
        var nul = 0
        for (x in b) if (x.toInt() == 0) nul++
        if (nul > 0 && nul.toDouble() / b.size > 0.05) return null
        return try {
            String(b, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
