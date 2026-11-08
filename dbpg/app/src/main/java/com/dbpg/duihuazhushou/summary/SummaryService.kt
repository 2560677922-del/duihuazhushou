package com.dbpg.duihuazhushou.summary

import android.content.Context
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SummaryBlock
import com.dbpg.stcore.stripHtmlNoise
import com.dbpg.stcore.stripImageMarkers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 自动摘要生成器（原生 OkHttp，直连对话 API）。
 *
 * ## 请求里有什么
 *
 * ```
 * [预设的全部提示词]        ← 原样、不截断（用户要求：砍一半预设就失效）
 * [系统指令 + 用户自定义]    ← "你是压缩器" + 用户补充偏好
 * [已有前情提要 + 本次原文]  ← 上下文，让滚动摘要能承接
 * ```
 *
 * ## 请求里**没有**什么
 *
 * 角色卡、世界书、正则、token 裁剪。
 * 前两个是"扮演"用的，摘要要的是压缩；后两个会改变文本形态，对摘要无益。
 *
 * ## 为什么不走 web 的 generate 通道
 *
 * 那条路会套上世界书与角色卡、占用生成状态机（用户可能正等着回复）。
 * 这里只借 web 的**预设解析能力**（`exportSummaryPreset`，同步取值），
 * 真正的 HTTP 请求由原生直发，互不干扰。
 */
object SummaryService {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dhp-summary").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 一段预设提示词（role + content）。已经由 web 做过宏清理：
     * `{{char}}` 是名字，角色卡/世界书字段是空串。
     */
    private data class PresetMsg(val role: String, val content: String)

    /**
     * 生成一段摘要。
     *
     * @param range 本次要压缩的轮次（含两端）
     * @param prior 已经生效的摘要块（会被合并进新摘要，保持"前情"连续）
     * @param presetId 会话绑定的预设 id；空 = 跟随全局默认
     * @param onDone 主线程回调；失败为 null（调用方静默跳过，绝不阻塞聊天）
     */
    fun run(
        context: Context,
        doc: SessionDoc,
        range: IntRange,
        cfg: SummaryConfig,
        prior: List<SummaryBlock>,
        presetId: String?,
        onDone: (SummaryBlock?) -> Unit,
    ) {
        val actions = doc.actions
        if (range.isEmpty() || range.first < 0 || range.last >= actions.size) {
            onDone(null)
            return
        }
        val llm = ConnectionStore.llmRuntime()
        val baseUrl = llm.optString("baseUrl").trim()
        val apiKey = llm.optString("apiKey").trim()
        val model = llm.optString("model").trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            onDone(null)
            return
        }

        val transcript = buildTranscript(actions, range)
        if (transcript.isBlank()) {
            onDone(null)
            return
        }

        // 预设必须先取（evaluateJavascript 只能在主线程发），拿到后再进 IO 线程。
        // 引擎没起来 / 没预设：preset 为空继续跑，摘要本身不依赖它。
        val proceed = { preset: List<PresetMsg> ->
            io.execute {
                val text = attempt(baseUrl, apiKey, model, preset, prior, transcript, cfg)
                    ?: attempt(baseUrl, apiKey, model, preset, prior, transcript, cfg)
                if (text.isNullOrBlank()) {
                    main.post { onDone(null) }
                    return@execute
                }
                /*
                 * 合并后仍然只落**一条**块：
                 *   fromIndex 取最早那条的起点，toIndex 取本次区间终点。
                 * 这样"前情提要"始终是一段连续覆盖，用户编辑时也只有一处。
                 */
                val from = prior.firstOrNull()?.fromIndex ?: range.first
                val block = SummaryBlock(
                    id = "sum-" + System.currentTimeMillis(),
                    text = text.trim().take(cfg.maxChars + 80),
                    fromIndex = from,
                    toIndex = range.last,
                    toActionId = actions[range.last].id,
                    createdAt = System.currentTimeMillis(),
                    model = model,
                )
                main.post { onDone(block) }
            }
        }

        if (!cfg.withPreset) {
            proceed(emptyList())
            return
        }
        EngineBridge.fetchJson("exportSummaryPreset", presetId.orEmpty()) { obj ->
            proceed(parsePreset(obj))
        }
    }

    // ------------------------------------------------------------ 内部

    private fun parsePreset(obj: JSONObject?): List<PresetMsg> {
        val arr = obj?.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<PresetMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content")
            if (content.isBlank()) continue
            val role = o.optString("role").ifBlank { "system" }
            out.add(PresetMsg(role, content))
        }
        return out
    }

    /**
     * 拼接对话原文。
     *
     * ★ 不逐条截断：以前每轮 take(600) 会把长回复里的关键情节砍掉，
     *   摘要出来的东西自然"记不住事"。这里只在**总量**上做保护
     *   （[MAX_TRANSCRIPT]），且保留头尾 —— 开头交代背景、结尾是当前局面，
     *   中间的过程细节相对可丢。
     */
    private fun buildTranscript(
        actions: List<com.dbpg.stcore.session.SessionAction>,
        range: IntRange,
    ): String {
        val sb = StringBuilder()
        sb.append("【第 ").append(range.first + 1).append(" 轮到第 ")
            .append(range.last + 1).append(" 轮】\n")
        for (i in range) {
            val a = actions[i]
            val user = clean(a.user)
            val ai = clean(a.ai)
            if (user.isNotEmpty()) sb.append("用户：").append(user).append('\n')
            if (ai.isNotEmpty()) sb.append("角色：").append(ai).append('\n')
        }
        return clipTotal(sb.toString(), MAX_TRANSCRIPT)
    }

    /**
     * 超长时保留头 40% + 尾 60%，中间用一行省略号衔接。
     *
     * 为什么不直接砍尾：对话的"现在"在结尾，砍尾会让摘要丢掉最新局面，
     * 模型下一轮就接不上。
     */
    private fun clipTotal(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = (limit * 0.4).toInt()
        val tail = limit - head
        return text.substring(0, head) +
            "\n……（此处省略 " + (text.length - limit) + " 字）……\n" +
            text.substring(text.length - tail)
    }

    private fun attempt(
        baseUrl: String,
        apiKey: String,
        model: String,
        preset: List<PresetMsg>,
        prior: List<SummaryBlock>,
        transcript: String,
        cfg: SummaryConfig,
    ): String? {
        val ctx = StringBuilder()
        if (prior.isNotEmpty()) {
            ctx.append("【已有前情提要（请在此基础上续写，不要重复原文）】\n")
            prior.forEach { ctx.append(it.text.trim()).append('\n') }
            ctx.append('\n')
        }
        ctx.append("【本次需要压缩的对话】\n").append(transcript)

        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("temperature", 0.3)
            // 中文摘要 1 字约 1.5 token，留一倍冗余防止被截断
            put("max_tokens", (cfg.maxChars * 2 + 160).coerceAtMost(4000))
            put(
                "messages",
                JSONArray().apply {
                    // 1) 预设原文：全量、不裁剪
                    preset.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                    // 2) 任务指令（含用户自定义补充）放在预设之后：
                    //    后置的指令权重更高，能压过预设里"扮演"的倾向
                    put(JSONObject().put("role", "system").put("content", systemPrompt(cfg)))
                    // 3) 上下文
                    put(JSONObject().put("role", "user").put("content", ctx.toString()))
                },
            )
        }

        val req = Request.Builder()
            .url(endpoint(baseUrl))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use null
                extractContent(raw)
            }
        }.getOrNull()
    }

    /** 内置指令 + 用户自定义；自定义永远追加在后面，不覆盖内置约束。 */
    private fun systemPrompt(cfg: SummaryConfig): String {
        val custom = cfg.customPrompt.trim()
        if (custom.isEmpty()) return SYSTEM_PROMPT
        return SYSTEM_PROMPT + "\n\n【用户附加要求（优先遵守）】\n" + custom
    }

    /** 兼容 OpenAI / 部分中转的两种返回形状。 */
    private fun extractContent(raw: String): String? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val choices = o.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val msg = first.optJSONObject("message")
        val content = msg?.optString("content")
        if (!content.isNullOrBlank()) return content
        val text = first.optString("text")
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * 端点拼接：和 web 的 apiClient 保持同一套容错。
     * 用户可能填 `https://x.com`、`https://x.com/v1` 或完整 `/chat/completions`。
     */
    private fun endpoint(baseUrl: String): String {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.endsWith("/chat/completions", ignoreCase = true)) return raw
        if (raw.endsWith("/v1", ignoreCase = true)) return "$raw/chat/completions"
        return "$raw/v1/chat/completions"
    }

    private fun clean(text: String): String {
        if (text.isBlank()) return ""
        return stripImageMarkers(stripHtmlNoise(text)).trim()
    }

    /** 原文总量上限：约 3 万 token，远大于正常 8 轮，几乎不会触发。 */
    private const val MAX_TRANSCRIPT = 60000

    private val SYSTEM_PROMPT = """
        你是对话压缩器。把给定的一段角色扮演对话压缩成"前情提要"，供后续续写使用。
        要求：
        1. 按时间顺序叙述，不要评价、不要文学化描写；
        2. 必须保留：人物（姓名/身份/关系变化）、地点、关键物品、承诺与约定、
           已发生的关键事件、尚未解决的冲突、角色当前状态（伤势/情绪/目标）；
        3. 删除：环境描写、重复对话、语气词、心理独白；
        4. 用简体中文，纯文本，不要 Markdown 标题，不要任何前缀说明；
        5. 如果给了《已有前情提要》，把新内容接在后面写成一段连贯的提要，
           不要复述已有部分；
        6. 只输出提要正文。
    """.trimIndent()
}
