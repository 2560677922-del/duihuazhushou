package com.dbpg.stcore

import com.dbpg.stcore.imagegen.ImageFormat
import com.dbpg.stcore.imagegen.ImageRequestBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRequestTest {

    @Test
    fun `MIME 映射扩展名`() {
        assertEquals("png", ImageFormat.extFor("image/png"))
        assertEquals("jpg", ImageFormat.extFor("image/jpeg"))
        assertEquals("webp", ImageFormat.extFor("image/webp"))
        assertEquals("gif", ImageFormat.extFor("image/gif"))
        assertEquals("png", ImageFormat.extFor("application/octet-stream"))
    }

    @Test
    fun `isImage 判断`() {
        assertTrue(ImageFormat.isImage("image/png"))
        assertFalse(ImageFormat.isImage("application/json"))
        assertFalse(ImageFormat.isImage(null))
    }

    @Test
    fun `构造 OpenAI 请求体`() {
        val json = ImageRequestBuilder.buildOpenAiJson(
            model = "flux",
            prompt = "一只猫",
            size = "1024x1024",
            n = 1,
            responseFormat = "b64_json",
        )
        val o = JSONObject(json)
        assertEquals("flux", o.getString("model"))
        assertEquals("一只猫", o.getString("prompt"))
        assertEquals("1024x1024", o.getString("size"))
        assertEquals(1, o.getInt("n"))
        assertEquals("b64_json", o.getString("response_format"))
    }

    @Test
    fun `请求体支持 extra 透传`() {
        val json = ImageRequestBuilder.buildOpenAiJson(
            model = "m",
            prompt = "p",
            extra = mapOf("watermark" to false, "negative_prompt" to "blurry"),
        )
        val o = JSONObject(json)
        assertFalse(o.getBoolean("watermark"))
        assertEquals("blurry", o.getString("negative_prompt"))
    }

    @Test
    fun `请求头带 Bearer`() {
        val h = ImageRequestBuilder.buildHeaders("sk-123")
        assertEquals("Bearer sk-123", h["Authorization"])
        assertEquals("application/json", h["Content-Type"])
    }

    @Test
    fun `无 key 时不带 Authorization`() {
        val h = ImageRequestBuilder.buildHeaders("")
        assertFalse(h.containsKey("Authorization"))
    }

    @Test
    fun `请求头 extra 覆盖默认`() {
        val h = ImageRequestBuilder.buildHeaders("sk", mapOf("Accept" to "image/png"))
        assertEquals("image/png", h["Accept"])
    }
}
