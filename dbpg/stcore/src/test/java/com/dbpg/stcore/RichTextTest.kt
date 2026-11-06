package com.dbpg.stcore

import com.dbpg.stcore.richtext.RichStyle
import com.dbpg.stcore.richtext.RichTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {

    @Test
    fun `粗体`() {
        val r = RichTextParser.parse("**你好**世界")
        assertEquals("你好世界", r.plain)
        assertEquals(1, r.spans.size)
        assertEquals(0, r.spans[0].start)
        assertEquals(2, r.spans[0].end)
        assertEquals(RichStyle.BOLD, r.spans[0].style)
    }

    @Test
    fun `斜体`() {
        val r = RichTextParser.parse("*斜*体")
        assertEquals("斜体", r.plain)
        assertEquals(RichStyle.ITALIC, r.spans[0].style)
        assertEquals(0, r.spans[0].start)
        assertEquals(1, r.spans[0].end)
    }

    @Test
    fun `代码内部不再解析`() {
        val r = RichTextParser.parse("`**x**`")
        assertEquals("**x**", r.plain)
        assertEquals(1, r.spans.size)
        assertEquals(RichStyle.CODE, r.spans[0].style)
    }

    @Test
    fun `删除线`() {
        val r = RichTextParser.parse("a~~删~~b")
        assertEquals("a删b", r.plain)
        assertEquals(RichStyle.STRIKE, r.spans[0].style)
        assertEquals(1, r.spans[0].start)
        assertEquals(2, r.spans[0].end)
    }

    @Test
    fun `markdown 链接`() {
        val r = RichTextParser.parse("点[这里](https://x.com/a)看")
        assertEquals("点这里看", r.plain)
        assertEquals(RichStyle.LINK, r.spans[0].style)
        assertEquals("https://x.com/a", r.spans[0].url)
        assertEquals(1, r.spans[0].start)
        assertEquals(3, r.spans[0].end)
    }

    @Test
    fun `裸链接自动识别`() {
        val r = RichTextParser.parse("看 https://a.com/b 这里")
        assertTrue(r.plain.contains("https://a.com/b"))
        val link = r.spans.first { it.style == RichStyle.LINK }
        assertEquals("https://a.com/b", link.url)
    }

    @Test
    fun `裸链接尾部标点被剔除`() {
        val r = RichTextParser.parse("地址 https://a.com/x.")
        val link = r.spans.first { it.style == RichStyle.LINK }
        assertEquals("https://a.com/x", link.url)
        // 句号仍作为普通文字保留
        assertTrue(r.plain.endsWith("."))
    }

    @Test
    fun `HTML 标签归一`() {
        val r = RichTextParser.parse("<b>粗</b>和<i>斜</i>")
        assertEquals("粗和斜", r.plain)
        assertEquals(RichStyle.BOLD, r.spans[0].style)
        assertEquals(RichStyle.ITALIC, r.spans[1].style)
    }

    @Test
    fun `HTML br 转换换行`() {
        val r = RichTextParser.parse("第一行<br>第二行")
        assertEquals("第一行\n第二行", r.plain)
    }

    @Test
    fun `未闭合标记原样保留`() {
        val r = RichTextParser.parse("**abc 没闭合")
        assertEquals("**abc 没闭合", r.plain)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `算式里的星号不当斜体`() {
        val r = RichTextParser.parse("2 * 3 * 4")
        assertEquals("2 * 3 * 4", r.plain)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `多个样式区间按顺序`() {
        val r = RichTextParser.parse("**a** and *b*")
        assertEquals("a and b", r.plain)
        assertEquals(2, r.spans.size)
        assertEquals(RichStyle.BOLD, r.spans[0].style)
        assertEquals(RichStyle.ITALIC, r.spans[1].style)
        assertEquals(6, r.spans[1].start)
    }

    @Test
    fun `空串与纯文本`() {
        assertEquals("", RichTextParser.parse("").plain)
        val r = RichTextParser.parse("就是普通一句话")
        assertEquals("就是普通一句话", r.plain)
        assertTrue(r.spans.isEmpty())
    }
}
