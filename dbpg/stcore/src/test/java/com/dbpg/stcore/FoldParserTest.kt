package com.dbpg.stcore

import com.dbpg.stcore.richtext.FoldParser
import com.dbpg.stcore.richtext.MsgBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折叠栏解析：`<details>/<summary>` 的边界情况（ST 预设里很常见）。 */
class FoldParserTest {

    @Test
    fun `no details yields one text block`() {
        val blocks = FoldParser.split("普通一段\n\n第二段")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MsgBlock.Text)
        assertEquals("普通一段\n\n第二段", (blocks[0] as MsgBlock.Text).html)
    }

    @Test
    fun `details with summary is folded and closed by default`() {
        val html = "<p>前言</p><details><summary>小猫之神 - 折叠栏</summary>吾乃小猫之神...</details><p>后记</p>"
        val blocks = FoldParser.split(html)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MsgBlock.Text)
        val fold = blocks[1] as MsgBlock.Fold
        assertEquals("小猫之神 - 折叠栏", fold.title)
        assertEquals("吾乃小猫之神...", fold.body)
        assertEquals(false, fold.open)
        assertTrue(blocks[2] is MsgBlock.Text)
    }

    @Test
    fun `open attribute expands by default`() {
        val fold = FoldParser.split("<details open><summary>T</summary>B</details>")[0] as MsgBlock.Fold
        assertEquals(true, fold.open)
    }

    @Test
    fun `tags are case insensitive`() {
        val fold = FoldParser.split("<DETAILS><SUMMARY>标题</SUMMARY>内容</DETAILS>")[0] as MsgBlock.Fold
        assertEquals("标题", fold.title)
        assertEquals("内容", fold.body)
    }

    @Test
    fun `missing summary falls back to default title`() {
        val fold = FoldParser.split("<details>只有内容</details>")[0] as MsgBlock.Fold
        assertEquals("详情", fold.title)
        assertEquals("只有内容", fold.body)
    }

    @Test
    fun `unclosed details keeps the rest as body`() {
        val blocks = FoldParser.split("<details><summary>标题</summary>正文还没结束")
        assertEquals(1, blocks.size)
        val fold = blocks[0] as MsgBlock.Fold
        assertEquals("标题", fold.title)
        assertEquals("正文还没结束", fold.body)
    }

    @Test
    fun `nested details uses the outer block`() {
        val html = "<details><summary>外</summary>前<details><summary>内</summary>里</details>后</details>"
        val blocks = FoldParser.split(html)
        assertEquals(1, blocks.size)
        val fold = blocks[0] as MsgBlock.Fold
        assertEquals("外", fold.title)
        assertTrue(fold.body.contains("内"))
        assertTrue(fold.body.contains("后"))
    }

    @Test
    fun `multiple folds with interleaved text`() {
        val html = "A<details><summary>T1</summary>B1</details>中间<details><summary>T2</summary>B2</details>C"
        val blocks = FoldParser.split(html)
        assertEquals(5, blocks.size)
        assertEquals("T1", (blocks[1] as MsgBlock.Fold).title)
        assertEquals("T2", (blocks[3] as MsgBlock.Fold).title)
        assertTrue((blocks[4] as MsgBlock.Text).html.contains("C"))
    }

    @Test
    fun `style and script blocks are dropped`() {
        val html = "<style>.x{color:red}</style><details><summary>T</summary>B</details>"
        val blocks = FoldParser.split(html)
        assertEquals(1, blocks.size)
        val fold = blocks[0] as MsgBlock.Fold
        assertTrue(!fold.body.contains("color:red"))
    }

    @Test
    fun `summary with inline tags keeps markup for rich rendering`() {
        val fold = FoldParser.split("<details><summary><b>加粗</b>标题</summary>B</details>")[0] as MsgBlock.Fold
        assertEquals("<b>加粗</b>标题", fold.title)
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(FoldParser.split("").isEmpty())
        assertTrue(FoldParser.split("   ").isEmpty())
    }
}
