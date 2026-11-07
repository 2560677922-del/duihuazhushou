package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.imagegen.AutoImageService
import com.dbpg.duihuazhushou.session.SessionRepo
import com.dbpg.stcore.ContentBlock
import com.dbpg.stcore.NativeImage
import com.dbpg.stcore.imagegen.AutoImageEnv
import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.session.ActionImage
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionEditor
import com.dbpg.stcore.session.SessionHistory
import com.dbpg.stcore.stripHtmlNoise
import com.dbpg.stcore.stripImageMarkers
import com.dbpg.stcore.transformForDisplayRich
import java.util.concurrent.Executors

/**
 * 聊天页的"大脑"：会话数据 + 所有消息操作 + 生成编排。
 *
 * 分层（有意压平，任何一层都不认识下一层的实现细节）：
 *
 *   NativeChatActivity   只管控件、弹窗、跳转
 *        ↕ Sink 回调
 *   ChatController       本类：SessionDoc ↔ ChatItem，操作后落盘，生成状态机
 *        ↕ 纯函数                     ↕ 通道
 *   SessionEditor/History       GenBridge → web ST 引擎 → API
 *        ↕
 *   SessionRepo（文件）
 *
 * ★ 单一数据源原则：会话只存原生 [SessionDoc] 一份。
 *   切换版本只改 `action.user/ai`（当前版本），`variants` 留在本地；
 *   生成时通过 [SessionHistory.export] 导出的**只有当前版本**，
 *   所以不存在"网页和原生各存一份、切了版本对不上"的可能。
 *
 * ★ 图片不进上下文：导出走 [SessionHistory]，内部会 stripImageMarkers。
 *   配图只以 [ActionImage.localPath] 挂在消息上，给界面看，不给模型看。
 */
class ChatController(
    private val appContext: Context,
    private val imageCache: ImageFileCache,
    private val sink: Sink,
) : GenBridge.Sink {

    interface Sink {
        /** 列表数据变了。scroll=true 表示这一轮必须贴底（发送/完成）。 */
        fun onItems(items: List<ChatItem>, scrollToBottom: Boolean)
        fun onTitle(title: String)
        /** 生成中：停止按钮可见、输入框禁用 */
        fun onGenerating(active: Boolean)
        fun onToast(message: String)
        /** 分支出新会话，界面负责跳过去 */
        fun onBranchCreated(sessionId: String, title: String)
    }

    var doc: SessionDoc? = null
        private set

    private var display: DisplayConfig = DisplayConfig.empty()
    private var appearance: AppearanceConfig = AppearanceConfig()

    /** 生图配置快照：打开会话时拉一次，之后自动配图全用它，不再问 web。 */
    private var imageEnv: AutoImageEnv? = null

    private var genId: String? = null
    private var genActionId: String = ""
    private var genText: String = ""
    private var genIsNew: Boolean = false

    /**
     * 渲染专用后台线程。
     *
     * 单线程是刻意的：两次 render 并发跑没有意义，而且 [imageCache] 落盘
     * 也在这个线程上，串行能保证同一张图不会被同时写两遍。
     */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-chat-render") }
    private val main = Handler(Looper.getMainLooper())

    /** 渲染代次：只有最新一次的结果允许提交给界面，避免旧结果覆盖新结果。 */
    private var renderSeq = 0

    // ------------------------------------------------------------ 打开 / 刷新

    /**
     * 打开一个会话。
     *
     * ★ 整段读盘 + 快照解析都在后台线程，回调再切回主线程 bind。
     *   以前是"异步读盘、主线程解析 + 主线程渲染"，会话一长就把第一帧
     *   按住，用户看到的就是黑屏卡死。现在这条链上没有任何一步压主线程。
     *
     * @param sessionId 会话 id；为空时才去快照里找（兼容老入口）
     * @param snapshot  兜底用：本地还没有这份会话时，从网页快照构造一份再落盘
     */
    fun open(sessionId: String, snapshot: String? = null) {
        SessionRepo.init(appContext)
        io.execute {
            val snap = snapshot?.takeIf { it.isNotBlank() }
            val sid = sessionId.ifBlank { snap?.let { SessionRepo.sessionIdOf(it) }.orEmpty() }
            val loaded = if (sid.isNotBlank()) SessionRepo.loadSync(sid) else null
            val d = loaded ?: run {
                val fromSnap = snap?.let { SessionRepo.fromSnapshot(it) }
                val made = fromSnap ?: SessionRepo.create("新冒险")
                SessionRepo.saveSync(made)
                made
            }
            main.post { bind(d) }
        }
    }

    private fun bind(d: SessionDoc) {
        doc = d
        sink.onTitle(d.title.ifBlank { "冒险" })
        render(true)
        refreshDisplayConfig()
        refreshImageEnv()
    }

    /** 从设置页/其他界面回来时重新读盘（可能被别的地方改过）。 */
    fun reload() {
        val id = doc?.id ?: return
        SessionRepo.load(id) { loaded ->
            if (loaded != null) {
                doc = loaded
                render(true)
            }
        }
    }

    /**
     * 重拉"怎么渲染 / 生图配置"。
     *
     * 用户在网页设置里换了预设、正则、生图接口后回到聊天页，这里要跟上，
     * 否则新消息用的还是旧配置。
     */
    fun refreshConfig() {
        refreshDisplayConfig()
        refreshImageEnv()
    }

    fun applyAppearance(cfg: AppearanceConfig) {
        if (cfg == appearance) return
        appearance = cfg
        render(false)
    }

    private fun refreshDisplayConfig() {
        val id = doc?.id ?: return
        GenBridge.fetchDisplayConfig(id) { json ->
            display = DisplayConfig.parse(json)
            render(false)
        }
    }

    private fun refreshImageEnv() {
        GenBridge.fetchImageConfig { json ->
            imageEnv = AutoImageEnv.parse(json)
        }
    }

    // ------------------------------------------------------------ 渲染

    /**
     * 把 SessionDoc 转成列表项。所有操作走完都调它一次。
     *
     * ★ 计算全部在 [io] 上做，主线程只负责最后 submitList。
     *
     *   这一步要跑正则（用户导入的美化规则单条就能有两万多字符）、
     *   做宏替换、解图片路径。放在主线程的话，一个长会话打开时
     *   第一帧都画不出来 —— 表现就是黑屏卡死，而不是"慢一点"。
     *
     *   用 [renderSeq] 防乱序：流式生成时 render 会被高频调用，
     *   后台是串行的，但排队的结果可能已经不是最新的，旧结果直接丢弃。
     */
    private fun render(scrollToBottom: Boolean) {
        val d = doc ?: return
        val seq = ++renderSeq
        val displaySnap = display
        val appearanceSnap = appearance
        val genIdSnap = genId
        val genActionSnap = genActionId
        val genTextSnap = genText

        io.execute {
            val items = runCatching {
                buildItems(d, displaySnap, appearanceSnap, genIdSnap, genActionSnap, genTextSnap)
            }.getOrElse { e ->
                android.util.Log.w("DHP", "渲染会话失败", e)
                emptyList()
            }
            main.post {
                if (seq != renderSeq) return@post
                sink.onItems(items, scrollToBottom)
            }
        }
    }

    private fun buildItems(
        d: SessionDoc,
        display: DisplayConfig,
        appearance: AppearanceConfig,
        genId: String?,
        genActionId: String,
        genText: String,
    ): List<ChatItem> {
        val out = ArrayList<ChatItem>(d.actions.size * 2 + 1)

        // 空会话：把角色卡的开场白当一条只读消息显示，避免"一片空白"
        if (d.actions.isEmpty()) {
            val opening = d.sceneDescription.ifBlank { display.macroContext.original.orEmpty() }
            if (opening.isNotBlank()) {
                out.add(ChatItem(id = "opening", role = "assistant", content = opening))
            }
            return out
        }

        for ((i, a) in d.actions.withIndex()) {
            if (a.user.isNotBlank()) {
                out.add(
                    ChatItem(
                        id = "u:${a.id}",
                        role = "user",
                        content = a.user,
                        actionId = a.id,
                    ),
                )
            }

            val generating = genId != null && genActionId == a.id
            val aiText = if (generating) genText else a.ai
            if (aiText.isBlank() && a.images.isEmpty() && !generating && a.user.isNotBlank()) {
                // 只有用户那句、没有回复：不显示空气泡（用户可能刚删了回复）
                continue
            }

            val rendered = renderAi(
                text = aiText,
                images = a.images,
                depth = d.actions.size - 1 - i,
                display = display,
                appearance = appearance,
            )
            out.add(
                ChatItem(
                    id = "a:${a.id}",
                    role = "assistant",
                    content = rendered.plain,
                    imagePaths = rendered.paths,
                    streaming = generating,
                    actionId = a.id,
                    variantIndex = a.variantIndex,
                    variantCount = a.variants.size,
                    html = rendered.html,
                ),
            )
        }
        return out
    }

    /** AI 正文 → 显示文本 + 本地图片路径（跑 ST 显示变换：思维链/正则/变量/剥标记）。 */
    /** renderAi 的输出：纯文本 + 图片路径 + 可选的富 HTML（给 WebView 渲染）。 */
    private data class RenderedMsg(
        val plain: String,
        val paths: List<String>,
        val html: String?,
    )

    private fun renderAi(
        text: String,
        images: List<ActionImage>,
        depth: Int,
        display: DisplayConfig,
        appearance: AppearanceConfig,
    ): RenderedMsg {
        if (text.isBlank()) return RenderedMsg("", emptyList(), null)

        val attachments = images.map {
            NativeImage(
                id = it.id,
                // 只有老数据才会带 dataUrl；新数据是 localPath，不走这里
                dataUrl = it.localPath ?: it.dataUrl,
                assetId = it.assetId,
                description = it.description.ifBlank { "配图" },
                markdownIndex = it.markdownIndex,
            )
        }

        val rich = transformForDisplayRich(
            text = text,
            attachments = attachments,
            scripts = display.scripts,
            vars = display.variables,
            ctx = display.macroContext,
            hideReasoning = appearance.hideReasoning,
            reasoningCutMarker = display.reasoningCutMarker.ifEmpty { null },
            depthOverride = depth,
        )

        val blocks = rich.blocks
        val sb = StringBuilder()
        val paths = ArrayList<String>()
        for (b in blocks) {
            when (b) {
                is ContentBlock.Text -> {
                    val t = b.text.trim()
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(t)
                    }
                }
                is ContentBlock.Image -> {
                    val p = b.image?.dataUrl
                    if (p != null) {
                        // 本地路径直接可用；dataUrl 才需要转存。
                        // 文件已不存在（用户在查看器里删过）就别进列表 ——
                        // 否则气泡里留着旧缩略图（LruCache），点开又"图片不存在"。
                        val path = if (p.startsWith("/")) p else imageCache.pathFor(p)
                        if (path != null && java.io.File(path).exists()) paths.add(path)
                    }
                }
            }
        }
        // 只有"像一份 HTML"的消息才交给 WebView：带 <style>、或块级标签 ≥2 个。
        // 普通 Markdown/纯文本继续走 TextView（更快、可选中复制）。
        val html = rich.html
        val looksLikeHtml = appearance.htmlRender && html.isNotBlank() &&
            (html.contains("<style", ignoreCase = true) ||
                Regex("<(div|details|table|section|article)\\b", RegexOption.IGNORE_CASE)
                    .findAll(html).count() >= 2)
        return RenderedMsg(sb.toString(), paths, if (looksLikeHtml) html else null)
    }

    // ------------------------------------------------------------ 发送 / 生成

    fun send(text: String) {
        val d = doc ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        if (genId != null) {
            sink.onToast("还在生成，先停一下")
            return
        }

        val actionId = SessionRepo.newId("a")
        val next = SessionEditor.appendAction(d, actionId, body, "")
        doc = next
        SessionRepo.save(next)
        render(true)
        beginGen(actionId, body, isNew = true)
    }

    /**
     * 重新生成某一条。
     *
     * 会砍掉这条之后的内容（它们建立在旧回答上，已不成立），
     * 但**保留当前这条的旧正文** —— 会进 variants，用户还能用 ‹ › 翻回来。
     */
    fun regenerate(actionId: String) {
        val d = doc ?: return
        if (genId != null) {
            sink.onToast("还在生成，先停一下")
            return
        }
        val idx = d.actions.indexOfFirst { it.id == actionId }
        if (idx < 0) return
        val dropped = d.actions.size - idx - 1

        val trimmed = SessionEditor.truncateAfter(d, actionId)
        doc = trimmed
        SessionRepo.save(trimmed)
        if (dropped > 0) sink.onToast("已作废后面 $dropped 条（重新生成）")

        val user = SessionHistory.userInputOf(trimmed, actionId)
        beginGen(actionId, user, isNew = false)
    }

    private fun beginGen(actionId: String, userInput: String, isNew: Boolean) {
        val d = doc ?: return
        val history = SessionHistory.export(d, uptoActionId = actionId)
        val env = imageEnv

        val reqId = GenBridge.request(
            sessionId = d.id,
            messages = history,
            userInput = userInput,
            imageInstruction = env?.settings?.enabled,
            imageMax = env?.settings?.maxPerReply ?: 2,
            sink = this,
        )
        if (reqId == null) {
            sink.onToast("生成引擎还没准备好（页面未加载完），稍后再试")
            if (isNew) {
                doc = SessionEditor.remove(d, actionId)
                doc?.let { SessionRepo.save(it) }
                render(true)
            }
            return
        }
        genId = reqId
        genActionId = actionId
        genText = ""
        genIsNew = isNew
        sink.onGenerating(true)
        render(true)
    }

    fun stop() {
        val id = genId ?: return
        GenBridge.stop(id)
        // 不在这里清状态：等 web 回 onGenStopped，把已生成的部分落下来
    }

    override fun onGenDelta(text: String) {
        if (genId == null) return
        genText = text
        render(false)
    }

    override fun onGenDone(full: String) {
        val d = doc ?: return
        val actionId = genActionId
        val text = full.ifBlank { genText }
        clearGenState()

        doc = SessionEditor.replaceAi(d, actionId, text)
        doc?.let { SessionRepo.save(it) }
        sink.onGenerating(false)
        render(true)

        if (text.isNotBlank()) maybeAutoImage(actionId, text)
    }

    override fun onGenError(message: String) {
        val d = doc ?: return
        val actionId = genActionId
        val isNew = genIsNew
        val partial = genText
        clearGenState()

        doc = when {
            partial.isNotBlank() -> SessionEditor.replaceAi(d, actionId, partial)
            isNew -> SessionEditor.remove(d, actionId)
            else -> d
        }
        doc?.let { SessionRepo.save(it) }
        sink.onGenerating(false)
        render(true)
        sink.onToast(message.ifBlank { "生成失败" })
    }

    override fun onGenStopped(partial: String) {
        val d = doc ?: return
        val actionId = genActionId
        val isNew = genIsNew
        val text = partial.ifBlank { genText }
        clearGenState()

        doc = when {
            text.isNotBlank() -> SessionEditor.replaceAi(d, actionId, text)
            isNew -> SessionEditor.remove(d, actionId)
            else -> d
        }
        doc?.let { SessionRepo.save(it) }
        sink.onGenerating(false)
        render(true)
    }

    private fun clearGenState() {
        genId = null
        genActionId = ""
        genText = ""
        genIsNew = false
    }

    // ------------------------------------------------------------ 会话属性

    /** 改标题：落盘 + 通知界面（聊天页顶栏即时更新）。 */
    fun rename(newTitle: String) {
        val d = doc ?: return
        val next = SessionEditor.rename(d, newTitle)
        doc = next
        SessionRepo.save(next)
        sink.onTitle(next.title.ifBlank { "冒险" })
    }

    // ------------------------------------------------------------ 快捷生图支撑

    /** 最近 N 条对话的纯文本上下文（用户 + AI 各算一条），给"生成画面标签"用。 */
    fun contextText(n: Int): String {
        val d = doc ?: return ""
        val take = n.coerceIn(1, 20)
        val actions = d.actions.takeLast(take)
        return actions.joinToString("\n") { a ->
            buildString {
                if (a.user.isNotBlank()) append("用户：").append(a.user.trim()).append('\n')
                if (a.ai.isNotBlank()) append("AI：").append(plainTextOf(a)).append('\n')
            }.trim()
        }
    }

    /**
     * 单条消息生图的上下文：目标消息那一轮 + 它之前 [before] 轮。
     *
     * 与 [contextText] 的区别是"锚点"：快捷 🎨 取的是最新 N 条，
     * 这里取的是**指定那条**往前数，回头给旧剧情补图时才不会带错上下文。
     */
    fun contextTextAt(actionId: String, before: Int): String {
        val d = doc ?: return ""
        val idx = d.actions.indexOfFirst { it.id == actionId }
        if (idx < 0) return ""
        val from = (idx - before.coerceAtLeast(0)).coerceAtLeast(0)
        return d.actions.subList(from, idx + 1).joinToString("\n") { a ->
            buildString {
                if (a.user.isNotBlank()) append("用户：").append(a.user.trim()).append('\n')
                if (a.ai.isNotBlank()) append("AI：").append(plainTextOf(a)).append('\n')
            }.trim()
        }
    }

    /** 一条消息的可读纯文本（跑显示清洗：剥 HTML 噪声/图片标记，不跑正则美化）。 */
    fun plainTextOf(a: SessionAction): String {
        var t = a.ai
        t = stripHtmlNoise(t)
        t = stripImageMarkers(t)
        return t.trim()
    }

    /** 最新一条有 AI 正文的消息（快捷出图的目标）。 */
    fun latestActionId(): String? =
        doc?.actions?.lastOrNull { it.ai.isNotBlank() }?.id

    /**
     * 把生成的图片挂到某条消息上并刷新。
     * 只动 images，不改正文 —— 出图失败不会影响聊天内容。
     */
    fun attachImage(actionId: String, localPath: String, prompt: String) {
        val d = doc ?: return
        val act = d.actions.find { it.id == actionId } ?: return
        val img = ActionImage(
            id = "img-" + System.currentTimeMillis(),
            description = "快捷生图",
            markdownIndex = -1,
            prompt = prompt.take(1000),
            localPath = localPath,
        )
        val next = act.copy(images = act.images + img)
        val doc2 = d.copy(
            actions = d.actions.map { if (it.id == actionId) next else it },
            updatedAt = System.currentTimeMillis(),
        )
        doc = doc2
        SessionRepo.save(doc2)
        render(true)
    }

    // ------------------------------------------------------------ 消息操作

    fun switchVariant(actionId: String, delta: Int) {
        val d = doc ?: return
        val act = d.actions.find { it.id == actionId } ?: return
        val target = (act.variantIndex + delta).coerceAtLeast(0)
        val next = SessionEditor.switchVariant(d, actionId, target)
        if (next === d) return
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    fun editUser(actionId: String, text: String) {
        val d = doc ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val next = SessionEditor.updateAction(d, actionId, user = body)
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    fun editAi(actionId: String, text: String) {
        val d = doc ?: return
        val next = SessionEditor.updateAction(d, actionId, ai = text)
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    fun deleteUser(actionId: String) {
        val d = doc ?: return
        val next = SessionEditor.removeUser(d, actionId)
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    fun deleteAi(actionId: String) {
        val d = doc ?: return
        val next = SessionEditor.removeAi(d, actionId)
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    fun deleteAction(actionId: String) {
        val d = doc ?: return
        val next = SessionEditor.remove(d, actionId)
        doc = next
        SessionRepo.save(next)
        render(false)
    }

    /** 从这一轮分支：复制到这一轮（含）成新会话，原会话不动。 */
    fun branchFrom(actionId: String) {
        val d = doc ?: return
        val newId = SessionRepo.newId("s")
        val branched = SessionEditor.branch(d, actionId, newId, { i -> SessionRepo.newId("a$i") })
            ?: return
        SessionRepo.save(branched)
        sink.onBranchCreated(branched.id, branched.title)
    }

    // ------------------------------------------------------------ 自动配图

    /**
     * 正文里出现 `[[IMG: 描述]]` 就后台出图，出一张挂一张。
     *
     * 不阻塞文字显示（用户要求：文字是文字，生图是生图）。
     * 原生自己打标签 + 出图，不经过 web 的生成通道。
     */
    private fun maybeAutoImage(actionId: String, text: String) {
        val env = imageEnv ?: return
        if (!env.settings.enabled || !env.config.usable) return
        if (!AutoImageSupport.supportsNative(env.config.provider)) {
            sink.onToast("当前生图接口暂不支持原生出图，请用网页生成")
            return
        }

        AutoImageService.run(
            context = appContext,
            text = text,
            env = env,
            onImage = { att ->
                // 会话可能已经切走/这条被删了：找不到就不写，避免往别的会话里塞图
                val d = doc ?: return@run
                if (d.actions.none { it.id == actionId }) return@run
                val next = SessionEditor.upsertImage(d, actionId, att)
                doc = next
                SessionRepo.save(next)
                render(false)
            },
            onDone = { render(false) },
        )
    }

    // ------------------------------------------------------------ 生命周期

    fun release() {
        // 正在生成就退出：把已经吐出来的正文存下来，别让用户白等
        val d = doc
        if (genId != null && d != null && genText.isNotBlank()) {
            doc = SessionEditor.replaceAi(d, genActionId, genText)
            doc?.let { SessionRepo.save(it) }
        }
        GenBridge.release(this)
        clearGenState()
    }

    /** 供界面取某条消息的图片（点图放大用）。 */
    fun imagePathsOf(item: ChatItem): List<String> = item.imagePaths

    /**
     * 摘掉消息里对某张已删本地图片的引用。
     *
     * 用户在查看器里删了文件（聊天场景单独删图）后必须调它：会话数据里的
     * ActionImage 还挂着这个路径，不摘的话下次渲染又会出现一个"点开说
     * 图片不存在"的空壳 —— 用户看到的就是"删了还显示"。
     * 只动 images，不动正文；摘完落盘并重绘。
     */
    fun removeImageByPath(path: String) {
        val d = doc ?: return
        val stem = java.io.File(path).nameWithoutExtension
        var changed = false
        val actions = d.actions.map { a ->
            val kept = a.images.filterNot { img ->
                val p = img.localPath ?: img.dataUrl
                when {
                    p.isNullOrBlank() -> false
                    p == path -> true
                    // dataUrl 转存文件以 hashCode 命名，按文件名对上
                    !p.startsWith("/") -> p.hashCode().toString() == stem
                    else -> false
                }
            }
            if (kept.size != a.images.size) {
                changed = true
                a.copy(images = kept)
            } else a
        }
        if (!changed) return
        val next = d.copy(actions = actions, updatedAt = System.currentTimeMillis())
        doc = next
        SessionRepo.save(next)
        render(false)
    }
}
