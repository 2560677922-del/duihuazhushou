package com.dbpg.duihuazhushou.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 生图运营商命令的回归测试（2026-11-11 配置页重构）。
 *
 * 与 LLM 那套同源的三件事：
 *   1. 编辑「非当前」运营商不能串到 currentApiId 那一家；
 *   2. 用刚编辑的地址拉模型/加模型，要落到这一家；
 *   3. 地址改了分组 key 会变，页面必须能跟到新 key，而不是被踢回列表。
 *
 * 额外锁场景隔离：对话配图 / 标签生图各记各的。
 */
class ConnectionStoreImageProviderTest {

    @Before
    fun setUp() {
        val f = File.createTempFile("conn-img", ".json")
        f.deleteOnExit()
        val doc = JSONObject()
            .put("llm", JSONObject().put("configs", JSONArray()).put("currentConfigId", ""))
            .put(
                "image", JSONObject()
                    .put("apiConfigs", JSONArray())
                    .put("styleConfigs", JSONArray())
                    .put("currentApiId", "")
                    .put("currentStyleId", "")
                    .put("sceneApiIds", JSONObject().put("chat", "").put("tag", ""))
                    .put("autoImage", JSONObject()),
            )
        inject("file", f)
        inject("doc", doc)
    }

    private fun inject(name: String, value: Any?) {
        val field = ConnectionStore::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.set(ConnectionStore, value)
    }

    private fun doc(): JSONObject =
        ConnectionStore::class.java.getDeclaredField("doc").apply { isAccessible = true }
            .get(ConnectionStore) as JSONObject

    private fun apply(cmd: String, body: JSONObject.() -> Unit = {}): JSONObject =
        ConnectionStore.apply(cmd, JSONObject().apply(body))

    private fun config(): JSONObject = ConnectionStore.snapshot("getImageConfig")!!

    private fun providers(): List<JSONObject> {
        val arr = config().optJSONArray("providers") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun twoProviders() {
        apply("imgAddProvider") {
            put("name", "甲").put("baseUrl", "https://a.example/v1").put("apiKey", "sk-a")
        }
        apply("imgAddProvider") {
            put("name", "乙").put("baseUrl", "https://b.example/v1").put("apiKey", "sk-b")
        }
    }

    @Test
    fun `新建运营商后快照带 uid 引擎与密钥`() {
        apply("imgAddProvider") {
            put("name", "甲").put("baseUrl", "https://a.example/v1").put("apiKey", "sk-a")
        }
        val p = providers().single()
        assertEquals("https://a.example/v1", p.optString("baseUrl"))
        assertEquals("sk-a", p.optString("apiKey"))
        assertEquals("custom", p.optString("engine"))
        assertTrue(p.optString("uid").isNotEmpty())
    }

    @Test
    fun `加模型按 key 落到指定运营商`() {
        twoProviders()
        val keyA = providers()[0].optString("key")
        val r = apply("imgAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("nai-diffusion-4").put("flux"))
        }
        assertEquals(2, r.optInt("added"))
        assertEquals(2, providers()[0].optJSONArray("models")!!.length())
        val other = providers()[1].optJSONArray("models")!!
        val otherNames = (0 until other.length()).map { other.optJSONObject(it)?.optString("model").orEmpty() }
        assertTrue("模型不能串到另一家", otherNames.none { it == "nai-diffusion-4" || it == "flux" })
    }

    @Test
    fun `重复模型跳过`() {
        twoProviders()
        val keyA = providers()[0].optString("key")
        apply("imgAddModelsToProvider") {
            put("key", keyA).put("models", JSONArray().put("flux"))
        }
        val r = apply("imgAddModelsToProvider") {
            put("key", keyA).put("models", JSONArray().put("flux").put("sdxl"))
        }
        assertEquals(1, r.optInt("added"))
        assertEquals(1, r.optInt("skipped"))
    }

    @Test
    fun `保存非当前运营商不串台，地址变更回新 key`() {
        twoProviders()
        val keyA = providers()[0].optString("key")
        val curBefore = config().optJSONObject("current")!!.optString("uid")

        val r = apply("imgSaveProviderConn") {
            put("key", keyA)
            put("name", "甲改名")
            put("baseUrl", "https://new.example/v1")
            put("apiKey", "sk-new")
        }
        assertEquals("https://new.example/v1", r.optString("key"))
        val ps = providers()
        assertEquals("https://new.example/v1", ps[0].optString("baseUrl"))
        assertEquals("sk-new", ps[0].optString("apiKey"))
        assertEquals("https://b.example/v1", ps[1].optString("baseUrl"))
        assertEquals("当前接口不能被编辑别人改掉", curBefore, config().optJSONObject("current")!!.optString("uid"))
    }

    @Test
    fun `场景绑定互不影响，tag 不改 currentApiId`() {
        twoProviders()
        val ps = providers()
        val keyA = ps[0].optString("key")
        val keyB = ps[1].optString("key")
        apply("imgAddModelsToProvider") { put("key", keyA).put("models", JSONArray().put("m-a")) }
        apply("imgAddModelsToProvider") { put("key", keyB).put("models", JSONArray().put("m-b")) }

        val uidA = providers()[0].optJSONArray("models")!!.optJSONObject(0)!!.optString("uid")
        val uidB = providers()[1].optJSONArray("models")!!.optJSONObject(0)!!.optString("uid")
        apply("imgUseApi") { put("uid", uidA).put("scope", "chat") }
        apply("imgUseApi") { put("uid", uidB).put("scope", "tag") }

        assertEquals(uidA, config().optJSONObject("current")!!.optString("uid"))
        val after = providers()
        assertEquals(listOf("chat"), (0 until after[0].optJSONArray("usedBy")!!.length())
            .map { after[0].optJSONArray("usedBy")!!.optString(it) })
        assertEquals(listOf("tag"), (0 until after[1].optJSONArray("usedBy")!!.length())
            .map { after[1].optJSONArray("usedBy")!!.optString(it) })
    }

    @Test
    fun `删除运营商清掉场景里的悬空引用`() {
        twoProviders()
        val keyA = providers()[0].optString("key")
        val keyB = providers()[1].optString("key")
        apply("imgAddModelsToProvider") { put("key", keyA).put("models", JSONArray().put("m-a")) }
        apply("imgAddModelsToProvider") { put("key", keyB).put("models", JSONArray().put("m-b")) }
        val uidA = providers()[0].optJSONArray("models")!!.optJSONObject(0)!!.optString("uid")
        apply("imgUseApi") { put("uid", uidA).put("scope", "chat") }

        apply("imgRemoveProvider") { put("key", keyA) }
        val scene = doc().optJSONObject("image")!!.optJSONObject("sceneApiIds")!!
        assertEquals("", scene.optString("chat"))
        assertEquals(1, providers().size)
    }

    @Test
    fun `未知本地命令诚实报错`() {
        val r = apply("imgNotARealCommand")
        assertFalse("不能再回一个假成功", r.optString("error").isEmpty())
    }
}
