package com.dbpg.stcore

import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import com.dbpg.stcore.imagegen.ImageParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图渲染器的纯逻辑测试。
 *
 * 真机上要花额度、等十几秒才能发现「选了采样器却没进请求体」「本地部署
 * 不认 width/height」「V2 多带字段被判 422」，这里一次跑完。
 * 字段裁剪规则逐条对齐 web 端 `src/engine/novelai.ts` 的 buildNovelAIRequest，
 * 改任意一侧时两侧都要跟着改。
 */
class ImageEngineSupportTest {

    private fun body(engine: ImageEngine, model: String = "nai-diffusion-4-5-full"): JSONObject =
        ImageEngineSupport.buildBody(
            ImageParams(
                engine = engine,
                url = "https://relay.test/v1",
                apiKey = "sk-test",
                model = model,
                prompt = "1girl, forest",
                negativePrompt = "lowres",
                width = 832,
                height = 1216,
                cfgScale = 5.5,
                steps = 28,
                sampler = "k_euler_ancestral",
                seed = 12345,
                noiseSchedule = "karras",
            ),
        )

    @Test
    fun `NovelAI 中转发官方格式，采样参数一个不少`() {
        val b = body(ImageEngine.NAI_RELAY)
        assertEquals("1girl, forest", b.getString("input"))
        assertEquals("nai-diffusion-4-5-full", b.getString("model"))
        assertEquals("generate", b.getString("action"))
        val p = b.getJSONObject("parameters")
        assertEquals(832, p.getInt("width"))
        assertEquals(1216, p.getInt("height"))
        assertEquals(5.5, p.getDouble("scale"), 0.0001)
        assertEquals("k_euler_ancestral", p.getString("sampler"))
        assertEquals(28, p.getInt("steps"))
        assertEquals(12345L, p.getLong("seed"))
        assertEquals("lowres", p.getString("negative_prompt"))
        assertEquals("karras", p.getString("noise_schedule"))
        assertEquals(3, p.getInt("params_version"))
        assertTrue(p.has("skip_cfg_above_sigma"))
        assertTrue(p.isNull("skip_cfg_above_sigma"))
    }

    @Test
    fun `V4 有 autoSmea，V3 有 sm，老模型不带 noise_schedule`() {
        val v4 = body(ImageEngine.NAI_RELAY).getJSONObject("parameters")
        assertTrue(v4.has("autoSmea"))
        assertFalse(v4.has("sm"))

        val v3 = body(ImageEngine.NAI, "nai-diffusion-3").getJSONObject("parameters")
        assertTrue(v3.has("sm"))
        assertTrue(v3.has("sm_dyn"))
        assertFalse(v3.has("autoSmea"))

        val old = body(ImageEngine.NAI, "nai-diffusion-2").getJSONObject("parameters")
        assertFalse(old.has("noise_schedule"))
        assertFalse(old.has("skip_cfg_above_sigma"))
    }

    @Test
    fun `NovelAI 官方直连与中转请求体一致`() {
        assertEquals(
            body(ImageEngine.NAI_RELAY).toString(),
            body(ImageEngine.NAI).toString(),
        )
    }

    @Test
    fun `本地部署只认 size 字符串，可选参数带进请求`() {
        val b = body(ImageEngine.LOCAL, "sdxl")
        assertEquals("832x1216", b.getString("size"))
        assertFalse(b.has("width"))
        assertFalse(b.has("height"))
        assertEquals("sdxl", b.getString("model"))
        assertEquals(28, b.getInt("steps"))
        assertEquals(5.5, b.getDouble("cfg_scale"), 0.0001)
        assertEquals("k_euler_ancestral", b.getString("sampler"))
        assertEquals("lowres", b.getString("negative_prompt"))
    }

    @Test
    fun `通用 OpenAI 兼容只发最小字段`() {
        val b = body(ImageEngine.OPENAI, "flux")
        assertEquals("flux", b.getString("model"))
        assertEquals("832x1216", b.getString("size"))
        assertFalse(b.has("steps"))
        assertFalse(b.has("sampler"))
    }

    @Test
    fun `DALL-E 尺寸吸附到固定档位`() {
        assertEquals("1024x1792", body(ImageEngine.DALLE).getString("size"))
        val wide = ImageEngineSupport.buildBody(
            ImageParams(engine = ImageEngine.DALLE, url = "", apiKey = "", model = "", prompt = "x", width = 1216, height = 832),
        )
        assertEquals("1792x1024", wide.getString("size"))
    }

    @Test
    fun `端点候选覆盖 NovelAI 与本地两种挂载方式`() {
        val nai = ImageEngineSupport.candidateUrls(ImageEngine.NAI_RELAY, "https://relay.test/v1")
        assertTrue(nai.first().endsWith("/ai/generate-image"))
        assertTrue(nai.any { it.contains("/v1/images/generations") })

        val local = ImageEngineSupport.candidateUrls(ImageEngine.LOCAL, "http://192.168.1.9:7860")
        assertTrue(local.all { it.contains("/images/generations") })
        assertTrue(local.first().contains("192.168.1.9"))
    }

    @Test
    fun `尺寸档位全是小图且为 8 的倍数`() {
        val presets = ImageEngineSupport.SIZE_PRESETS
        assertEquals(9, presets.size)
        for ((_, w, h) in presets) {
            assertEquals(0, w % 8)
            assertEquals(0, h % 8)
            assertTrue("${w}x${h} 应该是小图", w * h <= 1024 * 1216)
        }
        assertTrue(presets.any { it.second == 832 && it.third == 1216 })
        assertTrue(presets.any { it.second == 1216 && it.third == 832 })
        assertTrue(presets.any { it.second == 512 && it.third == 512 })
    }
}
