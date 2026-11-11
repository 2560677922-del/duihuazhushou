package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.imagegen.ImageGenService
import com.dbpg.duihuazhushou.session.SessionBindingStore
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.stcore.imagegen.ImageEngine
import com.dbpg.stcore.imagegen.ImageEngineSupport
import com.dbpg.stcore.imagegen.ImageParams
import org.json.JSONObject

/**
 * 「按对话生图」：读配置 → 取上文 →（可选）模型转标签 → 出图 → 挂回消息。
 *
 * 单人聊天和群聊共用这一份。两边唯一的差别是"哪条消息当锚点、
 * 哪一个 controller 落图"，所以这两件事从外面传进来，逻辑本身不再复制。
 *
 * 配置读的是 image store 的 **chat 场景**，不是全局 current：标签生图那边
 * 换运营商，不应该把聊天里的 🎨 一起改掉。
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

    private val main = Handler(Looper.getMainLooper())

    private var busy = false
    private var target: ChatController? = null
    private var actionId = ""

    private var styleArtist = ""
    private var styleQuality = ""
    private var styleNegative = ""
    private var styleDna = ""
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

    fun isBusy(): Boolean = busy

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
        onToast("读取生图配置…")

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

            EngineBridge.fetch(activity, "getImageStyle") { st ->
                if (st == null) {
                    finish("风格读取失败")
                    return@fetch
                }
                styleArtist = st.optString("artistTags")
                styleQuality = st.optString("qualityTags")
                styleNegative = st.optString("negativePrompt")
                val ref = st.optJSONObject("genRefs")?.optJSONObject("chat") ?: JSONObject()
                refImage = ref.optString("image")
                refStrength = ref.optDouble("strength", 0.6)
                refInfo = ref.optDouble("info", 0.7)
                steps = st.optInt("steps", 28)
                cfgScale = st.optDouble("cfgScale", 6.0)
                sampler = st.optString("sampler")
                    .ifBlank { ImageEngineSupport.defaultSamplerFor(apiModel) }
                noise = st.optString("noiseSchedule")

                if (apiUrl.isBlank()) {
                    finish("还没配置生图运营商")
                    return@fetch
                }

                val context = if (anchorActionId != null) {
                    c.contextTextAt(anchor, n).orEmpty()
                } else {
                    c.contextText(n).orEmpty()
                }
                if (context.isBlank()) {
                    finish("上下文为空")
                    return@fetch
                }
                // 角色 DNA：本对话勾选的英文外观标签，只在生图时生效
                styleDna = ConnectionStore.dnaTags(
                    SessionBindingStore.load(activity, c.doc?.id.orEmpty()).dnaIds,
                )
                val withDna = if (styleDna.isBlank()) context
                else "人物外观参考（提示词中必须保留这些特征）：$styleDna\n\n$context"

                if (useLlm) {
                    onToast("让模型生成画面标签…")
                    EngineBridge.command(
                        JSONObject().put("cmd", "optimizeImagePrompt")
                            .put("context", withDna).put("llmConfigId", llmId),
                    )
                } else {
                    // 不用模型：直接用这段上下文（截断），够用且零额外成本
                    draw(context.take(600))
                }
            }
        }
    }

    /**
     * 引擎回的 `optimizeImagePrompt` 结果。
     *
     * 调用方（Activity）的 ApiResultListener 直接转进来即可；
     * 不是这个 kind 的返回会被忽略，所以共用一条全局回调没问题。
     */
    fun onEngineResult(json: String) {
        val o = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return
        }
        if (o.optString("kind") != "optimizeImagePrompt") return
        if (!o.optBoolean("ok")) {
            // 模型失败不阻塞出图：退回原文当标签，至少能出一张
            onToast("标签生成失败（${o.optString("error")}），改用原文")
            val c = target
            val fallback = c?.let { ct ->
                val act = ct.doc?.actions?.find { it.id == actionId }
                    ?: ct.doc?.actions?.lastOrNull { it.ai.isNotBlank() }
                act?.let { ct.plainTextOf(it) }
            }.orEmpty()
            draw(fallback.take(600))
            return
        }
        draw(o.optString("prompt"))
    }

    /** 用户中途退出界面：不再回调 UI，但已经发出的请求让它自然结束。 */
    fun detach() {
        target = null
        busy = false
    }

    // ------------------------------------------------------------ 内部

    private fun draw(tags: String) {
        if (tags.isBlank()) {
            finish("提示词为空")
            return
        }
        val full = listOf(styleArtist, styleQuality, styleDna, tags)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
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
                    finish("已生成 1 张")
                }
            }

            override fun onError(message: String) {
                svc.shutdown()
                main.post { finish("出图失败：$message") }
            }
        })
    }

    private fun setBusy(v: Boolean) {
        busy = v
        onBusy(v)
    }

    private fun finish(msg: String) {
        setBusy(false)
        onToast(msg)
    }
}
