package com.dbpg.duihuazhushou.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 运营商维度命令的回归测试（2026-11-11 配置页「列表 → 详情」重构配套）。
 *
 * 锁死三个最容易复发的坑：
 *   1. 编辑「非当前」运营商时，保存/加模型不能串到 currentConfigId 指向的那家；
 *   2. 停用运营商是真停用（当前模型要挪走），不是只改个徽章；
 *   3. 老数据没有 providerDisabled 字段时，一律视为启用（向上兼容）。
 *
 * ConnectionStore 平时靠 DhpApp.instance 初始化，JVM 单测里没有 Application。
 * 这里用反射直接注入内存 doc 与临时文件，绕开 Android Context，
 * 测的仍是生产代码里那条 apply() 分支。
 */
class ConnectionStoreProviderTest {

    @Before
    fun setUp() {
        val f = File.createTempFile("conn-store", ".json")
        f.deleteOnExit()
        val doc = JSONObject()
            .put(
                "llm", JSONObject()
                    .put("configs", JSONArray())
                    .put("currentConfigId", "")
                    .put("providerNotes", JSONObject()),
            )
            .put("image", JSONObject())
        inject("file", f)
        inject("doc", doc)
    }

    private fun inject(name: String, value: Any?) {
        val field = ConnectionStore::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.set(ConnectionStore, value)
    }

    private fun apply(cmd: String, body: JSONObject.() -> Unit = {}): JSONObject =
        ConnectionStore.apply(cmd, JSONObject().apply(body))

    private fun providers(): List<JSONObject> {
        val arr = ConnectionStore.snapshot("getConfig")!!.optJSONArray("providers") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 新建运营商自带一条空模型的占位 config，所以断言要看模型名而不是条数。 */
    private fun modelNames(p: JSONObject): List<String> {
        val arr = p.optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).map { arr.optJSONObject(it)?.optString("model").orEmpty() }
    }

    /** 建两家：第二次新建会把 currentConfigId 指到「乙」，正是串台最容易发生的场景。 */
    private fun twoProviders(): String {
        apply("llmAddProvider") {
            put("name", "甲").put("baseUrl", "https://a.example/v1").put("apiKey", "sk-a")
        }
        apply("llmAddProvider") {
            put("name", "乙").put("baseUrl", "https://b.example/v1").put("apiKey", "sk-b")
        }
        return providers()[0].optString("key")
    }

    @Test
    fun `快照带 apiKey 与 enabled，老数据默认启用`() {
        twoProviders()
        val ps = providers()
        assertEquals(2, ps.size)
        assertTrue("老数据没有 providerDisabled 时应全部启用", ps.all { it.optBoolean("enabled", true) })
        assertEquals("sk-a", ps[0].optString("apiKey"))
        assertEquals("https://a.example/v1", ps[0].optString("baseUrl"))
        assertEquals("甲", ps[0].optString("note"))
    }

    @Test
    fun `加模型按 key 落到指定运营商，不会串到当前那家`() {
        val keyA = twoProviders()
        val r = apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-1").put("m-2"))
        }
        assertEquals(2, r.optInt("added"))
        val ps = providers()
        assertEquals(2, ps[0].optJSONArray("models")!!.length())
        assertTrue("模型不能串到当前那家", modelNames(ps[1]).none { it == "m-1" || it == "m-2" })
    }

    @Test
    fun `加模型后分组 key 稳定，运营商名字不丢`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-1"))
        }
        val a = providers()[0]
        assertEquals("分组 key 漂移会让详情页被踢回列表", keyA, a.optString("key"))
        assertEquals("甲", a.optString("note"))
    }

    @Test
    fun `重复模型跳过，不产生重复条目`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-1").put("m-2"))
        }
        val r = apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-2").put("m-3"))
        }
        assertEquals(1, r.optInt("added"))
        assertEquals(1, r.optInt("skipped"))
        assertEquals(3, providers()[0].optJSONArray("models")!!.length())
    }

    @Test
    fun `保存连接只改这一家，组内全部模型同步`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-1").put("m-2"))
        }
        apply("llmSaveProviderConn") {
            put("key", keyA)
            put("name", "甲改名")
            put("baseUrl", "https://new.example/v1")
            put("apiKey", "sk-new")
        }
        val ps = providers()
        assertEquals("https://new.example/v1", ps[0].optString("baseUrl"))
        assertEquals("sk-new", ps[0].optString("apiKey"))
        assertEquals("甲改名", ps[0].optString("note"))
        assertEquals("https://b.example/v1", ps[1].optString("baseUrl"))
        assertEquals("sk-b", ps[1].optString("apiKey"))
    }

    @Test
    fun `停用是真的停用，当前模型挪到其它启用的运营商`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("m-1"))
        }
        val uidA = providers()[0].optJSONArray("models")!!.optJSONObject(0)!!.optString("uid")
        apply("useModel") { put("uid", uidA) }
        assertEquals(uidA, ConnectionStore.snapshot("getConfig")!!.optString("uid"))

        apply("llmSetProviderEnabled") { put("key", keyA).put("enabled", false) }
        val ps = providers()
        assertFalse(ps[0].optBoolean("enabled", true))
        assertTrue(ps[1].optBoolean("enabled", true))
        assertNotEquals(uidA, ConnectionStore.snapshot("getConfig")!!.optString("uid"))
    }

    @Test
    fun `重新启用后开关状态回写正确`() {
        val keyA = twoProviders()
        apply("llmSetProviderEnabled") { put("key", keyA).put("enabled", false) }
        assertFalse(providers()[0].optBoolean("enabled", true))
        apply("llmSetProviderEnabled") { put("key", keyA).put("enabled", true) }
        assertTrue(providers()[0].optBoolean("enabled", true))
    }

    @Test
    fun `key 不存在时诚实报错，不假装成功`() {
        val r = apply("llmSaveProviderConn") {
            put("key", "不存在的 key").put("baseUrl", "https://x/v1")
        }
        assertTrue(r.optString("error").isNotEmpty())
    }

    /**
     * 对话页右上角的模型 chip 曾经读 `modelName`（即 `_name` 别名），
     * 网页侧新建模型时别名默认叫「新模型」，于是切后台再回来就显示「新模型」，
     * 而请求一直走真实 model。现在快照只暴露真实 model。
     */
    @Test
    fun `getConfig 只回真实模型 id，不再带别名 modelName`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") {
            put("key", keyA)
            put("models", JSONArray().put("gemini-3.1-pro"))
        }
        val cfg = ConnectionStore.snapshot("getConfig")!!
        val uid = cfg.optJSONArray("providers")!!.optJSONObject(0)!!
            .optJSONArray("models")!!.optJSONObject(0)!!.optString("uid")
        apply("useModel") { put("uid", uid) }

        val after = ConnectionStore.snapshot("getConfig")!!
        assertEquals("gemini-3.1-pro", after.optString("model"))
        assertFalse("别名会让人误以为换了模型", after.has("modelName"))
    }

    @Test
    fun `currentConfigId 失效时修复到第一个启用项`() {
        val keyA = twoProviders()
        apply("llmAddModelsToProvider") { put("key", keyA).put("models", JSONArray().put("m-1")) }
        val allUids = providers().flatMap { p ->
            val ms = p.optJSONArray("models") ?: JSONArray()
            (0 until ms.length()).map { ms.optJSONObject(it)!!.optString("uid") }
        }
        val doc = ConnectionStore::class.java.getDeclaredField("doc").apply { isAccessible = true }
            .get(ConnectionStore) as JSONObject
        doc.optJSONObject("llm")!!.put("currentConfigId", "ghost-uid")

        // 任意一条写命令都会走到 normalize()
        apply("saveParams") { put("params", JSONObject()) }
        val repaired = ConnectionStore.snapshot("getConfig")!!.optString("uid")
        assertTrue("必须修复成一个真实存在的模型", repaired in allUids)
    }

    @Test
    fun `全部停用时当前模型留空，不偷偷复活停用项`() {
        twoProviders()
        val ps = providers()
        apply("llmSetProviderEnabled") { put("key", ps[0].optString("key")).put("enabled", false) }
        apply("llmSetProviderEnabled") { put("key", ps[1].optString("key")).put("enabled", false) }
        assertEquals("", ConnectionStore.snapshot("getConfig")!!.optString("uid"))
    }
}
