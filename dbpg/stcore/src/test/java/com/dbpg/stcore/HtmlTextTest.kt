package com.dbpg.stcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 换行转 <br> 的规则。
 *
 * 这些用例对应真实踩过的坑：卡片里所有段落挤成一行、CSS 被换行搞坏、
 * 块级标签之间凭空多出空行。
 */
class HtmlTextTest {

    @Test
    fun `没有换行时原样返回`() {
        val s = "一句话，没有空行"
        assertSame(s, htmlifyNewlines(s))
    }

    @Test
    fun `单个换行变 br`() {
        assertEquals("第一行<br>第二行", htmlifyNewlines("第一行\n第二行"))
    }

    @Test
    fun `空行变两个 br`() {
        assertEquals("第一段<br><br>第二段", htmlifyNewlines("第一段\n\n第二段"))
    }

    @Test
    fun `三连换行也压成一个空行`() {
        assertEquals("A<br><br>B", htmlifyNewlines("A\n\n\nB"))
    }

    @Test
    fun `每行两端空白被去掉`() {
        assertEquals("甲<br>乙", htmlifyNewlines("  甲  \n\t乙\t"))
    }

    @Test
    fun `内联标签之间的空行节点要变 br`() {
        // 段落被内联标签隔开时，空行是独立文本节点 `\n\n`
        assertEquals(
            "<span>甲</span><br><em>乙</em>",
            htmlifyNewlines("<span>甲</span>\n\n<em>乙</em>"),
        )
    }

    @Test
    fun `块级标签之间的纯缩进不产生 br`() {
        val src = "<div>内容</div>\n<div>另一块</div>"
        // 两个标签之间只有一个 \n，是缩进不是空行
        assertEquals("<div>内容</div>\n<div>另一块</div>", htmlifyNewlines(src))
    }

    @Test
    fun `块内首尾的单换行被删掉`() {
        val src = "<div>\n文字\n</div>"
        assertEquals("<div>文字</div>", htmlifyNewlines(src))
    }

    @Test
    fun `内联标签前的空行必须保留`() {
        // 这是截图里"两段挤在一起"的元凶：
        // 正文 …另一条路。\n\n 然后是 <em>内心</em>
        val src = "他说完。\n\n<em>她没回应。</em>"
        assertEquals("他说完。<br><em>她没回应。</em>", htmlifyNewlines(src))
    }

    @Test
    fun `style 内部换行原样保留`() {
        val css = "<style>\n.a{color:red}\n.b{color:#fff}\n</style>"
        assertEquals(css, htmlifyNewlines(css))
    }

    @Test
    fun `script 内部换行原样保留`() {
        val js = "<script>\nvar a = 1;\n// 注释\nif (a) { a++; }\n</script>"
        assertEquals(js, htmlifyNewlines(js))
    }

    @Test
    fun `pre 内部换行原样保留`() {
        val pre = "<pre>\n第一行\n第二行\n</pre>"
        assertEquals(pre, htmlifyNewlines(pre))
    }

    @Test
    fun `style 之后的正文照常转换`() {
        val src = "<style>.a{color:red}</style>\n正文一\n\n正文二"
        val out = htmlifyNewlines(src)
        assertEquals("<style>.a{color:red}</style>正文一<br><br>正文二", out)
    }

    @Test
    fun `属性里的换行不会破坏标签`() {
        val src = "<div class=\"a\nb\">文字</div>"
        // 标签整体原样，不去动属性里的换行（浏览器会自己规范化）
        assertEquals(src, htmlifyNewlines(src))
    }

    @Test
    fun `幂等`() {
        val src = "甲\n\n乙\n\n<em>丙</em>"
        val once = htmlifyNewlines(src)
        assertEquals(once, htmlifyNewlines(once))
    }

    @Test
    fun `空串安全`() {
        assertEquals("", htmlifyNewlines(""))
        assertEquals("   ", htmlifyNewlines("   "))
    }

    @Test
    fun `CRLF 等价于 LF`() {
        assertEquals("甲<br>乙", htmlifyNewlines("甲\r\n乙"))
    }
}
