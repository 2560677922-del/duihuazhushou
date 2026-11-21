package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.imagegen.ImagePromptRunner
import com.dbpg.duihuazhushou.imagegen.PromptPresetPlugin
import com.dbpg.duihuazhushou.session.SessionBindingStore
import com.dbpg.duihuazhushou.AppLog
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import com.dbpg.stcore.imagegen.ImageParams
import com.dbpg.stcore.imagegen.NativeImageConfig
import com.dbpg.stcore.imagegen.NativeLlmConfig
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 「按对话生图」：读配置 → 取上文 →（可选）模型转标签 → 出图 → 挂回消息。
 *
 * 单人聊天和群聊共用这一份。两边唯一的差别是"哪条消息当锚点、
 * 哪一个 controller 落图"，所以这两件事从外面传进来，逻辑本身不再复制。
 *
 * 配置读的是 image store 的 **chat 场景**，不是全局 current：标签生图那边
 * 换运营商，不应该把聊天里的 🎨 一起改掉。
 *
 * ## 出图参数 vs 提示词预设
 *
 * - **画风 / 质量 / 负面 / 采样 / 尺寸**：一律来自「风格」，本类不覆盖。
 * - **提示词预设**（破甲）：只作用于「文字 → 标签」那一步，预设存在本机（插件自带库），
 *   由 [ImagePromptRunner] 统一装配，选哪套由 [PromptPresetPlugin] 决定。
 *   两者是两层东西：前者决定"画成什么样"，后者决定"模型肯不肯把剧情写成标签"。
 *
 * 模型那一步是**原生自己发**的（不再经 web 的 optimizeImagePrompt 命令）：
 * 少一次 IPC、少一份状态，也让"插件卸载后行为回到原点"变得可验证。
 *
 * ★ 回调都保证在主线程。
 *
 * @param controllerOf 出图开始时取一次并锁定：群聊里用户可能在出图期间
 *                     切频道，图必须落回发起时那个频道。
 */
class QuickImage(
    private val activity: Activity,
    private val controllerOf: () -> ChatController?,
    /** 忙闲变化：界面据此把入口置灰，避免连点出一串图。 */
    private val onBusy: (Boolean) -> Unit = {},
    private val onToast: (String) -> Unit = {},
) {

    companion object {
        /** 一次出图（含标签优化）超过这个时间就认为卡住了 */
        private const val WATCHDOG_MS = 150_000L
    }

    private val main = Handler(Looper.getMainLooper())
    /** 标签优化是网络请求，不能占主线程 */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-quickimage") }

    private var busy = false
    private var target: ChatController? = null
    private var actionId = ""

    private var styleArtist = ""
    private var styleQuality = ""
    private var styleNegative = ""
    private var styleDna = ""
    private var styleTemplate = ""
    private var styleSize = ""

    private var apiUrl = ""
    private var apiKey = ""
    private var apiModel = ""
    private var engine = ImageEngine.NAI_RELAY
    private var width = 832
    private var height = 1216
    private var steps = 28
    private var cfgScale = 6.0
    private var sampler = "k_euler_ancestral"
    private var noise = ""

    /** 出图参考图（锁人物形象那张），来自 image.genRefs.chat。 */
    private var refImage = ""
    private var refStrength = 0.6
    private var refInfo = 0.7

    /** 标签优化用的对话模型（本场景绑定 / 全局当前）。 */
    private var llm = NativeLlmConfig()

    /** 本次按什么预设走，给 toast 用（不使用 / 跟随资产页 / 具体某套）。 */
    private var presetNote = ""

    /**
     * 这次标签的瑕疵说明（混了中文、标签太少…）。
     *
     * 判定不再因为这种"不够好"就不出图，改成画完顺带提一句 ——
     * 用户至少能看到图，并且知道"画面偏"是标签的锅，不是模型坏了。
     */
    private var tagWarning = ""

    fun isBusy(): Boolean = busy

    private val watchdog = Runnable {
        if (busy) finish("出图超时，稍后再试")
    }

    /**
     * @param anchorActionId 图挂到哪条消息；空 = 该会话最新一条 AI 回复
     * @param beforeCount    锚点之前再带几条当上下文；null = 用配置里的 quickContextWindow
     */
    fun generate(anchorActionId: String? = null, beforeCount: Int? = null) {
        if (busy) {
            onToast("还在出图，稍等一下")
            return
        }
        val c = controllerOf()
        if (c == null) {
            onToast("还没有可配图的对话")
            return
        }
        val anchor = anchorActionId ?: c.latestActionId()
        if (anchor == null) {
            onToast("还没有可配图的回复")
            return
        }
        target = c
        actionId = anchor
        setBusy(true)
        // 兜底看门狗：标签模型不返回时不要把入口永久按灰。
        // 只解放 UI，不取消请求 —— 图回来了照样挂上去。
        main.postDelayed(watchdog, WATCHDOG_MS)
        onToast("读取生图配置…")

        /*
         * 不再先拉一次 web 侧快照。
         *
         * v1.3~v1.4 这里要拉 `presetBlock`（当前对话预设里的提示词片段），
         * 因为「跟随当前对话」要用它。现在预设直接取自**资产页**，
         * 插件自己缓存好了文本，出图路径上不需要任何 WebView 求值 ——
         * 少了 3 秒超时兜底，引擎没起来也照样能出图。
         */
        startGenerate(c, anchor, anchorActionId, beforeCount)
    }

    private fun startGenerate(
        c: ChatController,
        anchor: String,
        anchorActionId: String?,
        beforeCount: Int?,
    ) {
        loadConfig(c, beforeCount) { n, useLlm ->
            val context = if (anchorActionId != null) {
                c.contextTextAt(anchor, n).orEmpty()
            } else {
                c.contextText(n).orEmpty()
            }
            if (context.isBlank()) {
                finish("上下文为空")
                return@loadConfig
            }
            if (!useLlm) {
                // 不用模型：直接用这段上下文（截断），够用且零额外成本
                draw(context.take(600))
                return@loadConfig
            }
            onToast(
                if (presetNote.isBlank()) "让模型生成画面标签…"
                else "让模型生成画面标签…（$presetNote）",
            )
            val cfg = promptConfig()
            val dna = styleDna
            io.execute {
                // 后台线程的未捕获异常会直接杀掉进程（表现就是"点一下就退回首页"），
                // 所以这里整段兜底：出错也要回到主线程把忙闲复位并说一句原因。
                val outcome = runCatching {
                    ImagePromptRunner.optimizeOrFail(
                        context = activity,
                        config = cfg,
                        llm = llm,
                        dnaTags = dna,
                        description = context,
                        contextLabel = "剧情片段",
                    )
                }.getOrElse {
                    // 堆栈单独记一条：finish 只会留下人话消息，异常到底是空指针
                    // 还是 JSON 解析炸了，只有这里有
                    AppLog.e("生图", "出图异常", it)
                    main.post { finish("出图出错：${it.message ?: it.javaClass.simpleName}") }
                    return@execute
                }
                when (outcome) {
                    is ImagePromptRunner.Outcome.Ok -> main.post {
                        tagWarning = outcome.warning
                        draw(outcome.tags)
                    }
                    // 模型拒答 / 只回了中文 / 一个词都没给：这次不画。
                    // 硬画出来的是废图，还得用户自己去删。
                    is ImagePromptRunner.Outcome.Failed -> main.post {
                        finish("没出图：${outcome.reason}")
                    }
                }
            }
        }
    }

    /**
     * 单张图「重新生成」：沿用这张图当初的提示词，不再走模型转标签。
     *
     * 用户要的是"同一张再roll一次"，而不是"根据这段剧情重新想一个画面"——
     * 后者换的是构图，前者换的是细节，两者不能混。
     *
     * @param exactPrompt 原图保存的完整提示词（含当时的画风前缀）
     */
    fun regenerate(anchorActionId: String, exactPrompt: String) {
        if (busy) {
            onToast("还在出图，稍等一下")
            return
        }
        val c = controllerOf()
        if (c == null) {
            onToast("还没有可配图的对话")
            return
        }
        if (exactPrompt.isBlank()) {
            // 老数据没存提示词：退回按剧情重推，至少能出图
            generate(anchorActionId)
            return
        }
        target = c
        actionId = anchorActionId
        setBusy(true)
        main.postDelayed(watchdog, WATCHDOG_MS)
        onToast("按原提示词重新出图…")
        loadConfig(c, null) { _, _ ->
            draw(exactPrompt, exact = true)
        }
    }

    /**
     * 读两套配置（接口/场景 + 风格），把字段填好再回调。
     *
     * 抽出来是因为「重新生成」和「按对话生图」走的是同一份配置，
     * 只是拿到配置之后一个用原文、一个去推标签。
     *
     * @param then 参数：上文条数、是否走模型优化
     */
    private fun loadConfig(
        c: ChatController,
        beforeCount: Int?,
        then: (n: Int, useLlm: Boolean) -> Unit,
    ) {
        EngineBridge.fetch(activity, "getImageConfig") { cfg ->
            if (cfg == null) {
                finish("引擎还没就绪")
                return@fetch
            }
            val cur = cfg.optJSONObject("scenes")?.optJSONObject("chat")
                ?: cfg.optJSONObject("current") ?: JSONObject()
            val auto = cfg.optJSONObject("auto") ?: JSONObject()
            val n = beforeCount ?: auto.optInt("quickContextWindow", 5)
            val useLlm = cur.optBoolean("useLlmOptimization", true)
            val llmId = cur.optString("llmConfigId")

            apiUrl = cur.optString("baseUrl")
            apiKey = cur.optString("apiKey")
            apiModel = cur.optString("model")
            width = cur.optInt("width", 832)
            height = cur.optInt("height", 1216)
            engine = ImageEngine.of(cur.optString("engine"))
            styleSize = "${width}x$height"

            // 标签优化用的对话模型：本接口绑的那个，没绑就跟随全局当前
            llm = ConnectionStore.llmRuntime(llmId).let { r ->
                NativeLlmConfig(
                    baseUrl = r.optString("baseUrl"),
                    apiKey = r.optString("apiKey"),
                    model = r.optString("model"),
                    temperature = r.optDouble("temperature", 0.7),
                    maxTokens = r.optInt("maxTokens", 900),
                )
            }

            EngineBridge.fetch(activity, "getImageStyle") { st ->
                if (st == null) {
                    finish("风格读取失败")
                    return@fetch
                }
                // 画风 / 负面 / 参考图只认风格；提示词预设不参与这些
                styleArtist = st.optString("artistTags")
                styleQuality = st.optString("qualityTags")
                styleNegative = st.optString("negativePrompt")
                styleTemplate = st.optString("promptTemplate")
                val ref = st.optJSONObject("genRefs")?.optJSONObject("chat") ?: JSONObject()
                refImage = ref.optString("image")
                refStrength = ref.optDouble("strength", 0.6)
                refInfo = ref.optDouble("info", 0.7)
                steps = st.optInt("steps", 28)
                cfgScale = st.optDouble("cfgScale", 6.0)
                sampler = st.optString("sampler")
                    .ifBlank { ImageEngineSupport.defaultSamplerFor(apiModel) }
                noise = st.optString("noiseSchedule")

                // 角色 DNA：本对话勾选的英文外观标签，只在生图时生效
                styleDna = ConnectionStore.dnaTags(
                    SessionBindingStore.load(activity, c.doc?.id.orEmpty()).dnaIds,
                )
                // 只取一句话说明给 toast（用哪套预设 / 没在用）
                // 读不出来就当没有 —— 出图这条路上不允许任何"附带功能"抛异常
                // 只有**真的注入了**预设才提一句；没导入过就闭嘴，
                // 否则每次出图都挂一句"还没有导入预设"，像在逼用户用预设。
                presetNote = runCatching {
                    val p = PromptPresetPlugin.plan(activity)
                    if (p.block.isBlank()) "" else p.note
                }.getOrDefault("")

                if (apiUrl.isBlank()) {
                    finish("还没配置生图运营商")
                    return@fetch
                }
                then(n, useLlm)
            }
        }
    }

    /** 交给 [ImagePromptRunner] 的那份配置：只用到模板与画师串，出图参数不在这里。 */
    private fun promptConfig() = NativeImageConfig(
        promptTemplate = styleTemplate,
        artistTags = styleArtist,
        qualityTags = styleQuality,
        negativePrompt = styleNegative,
    )

    /**
     * 引擎回的 `optimizeImagePrompt` 结果（兼容保留）。
     *
     * 现在标签优化由原生直接调模型（[ImagePromptRunner]），不再发这条命令；
     * 这个方法留给"网页版/旧缓存仍把结果递过来"的情况，收到就照常出图，
     * 不至于把一次已经成功的优化白扔。
     */
    fun onEngineResult(json: String) {
        val o = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return
        }
        if (o.optString("kind") != "optimizeImagePrompt") return
        if (busy) return
        if (!o.optBoolean("ok")) return
        val prompt = o.optString("prompt")
        if (prompt.isBlank()) return
        setBusy(true)
        main.postDelayed(watchdog, WATCHDOG_MS)
        draw(prompt)
    }

    /** 用户中途退出界面：不再回调 UI，但已经发出的请求让它自然结束。 */
    fun detach() {
        target = null
        busy = false
    }

    // ------------------------------------------------------------ 内部

    /**
     * @param exact true = [tags] 就是最终提示词，不再拼画风/质量/DNA。
     *              单图重新生成走这条，否则原提示词里的画风会被拼第二遍。
     */
    private fun draw(tags: String, exact: Boolean = false) {
        if (tags.isBlank()) {
            finish("提示词为空")
            return
        }
        val full = if (exact) {
            tags.trim()
        } else {
            listOf(styleArtist, styleQuality, styleDna, tags)
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }
        onToast("出图中…")

        val model = apiModel.ifBlank { "nai-diffusion-4-5-full" }
        val params = ImageParams(
            engine = engine,
            url = apiUrl,
            apiKey = apiKey,
            model = model,
            prompt = full,
            negativePrompt = styleNegative,
            width = width,
            height = height,
            cfgScale = cfgScale,
            steps = steps,
            sampler = sampler,
            noiseSchedule = noise,
            qualityToggle = true,
            refImageBase64 = refImage.ifBlank { null },
            refStrength = refStrength,
            refInfoExtracted = refInfo,
        )
        val urls = ImageEngineSupport.candidateUrls(engine, apiUrl)
        val req = ImageGenService.Request(
            url = urls.firstOrNull() ?: apiUrl,
            apiKey = apiKey,
            model = model,
            prompt = full,
            size = styleSize.ifBlank { null },
            responseFormat = "b64_json",
            params = params,
            endpointCandidates = urls.drop(1),
        )
        val svc = ImageGenService(activity.applicationContext)
        svc.generate(req, object : ImageGenService.Callback {
            override fun onSuccess(images: List<com.dbpg.duihuazhushou.imagegen.ImageStore.StoredImage>) {
                svc.shutdown()
                val first = images.firstOrNull()
                if (first == null) {
                    finish("没有解析到图片")
                    return
                }
                main.post {
                    // 期间可能已经切走频道：图只落到发起时那个会话上
                    target?.attachImage(actionId, first.path, full)
                    finish(
                        if (tagWarning.isBlank()) "已生成 1 张"
                        else "已生成 1 张（$tagWarning）",
                    )
                }
            }

            override fun onError(message: String) {
                svc.shutdown()
                main.post { finish("出图失败：$message") }
            }
        })
    }

    private fun setBusy(v: Boolean) {
        if (v) tagWarning = ""
        busy = v
        if (!v) main.removeCallbacks(watchdog)
        onBusy(v)
    }

    /** 所有失败路径的唯一出口（见各调用点），顺手留一条日志。 */
    private fun finish(msg: String) {
        AppLog.e("生图", msg)
        setBusy(false)
        onToast(msg)
    }
}
