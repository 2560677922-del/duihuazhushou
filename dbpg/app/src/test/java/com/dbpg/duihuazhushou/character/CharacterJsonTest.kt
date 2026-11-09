package com.dbpg.duihuazhushou.character

import com.dbpg.stcore.character.CharacterCard
import com.dbpg.stcore.character.CharacterCardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 角色卡写出 / 回读的回归测试。
 *
 * 覆盖编辑器落盘的两条格式：
 *   - 纯 V2 JSON（无头像）
 *   - PNG 卡（V2 JSON 嵌进 tEXt 块，头像原样保留）
 *
 * 重点锁住三个坑：
 *   1. PNG 卡写出来必须能被列表页的同一个解析器读回去（字段一一对应）；
 *   2. 反复编辑时旧 `chara` 块要被剥掉，不能一层层嵌套（文件越滚越大、字段串位）；
 *   3. 头像字节不被重新编码 —— 嵌进去之后图片块（IDAT）保持原样。
 */
class CharacterJsonTest {

    private val card = CharacterCard(
        spec = "chara_card_v2",
        name = "测试猫娘",
        description = "一只会说话的猫。",
        personality = "傲娇",
        scenario = "雨夜的便利店",
        firstMessage = "哼，才不是特意等你的。",
        messageExample = "<START>\n{{user}}: 你好\n{{char}}: 喵。",
        alternateGreetings = listOf("第二条开场白", "第三条开场白"),
        tags = listOf("猫娘", "中文"),
        creator = "tester",
        characterVersion = "1.2",
    )

    @Test
    fun v2JsonRoundTrip() {
        val back = CharacterCardParser.parseJson(CharacterJson.toV2(card), "fallback")
        assertNotNull(back)
        assertEquals(card.name, back!!.name)
        assertEquals(card.description, back.description)
        assertEquals(card.personality, back.personality)
        assertEquals(card.scenario, back.scenario)
        assertEquals(card.firstMessage, back.firstMessage)
        assertEquals(card.messageExample, back.messageExample)
        assertEquals(card.alternateGreetings, back.alternateGreetings)
        assertEquals(card.tags, back.tags)
        assertEquals(card.creator, back.creator)
        assertEquals(card.characterVersion, back.characterVersion)
    }

    @Test
    fun pngCardRoundTrip() {
        val avatar = minimalPng()
        val png = CharacterJson.toPngCard(card, avatar)
        assertNotNull("PNG 头像应原样可嵌，不该失败", png)

        val back = CharacterCardParser.parse(png!!)
        assertNotNull(back)
        assertEquals(card.name, back!!.name)
        assertEquals(card.description, back.description)
        assertEquals(card.firstMessage, back.firstMessage)
        assertEquals(card.alternateGreetings, back.alternateGreetings)
        assertEquals(card.tags, back.tags)
        assertEquals(card.characterVersion, back.characterVersion)
        // 解析器应把整张 PNG 当头像带回来
        assertNotNull(back.avatarBytes)
        assertTrue("回读出的头像必须是合法 PNG", CharacterCardParser.isPng(back.avatarBytes!!))
    }

    @Test
    fun repeatedEditDoesNotNestOldChunks() {
        val avatar = minimalPng()
        val first = CharacterJson.toPngCard(card, avatar)!!
        assertEquals("第一次嵌入只有一个 chara 块", 1, countCharChunks(first))

        // 第二次编辑：拿第一次的结果当头像再嵌一遍（模拟"编辑已有 PNG 卡"）
        val card2 = card.copy(name = "改名后的猫娘")
        val second = CharacterJson.toPngCard(card2, first)!!
        assertEquals("反复编辑不得嵌套旧块", 1, countCharChunks(second))

        val back = CharacterCardParser.parse(second)
        assertNotNull(back)
        assertEquals("必须读到最上面那个（最新的）chara 块", "改名后的猫娘", back!!.name)
        assertEquals(card2.description, back.description)
    }

    @Test
    fun imageBytesAreNotReencoded() {
        val avatar = minimalPng()
        val png = CharacterJson.toPngCard(card, avatar)!!
        // 嵌入只是插入了一个 tEXt 块：去掉它之后，其余字节必须和原头像完全一致
        val stripped = stripForTest(png)
        assertTrue("图片字节被改动过（不应重编码）", stripped.contentEquals(avatar))
    }

    @Test
    fun emptyFieldsStillParse() {
        val blank = CharacterCard(name = "空卡")
        val png = CharacterJson.toPngCard(blank, minimalPng())
        assertNotNull(png)
        val back = CharacterCardParser.parse(png!!)
        assertNotNull(back)
        assertEquals("空卡", back!!.name)
        assertEquals("", back.description)
        assertTrue(back.alternateGreetings.isEmpty())
    }

    // ------------------------------------------------------------ 工具

    /** 最小合法 PNG：签名 + IHDR(1x1) + IDAT(空) + IEND。CRC 用 0，解析器不校验。 */
    private fun minimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, 1) // width
        writeInt(ihdr, 4, 1) // height
        ihdr[8] = 8  // bit depth
        ihdr[9] = 6  // color type RGBA
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", ByteArray(0))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); writeInt(len, 0, data.size)
        out.write(len)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // CRC 占位
    }

    private fun writeInt(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte()
        b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte()
    }

    private fun countCharChunks(png: ByteArray): Int {
        var pos = 8
        var n = 0
        while (pos + 8 <= png.size) {
            val len = readInt(png, pos)
            if (len < 0 || len > png.size - pos - 12) break
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val end = dataStart + len + 4
            if (type == "tEXt") {
                var z = dataStart
                while (z < end && png[z].toInt() != 0) z++
                if (z < end) {
                    val kw = String(png, dataStart, z - dataStart, Charsets.ISO_8859_1)
                    if (kw == "chara" || kw == "ccv3") n++
                }
            }
            pos = end
        }
        return n
    }

    /** 把 chara 文本块剔除，用于比对"除嵌入块之外图片字节原样不动"。 */
    private fun stripForTest(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(png, 0, 8)
        var pos = 8
        while (pos + 8 <= png.size) {
            val len = readInt(png, pos)
            if (len < 0 || len > png.size - pos - 12) break
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val end = dataStart + len + 4
            val isChar = if (type == "tEXt") {
                var z = dataStart
                while (z < end && png[z].toInt() != 0) z++
                z < end && String(png, dataStart, z - dataStart, Charsets.ISO_8859_1) == "chara"
            } else false
            if (!isChar) out.write(png, pos, end - pos)
            pos = end
        }
        return out.toByteArray()
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or
            ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or
            (b[p + 3].toInt() and 0xFF)
}
