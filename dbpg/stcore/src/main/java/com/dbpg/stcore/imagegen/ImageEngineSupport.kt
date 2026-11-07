package com.dbpg.stcore.imagegen

import org.json.JSONObject
import kotlin.random.Random

/**
 * 生图引擎（渲染器）抽象层。
 *
 * ## 为什么需要它
 *
 * 各家生图接口的**参数语义完全不同**，硬套 OpenAI 的 `{model,prompt,size}`
 * 会丢一大半功能：
 *
 * - NovelAI 官方/中转：`scale`(CFG)、`sampler`、`steps`、`ucPreset`、
 *   `qualityToggle`、`noise_schedule`、`cfg_rescale`，而且字段要按**模型代际**
 *   裁剪（V4 才有 `autoSmea`，V3 才有 `sm/sm_dyn`，V2 不认 `noise_schedule`）。
 * - 本地部署（SD-WebUI / ComfyUI / 「AI 出图工作台」这类 OpenAI 包装）：
 *   尺寸只认 `size: "832x1216"`，另外接受 `negative_prompt`、`cfg_scale`、
 *   `sampler`、`steps`、`seed`。
 * - OpenAI DALL·E：只认固定尺寸档位和 `quality`，不认采样器。
 *
 * 之前原生只发 OpenAI 那种最小请求体，于是用户选了「砂糖」这种 NovelAI 中转，
 * `sampler / steps / scale` 全被丢掉 —— 看起来「选了没用」。
 * 这里把请求体按引擎分开构造，**每个引擎该发的字段一个不少**，
 * 字段裁剪规则逐条对齐 web 端 `src/engine/novelai.ts` 的 `buildNovelAIRequest`，
 * 保证网页版和原生版发出去的 JSON 一致。
 *
 * 纯逻辑、无 IO，可以直接写 JVM 单测（同输入 → 同 JSON）。
 */

/** 支持的渲染引擎。 */
enum class ImageEngine(
    /** 存进配置里的稳定标识（与 web 端 ImageProvider 对齐，老数据直接可用） */
    val id: String,
    /** 界面显示名 */
    val label: String,
    /** 一句话说明，给用户选的时候看 */
    val hint: String,
) {
    /** NovelAI 官方格式中转（本项目推荐路径） */
    NAI_RELAY("novelai-relay", "NovelAI · 中转", "官方格式 /ai/generate-image，参数最全"),

    /** NovelAI 官方直连 */
    NAI("novelai", "NovelAI · 官方", "api.novelai.net 直连，需要官方 token"),

    /** 本地部署（SD-WebUI / ComfyUI / AI 出图工作台） */
    LOCAL("local", "本地 / 局域网部署", "OpenAI 协议，支持 sampler / steps / cfg"),

    /** 通用 OpenAI 兼容中转 */
    OPENAI("custom", "通用 OpenAI 兼容", "只发 model / prompt / size，兼容性最广"),

    /** DALL·E */
    DALLE("dall-e", "OpenAI DALL·E", "固定尺寸档位，不认采样器");

    /** 这个引擎是否用 NovelAI 那套采样器 / 步数 / CFG 语义。 */
    val usesNaiParams: Boolean get() = this == NAI || this == NAI_RELAY

    /** 是否支持自定义采样器与步数（本地部署多数支持，通用 OpenAI 中转基本不支持）。 */
    val supportsSampler: Boolean get() = usesNaiParams || this == LOCAL

    companion object {
        fun of(id: String?): ImageEngine =
            entries.firstOrNull { it.id == (id ?: "").trim() } ?: OPENAI
    }
}

/**
 * 一次请求的完整参数。字段名与 web 端 ImageGenConfig 对齐，
 * 便于原生与网页共用同一份配置（见 `settings/ConnectionStore.kt`）。
 */
data class ImageParams(
    val engine: ImageEngine,
    val url: String,
    val apiKey: String,
    val model: String,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 832,
    val height: Int = 1216,
    /** NovelAI 叫 scale，本地/OpenAI 叫 cfg_scale */
    val cfgScale: Double = 6.0,
    val steps: Int = 28,
    val sampler: String = "",
    val seed: Long = 0,
    val ucPreset: Int = 0,
    val qualityToggle: Boolean = true,
    val noiseSchedule: String = "",
    val cfgRescale: Double = 0.0,
    val autoSmea: Boolean = false,
    val dynamicThresholding: Boolean = false,
    val sm: Boolean = false,
    val smDyn: Boolean = false,
    /** img2img 底图（纯 base64，可带 dataURL 前缀，构造时自动剥掉） */
    val imageBase64: String? = null,
    val strength: Double = 0.7,
) {
    val sizeText: String get() = "${width}x$height"
}

object ImageEngineSupport {

    /** NovelAI 官方可用采样器（含 _gpu 变体），与 web 端 NOVELAI_SAMPLERS 一致。 */
    val NAI_SAMPLERS = listOf(
        "k_euler", "k_euler_ancestral", "k_dpmpp_2s_ancestral", "k_dpmpp_2m",
        "k_dpmpp_2m_sde", "k_dpmpp_sde", "k_dpmpp_2m_sde_gpu",
        "k_dpmpp_2s_ancestral_gpu", "k_dpmpp_sde_gpu", "ddim", "plms",
        "k_dpmpp_2m_gpu", "k_euler_gpu", "k_euler_ancestral_gpu",
    )

    /** 采样器中文说明，给选择器当副标题。 */
    val SAMPLER_HINTS: Map<String, String> = mapOf(
        "k_euler" to "Euler · 稳定，速度快",
        "k_euler_ancestral" to "Euler Ancestral · 更有变化，V4/V4.5 推荐",
        "k_dpmpp_2s_ancestral" to "DPM++ 2S Ancestral · 细节丰富",
        "k_dpmpp_2m" to "DPM++ 2M · 平滑稳定",
        "k_dpmpp_2m_sde" to "DPM++ 2M SDE · 细节与稳定平衡",
        "k_dpmpp_sde" to "DPM++ SDE · 强细节",
        "k_dpmpp_2m_sde_gpu" to "DPM++ 2M SDE (GPU) · V3 常用",
        "k_dpmpp_2s_ancestral_gpu" to "DPM++ 2S Ancestral (GPU)",
        "k_dpmpp_sde_gpu" to "DPM++ SDE (GPU)",
        "ddim" to "DDIM · 老式，兼容旧图",
        "plms" to "PLMS · 最老式",
        "k_dpmpp_2m_gpu" to "DPM++ 2M (GPU)",
        "k_euler_gpu" to "Euler (GPU)",
        "k_euler_ancestral_gpu" to "Euler Ancestral (GPU)",
    )

    /** NovelAI 官方模型（含 V5）。 */
    val NAI_MODELS: List<Pair<String, String>> = listOf(
        "nai-diffusion-4-5-full" to "V4.5 Full",
        "nai-diffusion-4-5-curated" to "V4.5 Curated（动漫向）",
        "nai-diffusion-5-full" to "V5 Full",
        "nai-diffusion-5-curated" to "V5 Curated",
        "nai-diffusion-4-full" to "V4 Full",
        "nai-diffusion-4-curated-preview" to "V4 Curated",
        "nai-diffusion-3" to "V3（Anime V3）",
        "nai-diffusion-furry-3" to "V3 Furry（兽人向）",
    )

    /** 噪声调度（V3/V4 起支持）。 */
    val NAI_SCHEDULES = listOf("native", "karras", "exponential", "polyexponential")

    /**
     * 小图尺寸快捷档位。
     *
     * 只给「省额度、快出图」的小尺寸 —— 大图留给专业工具。
     * 竖图 / 方图 / 横图各若干，全是 8 的倍数（SD 与 NAI 都对非 8 倍数不友好）。
     */
    val SIZE_PRESETS: List<Triple<String, Int, Int>> = listOf(
        Triple("竖 832×1216", 832, 1216),
        Triple("竖 512×768", 512, 768),
        Triple("竖 768×1024", 768, 1024),
        Triple("方 512×512", 512, 512),
        Triple("方 832×832", 832, 832),
        Triple("方 1024×1024", 1024, 1024),
        Triple("横 1216×832", 1216, 832),
        Triple("横 768×512", 768, 512),
        Triple("横 1024×768", 1024, 768),
    )

    /** V4/V4.5 用 GPU 变体效果更好；按模型代际给默认采样器。 */
    fun defaultSamplerFor(model: String): String = when {
        model.startsWith("nai-diffusion-4") -> "k_euler_ancestral"
        model.startsWith("nai-diffusion-3") -> "k_dpmpp_2m_sde"
        else -> "k_dpmpp_2m_sde"
    }

    /**
     * 按引擎构造请求体。
     *
     * 多带一个不支持的字段就可能被中转端判 422，而且错误信息通常看不清，
     * 所以字段裁剪必须按引擎和模型代际严格分开。
     */
    fun buildBody(p: ImageParams): JSONObject = when (p.engine) {
        ImageEngine.NAI, ImageEngine.NAI_RELAY -> buildNaiBody(p)
        ImageEngine.LOCAL -> buildLocalBody(p)
        ImageEngine.DALLE -> buildDalleBody(p)
        ImageEngine.OPENAI -> buildOpenAiBody(p)
    }

    /** NovelAI 官方格式：`{ input, model, action, parameters:{...} }`。 */
    private fun buildNaiBody(p: ImageParams): JSONObject {
        val model = p.model.ifBlank { "nai-diffusion-4-5-full" }
        // V4 及更新共用一套参数；V3 单独；更早的模型字段最少
        val isV4 = Regex("^nai-diffusion-[45]").containsMatchIn(model)
        val isV3 = Regex("^nai-diffusion-(3|furry-3)").containsMatchIn(model)
        val isOld = !isV4 && !isV3

        val params = JSONObject().apply {
            put("params_version", 3)
            put("width", p.width)
            put("height", p.height)
            put("scale", p.cfgScale)
            put("sampler", p.sampler.ifBlank { defaultSamplerFor(model) })
            put("steps", p.steps)
            put("seed", if (p.seed > 0) p.seed else randomSeed())
            put("n_samples", 1)
            put("ucPreset", p.ucPreset)
            put("qualityToggle", p.qualityToggle)
            put("negative_prompt", p.negativePrompt)
            put("cfg_rescale", p.cfgRescale)
            put("noise_schedule", p.noiseSchedule.ifBlank { if (isV4 || isV3) "karras" else "native" })
            put("legacy", false)
            put("legacy_v3_extend", false)
            put("add_original_image", true)
            put("controlnet_strength", 1)
            put("normalize_reference_strength_multiple", true)
            // web 端 buildNovelAIRequest 会显式给 null；部分中转按字段存在与否做校验
            put("skip_cfg_above_sigma", JSONObject.NULL)
        }
        if (isV4) {
            params.put("use_coords", false)
            params.put("autoSmea", p.autoSmea)
            params.put("dynamic_thresholding", p.dynamicThresholding)
        }
        if (isV3) {
            params.put("sm", p.sm)
            params.put("sm_dyn", p.smDyn)
            params.put("dynamic_thresholding", p.dynamicThresholding)
        }
        if (isOld) {
            // V2 不认这些，带上会被判 422
            params.remove("noise_schedule")
            params.remove("skip_cfg_above_sigma")
        }
        p.imageBase64?.takeIf { it.isNotBlank() }?.let { raw ->
            params.put("image", raw.replace(Regex("^data:image/\\w+;base64,"), ""))
            params.put("strength", p.strength)
            params.put("noise", 0)
            params.put("extra_noise_seed", params.opt("seed"))
            params.put("inpaintImg2ImgStrength", p.strength)
        }
        return JSONObject().apply {
            put("input", p.prompt)
            put("model", model)
            put("action", if (p.imageBase64.isNullOrBlank()) "generate" else "img2img")
            put("parameters", params)
        }
    }

    /**
     * 本地部署（OpenAI 协议的 SD / ComfyUI 包装）。
     *
     * 尺寸**必须**用 `size: "832x1216"`（这类服务不认 width/height）；
     * `steps / cfg_scale / sampler` 只在显式设置时才发，让服务端用它自己的默认，
     * 否则容易踩到「这个采样器不支持」的 4xx。
     */
    private fun buildLocalBody(p: ImageParams): JSONObject {
        val o = JSONObject().apply {
            put("prompt", p.prompt)
            put("n", 1)
            // 一定要 b64_json：本地服务不一定会把图存在能对外提供的路径上
            put("response_format", "b64_json")
            put("seed", if (p.seed > 0) p.seed else randomSeed())
            put("size", p.sizeText)
        }
        if (p.model.isNotBlank()) o.put("model", p.model)
        if (p.steps > 0) o.put("steps", p.steps)
        if (p.cfgScale > 0) o.put("cfg_scale", p.cfgScale)
        if (p.sampler.isNotBlank()) o.put("sampler", p.sampler)
        if (p.negativePrompt.isNotBlank()) o.put("negative_prompt", p.negativePrompt)
        p.imageBase64?.takeIf { it.isNotBlank() }?.let {
            o.put("image", it.replace(Regex("^data:image/\\w+;base64,"), ""))
            o.put("strength", p.strength)
        }
        return o
    }

    /** DALL·E：只认固定尺寸档位 + quality，不认采样器 / 步数 / 负面词。 */
    private fun buildDalleBody(p: ImageParams): JSONObject {
        // DALL·E 3 只接受这几档；其它尺寸会被拒，就近吸附
        val allowed = listOf(1024 to 1024, 1792 to 1024, 1024 to 1792)
        val (w, h) = allowed.minBy { (aw, ah) ->
            val ratio = p.width.toDouble() / p.height
            val ar = aw.toDouble() / ah
            kotlin.math.abs(ratio - ar)
        }
        return JSONObject().apply {
            put("model", p.model.ifBlank { "dall-e-3" })
            put("prompt", p.prompt)
            put("size", "${w}x$h")
            put("n", 1)
            put("response_format", "b64_json")
        }
    }

    /** 通用 OpenAI 兼容中转：字段越少越不容易被拒。 */
    private fun buildOpenAiBody(p: ImageParams): JSONObject = JSONObject().apply {
        put("model", p.model.ifBlank { "flux" })
        put("prompt", p.prompt)
        put("size", p.sizeText)
        put("n", 1)
        put("response_format", "b64_json")
        if (p.negativePrompt.isNotBlank()) put("negative_prompt", p.negativePrompt)
    }

    /**
     * 生成端点候选（按「最可能成功」排序，逐条试）。
     *
     * 复刻 web 端 `candidateNovelAIEndpoints` / `candidateImageEndpoints`：
     * 中转站可能挂 `/ai/generate-image`、`/v1/images/generations` 或别的前缀，
     * 与其让用户猜，不如按经验顺序自动探测。
     */
    fun candidateUrls(engine: ImageEngine, baseUrl: String): List<String> {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) {
            return if (engine == ImageEngine.NAI) listOf("https://api.novelai.net/ai/generate-image") else emptyList()
        }
        val origin = runCatching {
            val u = java.net.URI(raw)
            if (u.scheme == null || u.host == null) null
            else "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }.getOrNull() ?: return emptyList()
        val path = raw.trimEnd('/')

        return when (engine) {
            ImageEngine.NAI -> {
                // 官方固定这一个路径
                if (Regex("/ai/generate-image$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) listOf(path)
                else linkedSetOf("$origin/ai/generate-image", "$path/ai/generate-image").toList()
            }
            ImageEngine.NAI_RELAY -> {
                if (Regex("/ai/generate-image$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
                    listOf(path)
                } else {
                    linkedSetOf(
                        "$origin/ai/generate-image",
                        "$path/ai/generate-image",
                        "$origin/v1/images/generations",
                        "$path/images/generations",
                    ).toList()
                }
            }
            ImageEngine.LOCAL -> {
                if (Regex("/images/generations$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) listOf(path)
                else linkedSetOf("$path/v1/images/generations", "$path/images/generations").toList()
            }
            else -> {
                if (Regex("/images/generations$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) listOf(path)
                else {
                    val stripped = path.replace(Regex("/(v\\d+|api)(/.*)?$", RegexOption.IGNORE_CASE), "")
                    val base = stripped.ifEmpty { origin }
                    linkedSetOf(
                        "$origin/v1/images/generations",
                        "$origin/images/generations",
                        "$base/v1/images/generations",
                        "$path/v1/images/generations",
                        "$path/images/generations",
                    ).toList()
                }
            }
        }
    }

    /** 随机 seed（0 表示让服务端自己取）。 */
    fun randomSeed(): Long = Random.nextLong(1, 4294967295L)
}

/** 与 web 端同名的简写，避免调用方写长名。 */
val NAI_SAMPLERS: List<String> get() = ImageEngineSupport.NAI_SAMPLERS
