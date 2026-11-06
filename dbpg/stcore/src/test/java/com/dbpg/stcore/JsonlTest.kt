package com.dbpg.stcore

import com.dbpg.stcore.jsonl.SillyTavernChatLogReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlTest {

    @Test
    fun `解析正常 jsonl 三种角色`() {
        val text = """
            {"name":"You","is_user":true,"is_system":false,"send_date":"2024-05-01T12:00:00.000Z","mes":"你好啊"}
            {"name":"Alice","is_user":false,"is_system":false,"send_date":"2024-05-01T12:00:05.000Z","mes":"你也是"}
            {"name":"System","is_user":false,"is_system":true,"send_date":"2024-05-01T12:00:06.000Z","mes":"场景已切换"}
        """.trimIndent()
        val list = SillyTavernChatLogReader.parse(text)
        assertEquals(3, list.size)
        assertEquals("user", list[0].role)
        assertEquals("你好啊", list[0].content)
        assertEquals("You", list[0].name)
        assertEquals("assistant", list[1].role)
        assertEquals("Alice", list[1].name)
        assertEquals("system", list[2].role)
        assertTrue(list[2].isSystem)
    }

    @Test
    fun `元数据行被跳过`() {
        val text = """
            {"name":"Alice","is_user":false,"mes":"只有这一条消息"}
            {"user_name":"You","character_name":"Alice","chat_metadata":{"world":"x"}}
        """.trimIndent()
        val list = SillyTavernChatLogReader.parse(text)
        assertEquals(1, list.size)
        assertEquals("只有这一条消息", list[0].content)
    }

    @Test
    fun `脏行与空行被跳过不整体失败`() {
        val text = "\n" +
            "{\"name\":\"A\",\"is_user\":false,\"mes\":\"ok1\"}\n" +
            "这不是 JSON\n" +
            "{\"name\":\"B\",\"is_user\":true,\"mes\":\"ok2\"}\n"
        val list = SillyTavernChatLogReader.parse(text)
        assertEquals(2, list.size)
        assertEquals("ok1", list[0].content)
        assertEquals("ok2", list[1].content)
    }

    @Test
    fun `mes 缺失时回退到当前 swipe`() {
        val line = """{"name":"A","is_user":false,"swipes":["回复A","回复B"],"swipe_id":1}"""
        val e = SillyTavernChatLogReader.parseLine(line)!!
        assertEquals("回复B", e.content)
        assertEquals(listOf("回复A", "回复B"), e.swipes)
        assertEquals(1, e.swipeId)
    }

    @Test
    fun `extra 与名字读取`() {
        val line = """{"name":"A","is_user":false,"mes":"hi","extra":{"model":"gpt","img":1}}"""
        val e = SillyTavernChatLogReader.parseLine(line)!!
        assertEquals("gpt", e.extra["model"])
        assertEquals(1, e.extra["img"])
    }

    @Test
    fun `时间戳 ISO 带 Z 能解析`() {
        val ms = SillyTavernChatLogReader.parseTimestamp("2024-05-01T12:00:00.000Z")
        // 2024-05-01T12:00:00Z
        assertEquals(1714564800000L, ms)
    }

    @Test
    fun `时间戳老格式 at 分隔能解析`() {
        assertTrue(SillyTavernChatLogReader.parseTimestamp("2024-05-01@12:00") != null)
    }

    @Test
    fun `时间戳数字按秒或毫秒处理`() {
        assertEquals(1714564800000L, SillyTavernChatLogReader.parseTimestamp(1714564800L))
        assertEquals(1714564800000L, SillyTavernChatLogReader.parseTimestamp(1714564800000L))
    }

    @Test
    fun `时间戳非法返回 null`() {
        assertNull(SillyTavernChatLogReader.parseTimestamp("不是时间"))
        assertNull(SillyTavernChatLogReader.parseTimestamp(null))
    }

    @Test
    fun `role 字段正确归一`() {
        val u = SillyTavernChatLogReader.parseLine("""{"is_user":true,"mes":"x"}""")!!
        val a = SillyTavernChatLogReader.parseLine("""{"is_user":false,"mes":"x"}""")!!
        val s = SillyTavernChatLogReader.parseLine("""{"is_system":true,"mes":"x"}""")!!
        assertEquals("user", u.role)
        assertEquals("assistant", a.role)
        assertEquals("system", s.role)
    }
}
