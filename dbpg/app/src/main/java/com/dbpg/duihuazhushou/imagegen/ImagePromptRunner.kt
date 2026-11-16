package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.imagegen.NativeImageConfig
import com.dbpg.stcore.imagegen.NativeLlmConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 「文字 → 画面标签」这条**独立支线**。
 *
 * ## 为什么单独拎出来
 *
 * 出图之前要先让语言模型把剧情改写成 Danbooru 标签。这一步：
 *
 * - **不产生任何聊天内容** —— 它只往生图模型递一份 prompt，结果只有图片回气泡，
 *   不插消息、不写正文、不改会话；
 * - **跟聊天请求体无关** —— 用哪个模型、哪套破甲，都是生图自己的事，
 *   跟当前对话用的预设/世界书没有耦合；
 * - **失败了就不出图** —— 模型拒答、返回中文长句、一个字都没给，都要当场
 *   停下并把原因交回界面。拿一段"抱歉，我无法…"去调生图模型，出来的是一张
 *   和剧情毫无关系的废图：图花了额度、用户还得手动删。宁可这次不画。
 *
 * 所以它被做成一个可以单独调用的对象：给一段描述，还一段标签或一句失败原因。谁调都行
 * （聊天 🎨、自动配图、以后的独立出图入口），行为完全一致 ——
 * 这是之前三处各写一套、结果"标签页带画风、聊天里不带"的教训。
 *
 * ## 拼 system 的顺序
 *
 * ```
 * [任务模板]        告诉模型任务是什么（剧情 → 标签），来自「风格」
 * [预设片段]        资产页里那套预设的已启用段（破甲 / 尺度说明）
 * [角色 DNA]        外观锚定，权重高，放后面
 * [输出约束]        固定一句：只准输出标签 JSON
 * ```
 *
 * 画风（画师串 / 质量词 / 负面 / 采样）**不在这里**：它们只作用于
 * 「标签 → 图片」那一步，由 [AutoImageSupport.finalPrompt] 拼，来源是「风格」。
 */
object ImagePromptRunner {

    /** 一次「文字 → 标签」的结果。失败时调用方**不要再出图**。 */
    sealed interface Outcome {
        data class Ok(val tags: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 一次优化要发的两条消息。 */
    data class Messages(
        val system: String,
        val user: String,
        val temperature: Double,
        val maxTokens: Int,
        /** 本次按什么预设跑的，给界面提示用。 */
        val note: String,
    )

    /** 标签长度够用就行；给太大反而让模型开始"发挥"。 */
    private const val MAX_TOKENS_CAP = 1200
    private const val MAX_TOKENS_FLOOR = 300

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 组 messages。
     *
     * @param config       生图配置（只取 promptTemplate / artistTags 这类"任务说明"要素）
     * @param llm          标签模型配置；温度与长度从这里取，再按"打标签"收敛
     * @param dnaTags      角色外观标签（英文），恒拼进 system 中后段
     * @param description  要转成标签的那段文字（一段画面描述，或整段上文）
     */
    fun build(
        context: Context,
        config: NativeImageConfig,
        llm: NativeLlmConfig,
        dnaTags: String,
        description: String,
        contextLabel: String = "画面描述",
    ): Messages {
        // 预设是"锦上添花"，读不出来也得出图 —— 任何异常都退化成"不用预设"，
        // 绝不能让它把整次生图带走（这是 v1.7 之后最容易崩的一处）。
        val plan = runCatching { PromptPresetPlugin.plan(context) }
            .getOrElse { PromptPresetPlugin.Plan.none("预设读取失败") }
        val (baseSystem, user) = AutoImageSupport.renderTemplate(
            template = config.promptTemplate,
            contextText = description,
            contextLabel = contextLabel,
            artistTags = config.artistTags,
        )
        val dnaBlock = if (dnaTags.isBlank()) {
            ""
        } else {
            "人物外观参考（提示词中必须保留这些特征）：$dnaTags"
        }
        // 顺序见 AutoImageSupport.composeSystem：模板 → 预设 → DNA → 约束
        //
        // 约束只在真的注入了预设时才加：没注入任何"身份与尺度设定"却说
        // "以上设定只为解除限制"，模型会莫名其妙（也白占 token）。
        val system = AutoImageSupport.composeSystem(
            task = baseSystem,
            presetBlock = plan.block,
            dnaBlock = dnaBlock,
            suffix = if (plan.block.isBlank()) "" else PromptPresetPlugin.OUTPUT_CONSTRAINT,
        )

        return Messages(
            system = system,
            user = user,
            // 写作预设爱用 1.0~1.3 的温度，那对"打标签"只有跑题的份
            temperature = llm.temperature.coerceIn(0.0, 0.9),
            maxTokens = llm.maxTokens.coerceIn(MAX_TOKENS_FLOOR, MAX_TOKENS_CAP),
            note = plan.note,
        )
    }

    /**
     * 发一次 chat/completions，拿回标签。
     *
     * 每条回复都要过 [AutoImageSupport.judgeOptimized]：拒答、空回复、
     * 中文长句一律算失败。一个端点失败了就换下一个候选——网关之间差别很大，
     * 第一条挂掉不代表整次都废。
     *
     * @return [Outcome.Ok] 带可用标签；[Outcome.Failed] 带一句能直接显示的原因
     */
    fun optimize(llm: NativeLlmConfig, messages: Messages): Outcome {
        if (!llm.usable) return Outcome.Failed("没有可用的提示词模型（检查密钥与模型名）")
        val body = AutoImageSupport.buildOptimizeBody(
            model = llm.model,
            system = messages.system,
            user = messages.user,
            temperature = messages.temperature,
            maxTokens = messages.maxTokens,
        )
        var answered = false
        var lastReason = ""
        for (url in AutoImageSupport.chatEndpoints(llm.baseUrl)) {
            val json = postJson(url, llm.apiKey, body) ?: continue
            answered = true
            val check = AutoImageSupport.judgeOptimized(
                AutoImageSupport.extractCompletionText(json),
            )
            if (check.ok) return Outcome.Ok(check.tags)
            lastReason = check.reason
        }
        return Outcome.Failed(
            when {
                !answered -> "提示词模型没有响应"
                lastReason.isNotBlank() -> lastReason
                else -> "模型没给出可用标签"
            },
        )
    }

    /**
     * 一步到位：组 messages + 发请求。
     *
     * ★ 失败**不退回原文**。原文是中文剧情，直接当 prompt 送去生图就是一张废图；
     * 由调用方把 [Outcome.Failed.reason] 显示出来，比默默画错强。
     */
    fun optimizeOrFail(
        context: Context,
        config: NativeImageConfig,
        llm: NativeLlmConfig,
        dnaTags: String,
        description: String,
        contextLabel: String = "画面描述",
    ): Outcome {
        val msgs = build(context, config, llm, dnaTags, description, contextLabel)
        return optimize(llm, msgs)
    }

    /** 拼最终正向提示词：画师串 → 质量词 → DNA → 画面标签。 */
    fun compose(config: NativeImageConfig, optimized: String, rawText: String, dnaTags: String): String =
        AutoImageSupport.finalPrompt(config, optimized, rawText, dnaTags)

    private fun postJson(url: String, apiKey: String, json: String): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()
}
