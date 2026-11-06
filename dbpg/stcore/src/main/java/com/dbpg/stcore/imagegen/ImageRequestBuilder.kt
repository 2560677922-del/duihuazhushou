package com.dbpg.stcore.imagegen

import org.json.JSONObject

/**
 * 生图请求体 / 请求头的构造（纯逻辑，可 JVM 单测）。
 *
 * 目前按 OpenAI `/images/generations` 的通用格式构造，绝大多数中转站都吃这一套。
 * 需要额外字段（比如某些站的 `watermark`、`negative_prompt`）时，
 * 通过 [buildOpenAiJson] 的 extra 透传，原生端不必为每家站改代码。
 */
object ImageRequestBuilder {

    /**
     * 构造 Chat 式请求体（/v1/images/generations）。
     * @param extra 额外顶层字段，原样塞进 JSON（可为空）
     */
    fun buildOpenAiJson(
        model: String,
        prompt: String,
        size: String? = null,
        n: Int = 1,
        responseFormat: String = "b64_json",
        quality: String? = null,
        style: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val o = JSONObject()
        o.put("model", model)
        o.put("prompt", prompt)
        if (!size.isNullOrBlank()) o.put("size", size)
        o.put("n", n)
        // 优先要 b64：少一次下载；如果中转站只认 url 也没关系，解析层两种都支持
        if (responseFormat.isNotBlank()) o.put("response_format", responseFormat)
        if (!quality.isNullOrBlank()) o.put("quality", quality)
        if (!style.isNullOrBlank()) o.put("style", style)
        for ((k, v) in extra) if (v != null) o.put(k, v)
        return o.toString()
    }

    /** 构造请求头。有 apiKey 就带 Bearer；extra 覆盖默认。 */
    fun buildHeaders(apiKey: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, image/*, */*"
        if (apiKey.isNotBlank()) h["Authorization"] = "Bearer ${apiKey.trim()}"
        for ((k, v) in extra) if (v.isNotBlank()) h[k] = v
        return h
    }
}
