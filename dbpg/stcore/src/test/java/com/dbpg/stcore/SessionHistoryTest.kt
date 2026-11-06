package com.dbpg.stcore

import com.dbpg.stcore.session.MessageVariant
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出历史的三条铁律：只发当前版本、图片不进上下文、空侧不占位。
 * 这三条一旦破了就是线上事故（串版本 / 图片文本污染上下文），所以单测要钉死。
 */
class SessionHistoryTest {

    private fun doc(vararg actions: SessionAction) = SessionDoc(id = "s1", title = "t", actions = actions.toList())

    @Test
    fun `按时间顺序交替导出 user 与 assistant`() {
        val d = doc(
            SessionAction(id = "a1", user = "你好", ai = "你好呀"),
            SessionAction(id = "a2", user = "走左边", ai = "你向左走去"),
        )
        val msgs = SessionHistory.export(d)
        assertEquals(listOf("user", "assistant", "user", "assistant"), msgs.map { it.role })
        assertEquals("你好", msgs[0].content)
        assertEquals("你向左走去", msgs[3].content)
    }

    @Test
    fun `只导出当前版本 历史版本不外发`() {
        val d = doc(
            SessionAction(
                id = "a1",
                user = "你好",
                // 当前显示的是第二版（用户点了 ‹）
                ai = "第二版回答",
                variants = listOf(
                    MessageVariant(user = "你好", ai = "最新一版回答"),
                    MessageVariant(user = "你好", ai = "第二版回答"),
                ),
                variantIndex = 1,
            ),
        )
        val msgs = SessionHistory.export(d)
        assertEquals(2, msgs.size)
        assertEquals("第二版回答", msgs[1].content)
        assertTrue(msgs.none { it.content.contains("最新一版") })
    }

    @Test
    fun `图片标记不进上下文`() {
        val d = doc(
            SessionAction(
                id = "a1",
                user = "看看周围",
                ai = "你环顾四周。\n\n[[IMG: 昏暗的酒馆大厅]]\n\n角落里有人招手。",
            ),
        )
        val msgs = SessionHistory.export(d)
        val ai = msgs[1].content
        assertTrue(!ai.contains("IMG"))
        assertTrue(!ai.contains("[["))
        assertTrue(ai.contains("昏暗") || ai.contains("角落"))
    }

    @Test
    fun `base64 内联图片也被清掉`() {
        val base64 = "A".repeat(200)
        val d = doc(
            SessionAction(id = "a1", user = "u", ai = "看图：\n\n![x](data:image/png;base64,$base64)\n\n完"),
        )
        val ai = SessionHistory.export(d)[1].content
        assertTrue(!ai.contains("base64"))
        assertTrue(ai.contains("完"))
    }

    @Test
    fun `删掉 AI 的那一轮只导出 user`() {
        val d = doc(
            SessionAction(id = "a1", user = "我推开门", ai = ""),
            SessionAction(id = "a2", user = "继续", ai = "门后是走廊"),
        )
        val msgs = SessionHistory.export(d)
        assertEquals(3, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("我推开门", msgs[0].content)
        // 不能出现空的 assistant
        assertTrue(msgs.none { it.content.isBlank() })
    }

    @Test
    fun `删掉 user 的那一轮只导出 assistant`() {
        val d = doc(SessionAction(id = "a1", user = "", ai = "系统开场白"))
        val msgs = SessionHistory.export(d)
        assertEquals(1, msgs.size)
        assertEquals("assistant", msgs[0].role)
    }

    @Test
    fun `重新生成时截断到目标轮之前`() {
        val d = doc(
            SessionAction(id = "a1", user = "第一步", ai = "你迈出第一步"),
            SessionAction(id = "a2", user = "第二步", ai = "旧的第二步回答"),
            SessionAction(id = "a3", user = "第三步", ai = "第三步回答"),
        )
        val msgs = SessionHistory.export(d, uptoActionId = "a2")
        assertEquals(2, msgs.size)
        assertEquals("第一步", msgs[0].content)
        // 目标轮自己的 user 单独作为 currentUserInput 发，不能重复
        assertTrue(msgs.none { it.content == "第二步" })
        // 后面的轮次也不能带进来
        assertTrue(msgs.none { it.content.contains("第三步") })
    }

    @Test
    fun `重新生成第一轮时历史为空`() {
        val d = doc(SessionAction(id = "a1", user = "开场", ai = "开场白"))
        assertTrue(SessionHistory.export(d, uptoActionId = "a1").isEmpty())
        assertEquals("开场", SessionHistory.userInputOf(d, "a1"))
    }

    @Test
    fun `未知 actionId 当作导出全部 不崩溃`() {
        val d = doc(SessionAction(id = "a1", user = "u", ai = "a"))
        assertEquals(2, SessionHistory.export(d, uptoActionId = "不存在").size)
    }

    @Test
    fun `空白正文被跳过`() {
        val d = doc(SessionAction(id = "a1", user = "   ", ai = "\n\n  \n"))
        assertTrue(SessionHistory.export(d).isEmpty())
    }

    @Test
    fun `userInputOf 也剥图片标记`() {
        val d = doc(SessionAction(id = "a1", user = "看这个 [[IMG: 一只猫]]", ai = "看到了"))
        assertEquals("看这个", SessionHistory.userInputOf(d, "a1"))
    }

    @Test
    fun `场景描述作为第一条 assistant 排在最前`() {
        val d = SessionDoc(
            id = "s1",
            sceneDescription = "雨夜的酒馆，炉火噼啪作响。",
            actions = listOf(SessionAction(id = "a1", user = "坐下", ai = "你坐下了")),
        )
        val msgs = SessionHistory.export(d)
        assertEquals(3, msgs.size)
        assertEquals("assistant", msgs[0].role)
        assertEquals("雨夜的酒馆，炉火噼啪作响。", msgs[0].content)
        assertEquals("坐下", msgs[1].content)
    }

    @Test
    fun `重新生成时场景描述仍然保留`() {
        val d = SessionDoc(
            id = "s1",
            sceneDescription = "开场",
            actions = listOf(SessionAction(id = "a1", user = "第一步", ai = "旧回答")),
        )
        val msgs = SessionHistory.export(d, uptoActionId = "a1")
        assertEquals(1, msgs.size)
        assertEquals("开场", msgs[0].content)
    }
}
