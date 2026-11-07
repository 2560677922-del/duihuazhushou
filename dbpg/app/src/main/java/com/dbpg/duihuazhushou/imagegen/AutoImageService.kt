package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbpg.stcore.imagegen.AutoImageEnv
import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageParams
import com.dbpg.stcore.session.ActionImage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生自动配图：把 AI 正文里的 `[[IMG: 描述]]` 一个个画出来。
 *
 * ★ 为什么不经 web 生成通道：
 *   自动配图 = "把一段正文交给对话模型打标签" + "拿标签调一次生图接口"。
 *   这两步**跟 ST 预设、世界书、正则、变量完全无关** —— 模型输出什么、
 *   请求体长什么样都是固定的。绕 web 只会多一次往返、多一份可能不同步的状态。
 *   所以原生直接从 web 读一份配置快照（[AutoImageSupport] 的 AutoImageEnv），
 *   之后全部自己发请求。
 *
 * 并发策略：**串行**。中转站大多有严格并发限制，并且一次回复通常只配 1~2 张；
 * 串行能保证"第一张出来马上就能看到"，比并发一起等 30 秒体验好得多。
 *
 * 失败处理：某个端点 404/401 就换下一个候选（与网页 candidateImageEndpoints 一致），
 * 单张失败不影响其他张；全部失败时 [onDone] 仍会回调，界面只需要"停止转圈"。
 */
object AutoImageService {

    /** 一张图出好了（主线程）。 */
    fun interface ImageCallback {
        fun onImage(image: ActionImage)
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-autoimage") }
    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 跑一次自动配图。
     *
     * @param text    这一轮 AI 的完整正文（**含** `[[IMG:]]` 标记）
     * @param onImage 每出好一张调一次，调用方负责写进会话并刷新列表
     * @param onDone  全部结束（成功几张算几张，不会因为某张失败就中断）
     */
    fun run(
        context: Context,
        text: String,
        env: AutoImageEnv,
        onImage: ImageCallback,
        onDone: () -> Unit,
    ) {
        if (!env.settings.enabled || !env.config.usable) {
            onDone()
            return
        }
        val targets = AutoImageSupport.plan(text, env.settings.maxPerReply)
        if (targets.isEmpty()) {
            onDone()
            return
        }

        io.execute {
            val service = ImageGenService(context)
            try {
                for (t in targets) {
                    val image = runCatching { drawOne(service, env, t.description) }.getOrNull()
                    if (image != null) {
                        val att = ActionImage(
                            id = "img_" + System.currentTimeMillis().toString(36) +
                                (0..999).random(),
                            description = t.description,
                            markdownIndex = t.markdownIndex,
                            prompt = image.prompt,
                            localPath = image.path,
                        )
                        main.post { onImage.onImage(att) }
                    }
                }
            } finally {
                service.shutdown()
                main.post { onDone() }
            }
        }
    }

    private data class Drawn(val path: String, val prompt: String)

    private fun drawOne(
        service: ImageGenService,
        env: AutoImageEnv,
        description: String,
    ): Drawn? {
        val optimized = if (env.settings.skipOptimization) {
            description
        } else {
            optimize(env, description).ifBlank { description }
        }
        val prompt = AutoImageSupport.finalPrompt(env.config, optimized, description)
        if (prompt.isBlank()) return null

        val c = env.config
        // 与标签生图/快捷生图共用同一套渲染器：NovelAI 中转要发官方格式，
        // 一律发 OpenAI 最小请求体会把 sampler / steps / CFG 全丢掉。
        val engine = ImageEngine.of(c.engine)
        val endpoints = AutoImageSupport.imageEndpoints(c.baseUrl, c.endpoint)
        for (url in endpoints) {
            val params = ImageParams(
                engine = engine,
                url = url,
                apiKey = c.apiKey,
                model = c.model,
                prompt = prompt,
                negativePrompt = c.negativePrompt,
                width = c.width,
                height = c.height,
                cfgScale = c.cfgScale,
                steps = c.steps,
                sampler = c.sampler,
                noiseSchedule = c.noiseSchedule,
            )
            val req = ImageGenService.Request(
                url = url,
                apiKey = c.apiKey,
                model = c.model,
                prompt = prompt,
                size = c.size,
                n = 1,
                responseFormat = "b64_json",
                params = params,
            )
            val imgs = runCatching { service.generateSync(req) }.getOrNull()
            val first = imgs?.firstOrNull()
            if (first != null) return Drawn(first.path, prompt)
        }
        return null
    }

    /**
     * 用对话模型把画面描述转成 Danbooru 标签。
     *
     * 失败一律退回原始描述 —— 宁可出得糙一点，也不能因为优化失败就不出图。
     */
    private fun optimize(env: AutoImageEnv, description: String): String {
        val llm = env.llm
        if (!llm.usable) return description

        val (system, user) = AutoImageSupport.renderTemplate(
            template = env.config.promptTemplate,
            contextText = buildString {
                if (env.presetBlock.isNotBlank()) append(env.presetBlock).append("\n\n")
                if (env.dnaTags.isNotBlank()) append(env.dnaTags).append("\n\n")
                append(description)
            },
            contextLabel = "画面描述",
            artistTags = env.config.artistTags,
        )
        val body = AutoImageSupport.buildOptimizeBody(
            model = llm.model,
            system = system,
            user = user,
            temperature = llm.temperature,
            maxTokens = llm.maxTokens,
        )

        for (url in AutoImageSupport.chatEndpoints(llm.baseUrl)) {
            val json = postJson(url, llm.apiKey, body) ?: continue
            val reply = AutoImageSupport.extractCompletionText(json)
            val prompt = AutoImageSupport.parseOptimizedPrompt(reply)
            if (prompt.isNotBlank()) return prompt
        }
        return description
    }

    /** 发一次 POST，返回响应正文；非 2xx 或网络异常返回 null。 */
    private fun postJson(url: String, apiKey: String, json: String): String? {
        return runCatching {
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
}
