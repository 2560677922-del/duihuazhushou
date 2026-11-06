package com.dbpg.stcore

import com.dbpg.stcore.imagegen.AutoImageEnv
import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.imagegen.NativeImageConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动配图的纯逻辑测试。
 *
 * 这些用例对应的都是"真机上要等十几秒、还要花额度"才能发现的问题：
 * 标记取多了、提示词拼错顺序、模型回了 JSON 却抠不出 prompt、
 * 端点推导多一层 /v1 …… 在这里一次跑完。
 */
class AutoImageSupportTest {

    // ------------------------------------------------------------ 标记

    @Test
    fun `按上限取标记，空描述不算`() {
        val text = """
            第一段
            [[IMG: 雨夜的书店]]
            [[IMG:   ]]
            第二段
            [[IMG: 桌上的热茶]]
            [[IMG: 窗外的猫]]
        """.trimIndent()
        val plan = AutoImageSupport.plan(text, 10)
        assertEquals(3, plan.size)
        assertEquals("雨夜的书店", plan[0].description)
        assertEquals("桌上的热茶", plan[1].description)
    }

    @Test
    fun `上限夹在 1 到 10 之间`() {
        val text = (1..15).joinToString("\n") { "第 $it 段\n[[IMG: 画面 $it]]" }
        assertEquals(1, AutoImageSupport.plan(text, 0).size)
        assertEquals(10, AutoImageSupport.plan(text, 99).size)
        assertEquals(3, AutoImageSupport.plan(text, 3).size)
    }

    @Test
    fun `没有标记时返回空`() {
        assertTrue(AutoImageSupport.plan("只有正文", 5).isEmpty())
        assertTrue(AutoImageSupport.plan("", 5).isEmpty())
    }

    // ------------------------------------------------------------ 提示词

    private val cfg = NativeImageConfig(
        artistTags = "artist:someone",
        qualityTags = "masterpiece, best quality",
        negativePrompt = "lowres, bad hands",
    )

    @Test
    fun `画师串与质量词永远排在模型标签前面`() {
        val out = AutoImageSupport.finalPrompt(cfg, "1girl, smile", "")
        assertEquals("artist:someone, masterpiece, best quality, 1girl, smile", out)
    }

    @Test
    fun `重复标签只留一次，忽略大小写但保留原写法`() {
        val out = AutoImageSupport.composePrompt(listOf("1Girl, smile", "1girl, Smile, hat"))
        assertEquals("1Girl, smile, hat", out)
    }

    @Test
    fun `模型返回的画师与质量词会被剔除`() {
        val out = AutoImageSupport.stripStyleTags("artist:foo, masterpiece, 1girl, (best quality:1.2), hat")
        assertEquals("1girl, hat", out)
    }

    @Test
    fun `模型什么都没吐时退回正文，不让提示词变空`() {
        val out = AutoImageSupport.finalPrompt(cfg, "masterpiece, best quality", "一个女孩站在雨里")
        assertEquals("artist:someone, masterpiece, best quality, 一个女孩站在雨里", out)
    }

    // ------------------------------------------------------------ 模型回复解析

    @Test
    fun `从 JSON 回复里抠出 prompt`() {
        val reply = "好的，结果如下：\n```json\n{\"prompt\": \"1girl, rain, night\", \"tags\": [\"1girl\"]}\n```"
        assertEquals("1girl, rain, night", AutoImageSupport.parseOptimizedPrompt(reply))
    }

    @Test
    fun `回复不是 JSON 时整段当标签`() {
        assertEquals("1girl, rain", AutoImageSupport.parseOptimizedPrompt("  1girl, rain  "))
    }

    @Test
    fun `JSON 被截断时退回原文而不是抛异常`() {
        val reply = "{\"prompt\": \"1girl, rai"
        assertEquals(reply, AutoImageSupport.parseOptimizedPrompt(reply))
    }

    @Test
    fun `兼容 chat completions 的两种返回形状`() {
        val a = JSONObject()
            .put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", "甲"))))
            .toString()
        val b = JSONObject()
            .put("choices", org.json.JSONArray().put(JSONObject().put("text", "乙")))
            .toString()
        assertEquals("甲", AutoImageSupport.extractCompletionText(a))
        assertEquals("乙", AutoImageSupport.extractCompletionText(b))
        assertEquals("", AutoImageSupport.extractCompletionText("not json"))
    }

    // ------------------------------------------------------------ 端点

    @Test
    fun `显式端点原样使用，不再猜`() {
        val out = AutoImageSupport.imageEndpoints("https://x.com", "https://x.com/api/generate")
        assertEquals(listOf("https://x.com/api/generate"), out)
    }

    @Test
    fun `完整 images 端点不会被再拼一层`() {
        val out = AutoImageSupport.imageEndpoints("https://x.com/v1/images/generations")
        assertEquals(listOf("https://x.com/v1/images/generations"), out)
    }

    @Test
    fun `baseUrl 推导候选时优先 v1，且去掉重复`() {
        val out = AutoImageSupport.imageEndpoints("https://x.com/v1")
        assertEquals("https://x.com/v1/images/generations", out.first())
        assertEquals(out.distinct(), out)
        assertTrue(out.contains("https://x.com/images/generations"))
    }

    @Test
    fun `对话端点补 chat completions`() {
        val out = AutoImageSupport.chatEndpoints("https://x.com/v1")
        assertEquals("https://x.com/v1/chat/completions", out.first())
    }

    @Test
    fun `NovelAI 官方请求体不在原生范围`() {
        assertFalse(AutoImageSupport.supportsNative("novelai"))
        assertFalse(AutoImageSupport.supportsNative("novelai-relay"))
        assertTrue(AutoImageSupport.supportsNative("novelai-openai"))
        assertTrue(AutoImageSupport.supportsNative("custom"))
        assertTrue(AutoImageSupport.supportsNative("local"))
        assertTrue(AutoImageSupport.supportsNative("dall-e"))
    }

    // ------------------------------------------------------------ 配置解析

    @Test
    fun `网页配置缺字段时退回默认值而不是整段失败`() {
        val env = AutoImageEnv.parse("""{"config":{"baseUrl":"https://x.com","provider":"custom"}}""")
        assertEquals("https://x.com", env.config.baseUrl)
        assertEquals(832, env.config.width)
        assertEquals(1216, env.config.height)
        assertFalse(env.settings.enabled)
        assertEquals(2, env.settings.maxPerReply)
        assertTrue(env.settings.skipOptimization)
        assertFalse(env.llm.usable)
    }

    @Test
    fun `坏 JSON 也给默认环境`() {
        val env = AutoImageEnv.parse("不是 JSON")
        assertEquals("", env.config.baseUrl)
        assertFalse(env.settings.enabled)
    }

    @Test
    fun `完整配置按字段读进来`() {
        val env = AutoImageEnv.parse(
            """
            {
              "config": {"provider":"novelai-openai","baseUrl":"https://relay/v1","apiKey":"sk-1",
                         "model":"nai-4","width":1024,"height":1024,"negativePrompt":"lowres",
                         "artistTags":"artist:x","qualityTags":"masterpiece"},
              "autoImage": {"enabled":true,"maxPerReply":4,"skipOptimization":false,
                            "usePreset":true,"presetMaxChars":2000},
              "llm": {"baseUrl":"https://llm/v1","apiKey":"sk-2","model":"gpt-4o-mini","temperature":0.5},
              "presetBlock": "写实风格",
              "dnaTags": "silver hair"
            }
            """.trimIndent(),
        )
        assertEquals("novelai-openai", env.config.provider)
        assertEquals(1024, env.config.width)
        assertEquals(4, env.settings.maxPerReply)
        assertEquals(0.5, env.llm.temperature, 0.0001)
        assertTrue(env.llm.usable)
        assertEquals("写实风格", env.presetBlock)
        assertEquals("silver hair", env.dnaTags)
    }

    @Test
    fun `生图请求体带 size 与负向提示词`() {
        val body = JSONObject(AutoImageSupport.buildImageBody(cfg, "1girl, rain"))
        assertEquals("1girl, rain", body.getString("prompt"))
        assertEquals("832x1216", body.getString("size"))
        assertEquals("lowres, bad hands", body.getString("negative_prompt"))
        assertEquals("b64_json", body.getString("response_format"))
        assertEquals(1, body.getInt("n"))
    }
}
