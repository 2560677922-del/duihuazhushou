package com.dbpg.duihuazhushou.chat

import android.content.Context
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
import com.dbpg.stcore.transformForDisplay

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

    // ------------------------------------------------------------ 打开 / 刷新

    /**
     * 打开一个会话。
     *
     * @param snapshot 兜底用：本地还没有这份会话时，从网页快照构造一份再落盘。
     *                 正常迁移过的会话不会走到这里。
     */
    fun open(sessionId: String, snapshot: String? = null) {
        SessionRepo.init(appContext)
        SessionRepo.load(sessionId) { loaded ->
            val d = loaded ?: run {
                val fromSnap = snapshot?.let { SessionRepo.fromSnapshot(it) }
                val made = fromSnap ?: SessionRepo.create("新冒险")
                SessionRepo.save(made)
                made
            }
            bind(d)
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

    /** 把 SessionDoc 转成列表项。所有操作走完都调它一次。 */
    private fun render(scrollToBottom: Boolean) {
        val d = doc ?: return
        sink.onItems(buildItems(d), scrollToBottom)
    }

    private fun buildItems(d: SessionDoc): List<ChatItem> {
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

            val (text, paths) = renderAi(
                text = aiText,
                images = a.images,
                depth = d.actions.size - 1 - i,
                streaming = false,
            )
            out.add(
                ChatItem(
                    id = "a:${a.id}",
                    role = "assistant",
                    content = text,
                    imagePaths = paths,
                    streaming = generating,
                    actionId = a.id,
                    variantIndex = a.variantIndex,
                    variantCount = a.variants.size,
                ),
            )
        }
        return out
    }

    /** AI 正文 → 显示文本 + 本地图片路径（跑 ST 显示变换：思维链/正则/变量/剥标记）。 */
    private fun renderAi(
        text: String,
        images: List<ActionImage>,
        depth: Int,
        streaming: Boolean,
    ): Pair<String, List<String>> {
        if (text.isBlank()) return "" to emptyList()

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

        val blocks = transformForDisplay(
            text = text,
            attachments = attachments,
            scripts = display.scripts,
            vars = display.variables,
            ctx = display.macroContext,
            hideReasoning = appearance.hideReasoning,
            reasoningCutMarker = display.reasoningCutMarker.ifEmpty { null },
            depthOverride = depth,
        )

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
                        // 本地路径直接可用；dataUrl 才需要转存
                        val path = if (p.startsWith("/")) p else imageCache.pathFor(p)
                        if (path != null) paths.add(path)
                    }
                }
            }
        }
        return sb.toString() to paths
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
}
