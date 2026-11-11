package com.dbpg.stcore

import com.dbpg.stcore.session.GroupRef
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊数据层：发言人标记、群归属、公共区导出。
 *
 * 这几项一旦错位，表现都是"看着能聊、其实全乱"：
 *   - speakerId 丢了 → 公共区所有气泡都算同一个人；
 *   - nativeGroup 丢了 → 群聊频道混进冒险列表；
 *   - 导出不给名字 → 模型把别人的台词当成自己说过的。
 */
class GroupSessionTest {

    @Test
    fun `speakerId 能完整往返且不影响单人会话`() {
        val a = SessionAction(id = "a1", user = "", ai = "我先说", speakerId = "char_a")
        val back = SessionAction.parse(a.toJson())
        assertEquals("char_a", back.speakerId)
        assertEquals("我先说", back.ai)

        // 单人会话：为空时不写键，避免每一条消息都背一个空字段
        val plain = SessionAction(id = "a2", user = "你好", ai = "你好")
        assertTrue(!plain.toJson().has("speakerId"))
        assertEquals("", SessionAction.parse(plain.toJson()).speakerId)
    }

    @Test
    fun `群归属能完整往返并出现在索引里`() {
        val d = SessionDoc(
            id = "s1",
            title = "公共区",
            group = GroupRef("g1", GroupRef.KIND_PUBLIC),
        )
        val back = SessionDoc.parse(d.toJsonString())!!
        assertEquals("g1", back.group?.groupId)
        assertEquals(GroupRef.KIND_PUBLIC, back.group?.kind)

        // 索引带上群 id，首页才能在列表层过滤掉频道会话（不用把会话全读一遍）
        val entry = back.toIndexEntry()
        assertEquals("g1", entry.groupId)
        assertEquals(GroupRef.KIND_PUBLIC, entry.channelKind)

        // 单人会话没有 group 键，索引里也是空串
        val solo = SessionDoc(id = "s2", title = "单人").toIndexEntry()
        assertEquals("", solo.groupId)
        assertNull(SessionDoc.parse(SessionDoc(id = "s2").toJsonString())!!.group)
    }

    @Test
    fun `公共区导出给每个发言加名字 私聊不加`() {
        val d = SessionDoc(
            id = "s1",
            group = GroupRef("g1", GroupRef.KIND_PUBLIC),
            actions = listOf(
                SessionAction(id = "a1", user = "你们来了", ai = "嗯，路上有点堵", speakerId = "c_a"),
                SessionAction(id = "a2", user = "", ai = "船票带了吗？", speakerId = "c_b"),
            ),
        )
        val names = mapOf("c_a" to "阿岚", "c_b" to "老周")
        val msgs = SessionHistory.export(d, speakerNames = names)
        assertEquals("【阿岚】嗯，路上有点堵", msgs[1].content)
        assertEquals("【老周】船票带了吗？", msgs[2].content)

        // 不传名字（私聊 / 单人）：一个字节都不该变
        val plain = SessionHistory.export(d)
        assertEquals("嗯，路上有点堵", plain[1].content)
        assertEquals("船票带了吗？", plain[2].content)
    }

    @Test
    fun `没有发言人的旧消息即使传了名字也不加前缀`() {
        val d = SessionDoc(
            id = "s1",
            group = GroupRef("g1", GroupRef.KIND_PUBLIC),
            actions = listOf(SessionAction(id = "a1", user = "在吗", ai = "在", speakerId = "")),
        )
        val msgs = SessionHistory.export(d, speakerNames = mapOf("c_a" to "阿岚"))
        assertEquals("在", msgs[1].content)
    }
}
