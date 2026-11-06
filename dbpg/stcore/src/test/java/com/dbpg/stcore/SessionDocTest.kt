package com.dbpg.stcore

import com.dbpg.stcore.session.ActionImage
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionIndexEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话数据模型测试。
 *
 * 这里测的不是"能不能解析"，而是**过一遍原生会不会丢东西**：
 * 迁移最怕的不是解析失败（能发现），而是静默丢字段（发现不了）。
 */
class SessionDocTest {

    private val webSessionJson = """
        {
          "id": "s_abc",
          "title": "雨夜书店",
          "sceneDescription": "深秋，雨。",
          "createdAt": 1730000000000,
          "updatedAt": 1730000500000,
          "actions": [
            {
              "id": "a1",
              "user": "我推开门",
              "ai": "门上的铃铛响了一声。",
              "timestamp": 1730000100000,
              "images": [
                {
                  "id": "im1",
                  "description": "雨中的书店橱窗",
                  "markdownIndex": 2,
                  "prompt": "rainy night bookshop, warm light",
                  "assetId": "asset_9",
                  "storage": "github",
                  "remotePath": "img/2026/01/x.webp"
                }
              ],
              "variants": [
                { "user": "我推开门", "ai": "门上的铃铛响了一声。", "timestamp": 1730000100000 },
                { "user": "我推开门", "ai": "（旧版）门开了。", "timestamp": 1730000050000 }
              ],
              "variantIndex": 0
            }
          ],
          "summaries": [
            { "id": "sm1", "text": "主角来到了书店。", "mode": "auto", "coveredUpTo": 2, "targetWords": 300 }
          ],
          "settings": { "autoSummary": true, "trigger": "messages", "everyMessages": 12 },
          "draft": { "user": "我抬头", "ai": "灯光", "updatedAt": 1730000600000 }
        }
    """.trimIndent()

    @Test
    fun `解析字段`() {
        val doc = SessionDoc.parse(webSessionJson)!!
        assertEquals("s_abc", doc.id)
        assertEquals("雨夜书店", doc.title)
        assertEquals(1, doc.actions.size)
        assertEquals("我推开门", doc.actions[0].user)
        assertEquals(1, doc.actions[0].images.size)
        assertEquals("asset_9", doc.actions[0].images[0].assetId)
        assertEquals(2, doc.actions[0].variants.size)
        assertEquals("我抬头", doc.draft?.user)
        assertEquals(1730000500000L, doc.updatedAt)
    }

    @Test
    fun `未知字段原样保留`() {
        val doc = SessionDoc.parse(webSessionJson)!!
        val out = doc.toJson()

        // 顶层未建模的字段
        assertEquals(1, out.getJSONArray("summaries").length())
        assertEquals(12, out.getJSONObject("settings").getInt("everyMessages"))
        // 配图里未建模的字段
        val im = out.getJSONArray("actions").getJSONObject(0).getJSONArray("images").getJSONObject(0)
        assertEquals("github", im.getString("storage"))
        assertEquals("img/2026/01/x.webp", im.getString("remotePath"))
    }

    @Test
    fun `往返一次内容不变`() {
        val doc = SessionDoc.parse(webSessionJson)!!
        val again = SessionDoc.parse(doc.toJsonString())!!
        // JSONObject.similar() 是 API 26 才有的，单元测编译在 android.jar 上会解析不到，
        // 这里自己做一个结构比较（只比数据类型和值，不比键顺序）。
        // 比的是"第一次序列化"与"解析后再序列化"：两者都应带 schemaVersion，
        // 拿原始输入比会把新增的版本号当成差异。
        assertTrue("往返后应完全等价", sameJson(doc.toJson(), again.toJson()))
    }

    /** 结构相等：对象按键递归、数组按下标、数字按值、其余按字符串。 */
    private fun sameJson(a: Any?, b: Any?): Boolean {
        return when {
            a is JSONObject && b is JSONObject -> {
                val ka = a.keys().asSequence().toSet()
                val kb = b.keys().asSequence().toSet()
                ka == kb && ka.all { sameJson(a.opt(it), b.opt(it)) }
            }
            a is org.json.JSONArray && b is org.json.JSONArray ->
                a.length() == b.length() && (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a == null || b == null -> a == b
            else -> a.toString() == b.toString()
        }
    }

    @Test
    fun `改已知字段不影响未知字段`() {
        val doc = SessionDoc.parse(webSessionJson)!!
        val edited = doc.copy(title = "改过的标题")
        val out = edited.toJson()
        assertEquals("改过的标题", out.getString("title"))
        // 摘要仍然在
        assertEquals("主角来到了书店。", out.getJSONArray("summaries").getJSONObject(0).getString("text"))
    }

    @Test
    fun `老数据缺字段不崩`() {
        val doc = SessionDoc.parse("""{"id":"s1"}""")!!
        assertEquals("s1", doc.id)
        assertTrue(doc.actions.isEmpty())
        assertNull(doc.draft)
        // 写回时补齐必要结构，web 能读
        val out = doc.toJson()
        assertEquals(0, out.getJSONArray("actions").length())
    }

    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(SessionDoc.parse("{ 不是 json"))
        assertNull(SessionDoc.parse(""))
        assertNull(SessionDoc.parse("   "))
    }

    @Test
    fun `时间戳兼容字符串与 ISO`() {
        val a = SessionDoc.parse("""{"id":"s","createdAt":"1730000000000"}""")!!
        assertEquals(1730000000000L, a.createdAt)

        val b = SessionDoc.parse("""{"id":"s","createdAt":"2024-10-27T03:33:20Z"}""")!!
        assertEquals(1730000000000L, b.createdAt)
    }

    @Test
    fun `空会话也能建索引`() {
        val doc = SessionDoc(id = "s1", title = "", createdAt = 1, updatedAt = 2)
        val idx = doc.toIndexEntry()
        assertEquals("s1", idx.id)
        assertEquals(0, idx.count)
        assertEquals(2L, idx.updatedAt)
    }

    @Test
    fun `索引用最后一条消息做预览`() {
        val doc = SessionDoc(
            id = "s1",
            actions = listOf(
                SessionAction(id = "a1", user = "你好", ai = "第一句"),
                SessionAction(id = "a2", user = "继续", ai = "第二句"),
            ),
        )
        assertEquals("第二句", doc.toIndexEntry().preview)
    }

    @Test
    fun `索引数组往返`() {
        val list = listOf(
            SessionIndexEntry(id = "a", title = "甲", count = 3, updatedAt = 10),
            SessionIndexEntry(id = "b", title = "乙", count = 1, updatedAt = 20),
        )
        val text = SessionIndexEntry.toJsonArray(list)
        val back = SessionIndexEntry.parseArray(text)
        assertEquals(list, back)
    }

    @Test
    fun `本地路径不会被当成 web 字段`() {
        val img = ActionImage(id = "i1", localPath = "/data/x.jpg")
        val o = img.toJson()
        assertEquals("/data/x.jpg", o.getString("nativeLocalPath"))
        // 不能污染 web 的 dataUrl
        assertTrue(!o.has("dataUrl"))
    }

    @Test
    fun `草稿为 null 时写回会删掉旧草稿`() {
        val doc = SessionDoc.parse(webSessionJson)!!
        val out = doc.copy(draft = null).toJson()
        assertTrue(!out.has("draft"))
    }

    @Test
    fun `空配图数组仍然写出`() {
        val act = SessionAction(id = "a1", user = "u", ai = "ai")
        val o = act.toJson()
        assertNotNull(o.getJSONArray("images"))
        assertEquals(0, o.getJSONArray("images").length())
    }
}
