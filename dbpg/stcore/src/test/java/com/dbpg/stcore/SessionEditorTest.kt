package com.dbpg.stcore

import com.dbpg.stcore.session.ActionImage
import com.dbpg.stcore.session.MessageVariant
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息编辑 / 版本切换的单测。
 *
 * 重点钉住三类"看起来能用、实际会翻车"的边界：
 *   1. 第一次重新生成必须把旧正文保下来，否则「‹ ›」翻不回初版；
 *   2. 空版本绝不能进历史（切过去整条消息会变空，按钮也会消失）；
 *   3. 分侧删除不能把另一侧一起带走。
 */
class SessionEditorTest {

    private fun doc(vararg acts: SessionAction) = SessionDoc(
        id = "s1",
        title = "测试会话",
        actions = acts.toList(),
        updatedAt = 1_700_000_000_000,
    )

    private fun act(
        id: String = "a1",
        user: String = "我推开门",
        ai: String = "门上的铃铛响了一声。",
        images: List<ActionImage> = emptyList(),
        variants: List<MessageVariant> = emptyList(),
        variantIndex: Int = 0,
    ) = SessionAction(
        id = id,
        user = user,
        ai = ai,
        timestamp = 1_700_000_000_000,
        images = images,
        variants = variants,
        variantIndex = variantIndex,
    )

    // ------------------------------------------------------------ 编辑

    @Test
    fun `编辑后旧内容进入历史，当前指向新版本`() {
        val out = SessionEditor.updateAction(doc(act()), "a1", ai = "铃铛没响。", now = 42)
        val a = out.actions.single()

        assertEquals("铃铛没响。", a.ai)
        assertEquals(0, a.variantIndex)
        assertEquals(2, a.variants.size)
        assertEquals("铃铛没响。", a.variants[0].ai)
        assertEquals("门上的铃铛响了一声。", a.variants[1].ai)
        assertEquals(42L, out.updatedAt)
    }

    @Test
    fun `编辑为完全相同的内容不会堆重复版本`() {
        val out = SessionEditor.updateAction(doc(act()), "a1", ai = "门上的铃铛响了一声。")
        val a = out.actions.single()
        assertEquals(1, a.variants.size)
    }

    @Test
    fun `版本最多保留 MAX_VARIANTS 条`() {
        var d = doc(act())
        repeat(SessionEditor.MAX_VARIANTS + 5) { i ->
            d = SessionEditor.updateAction(d, "a1", ai = "第 $i 版", now = i.toLong())
        }
        assertEquals(SessionEditor.MAX_VARIANTS, d.actions.single().variants.size)
        assertEquals("第 ${SessionEditor.MAX_VARIANTS + 4} 版", d.actions.single().ai)
    }

    // ------------------------------------------------------------ 重新生成

    @Test
    fun `第一次重新生成保留原回复`() {
        val out = SessionEditor.replaceAi(doc(act()), "a1", "新的回复", now = 9)
        val a = out.actions.single()
        assertEquals("新的回复", a.ai)
        assertEquals(2, a.variants.size)
        assertEquals("新的回复", a.variants[0].ai)
        assertEquals("门上的铃铛响了一声。", a.variants[1].ai)
        assertEquals(9L, a.variants[0].timestamp)
    }

    @Test
    fun `回复已被删除时重新生成不会留下幽灵版本`() {
        val noAi = doc(act(ai = ""))
        val out = SessionEditor.replaceAi(noAi, "a1", "从零生成", now = 9)
        val a = out.actions.single()
        assertEquals(1, a.variants.size)
        assertEquals("从零生成", a.variants[0].ai)
    }

    @Test
    fun `空版本不会进入历史`() {
        // 「幽灵版本」= ai 与 user 都空，判定与 web 的 onlyReal 一致
        // （src/stores/adventure.ts:123）。只有 ai 为空、user 仍有内容的
        // 版本是合法历史，不该被丢掉。
        val dirty = doc(
            act(
                variants = listOf(
                    MessageVariant(user = "", ai = ""),
                    MessageVariant(user = "旧指令", ai = "旧回复"),
                ),
            ),
        )
        val out = SessionEditor.replaceAi(dirty, "a1", "新回复")
        val a = out.actions.single()
        assertTrue(a.variants.all { it.ai.isNotBlank() || it.user.isNotBlank() })
        assertEquals(2, a.variants.size)
    }

    // ------------------------------------------------------------ 版本切换

    @Test
    fun `切到旧版会把正文换回旧内容`() {
        var d = doc(act())
        d = SessionEditor.replaceAi(d, "a1", "第二版", now = 2)
        d = SessionEditor.replaceAi(d, "a1", "第三版", now = 3)

        val older = SessionEditor.switchVariant(d, "a1", 1)
        assertEquals("第二版", older.actions.single().ai)
        assertEquals(1, older.actions.single().variantIndex)

        val oldest = SessionEditor.switchVariant(older, "a1", 2)
        assertEquals("门上的铃铛响了一声。", oldest.actions.single().ai)

        val newest = SessionEditor.switchVariant(oldest, "a1", 0)
        assertEquals("第三版", newest.actions.single().ai)
    }

    @Test
    fun `切换下标越界时夹紧而不是崩`() {
        var d = doc(act())
        d = SessionEditor.replaceAi(d, "a1", "第二版")
        val out = SessionEditor.switchVariant(d, "a1", 99)
        assertEquals(1, out.actions.single().variantIndex)
    }

    @Test
    fun `切换前剔除历史里的空版本`() {
        // 与 web switchVariant（src/stores/adventure.ts:506）一样只剔除
        // ai 和 user 都空的幽灵版本。ai 是空白但 user 有内容的版本按 web
        // 语义保留，不能在这里假设它会被删，否则原生会偏离网页版行为。
        val dirty = doc(
            act(
                ai = "当前",
                variants = listOf(
                    MessageVariant(user = "u", ai = "当前"),
                    MessageVariant(user = "", ai = "   "),
                    MessageVariant(user = "u", ai = "更早"),
                ),
                variantIndex = 0,
            ),
        )
        val out = SessionEditor.switchVariant(dirty, "a1", 1)
        assertEquals("更早", out.actions.single().ai)
        assertEquals(2, out.actions.single().variants.size)
    }

    // ------------------------------------------------------------ 删除（分侧）

    @Test
    fun `删 AI 保留用户发言和整条消息`() {
        val out = SessionEditor.removeAi(doc(act()), "a1")
        val a = out.actions.single()
        assertEquals("我推开门", a.user)
        assertEquals("", a.ai)
        assertTrue(a.variants.isEmpty())
    }

    @Test
    fun `删 AI 时一并清掉配图`() {
        val withImg = doc(act(images = listOf(ActionImage(id = "im1", description = "图"))))
        assertEquals(listOf("im1"), SessionEditor.imageIdsOf(withImg, "a1"))
        val out = SessionEditor.removeAi(withImg, "a1")
        assertTrue(out.actions.single().images.isEmpty())
    }

    @Test
    fun `删 AI 且用户发言为空时整条移除`() {
        val out = SessionEditor.removeAi(doc(act(user = "  ")), "a1")
        assertTrue(out.actions.isEmpty())
    }

    @Test
    fun `删用户保留 AI 回复`() {
        val out = SessionEditor.removeUser(doc(act()), "a1")
        val a = out.actions.single()
        assertEquals("", a.user)
        assertEquals("门上的铃铛响了一声。", a.ai)
    }

    @Test
    fun `删用户且两侧都空时整条移除`() {
        val out = SessionEditor.removeUser(doc(act(ai = "  ")), "a1")
        assertTrue(out.actions.isEmpty())
    }

    @Test
    fun `整条删除后 updatedAt 更新`() {
        val out = SessionEditor.remove(doc(act()), "a1", now = 123)
        assertTrue(out.actions.isEmpty())
        assertEquals(123L, out.updatedAt)
    }

    // ------------------------------------------------------------ 分支 / 草稿

    @Test
    fun `分支复制到指定一条为止并换新 id`() {
        val d = doc(act("a1"), act("a2", "继续", "风更大了。"), act("a3", "再走", "雨停了。"))
        val b = SessionEditor.branch(d, "a2", "s2", { "n$it" })
        assertNotNull(b)
        assertEquals("s2", b!!.id)
        assertEquals("测试会话（1）", b.title)
        assertEquals(2, b.actions.size)
        assertEquals(listOf("n0", "n1"), b.actions.map { it.id })
        // 原会话不动
        assertEquals(3, d.actions.size)
    }

    @Test
    fun `分支编号按已有标题递增而不是叠加后缀`() {
        // 从「雨夜」分出一条 → （1）
        assertEquals("（1）", SessionEditor.branchSuffix("雨夜", listOf("雨夜", "别的会话")))
        // 再从「雨夜（1）」分 → （2），不能变成「雨夜（1）（1）」
        assertEquals("（2）", SessionEditor.branchSuffix("雨夜（1）", listOf("雨夜", "雨夜（1）")))
        // 删掉中间那条也不撞号：已有（1）（3）→ （4）
        assertEquals("（4）", SessionEditor.branchSuffix("雨夜", listOf("雨夜（1）", "雨夜（3）")))
        // 半角括号同样认
        assertEquals("（3）", SessionEditor.branchSuffix("雨夜 (2)", listOf("雨夜(2)")))
        // 前缀相同的别的标题不能被算进来（「雨夜篇」不是「雨夜」的分支）
        assertEquals("（1）", SessionEditor.branchSuffix("雨夜", listOf("雨夜篇（5）")))
    }

    @Test
    fun `分支标题带上编号后不会重复叠后缀`() {
        val d = doc(act("a1")).copy(title = "雨夜（1）")
        val b = SessionEditor.branch(d, "a1", "s2", { "n$it" }, titleSuffix = "（2）")!!
        assertEquals("雨夜（2）", b.title)
    }

    @Test
    fun `分支保留未知字段（raw 兜底不能被洗掉）`() {
        val raw = """{"id":"a1","user":"u","ai":"a","speakerId":"char_7","x":1}"""
        val d = doc(SessionAction.parse(org.json.JSONObject(raw)))
        val b = SessionEditor.branch(d, "a1", "s2", { "n$it" })!!
        val out = b.actions[0].toJson()
        assertEquals("char_7", out.optString("speakerId"))
        assertEquals(1, out.optInt("x"))
        // id 必须换成新的，否则两条会话的消息 id 撞车
        assertEquals("n0", out.optString("id"))
    }

    @Test
    fun `分支目标不存在返回 null`() {
        assertNull(SessionEditor.branch(doc(act()), "nope", "s2", { "n$it" }))
    }

    @Test
    fun `草稿写入与清空`() {
        val d = SessionEditor.setDraft(doc(act()), "新一轮", "写到一半", now = 5)
        assertEquals("写到一半", d.draft?.ai)
        assertEquals(5L, d.draft?.updatedAt)
        assertNull(SessionEditor.clearDraft(d).draft)
    }

    @Test
    fun `追加一轮会清掉草稿`() {
        val d = SessionEditor.setDraft(doc(act()), "下一轮", "半截")
        val out = SessionEditor.appendAction(d, "a2", "下一轮", "完整回复", now = 7)
        assertEquals(2, out.actions.size)
        assertNull(out.draft)
        assertEquals(7L, out.updatedAt)
    }

    @Test
    fun `配图 upsert 按 id 覆盖而不是重复追加`() {
        var d = doc(act(images = listOf(ActionImage(id = "im1", description = "旧"))))
        d = SessionEditor.upsertImage(d, "a1", ActionImage(id = "im1", description = "新"))
        d = SessionEditor.upsertImage(d, "a1", ActionImage(id = "im2", description = "第二张"))
        val imgs = d.actions.single().images
        assertEquals(2, imgs.size)
        assertEquals("新", imgs[0].description)
    }

    @Test
    fun `重新生成前砍掉后面的消息但保留当前条`() {
        val d = doc(act("a1"), act("a2", "继续", "风更大了。"), act("a3", "再走", "雨停了。"))
        val out = SessionEditor.truncateAfter(d, "a2", now = 77)
        assertEquals(listOf("a1", "a2"), out.actions.map { it.id })
        assertEquals(77L, out.updatedAt)

        // 已经在最后一条时原样返回
        assertSame(out, SessionEditor.truncateAfter(out, "a2"))
    }

    @Test
    fun `未知 id 的操作原样返回同一个对象`() {
        val d = doc(act())
        assertSame(d, SessionEditor.updateAction(d, "nope", ai = "x"))
        assertSame(d, SessionEditor.removeAi(d, "nope"))
        assertSame(d, SessionEditor.switchVariant(d, "nope", 1))
    }
}
