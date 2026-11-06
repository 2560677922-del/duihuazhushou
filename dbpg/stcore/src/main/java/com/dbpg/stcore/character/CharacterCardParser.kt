package com.dbpg.stcore.character

import com.dbpg.stcore.imagegen.Base64Codec
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * SillyTavern 角色卡解析器（只读）。
 *
 * 支持两种落盘形态：
 *  - **JSON 卡**：V1（字段在顶层）和 V2/V3（字段在 `data` 下，带 `spec`）
 *  - **PNG 卡**：图片的文本块里藏 base64 JSON（keyword = `chara` 或 `ccv3`），
 *    同时把整张图当作头像返回
 *
 * 设计原则：**任何解析失败都返回 null，不抛异常**。角色卡来自第三方，
 * 格式千奇百怪，一次坏卡不能把列表页拖崩。
 */
object CharacterCardParser {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 自动识别：先看 PNG 签名，否则当 JSON 文本。 */
    fun parse(bytes: ByteArray, fallbackName: String = ""): CharacterCard? {
        if (bytes.isEmpty()) return null
        return if (isPng(bytes)) parsePng(bytes) else parseJson(String(bytes, Charsets.UTF_8), fallbackName)
    }

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }

    // ---------------------------------------------------------------- JSON

    /**
     * 解析 JSON 卡文本。
     * @param fallbackName 卡里没写名字时用（一般是文件名），避免列表出现空标题
     */
    fun parseJson(json: String, fallbackName: String = "", avatarBytes: ByteArray? = null): CharacterCard? {
        val root = runCatching { JSONObject(json.trim().removePrefix("\uFEFF")) }.getOrNull() ?: return null

        // V2/V3：{ spec, spec_version, data:{...} }
        // 注意有些站导出的卡 spec 写了但 data 缺失，这里要退回顶层，不能直接判死
        val dataObj = root.optJSONObject("data")
        val spec = root.optString("spec", "")
        val v = if (dataObj != null && spec.startsWith("chara_card")) dataObj else root

        val name = v.optString("name", "").ifBlank { fallbackName }
        return CharacterCard(
            spec = spec,
            name = name,
            description = v.optString("description", ""),
            personality = v.optString("personality", ""),
            scenario = v.optString("scenario", ""),
            firstMessage = v.optString("first_mes", ""),
            messageExample = v.optString("mes_example", ""),
            creatorNotes = v.optString("creator_notes", ""),
            systemPrompt = v.optString("system_prompt", ""),
            postHistoryInstructions = v.optString("post_history_instructions", ""),
            alternateGreetings = stringList(v.optJSONArray("alternate_greetings")),
            tags = stringList(v.optJSONArray("tags")),
            creator = v.optString("creator", ""),
            characterVersion = v.optString("character_version", ""),
            avatarBytes = avatarBytes,
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    // ----------------------------------------------------------------- PNG

    /** 从 PNG 的文本块里取角色卡；取不到返回 null。 */
    fun parsePng(bytes: ByteArray): CharacterCard? {
        if (!isPng(bytes)) return null
        val card = readTextChunks(bytes) ?: return null
        val json = card.second
        // 有些卡把 PNG 头也一并 base64 了，解码前先判断是否真的是 base64 JSON
        val text = if (json.trimStart().startsWith("{")) json
        else String(Base64Codec.decode(json.trim()), Charsets.UTF_8)
        return parseJson(text, avatarBytes = bytes)
    }

    /**
     * 遍历 PNG chunk，返回第一个有效角色卡：Pair(关键字, JSON 文本)。
     *
     * 只看三种文本块：
     *  - tEXt：keyword\0text（未压缩，最常见）
     *  - zTXt：keyword\0method\0deflate(text)（图省体积的站会用）
     *  - iTXt：keyword\0flag\0method\0lang\0translated\0text（UTF-8，可能 deflate）
     */
    private fun readTextChunks(bytes: ByteArray): Pair<String, String>? {
        var pos = 8 // 跳过签名
        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            // 长度非法（负数/超长）直接放弃，防止坏文件把内存吃爆
            if (len < 0 || len > bytes.size - pos - 12) return null
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (type == "IEND") return null

            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                val hit = parseTextChunk(type, bytes, dataStart, len)
                if (hit != null) return hit
            }
            pos = dataStart + len + 4 // +4 是 CRC
        }
        return null
    }

    private fun parseTextChunk(type: String, bytes: ByteArray, start: Int, len: Int): Pair<String, String>? {
        val end = start + len
        val zero = indexOfZero(bytes, start, end)
        if (zero < 0) return null
        val keyword = String(bytes, start, zero - start, Charsets.ISO_8859_1)
        if (keyword != "chara" && keyword != "ccv3") return null

        val body = when (type) {
            "tEXt" -> decodeText(bytes.copyOfRange(zero + 1, end))
            "zTXt" -> {
                if (zero + 2 > end) return null           // keyword\0 + compressionMethod
                decodeText(inflate(bytes.copyOfRange(zero + 2, end)))
            }
            else -> readITxt(bytes, zero + 1, end)
        }
        return if (body.isNullOrBlank()) null else Pair(keyword, body)
    }

    /**
     * 文本块解码。
     *
     * 规范说 tEXt 是 Latin-1，但现实里绝大多数卡（尤其中文卡）直接塞 UTF-8；
     * 而 base64 是纯 ASCII，两种编码等价。所以统一先试 UTF-8，
     * 出现替换符（U+FFFD）说明不是合法 UTF-8，再退回单字节解码。
     */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.indexOf('\uFFFD') >= 0) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /** iTXt：compressionFlag(1) compressionMethod(1) langTag\0 translated\0 text */
    private fun readITxt(bytes: ByteArray, from: Int, end: Int): String? {
        if (from + 2 > end) return null
        val compressed = bytes[from].toInt() == 1
        var p = from + 2
        val langEnd = indexOfZero(bytes, p, end)
        if (langEnd < 0) return null
        p = langEnd + 1
        val transEnd = indexOfZero(bytes, p, end)
        if (transEnd < 0) return null
        p = transEnd + 1
        val textBytes = bytes.copyOfRange(p, end)
        return if (compressed) String(inflate(textBytes), Charsets.UTF_8)
        else String(textBytes, Charsets.UTF_8)
    }

    private fun inflate(raw: ByteArray): ByteArray {
        // PNG 的 zTXt/iTXt 用的是 zlib 流，InflaterInputStream 默认就按 zlib 解，
        // 比自己管理 Inflater 的 needsInput/finished 状态更不容易出错。
        return try {
            java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(raw)).use { input ->
                val out = ByteArrayOutputStream(raw.size * 4)
                val buf = ByteArray(8 * 1024)
                // 上限 8MB，防止压缩炸弹；正常角色卡远小于这个值
                while (out.size() < 8 * 1024 * 1024) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    private fun indexOfZero(bytes: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or
            ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or
            (b[p + 3].toInt() and 0xFF)
}
