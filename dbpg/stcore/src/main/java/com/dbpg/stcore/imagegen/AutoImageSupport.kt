package com.dbpg.stcore.imagegen

import com.dbpg.stcore.parseImageMarkers
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动配图的**纯逻辑**部分：配置解析、标记限额、提示词拼装、请求体构造、端点推导。
 *
 * 刻意不碰网络、不碰文件、不碰 Android —— 这样所有"拼错了 / 解析错了"的问题
 * 都能在 JVM 单测里暴露，而不用真机跑一次生图（一次几十秒，还费额度）。
 *
 * 网络与落盘分别在 [com.dbpg.duihuazhushou.imagegen.HttpImageClient] 与
 * `AutoImageService`；本对象只回答"该发什么、收到什么算数"。
 *
 * 与网页 `engine/imageGen.ts` 的对齐点：
 *   - 默认提示词模板、默认负向提示词逐字一致；
 *   - `composePrompt` 的"画师串 → 质量词 → 模型标签"顺序与去重规则一致；
 *   - `stripStyleTags` 剔除画师/质量词，避免模型自己决定画风；
 *   - 端点候选顺序与 `candidateImageEndpoints` 一致。
 */

/** 一次自动配图的目标：正文里的一个 `[[IMG: 描述]]`。 */
data class AutoImageTarget(
    val description: String,
    val markdownIndex: Int,
    /** 这个标记在原文里的字符位置（同一描述出现多次时用来区分） */
    val charIndex: Int = 0,
)

/** 从网页配置里读出来的、原生发请求需要的最小集合。 */
data class NativeImageConfig(
    val provider: String = "custom",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
    val width: Int = 832,
    val height: Int = 1216,
    val negativePrompt: String = "",
    val artistTags: String = "",
    val qualityTags: String = "",
    val promptTemplate: String = "",
) {
    val size: String get() = "${width}x$height"
    val usable: Boolean get() = baseUrl.isNotBlank() || endpoint.isNotBlank()
}

/** 自动配图设置（与 web `AutoImageSettings` 对应）。 */
data class NativeAutoImageSettings(
    val enabled: Boolean = false,
    val maxPerReply: Int = 2,
    val skipOptimization: Boolean = true,
    val usePreset: Boolean = false,
    val presetMaxChars: Int = 4000,
)

/** 提示词优化要用的对话模型配置。 */
data class NativeLlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 900,
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 网页一次下发的整套生图环境（配置 + 开关 + 可选的预设/DNA 片段）。 */
data class AutoImageEnv(
    val config: NativeImageConfig = NativeImageConfig(),
    val settings: NativeAutoImageSettings = NativeAutoImageSettings(),
    val llm: NativeLlmConfig = NativeLlmConfig(),
    val presetBlock: String = "",
    val dnaTags: String = "",
) {
    companion object {
        /**
         * 解析网页 `__dhpGen.imageConfig()` 返回的 JSON。
         *
         * 结构刻意做得"能缺就缺"：网页少给一个字段，原生就退回默认值，
         * 不要因为多了一个版本字段就整段配图罢工。
         */
        fun parse(json: String?): AutoImageEnv {
            val root = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return AutoImageEnv()
            val cfg = root.optJSONObject("config") ?: JSONObject()
            val auto = root.optJSONObject("autoImage") ?: JSONObject()
            val llm = root.optJSONObject("llm") ?: JSONObject()

            return AutoImageEnv(
                config = NativeImageConfig(
                    provider = cfg.optString("provider", "custom"),
                    baseUrl = cfg.optString("baseUrl", ""),
                    apiKey = cfg.optString("apiKey", ""),
                    model = cfg.optString("model", cfg.optString("novelaiModel", "")),
                    endpoint = cfg.optString("endpoint", ""),
                    width = cfg.optInt("width", 832),
                    height = cfg.optInt("height", 1216),
                    negativePrompt = cfg.optString("negativePrompt", ""),
                    artistTags = cfg.optString("artistTags", ""),
                    qualityTags = cfg.optString("qualityTags", ""),
                    promptTemplate = cfg.optString("promptTemplate", ""),
                ),
                settings = NativeAutoImageSettings(
                    enabled = auto.optBoolean("enabled", false),
                    maxPerReply = auto.optInt("maxPerReply", 2),
                    skipOptimization = auto.optBoolean("skipOptimization", true),
                    usePreset = auto.optBoolean("usePreset", false),
                    presetMaxChars = auto.optInt("presetMaxChars", 4000),
                ),
                llm = NativeLlmConfig(
                    baseUrl = llm.optString("baseUrl", ""),
                    apiKey = llm.optString("apiKey", ""),
                    model = llm.optString("model", ""),
                    temperature = llm.optDouble("temperature", 0.7),
                    maxTokens = llm.optInt("maxTokens", 900),
                ),
                presetBlock = root.optString("presetBlock", ""),
                dnaTags = root.optString("dnaTags", ""),
            )
        }
    }
}

object AutoImageSupport {

    /** 与网页 `DEFAULT_PROMPT_TEMPLATE` 逐字一致（换模板时两边要一起改）。 */
    const val DEFAULT_PROMPT_TEMPLATE: String = """把下面的{{contextLabel}}转成英文 Danbooru 画面标签。

规则：
1. 英文标签，逗号分隔，重要的放前面。
2. 描述角色（人数、发色发型、瞳色、服装、表情）与环境（背景、时间、天气、光线、镜头）。
3. 只写场景里真实存在或能自然推断的内容，不要臆造。
4. **动作与互动要写具体**：谁在做什么、和谁或什么东西互动、
   手在接触什么、身体朝向、视线看向哪里。用 2~4 个短语说清，
   不要只写 standing / sitting 这种笼统词。
5. 只输出 JSON，不要解释。

输出格式：
{"prompt": "标签，英文逗号分隔", "tags": ["主要标签"]}"""

    const val DEFAULT_NEGATIVE_PROMPT: String =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, " +
            "fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, " +
            "signature, watermark, username, blurry, artist name"

    /** 质量/画风词根：这些一律交给配置，不让模型决定。 */
    private val STYLE_WORDS = listOf(
        "masterpiece", "best quality", "high quality", "top quality", "amazing quality",
        "very aesthetic", "aesthetic", "absurdres", "highres", "ultra detailed",
        "extremely detailed", "detailed", "no text", "official art", "4k", "8k",
        "best anatomy", "perfect anatomy",
    )

    // ------------------------------------------------------------ 标记

    /**
     * 从正文里取要配图的位置，最多 [limit] 个。
     *
     * 上限夹到 [1, 10]：提示词里告诉模型"最多几张"时也是这个范围，
     * 否则设置页填 20 会出现"提示词说 10 张、实际生 20 张"的错位。
     */
    fun plan(text: String, limit: Int): List<AutoImageTarget> {
        if (text.isBlank()) return emptyList()
        val max = limit.coerceIn(1, 10)
        return parseImageMarkers(text)
            .asSequence()
            .filter { it.description.isNotBlank() }
            .take(max)
            .map { AutoImageTarget(it.description, it.markdownIndex, it.index) }
            .toList()
    }

    // ------------------------------------------------------------ 提示词

    /** 默认负向提示词：配置为空时兜底。 */
    fun negativePrompt(config: NativeImageConfig): String =
        config.negativePrompt.ifBlank { DEFAULT_NEGATIVE_PROMPT }

    /** 把模板里的占位符换成实际内容，返回 system / user 两条。 */
    fun renderTemplate(
        template: String,
        contextText: String,
        contextLabel: String,
        artistTags: String = "",
    ): Pair<String, String> {
        val tpl = template.ifBlank { DEFAULT_PROMPT_TEMPLATE }
        val system = tpl
            .replace(Regex("\\{\\{\\s*contextLabel\\s*}}"), contextLabel)
            .replace(Regex("\\{\\{\\s*contextText\\s*}}"), contextText)
            .replace(Regex("\\{\\{\\s*artistTags\\s*}}"), artistTags)
        val user = "以下是${contextLabel}内容：\n\n$contextText\n\n请提炼视觉要素并输出提示词（严格 JSON）。"
        return system to user
    }

    /**
     * 拼最终正向提示词：画师串 → 质量标签 → 主体标签，逗号切分并去重。
     *
     * 去重按小写比较但保留原始大小写：`1Girl` 和 `1girl` 是同一个标签，
     * 但没必要把用户的写法改掉。
     */
    fun composePrompt(parts: List<String?>): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (part in parts) {
            if (part.isNullOrBlank()) continue
            for (raw in part.split(',')) {
                val tag = raw.trim()
                if (tag.isEmpty()) continue
                val key = tag.lowercase()
                if (!seen.add(key)) continue
                out.add(tag)
            }
        }
        return out.joinToString(", ")
    }

    /**
     * 剔除模型返回里混进来的画师 / 质量词。
     *
     * 模板里已经写了"不要输出这些"，但小模型经常不听话；
     * 这一步是兜底，保证最终画风只由配置决定。
     */
    fun stripStyleTags(tags: String): String {
        if (tags.isBlank()) return ""
        return tags.split(',')
            .map { it.trim() }
            .filter { raw ->
                if (raw.isEmpty()) return@filter false
                val bare = raw
                    .replace(Regex("^\\(+|\\)+$"), "")
                    .replace(Regex(":\\s*[\\d.]+$"), "")
                    .trim()
                    .lowercase()
                if (bare.isEmpty()) return@filter false
                if (bare.startsWith("artist:")) return@filter false
                STYLE_WORDS.none { bare == it || bare.startsWith("$it ") }
            }
            .joinToString(", ")
    }

    /**
     * 组装最终提示词。
     *
     * @param optimized 已经过 LLM 优化（或用户直接给的描述）；为空时退回原文
     * @param rawText   正文原文，只在前两者都空时兜底，避免提示词变空
     */
    fun finalPrompt(
        config: NativeImageConfig,
        optimized: String,
        rawText: String,
    ): String {
        val tagsOnly = stripStyleTags(optimized)
        val body = tagsOnly.ifBlank { rawText.trim() }
        return composePrompt(listOf(config.artistTags, config.qualityTags, body))
    }

    // ------------------------------------------------------------ 请求体

    /** OpenAI chat/completions 请求体（提示词优化）。 */
    fun buildOptimizeBody(
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.7,
        maxTokens: Int = 900,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .toString()
    }

    /**
     * 从 chat/completions 的响应里取正文。
     * 兼容 `choices[0].message.content` / `choices[0].text` / 顶层 `content`。
     */
    fun extractCompletionText(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        root.optJSONArray("choices")?.optJSONObject(0)?.let { c ->
            c.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }?.let { return it }
            c.optString("text", "").takeIf { it.isNotBlank() }?.let { return it }
        }
        return root.optString("content", "")
    }

    /**
     * 从模型回复里抠出 `prompt`。
     *
     * 优先取 JSON 里的 prompt 字段；模型没按格式回（或返回被截断）时，
     * 把整段当标签用 —— 总比一滴提示词都没有强。
     */
    fun parseOptimizedPrompt(reply: String): String {
        val text = reply.trim()
        if (text.isEmpty()) return ""
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val obj = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            val p = obj?.optString("prompt", "").orEmpty().trim()
            if (p.isNotEmpty()) return p
        }
        return text
            .replace(Regex("```[a-zA-Z]*"), "")
            .replace("```", "")
            .trim()
    }

    /** OpenAI 图像接口的 size 字段。 */
    fun openAiSize(config: NativeImageConfig): String =
        "${config.width.coerceIn(64, 4096)}x${config.height.coerceIn(64, 4096)}"

    /** 生图请求体（OpenAI 风格网关 / 自建本地服务都吃这一套）。 */
    fun buildImageBody(config: NativeImageConfig, prompt: String): String {
        val o = JSONObject()
            .put("model", config.model.ifBlank { "dall-e-3" })
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", openAiSize(config))
            .put("response_format", "b64_json")
        // 负向提示词不是 OpenAI 官方字段，但大量中转站认它；有就带上。
        val neg = negativePrompt(config)
        if (neg.isNotBlank()) o.put("negative_prompt", neg)
        return o.toString()
    }

    // ------------------------------------------------------------ 端点

    /**
     * 生图端点候选（按网页 `candidateImageEndpoints` 的顺序）。
     *
     * 用户显式填了 `/images/generations` 就只认它 —— 那是"我已经知道地址"，
     * 再猜别的只会把 401 之外的错误也当成路径问题。
     */
    fun imageEndpoints(baseUrl: String, explicit: String = ""): List<String> {
        val exact = explicit.trim()
        if (exact.isNotEmpty()) return listOf(exact)
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.isEmpty()) return emptyList()
        if (Regex("/images/generations$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return listOf(raw)

        val origin = runCatching { java.net.URI(raw).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return listOf(raw)
        val base = raw.replace(Regex("/(v\\d+|api)(/.*)?$", RegexOption.IGNORE_CASE), "").ifBlank { origin }

        return listOf(
            "$origin/v1/images/generations",
            "$origin/images/generations",
            "$base/v1/images/generations",
            "$raw/v1/images/generations",
            "$raw/images/generations",
        ).distinct()
    }

    /** 对话端点候选：先补 `/v1/chat/completions`，再试用户直接填的完整地址。 */
    fun chatEndpoints(baseUrl: String): List<String> {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.isEmpty()) return emptyList()
        if (Regex("/chat/completions$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return listOf(raw)
        val origin = runCatching { java.net.URI(raw).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return listOf(raw)
        val base = raw.replace(Regex("/(v\\d+|api)(/.*)?$", RegexOption.IGNORE_CASE), "").ifBlank { origin }
        return listOf(
            "$base/v1/chat/completions",
            "$origin/v1/chat/completions",
            "$raw/chat/completions",
        ).distinct()
    }

    /**
     * 这个 provider 原生能不能自己发请求。
     *
     * NovelAI 官方 / 官方中转用的是另一套请求体（ZIP 包 + 专有字段），
     * 不在本轮范围；返回 false 时调用方应明确提示"改用网页生成"，
     * 而不是悄悄失败留下一堆空占位符。
     */
    fun supportsNative(provider: String): Boolean = when (provider.lowercase()) {
        "novelai", "novelai-relay" -> false
        else -> true
    }
}
