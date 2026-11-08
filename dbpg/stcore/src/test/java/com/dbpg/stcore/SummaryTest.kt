package com.dbpg.stcore

import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionHistory
import com.dbpg.stcore.session.SummaryBlock
import com.dbpg.stcore.session.SummaryPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动摘要的窗口与注入。
 *
 * 这几条是「用户以为会发生的事」：
 *   · 每 10 条触发、压缩最早 8 条（不是输出 8 条）
 *   · 压缩过的轮次不再发原文，但原文本身不能删
 *   · 摘要错位时必须退回原文 —— 张冠李戴比多花 token 严重得多
 */
class SummaryTest {

    private fun doc(n: Int, aiLen: Int = 10): SessionDoc = SessionDoc(
        id = "s1",
        title = "t",
        actions = (0 until n).map {
            SessionAction(id = "a$it", user = "问$it", ai = "答$it".padEnd(aiLen, '字'))
        },
    )

    private val count = SummaryPlanner.TriggerMode.COUNT
    private val chars = SummaryPlanner.TriggerMode.CHARS

    // ------------------------------------------------------------ 窗口

    @Test
    fun `攒够 10 条才触发 一次压缩最早 8 条`() {
        assertNull(
            SummaryPlanner.next(9, 0, count, 10, 4000, 8, 0) { 10 },
        )
        val r = SummaryPlanner.next(10, 0, count, 10, 4000, 8, 0) { 10 }
        assertEquals(0 until 8, r)
    }

    @Test
    fun `触发后剩下 2 条原文 下次继续从第 9 条起`() {
        val r = SummaryPlanner.next(18, 8, count, 10, 4000, 8, 0) { 10 }
        assertEquals(8 until 16, r)
    }

    @Test
    fun `不足一整个批次时只压已有的 不会越界`() {
        // 已摘要 8 条，总数 15：未摘要 7 条，够 10 的阈值吗？不够
        assertNull(SummaryPlanner.next(15, 8, count, 10, 4000, 8, 0) { 10 })
        // 达到 18 条才够
        assertEquals(8 until 16, SummaryPlanner.next(18, 8, count, 10, 4000, 8, 0) { 10 })
    }

    @Test
    fun `按字数触发 且一次最多取 takeCount`() {
        // 10 条 × 500 字 = 5000，达到 4000
        val r = SummaryPlanner.next(10, 0, chars, 10, 4000, 8, 0) { 500 }
        assertEquals(0 until 8, r)
        // 每条 100 字 = 1000，不够
        assertNull(SummaryPlanner.next(10, 0, chars, 10, 4000, 8, 0) { 100 })
    }

    @Test
    fun `轮数不够时直接返回 null`() {
        assertNull(SummaryPlanner.next(0, 0, count, 10, 4000, 8, 0) { 0 })
    }

    // ------------------------------------------------------------ 注入

    @Test
    fun `摘要替代原文 被覆盖的轮次不再出现`() {
        val d = doc(12)
        val block = SummaryBlock(
            id = "sum1",
            text = "前八轮的提要",
            fromIndex = 0,
            toIndex = 7,
            toActionId = "a7",
        )
        val msgs = SessionHistory.export(d, summaries = listOf(block))
        // 场景为空，第一条就是摘要
        assertTrue(msgs.first().content.contains("前情提要"))
        assertTrue(msgs.first().content.contains("前八轮的提要"))
        // 前 8 条的正文不该再出现
        assertTrue(msgs.none { it.content == "问0" })
        assertTrue(msgs.none { it.content.startsWith("答0") })
        // 第 9 条开始要原样在
        assertTrue(msgs.any { it.content == "问8" })
        assertTrue(msgs.any { it.content == "问11" })
    }

    @Test
    fun `关闭摘要时行为和旧版完全一致`() {
        val d = doc(3)
        val plain = SessionHistory.exportRaw(d)
        assertEquals(6, plain.size)
        assertEquals("问0", plain[0].content)
        assertEquals("答2".padEnd(10, '字'), plain[5].content)
    }

    @Test
    fun `重新生成早于摘要区间的消息 必须退回原文`() {
        val d = doc(12)
        val block = SummaryBlock(
            id = "sum1",
            text = "前八轮的提要",
            fromIndex = 0,
            toIndex = 7,
            toActionId = "a7",
        )
        // 重新生成第 3 条（a2）：它的上下文必须包含 0..1 的原文
        val msgs = SessionHistory.export(d, uptoActionId = "a2", summaries = listOf(block))
        assertTrue(msgs.none { it.content.contains("前情提要") })
        assertEquals("问0", msgs[0].content)
        assertEquals("问1", msgs[2].content)
    }

    @Test
    fun `锚点对不上时整条摘要作废 回退原文`() {
        val d = doc(12)
        // 声称覆盖到 a7，但实际 a7 的内容变了（例如用户编辑过 id 被换掉）
        val bad = SummaryBlock(
            id = "sum1",
            text = "错位的提要",
            fromIndex = 0,
            toIndex = 7,
            toActionId = "not-exist",
        )
        assertTrue(!SummaryPlanner.valid(bad, d.actions))
        val msgs = SessionHistory.export(d, summaries = listOf(bad))
        assertTrue(msgs.none { it.content.contains("错位的提要") })
        assertTrue(msgs.any { it.content == "问0" })
    }

    @Test
    fun `摘要区间越界时丢弃`() {
        val d = doc(3)
        val bad = SummaryBlock(id = "s", text = "x", fromIndex = 0, toIndex = 99, toActionId = "a2")
        assertTrue(!SummaryPlanner.valid(bad, d.actions))
    }
}
