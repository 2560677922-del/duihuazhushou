package com.dbpg.stcore

import com.dbpg.stcore.character.CharacterCardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * 角色卡解析测试。
 *
 * 重点覆盖第三方卡的"野生格式"：V1 老卡、spec 写了但 data 缺失、
 * PNG 里用 tEXt / zTXt 藏 base64、以及根本不是卡的图。
 */
class CharacterCardTest {

    private val v2Json = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "薇薇",
            "description": "雨夜书店的店主。",
            "personality": "安静\n话少",
            "scenario": "深夜的书店",
            "first_mes": "「欢迎光临……外面雨很大吧。」",
            "mes_example": "<START>\n{{user}}: 你好\n{{char}}: 嗯。",
            "creator_notes": "测试用",
            "system_prompt": "保持冷淡",
            "post_history_instructions": "别忘了称呼",
            "alternate_greetings": ["「又是你。」", "", "「伞借你。」"],
            "tags": ["治愈", "", "日常"],
            "creator": "dbpg",
            "character_version": "1.2"
          }
        }
    """.trimIndent()

    @Test
    fun `解析 V2 卡全部字段`() {
        val card = CharacterCardParser.parseJson(v2Json)
        assertNotNull(card)
        card!!
        assertEquals("chara_card_v2", card.spec)
        assertEquals("薇薇", card.name)
        assertEquals("雨夜书店的店主。", card.description)
        assertEquals("「欢迎光临……外面雨很大吧。」", card.firstMessage)
        assertEquals("保持冷淡", card.systemPrompt)
        // 空字符串要被过滤掉，不能占一个 swipe 位
        assertEquals(listOf("「又是你。」", "「伞借你。」"), card.alternateGreetings)
        assertEquals(listOf("治愈", "日常"), card.tags)
    }

    @Test
    fun `greetings 把 first_mes 排在备用开场白前面`() {
        val card = CharacterCardParser.parseJson(v2Json)!!
        assertEquals(3, card.greetings.size)
        assertEquals("「欢迎光临……外面雨很大吧。」", card.greetings[0])
    }

    @Test
    fun `subtitle 优先取 personality 首行`() {
        assertEquals("安静", CharacterCardParser.parseJson(v2Json)!!.subtitle)
    }

    @Test
    fun `解析 V1 老卡（字段在顶层）`() {
        val card = CharacterCardParser.parseJson("""{"name":"旧卡","description":"d","first_mes":"hi","mes_example":"e"}""")
        assertNotNull(card)
        assertEquals("旧卡", card!!.name)
        assertEquals("", card.spec)
        assertEquals("hi", card.firstMessage)
    }

    @Test
    fun `spec 存在但 data 缺失时退回顶层`() {
        val card = CharacterCardParser.parseJson("""{"spec":"chara_card_v2","name":"兜底","first_mes":"yo"}""")
        assertEquals("兜底", card?.name)
    }

    @Test
    fun `没写 name 时用文件名兜底`() {
        val card = CharacterCardParser.parseJson("""{"first_mes":"hi"}""", fallbackName = "未命名.png")
        assertEquals("未命名.png", card?.name)
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(CharacterCardParser.parseJson("{ 这不是 json "))
        assertNull(CharacterCardParser.parseJson(""))
    }

    @Test
    fun `识别 PNG 并读出 tEXt 里的 base64 卡`() {
        val png = buildPng("tEXt", "chara", v2Json)
        assertTrue(CharacterCardParser.isPng(png))
        val card = CharacterCardParser.parsePng(png)
        assertNotNull(card)
        assertEquals("薇薇", card!!.name)
        // 头像要带回去，列表页直接用，避免二次读文件
        assertEquals(png.size, card.avatarBytes?.size)
    }

    @Test
    fun `识别 zTXt 里的压缩卡（UTF-8 中文不乱码）`() {
        val png = buildPng("zTXt", "chara", v2Json)
        assertEquals("薇薇", CharacterCardParser.parsePng(png)?.name)
    }

    @Test
    fun `ccv3 关键字也能读`() {
        val png = buildPng("tEXt", "ccv3", v2Json)
        assertEquals("薇薇", CharacterCardParser.parsePng(png)?.name)
    }

    @Test
    fun `普通头像 PNG 没有文本块返回 null`() {
        assertNull(CharacterCardParser.parsePng(buildPng(null, "", "")))
    }

    @Test
    fun `不是 PNG 的字节不会被当成 PNG 卡`() {
        val fake = "not a png at all".toByteArray()
        assertFalse(CharacterCardParser.isPng(fake))
        assertNull(CharacterCardParser.parsePng(fake))
    }

    // ------------------------------------------------------------ 构造 PNG

    /**
     * 造一张最小 PNG：签名 + 一个 IHDR + 一个文本块 + IEND。
     * CRC 不参与解析（解析器只按长度跳块），这里填 0 即可。
     */
    private fun buildPng(type: String?, keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR：13 字节占位，解析器不看内容，只按长度跳过
        writeChunk(out, "IHDR", ByteArray(13))

        if (type != null) {
            val body = ByteArrayOutputStream()
            body.write(keyword.toByteArray(Charsets.ISO_8859_1))
            body.write(0)
            if (type == "tEXt") {
                body.write(java.util.Base64.getEncoder().encode(text.toByteArray(Charsets.UTF_8)))
            } else {
                body.write(0) // compression method = deflate
                body.write(deflate(text.toByteArray(Charsets.UTF_8)))
            }
            writeChunk(out, type, body.toByteArray())
        }

        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC 占位
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }
}
