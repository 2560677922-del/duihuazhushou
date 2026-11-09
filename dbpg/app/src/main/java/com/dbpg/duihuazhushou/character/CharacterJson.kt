package com.dbpg.duihuazhushou.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dbpg.stcore.character.CharacterCard
import com.dbpg.stcore.character.CharacterCardParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 把原生解析出来的卡还原成 ST V2 JSON，交给网页侧导入。
 *
 * 为什么要绕这一圈：网页的角色库存在 IndexedDB 里，原生读不到；
 * 而网页的 `assets.importCharacter()` 已经处理好建档/存档/选中。
 * 传标准 V2 JSON 过去，等于让网页"重新导入一次"，链路最短、最不容易错。
 */
object CharacterJson {

    fun toV2(card: CharacterCard): String {
        val data = JSONObject()
            .put("name", card.name)
            .put("description", card.description)
            .put("personality", card.personality)
            .put("scenario", card.scenario)
            .put("first_mes", card.firstMessage)
            .put("mes_example", card.messageExample)
            .put("creator_notes", card.creatorNotes)
            .put("system_prompt", card.systemPrompt)
            .put("post_history_instructions", card.postHistoryInstructions)
            .put("alternate_greetings", JSONArray(card.alternateGreetings))
            .put("tags", JSONArray(card.tags))
            .put("creator", card.creator)
            .put("character_version", card.characterVersion)

        return JSONObject()
            .put("spec", "chara_card_v2")
            .put("spec_version", "2.0")
            .put("data", data)
            .toString()
    }

    /**
     * 写成 **PNG 角色卡**：把 V2 JSON 塞进 PNG 的 `tEXt` 文本块（keyword=`chara`），
     * 和 SillyTavern 的导出格式完全一致 —— 网页版 / 其它 ST 客户端能直接读。
     *
     * 关键约束：头像**原样嵌进去，绝不重新编码**。
     *   - 头像本就是 PNG → 直接复用它的字节，只在 IHDR 之后插一个 tEXt 块；
     *   - 头像不是 PNG（jpg/webp…）→ 用 Bitmap 无损解码后再压成 PNG
     *     （PNG 是无损格式，像素不丢；顺便丢掉了原图的 EXIF 等元数据，反而更干净）。
     *
     * 返回 null 表示头像处理失败（调用方应降级成纯 JSON 卡，不丢文字字段）。
     */
    fun toPngCard(card: CharacterCard, avatar: ByteArray): ByteArray? {
        val clean = try {
            if (CharacterCardParser.isPng(avatar)) stripCharChunks(avatar)
            else convertToPng(avatar)
        } catch (_: Exception) {
            null
        } ?: return null
        return embedCharChunk(clean, toV2(card))
    }

    // ---------------------------------------------------------- PNG 卡拼装

    /** 在 IHDR 之后插一个 `chara` 文本块；IHDR 必须是 PNG 的第一块，插它后面永远合法。 */
    private fun embedCharChunk(png: ByteArray, json: String): ByteArray {
        val afterIhdr = PNG_SIG.size + 4 + 4 + 13 + 4 // 签名 + IHDR(len+type+data+crc)
        if (png.size < afterIhdr) return png
        val chunk = textChunk("chara", json)
        return png.copyOfRange(0, afterIhdr) + chunk + png.copyOfRange(afterIhdr, png.size)
    }

    /** 去掉已有的 `chara`/`ccv3` 文本块，避免反复编辑时旧块被一层层嵌套进去。 */
    private fun stripCharChunks(png: ByteArray): ByteArray {
        if (!isPngSig(png)) return png
        var pos = PNG_SIG.size
        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        while (pos + 8 <= png.size) {
            val len = readInt(png, pos)
            if (len < 0 || len > png.size - pos - 12) break
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val dataStart = pos + 8
            val end = dataStart + len + 4 // + CRC
            if ((type == "tEXt" || type == "zTXt" || type == "iTXt") && isCharChunk(png, dataStart, end)) {
                pos = end
                continue
            }
            out.write(png, pos, end - pos)
            pos = end
        }
        return out.toByteArray()
    }

    private fun isCharChunk(png: ByteArray, dataStart: Int, end: Int): Boolean {
        val zero = indexOfZero(png, dataStart, end)
        if (zero < 0) return false
        val kw = String(png, dataStart, zero - dataStart, Charsets.ISO_8859_1)
        return kw == "chara" || kw == "ccv3"
    }

    private fun convertToPng(src: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size)
            ?: throw IllegalStateException("头像解码失败")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** 构造一个 tEXt 块：length + "tEXt" + keyword\0text + CRC32。 */
    private fun textChunk(keyword: String, text: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val txt = text.toByteArray(Charsets.UTF_8)
        val data = kw + byteArrayOf(0) + txt
        val type = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().also { it.update(type + data) }.value.toInt()
        val buf = ByteArray(4 + 4 + data.size + 4)
        writeInt(buf, 0, data.size)
        System.arraycopy(type, 0, buf, 4, 4)
        System.arraycopy(data, 0, buf, 8, data.size)
        writeInt(buf, 8 + data.size, crc)
        return buf
    }

    private fun isPngSig(b: ByteArray): Boolean =
        b.size >= PNG_SIG.size && PNG_SIG.indices.all { b[it] == PNG_SIG[it] }

    private fun indexOfZero(b: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (b[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or
            ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or
            (b[p + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 24).toByte()
        b[p + 1] = (v ushr 16).toByte()
        b[p + 2] = (v ushr 8).toByte()
        b[p + 3] = v.toByte()
    }

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
