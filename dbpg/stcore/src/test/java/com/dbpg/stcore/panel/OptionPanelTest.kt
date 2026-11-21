package com.dbpg.stcore.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionPanelTest {

    // ------------------------------------------------------------ 基本解析

    @Test
    fun `没有标签时原文返回`() {
        val r = parseOptionPanel("*阿岚笑了笑* 今天天气不错。")
        assertNull(r.panel)
        assertEquals("*阿岚笑了笑* 今天天气不错。", r.rest)
    }

    @Test
    fun `option 项和裸行都算选项`() {
        val r = parseOptionPanel(
            """
            剧情还在继续。
            <options>
            <option>推开门进去</option>
            先观察一下
            <option>转身离开</option>
            </options>
            """.trimIndent(),
        )
        val p = r.panel!!
        assertEquals(listOf("推开门进去", "先观察一下", "转身离开"), p.options)
        assertEquals("剧情还在继续。", r.rest)
    }

    @Test
    fun `裸行去掉列表前缀`() {
        val r = parseOptionPanel("<options>\n- 选项甲\n* 选项乙\n1. 选项丙\n2) 选项丁\n</options>")
        assertEquals(listOf("选项甲", "选项乙", "选项丙", "选项丁"), r.panel!!.options)
    }

    @Test
    fun `summary 和 comment 各收各的`() {
        val r = parseOptionPanel(
            """
            正文。
            <summary>他们终于找到了密道。</summary>
            <comment>喵呜~ 记得保存进度</comment>
            """.trimIndent(),
        )
        val p = r.panel!!
        assertEquals(listOf("他们终于找到了密道。"), p.summaries)
        assertEquals(listOf("喵呜~ 记得保存进度"), p.comments)
        assertTrue(p.options.isEmpty())
        assertEquals("正文。", r.rest)
    }

    @Test
    fun `三类标签混排全收集`() {
        val r = parseOptionPanel(
            """
            <comment>先说一句</comment>
            <options><option>甲</option></options>
            <summary>摘要</summary>
            <options><option>乙</option></options>
            """.trimIndent(),
        )
        val p = r.panel!!
        assertEquals(listOf("甲", "乙"), p.options)
        assertEquals(listOf("摘要"), p.summaries)
        assertEquals(listOf("先说一句"), p.comments)
        assertEquals("", r.rest)
    }

    // ------------------------------------------------------------ HTML 清洗

    @Test
    fun `标签内容里的 HTML 剥掉只留文字`() {
        val r = parseOptionPanel(
            "<options><option><b>勇敢</b>地<br>往前</option></options>",
        )
        assertEquals(listOf("勇敢地\n往前"), r.panel!!.options)
    }

    @Test
    fun `script 和 style 整块丢弃`() {
        val r = parseOptionPanel(
            "<comment><style>.a{color:red}</style>留言<script>alert(1)</script>在此</comment>",
        )
        assertEquals(listOf("留言在此"), r.panel!!.comments)
    }

    @Test
    fun `HTML 实体解码`() {
        val r = parseOptionPanel("<summary>鱼 &amp; 猫 &nbsp; &lt;tag&gt;</summary>")
        assertEquals(listOf("鱼 & 猫   <tag>"), r.panel!!.summaries)
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `未闭合标签延续到末尾`() {
        val r = parseOptionPanel("前文\n<options>\n<option>甲</option>\n乙")
        assertEquals(listOf("甲", "乙"), r.panel!!.options)
        assertEquals("前文", r.rest)
    }

    @Test
    fun `空块不算有内容`() {
        val r = parseOptionPanel("<options></options><summary>  </summary>正文")
        assertNull(r.panel)
        assertEquals("正文", r.rest)
    }

    @Test
    fun `大写标签也认`() {
        val r = parseOptionPanel("<OPTIONS><OPTION>甲</OPTION></OPTIONS>")
        assertEquals(listOf("甲"), r.panel!!.options)
    }

    @Test
    fun `详情里的 summary 不是面板摘要`() {
        // <details><summary>标题</summary>内容</details> 是折叠块，不是面板标签。
        // 整块留给正文（FoldParser 渲染折叠），面板不收。
        val r = parseOptionPanel("<details><summary>折叠标题</summary>折叠内容</details>")
        assertNull(r.panel)
        assertTrue(r.rest.contains("折叠标题"))
        assertTrue(r.rest.contains("折叠内容"))
    }

    @Test
    fun `独立 summary 和折叠标题并存`() {
        val r = parseOptionPanel(
            "<details><summary>折叠标题</summary>折叠内容</details>\n<summary>真摘要</summary>",
        )
        assertEquals(listOf("真摘要"), r.panel!!.summaries)
        assertTrue(r.rest.contains("折叠标题"))
    }

    @Test
    fun `正文里的普通尖括号不误伤`() {
        val r = parseOptionPanel("比较 a < b 和 c > d 的大小")
        assertNull(r.panel)
        assertTrue(r.rest.contains("a < b"))
    }
}
