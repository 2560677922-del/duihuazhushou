package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.chat.render.CardRenderer
import com.dbpg.duihuazhushou.chat.render.HtmlSanitizer
import com.dbpg.duihuazhushou.chat.render.RenderCache
import com.dbpg.duihuazhushou.chat.render.plugin.PluginRegistry
import com.dbpg.duihuazhushou.chat.render.plugin.PluginRenderMeta
import com.dbpg.duihuazhushou.imagegen.AutoImageService
import com.dbpg.duihuazhushou.session.GroupStore
import com.dbpg.duihuazhushou.session.SessionBindingStore
import com.dbpg.duihuazhushou.session.SessionRepo
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.summary.SummaryConfig
import com.dbpg.duihuazhushou.summary.SummaryService
import com.dbpg.stcore.ContentBlock
import com.dbpg.stcore.NativeImage
import com.dbpg.stcore.imagegen.AutoImageEnv
import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.render.Hashing
import com.dbpg.stcore.render.RenderKey
import com.dbpg.stcore.render.StreamPipeline
import com.dbpg.stcore.richtext.FoldParser
import com.dbpg.stcore.session.ActionImage
import com.dbpg.stcore.session.GroupRef
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionEditor
import com.dbpg.stcore.session.SessionHistory
import com.dbpg.stcore.session.SummaryBlock
import com.dbpg.stcore.session.SummaryPlanner
import com.dbpg.stcore.stripHtmlNoise
import com.dbpg.stcore.stripImageMarkers
import com.dbpg.stcore.transformForDisplayRich
import org.json.JSONObject
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

    companion object {
        /**
         * 流式合并间隔。
         *
         * web 侧 80ms 推一次全量，这里再合并到 120ms：人眼对 8fps 的文字刷新
         * 已经无感，而 UI 每帧最多只做一次 setText，输入框不会被挤掉帧。
         */
        private const val DELTA_INTERVAL = 120L
    }

    interface Sink {
        /** 列表数据变了。scroll=true 表示这一轮必须贴底（发送/完成）。 */
        fun onItems(items: List<ChatItem>, scrollToBottom: Boolean)
        /**
         * 流式增量：只更新这一条的文字。
         *
         * ★ 不走 [onItems]：整表提交会触发 DiffUtil 全表比对 + 可见项全部 rebind，
         *   而流式每 120ms 就来一次。这里只改一条，界面层用 payload/轻量 bind 处理。
         */
        fun onStreamingText(actionId: String, text: String)
        fun onTitle(title: String)
        /** 生成中：停止按钮可见、输入框禁用 */
        fun onGenerating(active: Boolean)
        fun onToast(message: String)
        /** 分支出新会话，界面负责跳过去 */
        fun onBranchCreated(sessionId: String, title: String)

        /** 摘要任务开始/结束（界面可以转圈或置灰入口）。默认不关心。 */
        fun onSummaryState(active: Boolean) {}

        /** 摘要有变化（新增/编辑/删除）。默认不关心。 */
        fun onSummaryChanged() {}
    }

    var doc: SessionDoc? = null
        private set

    private var display: DisplayConfig = DisplayConfig.empty()
    private var appearance: AppearanceConfig = AppearanceConfig()

    /**
     * 渲染指纹的两个维度（内容哈希，不是自增版本号）。
     *
     * 自增版本号在进程重启后会归零，和磁盘里的旧产物撞 key，出现
     * "配置明明改了、界面还是老样子"。所以这里必须是"配置内容的哈希"。
     */
    private var cfgHash: Int = 0
    private var lookHash: Int = 0

    /** 是否存在 minDepth/maxDepth 脚本：只有这时 depth 才需要参与缓存键。 */
    private var depthSensitive: Boolean = false

    /**
     * 引擎侧「当前启用的预设 id」的本地快照。
     *
     * 会话没绑定预设时，生成请求必须显式带上它 —— 否则请求里没有
     * presetId，web 侧只能靠 getCurrentPreset() 猜；一旦引擎刚重建、
     * 或者用户导入新预设后没切全局默认，就会拿错预设（表现就是模型
     * 「直接拒绝」，因为破限/角色提示词没进去）。
     */
    private var activePresetId: String = ""

    private val cache: RenderCache by lazy { RenderCache.get(appContext) }

    /** 卡片位图：actionId → (路径, 高度)。渲染完成后才有。 */
    private data class CardRef(val path: String, val heightPx: Int)

    private val cardRefs = java.util.concurrent.ConcurrentHashMap<String, CardRef>()

    /** 最近一次构建出的 HTML（按 actionId）。卡片渲染要用它，避免再写回缓存读一遍。 */
    private val renderedHtml = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 渲染失败/超时的卡片：不再反复请求，直接长期显示纯文本。 */
    private val cardFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 生图配置快照：打开会话时拉一次，之后自动配图全用它，不再问 web。 */
    private var imageEnv: AutoImageEnv? = null

    /**
     * 会话绑定的预设 id（空 = 跟随全局默认）。
     *
     * 摘要请求要带"当前聊天的预设"，但不能自己在原生解析 ST 预设 ——
     * 排序、启用、宏、变量全在 web 兼容层。这里只记 id，取值时交给
     * `EngineBridge.fetchJson("exportSummaryPreset", id)`。
     */
    private var summaryPresetId: String? = null

    /**
     * 本会话扮演的角色卡（来自 SessionBindingStore）。
     *
     * 打开会话时读一次并绑到引擎；生成时再按请求带一份。
     * 空 = 老会话没记过角色，按引擎全局当前角色走。
     */
    private var characterId: String? = null

    /** 角色显示名（头像按它去原生卡库找图）。 */
    @Volatile
    private var characterName: String? = null

    /** 当前使用的用户角色（全局一份，切会话不重置）。 */
    private var persona: com.dbpg.duihuazhushou.character.PersonaStore.Persona? = null

    /** 本会话所属群聊（单人会话为 null）。渲染时用它把 speakerId 映射成名字。 */
    @Volatile
    private var group: GroupStore.Group? = null

    /** 同一时间只允许一个摘要任务：并发会互相覆盖区间。 */
    private var summarizing = false

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
        bindAssets(d)
        sink.onTitle(d.title.ifBlank { "冒险" })
        render(true)
        refreshDisplayConfig()
        refreshImageEnv()
        refreshActivePreset()
    }

    /**
     * 读本会话的资产绑定，并把「会话 → 角色卡」同步给引擎。
     *
     * 这一步是修"换个角色再回来就串卡"的关键：会话自己记着跟谁聊，
     * 打开时把这张卡绑上去，显示宏 `{{char}}` 用的就是它，不靠全局单例。
     */
    private fun bindAssets(d: SessionDoc) {
        val b = SessionBindingStore.load(appContext, d.id)
        summaryPresetId = b.presetId
        characterId = b.characterId
        characterName = b.characterName
        persona = com.dbpg.duihuazhushou.character.PersonaStore.active(appContext)
        group = d.group?.let { GroupStore.get(appContext, it.groupId) }
        GenBridge.bindSession(d.id, b.characterId.orEmpty())
        if (b.characterId == null && d.actions.isEmpty()) adoptCurrentCharacter(d, b)
    }

    /**
     * 空会话没有绑定角色时，把引擎当前角色固化到绑定里。
     *
     * 「用它开聊」的流程是：网页把角色设为 currentCharacterId → 建空会话 →
     * 拉起原生聊天。这时会话文件里还没有角色，不固化下来的话，用户换个角色
     * 再回来就串卡了（这正是这次要修的 bug）。
     *
     * 只对**空会话**做：老会话继续跟随全局，不会被"现在选中的卡"污染。
     */
    private fun adoptCurrentCharacter(d: SessionDoc, b: SessionBindingStore.Binding) {
        GenBridge.fetchAssetCatalog { json ->
            val root = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return@fetchAssetCatalog
            val cur = root.optJSONObject("current") ?: return@fetchAssetCatalog
            val cid = cur.optString("characterId", "")
            if (cid.isBlank()) return@fetchAssetCatalog
            // 这期间用户可能已经手动改过绑定 / 切了会话
            if (doc?.id != d.id) return@fetchAssetCatalog
            if (SessionBindingStore.load(appContext, d.id).characterId != null) return@fetchAssetCatalog

            val name = root.optJSONArray("characters")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == cid }?.optString("name")
            }.orEmpty()

            SessionBindingStore.save(
                appContext,
                d.id,
                b.copy(characterId = cid, characterName = name),
            )
            characterId = cid
            characterName = name
            GenBridge.bindSession(d.id, cid)
            render(true)
        }
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
     * 重新把当前内容推给界面（群聊切回一个后台频道时用）。
     *
     * 不读盘：数据一直在内存里，后台生成也会照常更新 doc，
     * 直接重绘比 reload 少一次 IO。
     */
    fun refreshView() = render(false)

    /** 群聊页销毁：停掉这个频道的生成并释放渲染线程。 */
    fun shutdown() {
        release()
        io.shutdown()
    }

    /** 这个频道是否正在生成（切回来时要还原发送/停止按钮）。 */
    fun isGenerating(): Boolean = genId != null

    /**
     * 重拉"怎么渲染 / 生图配置"。
     *
     * 用户在网页设置里换了预设、正则、生图接口后回到聊天页，这里要跟上，
     * 否则新消息用的还是旧配置。
     */
    fun refreshConfig() {
        // 绑定可能刚被「本对话资产」改过：摘要要跟着用新的预设
        doc?.let {
            bindAssets(it)
            GenBridge.bindSession(it.id, characterId.orEmpty())
        }
        refreshDisplayConfig()
        refreshImageEnv()
        refreshActivePreset()
    }

    /** 缓存引擎当前预设 id：生成请求（含重新生成）会显式带上它。 */
    private fun refreshActivePreset() {
        GenBridge.fetchAssetCatalog { json ->
            val current = runCatching { JSONObject(json ?: "{}").optJSONObject("current") }.getOrNull()
            activePresetId = current?.optString("presetId").orEmpty()
        }
    }

    fun applyAppearance(cfg: AppearanceConfig) {
        if (cfg == appearance) return
        val old = lookHash
        appearance = cfg
        lookHash = lookHashOf(cfg)
        // 外观变了，缓存里"长什么样"就全错了。整体清掉，代价是重算一次。
        if (lookHash != old) {
            cache.clearForLookChange()
            invalidateCards()
        }
        render(false)
    }

    private fun refreshDisplayConfig() {
        val id = doc?.id ?: return
        GenBridge.fetchDisplayConfig(id) { json ->
            val parsed = DisplayConfig.parse(json)
            val old = cfgHash
            display = parsed
            cfgHash = cfgHashOf(parsed)
            depthSensitive = parsed.scripts.any { it.minDepth != null || it.maxDepth != null }
            if (cfgHash != old && old != 0) {
                // 换预设/正则/变量：产物全部作废（全清最省心，旧 key 也不会再命中）
                cache.clearAll()
            }
            render(false)
        }
    }

    // ------------------------------------------------------------ 渲染指纹

    /**
     * "怎么渲染"的指纹：脚本 + 变量 + 宏上下文（角色卡身份部分）。
     *
     * ★ 刻意排除 macroContext.chat / lastMessage / currentMessage：
     *   它们随每条消息变化，纳入进来会导致**任何缓存都永远不命中**，
     *   缓存反而变成纯开销。
     *
     * ★ 但如果用户的美化正则真的引用了 {{lastMessage}}，排除就会导致
     *   "上一条变了、这一条还显示旧值"。所以这里做一次检查：
     *   命中这类宏就整体关掉缓存（[cacheable]），宁可慢也不能错。
     */
    private fun cfgHashOf(d: DisplayConfig): Int {
        val sb = StringBuilder(1024)
        for (s in d.scripts) {
            sb.append(s.id).append('\u0001').append(s.findRegex).append('\u0001')
                .append(s.replaceString).append('\u0001')
                .append(s.minDepth).append('\u0001').append(s.maxDepth).append('\u0001')
                .append(s.placement?.joinToString(",")).append('\u0001')
                .append(s.disabled).append(s.markdownOnly).append(s.promptOnly).append('\u0001')
                .append(s.trimStrings?.joinToString("\u0002")).append('\u0001')
                .append(s.substituteRegex).append('\u0003')
        }
        // 变量表按 key 排序后拼接：HashMap 的遍历顺序不稳定，直接拼会抖
        d.variables.session.toSortedMap().forEach { (k, v) -> sb.append(k).append('=').append(v).append('\u0001') }
        d.variables.global.toSortedMap().forEach { (k, v) -> sb.append("g:").append(k).append('=').append(v).append('\u0001') }
        val mc = d.macroContext
        sb.append(mc.char).append('\u0001').append(mc.user).append('\u0001')
            .append(mc.persona).append('\u0001').append(mc.description).append('\u0001')
            .append(mc.scenario).append('\u0001').append(mc.original).append('\u0001')
            .append(mc.mesExample)
        return Hashing.intOf(sb.toString())
    }

    /** "长什么样"的指纹：字号/行距/字体/颜色/宽度/思维链开关。 */
    private fun lookHashOf(c: AppearanceConfig): Int = Hashing.intOf(
        c.textSizeSp.toString(),
        c.lineSpacing.toString(),
        c.bubbleWidthPercent.toString(),
        c.fontFamily,
        c.fontColor,
        c.htmlRender.toString(),
        c.allowScript.toString(),
        c.hideReasoning.toString(),
        display.reasoningCutMarker,
    )

    /** 正则里引用了"会随消息变化"的宏时，缓存必须关掉。 */
    private val cacheable: Boolean
        get() = display.scripts.none { s ->
            val r = s.replaceString
            r.contains("{{chat}}") || r.contains("{{lastMessage}}") || r.contains("{{currentMessage}}")
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

            val rendered = renderCached(
                actionId = a.id,
                text = aiText,
                images = a.images,
                depth = d.actions.size - 1 - i,
                display = display,
                appearance = appearance,
                generating = generating,
                sessionId = d.id,
            )
            val webHtml = rendered.html
            if (webHtml != null) {
                renderedHtml[a.id] = webHtml
            } else {
                renderedHtml.remove(a.id)
            }
            val card = if (webHtml != null) cardRefs[a.id] else null
            out.add(
                ChatItem(
                    id = "a:${a.id}",
                    role = "assistant",
                    content = rendered.plain,
                    speakerName = speakerNameOf(d, a),
                    avatarKey = avatarKeyOf(d, a),
                    imagePaths = rendered.paths,
                    streaming = generating,
                    actionId = a.id,
                    variantIndex = a.variantIndex,
                    variantCount = a.variants.size,
                    html = webHtml,
                    // 含 <details> 的消息走原生折叠块：可交互、默认展开状态可配，
                    // 不会像位图卡片那样「默认收起 + 点不动」。
                    foldHtml = rendered.foldHtml,
                    cardPath = card?.path,
                    cardHeightPx = card?.heightPx ?: 0,
                ),
            )
        }
        return out
    }

    /**
     * 这条回复的发言人名字（群聊专用）。
     *
     * 公共区看 speakerId；成员频道固定是该频道的角色；单人会话返回空 ——
     * 单人聊天的角色由绑定决定，但原生只知道角色 uid，不知道显示名，
     * 与其显示一半不如不显示。
     */
    /**
     * 这条消息的头像 key：群聊按发言人；单人会话用绑定的角色名。
     *
     * 单人聊天不显示名字（私聊里每句都顶个"阿岚"很吵），只用头像。
     */
    private fun avatarKeyOf(d: SessionDoc, a: SessionAction): String {
        val id = a.speakerId.ifBlank {
            if (d.group?.kind == GroupRef.KIND_MEMBER) d.group?.memberId.orEmpty() else ""
        }
        if (id.isNotBlank()) {
            return group?.members?.firstOrNull { it.characterId == id }?.name.orEmpty()
        }
        return if (d.group == null) characterName.orEmpty() else ""
    }

    private fun speakerNameOf(d: SessionDoc, a: SessionAction): String {
        val g = group ?: return ""
        val id = a.speakerId.ifBlank {
            if (d.group?.kind == GroupRef.KIND_MEMBER) d.group?.memberId.orEmpty() else ""
        }
        if (id.isBlank()) return ""
        return g.members.firstOrNull { it.characterId == id }?.name.orEmpty()
    }

    /**
     * renderAi 的输出。
     *
     * 三个 HTML 字段刻意分开，因为它们去的地方完全不同：
     *   rich     —— 跑完正则的完整 HTML（Markdown 未转），也是渲染缓存的存储形态
     *   html     —— 交给 WebView 真渲染；只有「含结构性标记」时非空
     *   foldHtml —— 原生折叠块的降级路径；WebView 不可用时才有用
     *
     * 为什么 `html` 只判"含不含标记"、不再猜具体标签：
     *   上一版要求「块级标签 ≥2 或含 <style>」才走 WebView，`<details>` 单开
     *   标签只命中 1 次，于是被判成普通文本 —— 折叠栏就成了两行裸字。
     *   预设的正则写法本来就没有边界（div/table/summary/自定义 class），
     *   按标签白名单追是追不完的，改成"有标记就交给浏览器"。
     */
    private data class RenderedMsg(
        val plain: String,
        val paths: List<String>,
        val rich: String,
        val html: String?,
        val foldHtml: String?,
    )

    /**
     * 决定一条消息走哪种渲染：
     *   含结构性标记 → WebView（浏览器怎么渲染就怎么渲染）
     *   其余         → 单个 TextView（Markdown 走 RichTextParser，最快、可选中复制）
     */
    private fun decideRender(
        plain: String,
        paths: List<String>,
        rich: String,
        appearance: AppearanceConfig,
        meta: PluginRenderMeta? = null,
    ): RenderedMsg {
        /*
         * ★ 渲染插件槽（P0）：只在「装了插件、且插件认领这条消息」时介入。
         *   插件只产出一串 HTML，后面 WebView / iframe 整套完全复用现有代码，
         *   主渲染管线一行都没重写。没装插件（或插件放行）时 pluginHtml 为 null，
         *   下面三行与改动前逐字节一致 —— 这是零回归的保证。
         *
         *   ★ 插件输出不写回 RenderedMsg.rich：缓存里永远存原始 rich。
         *     renderCached 的命中分支会重跑本函数，若把改写结果写回缓存，
         *     下次命中就会二次改写。返回给界面的内容走 web 字段即可。
         */
        val pluginHtml = if (meta != null) {
            PluginRegistry.resolveHtml(appContext, rich, meta)
        } else null
        val effective = pluginHtml ?: rich

        /*
         * 先在 Kotlin 侧清洗再判：模型输出可以带 <script>，
         * 而 WebView 是开着 JS 的（marked + 高度回传都要用）。
         * 清洗放在判定之前，也顺带避免"只剩一个 <script> 也判成富文本"。
         */
        val cleaned = HtmlSanitizer.clean(effective, appearance.allowScript)
        val web = if (cleaned.isNotBlank() && appearance.htmlRender && hasMarkup(cleaned)) cleaned else null
        // 用户关掉富渲染时，至少让 <details> 还能用原生折叠块看（只认这一种）
        val fold = if (web == null && rich.isNotBlank() && FoldParser.hasFold(rich)) rich else null
        return RenderedMsg(plain, paths, rich, web, fold)
    }

    /**
     * 消息里有没有"结构性标记"。
     *
     * ★ `<br>` 必须排除：`htmlifyNewlines` 会给每条多行消息插 `<br>`，
     *   拿它当判据等于所有消息都走 WebView —— 那正是要避免的开销。
     *
     * 除 `<br>` 以外的标签一律算（包括 `<xy-card>` 这种作者自定义标签）：
     * 宁可多花一次 WebView 渲染，也不要因为"没见过这个标签"把内容显示成裸字。
     */
    private fun hasMarkup(html: String): Boolean =
        MARKUP_TAG.findAll(html).any { !it.value.startsWith("<br", ignoreCase = true) }

    /** 匹配任意开/自闭合标签。`<3`、`a<b` 这种不带标签边界的不会命中。 */
    private val MARKUP_TAG = Regex("<([a-zA-Z][a-zA-Z0-9-]*)(\\s|/?>)")

    // ------------------------------------------------------------ 卡片位图

    /**
     * 为一条卡片消息准备位图。
     *
     * 由界面在 bind 时调用（说明"这一条已经滚到可见区了"），
     * 因此渲染是**按需**的：200 条会话不会在打开时一次性画 200 张图。
     *
     * 失败一次就不再重试（[cardFailed]）：宁可长期显示纯文本，也不要让
     * 一张坏卡片把渲染队列堵死。
     */
    fun ensureCard(actionId: String, widthPx: Int) {
        if (actionId.isEmpty() || widthPx <= 0) return
        if (cardRefs.containsKey(actionId) || cardFailed.contains(actionId)) return
        val html = renderedHtml[actionId] ?: return

        // key 里必须带外观与宽度：换字体/改气泡宽度后旧位图就不对了
        val key = "c" + java.lang.Long.toHexString(
            Hashing.fnv(actionId, Hashing.text(html).toString(), lookHash.toString(), widthPx.toString()),
        )

        CardRenderer.render(
            context = appContext,
            key = key,
            html = html,
            widthPx = widthPx,
            style = CardRenderer.Style.of(appearance),
        ) { path, heightPx ->
            if (path == null || heightPx <= 0) {
                cardFailed.add(actionId)
                return@render
            }
            cardRefs[actionId] = CardRef(path, heightPx)
            // 位图就绪：只刷新这一条。用 render(false) 是因为高度/内容变了，
            // 需要走一次正常的列表重建（有缓存，代价很小）。
            render(false)
        }
    }

    /** 卡片位图作废（改外观/换气泡宽度时调用）。 */
    private fun invalidateCards() {
        cardRefs.clear()
        cardFailed.clear()
    }

    /**
     * 带缓存的渲染：内容定稿的消息只算一次。
     *
     * 命中条件 = 正文 + 图片 + 配置指纹 + 外观指纹 全一致。
     * 生成中的那一条**永远不走缓存**：它每 120ms 都在变，缓存只会制造垃圾。
     *
     * 这里是整个性能方案的核心：打开 200 条会话时，第二次开始几乎是纯 map 查找，
     * 不再重跑两万字的美化正则。
     */
    private fun renderCached(
        actionId: String,
        text: String,
        images: List<ActionImage>,
        depth: Int,
        display: DisplayConfig,
        appearance: AppearanceConfig,
        generating: Boolean,
        sessionId: String,
    ): RenderedMsg {
        if (text.isBlank()) return RenderedMsg("", emptyList(), "", null, null)
        // 插件上下文：会话绑定预设优先，未绑定则跟随引擎全局当前预设。
        val meta = PluginRenderMeta(
            messageId = actionId,
            depth = depth,
            presetId = summaryPresetId?.takeIf { it.isNotBlank() } ?: activePresetId,
            sessionId = sessionId,
        )
        if (generating || sessionId.isEmpty() || !cacheable) {
            return renderAi(text, images, depth, display, appearance, meta)
        }

        val imgSig = if (images.isEmpty()) "" else images.joinToString("\u0001") {
            it.id + ":" + (it.localPath ?: it.dataUrl ?: "") + ":" + it.markdownIndex
        }
        val key = RenderKey(
            actionId = actionId,
            contentHash = Hashing.text(text) xor Hashing.fnv(imgSig),
            cfgHash = cfgHash,
            lookHash = lookHash,
            // 只有存在 minDepth/maxDepth 脚本时才把深度纳入；深度精确参与，
            // 追加消息会让旧消息失效一次 —— 宁可多算一次，也不能显示错的内容。
            depth = if (depthSensitive) depth else -1,
        )

        cache.get(sessionId, key)?.let { e ->
            // 图片文件可能被用户在查看器里删了：过滤掉，但不影响正文
            val live = e.paths.filter { java.io.File(it).exists() }
            // 缓存里存的是富 HTML，卡片/折叠的判定在这里重算 —— 判定依赖
            // appearance，而它已经进了 lookHash，配置一变缓存自然失效。
            return decideRender(e.plain, live, e.html ?: "", appearance, meta)
        }

        val out = renderAi(text, images, depth, display, appearance, meta)
        cache.put(sessionId, key, RenderCache.Entry(out.plain, out.rich, out.paths))
        return out
    }

    private fun renderAi(
        text: String,
        images: List<ActionImage>,
        depth: Int,
        display: DisplayConfig,
        appearance: AppearanceConfig,
        meta: PluginRenderMeta? = null,
    ): RenderedMsg {
        if (text.isBlank()) return RenderedMsg("", emptyList(), "", null, null)

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
        // 三种渲染路径的判定集中在 decideRender：折叠块 / 卡片位图 / 纯文本。
        return decideRender(sb.toString(), paths, rich.html, appearance, meta)
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
    /**
     * 群聊公共区：让某个成员说一句。
     *
     * 仍然是一轮 = 一个 Action，只是 user 栏留空、ai 栏放回复、speakerId 记发言人。
     * 这样列表、变体、重新生成、摘要锚点的语义与普通轮次完全一致，不用第二套消息模型。
     *
     * @param prompt 送给模型的"接着说"提示；角色人格由请求里的角色卡提供
     */
    fun sendAs(speakerId: String, prompt: String = "继续") {
        val d = doc ?: return
        if (speakerId.isBlank()) return
        if (genId != null) {
            sink.onToast("还在生成，先停一下")
            return
        }
        val actionId = SessionRepo.newId("a")
        val next = SessionEditor.appendAction(
            doc = d,
            actionId = actionId,
            user = "",
            ai = "",
            speakerId = speakerId,
        )
        doc = next
        SessionRepo.save(next)
        render(true)
        beginGen(actionId, prompt, isNew = true, characterOverride = speakerId)
    }

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
        /*
         * 第一轮（角色卡开场白）没有用户发言，user 会是空串。
         * 空串在 web 侧不会 push 成最后一条 user，请求结构就和正常发送
         * 不一样（历史以 assistant 结尾）—— 模型容易直接拒答。
         * 补一个中性的「继续」让轮次结构保持一致，预设照常注入。
         */
        beginGen(actionId, user.ifBlank { "继续" }, isNew = false)
    }

    /**
     * 用户气泡下的「重新发送」：拿这一轮的原文再请求一次。
     *
     * 与 AI 气泡下的「重新生成」是同一套行为（砍掉后续、旧回复进 variants），
     * 只是入口和文案不同 —— 用户想要的是"我这句话再问一遍"，而模型侧
     * 需要的正是「原文 + 完整预设」的一次新请求。
     */
    fun resend(actionId: String) {
        val d = doc ?: return
        if (genId != null) {
            sink.onToast("还在生成，先停一下")
            return
        }
        if (d.actions.none { it.id == actionId }) return
        regenerate(actionId)
    }

    private fun beginGen(
        actionId: String,
        userInput: String,
        isNew: Boolean,
        /** 群聊公共区：这条回复由谁说（私聊为空） */
        speakerId: String = "",
        /** 这次生成要用谁的角色卡；空 = 用会话绑定的那个 */
        characterOverride: String = "",
    ) {
        val d = doc ?: return
        val group = d.group?.let { GroupStore.get(appContext, it.groupId) }
        val speakers = if (d.group?.kind == GroupRef.KIND_PUBLIC) {
            group?.members?.associate { it.characterId to it.name }.orEmpty()
        } else {
            emptyMap()
        }
        /*
         * 导出上下文：摘要开启时，被覆盖的轮次用摘要替代原文。
         *
         * 这一步在**原生**完成，web 只收到一份"已经变短的干净历史"，
         * 所以 assemblePrompt / token 裁剪都不用改，也不会打架。
         * 关闭摘要时走的是完全相同的旧路径（summaries 传空）。
         */
        val sCfg = SummaryConfig.load(appContext)
        val history = SessionHistory.export(
            doc = d,
            uptoActionId = actionId,
            summaries = if (sCfg.enabled) d.summaries else emptyList(),
            summaryRole = sCfg.role,
            // 公共区一条时间线上有多个人说话，必须标名字；私聊/单人不受影响
            speakerNames = speakers,
        )
        val env = imageEnv

        // 本对话的资产引用（世界书/预设/正则）：绑定 ∩ 资产页启用项在引擎侧过滤
        val binding = com.dbpg.duihuazhushou.session.SessionBindingStore.load(appContext, d.id)
        val books = booksFor(d, group, binding)
        val effectivePreset = binding.presetId?.takeIf { it.isNotBlank() }
            ?: group?.presetId?.takeIf { it.isNotBlank() }
            ?: activePresetId
        val bindingJson = JSONObject().apply {
            books?.let { put("worldBookIds", org.json.JSONArray(it)) }
            // 正则：成员显式选了用自己的，否则用群级的；都没配就跟随全局
            (binding.regexGroupIds ?: group?.regexGroupIds)?.let {
                put("regexGroupIds", org.json.JSONArray(it))
            }
            // 重新生成也必须带预设：会话没绑定时退回引擎当前启用的那个
            if (effectivePreset.isNotBlank()) put("presetId", effectivePreset)
        }

        // 谁的角色卡：本条消息的发言人（公共区）> 本次指定 > 会话绑定（成员频道）> 空
        // 从 action 读而不是从调用方传，重新生成同一条时才能自动用回同一个发言人。
        val speakerId = d.actions.find { it.id == actionId }?.speakerId.orEmpty()
        val roleId = speakerId.takeIf { it.isNotBlank() }
            ?: characterOverride.takeIf { it.isNotBlank() }
            ?: characterId.orEmpty()
        // 公共区里用户自己说话：不注入角色卡（否则会套用成员/上次选中的卡）
        val noCharacter = d.group?.kind == GroupRef.KIND_PUBLIC && speakerId.isEmpty()

        // 用户角色：所有会话共用（{{user}} 名字 + 人设块），没配就不带
        val personaJson = persona?.takeIf { it.name.isNotBlank() || it.description.isNotBlank() }
            ?.let {
                JSONObject().put("name", it.name).put("description", it.description)
            }

        val reqId = GenBridge.request(
            sessionId = d.id,
            messages = history,
            userInput = userInput,
            imageInstruction = env?.settings?.enabled,
            imageMax = env?.settings?.maxPerReply ?: 2,
            assetBinding = bindingJson,
            presetId = effectivePreset,
            characterId = roleId,
            noCharacter = noCharacter,
            userPersona = personaJson,
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

    /**
     * 本次生成该注入哪些世界书。
     *
     * 群聊的分层规则：
     *   - 公共区：只带群公共书；
     *   - 成员频道：群公共书 ∪ 该成员私有书（公共书强制生效，成员去不掉）；
     *   - 单人会话：完全按绑定（null = 跟随全局）。
     */
    private fun booksFor(
        d: SessionDoc,
        group: GroupStore.Group?,
        binding: SessionBindingStore.Binding,
    ): List<String>? = when (d.group?.kind) {
        GroupRef.KIND_PUBLIC -> group?.publicWorldBookIds ?: binding.worldBookIds
        GroupRef.KIND_MEMBER -> if (group == null) binding.worldBookIds else
            (group.publicWorldBookIds + binding.worldBookIds.orEmpty()).distinct()
        else -> binding.worldBookIds
    }

    fun stop() {
        val id = genId ?: return
        GenBridge.stop(id)
        // 不在这里清状态：等 web 回 onGenStopped，把已生成的部分落下来
    }

    override fun onGenDelta(text: String) {
        if (genId == null) return
        genText = text

        /*
         * 流式合并：web 侧已经是 80ms 一推，这里再按 120ms 合并一次。
         *
         * ★ 为什么不能直接 render()：那会把整个会话重新构建一遍（哪怕有缓存，
         *   也要对每条算指纹 + 建对象）。流式是"一条在变、其余不动"，
         *   所以只把这**一条**的文字推给界面，列表的其余部分完全不碰。
         *
         * ★ 为什么用轻管线而不是完整正则：正则可能跨整篇，半截文本会产出
         *   "半个 <div>"，定稿后又会跳变。这里只做思考块截断 + 删图片标记，
         *   定稿（onGenDone）再一次性跑完整管线并写入缓存。
         */
        if (pendingText == null) main.postDelayed(deltaFlush, DELTA_INTERVAL)
        pendingText = text
    }

    private var pendingText: String? = null

    private val deltaFlush = Runnable {
        val t = pendingText ?: return@Runnable
        pendingText = null
        val aid = genActionId
        if (aid.isEmpty() || genId == null) return@Runnable
        val shown = StreamPipeline.render(
            text = t,
            hideReasoning = appearance.hideReasoning,
            cutMarker = display.reasoningCutMarker.ifEmpty { null },
            streaming = true,
        )
        sink.onStreamingText(aid, shown)
    }

    private fun stopDeltaFlush() {
        pendingText = null
        main.removeCallbacks(deltaFlush)
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
        // 自动摘要放在生成完成之后：绝不能在流式过程中跑（会抢网络/抢主线程）
        maybeAutoSummarize()
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
        stopDeltaFlush()
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

    /**
     * 从这一轮分支：复制到这一轮（含）成新会话，原会话不动。
     *
     * 标题按「原标题（1）→（2）」自动编号，不用固定后缀 —— 否则连分两次
     * 会得到「A · 分支 · 分支」，列表里没法分辨。
     *
     * 编号要扫已有标题，而读索引是磁盘 IO，所以整个流程放在 SessionRepo
     * 的 IO 线程里做，回来再刷界面（`onBranchCreated` 在主线程）。
     *
     * 资产绑定（预设/世界书/正则/DNA）**必须一起复制**：它们是按 sessionId
     * 存的独立小文件，不复制就自动回落到"跟随全局"，表现为"分支一出去
     * 预设就换了"——很难联想到是分支的锅。
     */
    fun branchFrom(actionId: String) {
        val d = doc ?: return
        SessionRepo.list { entries ->
            // 这一刻可能已经切走会话了：以点击时的 doc 为准，但别把人拽回来
            if (doc?.id != d.id) return@list
            val suffix = SessionEditor.branchSuffix(d.title, entries.map { it.title })
            val newId = SessionRepo.newId("s")
            val branched = SessionEditor.branch(
                d, actionId, newId, { i -> SessionRepo.newId("a$i") }, titleSuffix = suffix,
            ) ?: return@list
            SessionRepo.save(branched)
            // 绑定文件很小，同步读写没问题；不复制就等于分支换了套资产
            val binding = SessionBindingStore.load(appContext, d.id)
            if (!binding.isDefault) SessionBindingStore.save(appContext, branched.id, binding)
            sink.onBranchCreated(branched.id, branched.title)
        }
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

        // 本对话的 DNA（人物外观锚定）＋ 出图参考图：都只在生图时生效。
        // 出图参考图取 genRefs.chat（聊天 ⋯ 菜单里设的那张），不是风格页的展示图。
        val binding = com.dbpg.duihuazhushou.session.SessionBindingStore.load(appContext, doc?.id.orEmpty())
        val envWithDna = env.copy(dnaTags = ConnectionStore.dnaTags(binding.dnaIds))
        val genRef = ConnectionStore.snapshot("getImageStyle")
            ?.optJSONObject("genRefs")?.optJSONObject("chat") ?: JSONObject()

        AutoImageService.run(
            context = appContext,
            text = text,
            env = envWithDna,
            refImage = genRef.optString("image").takeIf { it.isNotBlank() },
            refStrength = genRef.optDouble("strength", 0.6),
            refInfoExtracted = genRef.optDouble("info", 0.7),
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

    // ------------------------------------------------------------ 自动摘要

    /** 摘要是否正在跑（界面给入口转圈）。 */
    fun isSummarizing(): Boolean = summarizing

    /** 当前会话里"仍然有效"的摘要块（按时间顺序，通常只有一条）。 */
    fun summaries(): List<SummaryBlock> {
        val d = doc ?: return emptyList()
        return SummaryPlanner.effective(d.summaries, d.actions).first
    }

    /** 摘要覆盖到第几轮（不含）；0 = 还没摘要过。 */
    fun summarizedUpTo(): Int {
        val d = doc ?: return 0
        return SummaryPlanner.effective(d.summaries, d.actions).second
    }

    /**
     * 生成结束后检查一次：攒够了就自动摘要。
     *
     * 触发口径（用户原话）：每 [SummaryConfig.everyCount] 条（或每
     * [SummaryConfig.everyChars] 字）触发一次，压缩**最早的**
     * [SummaryConfig.takeCount] 条 —— 不是"输出 N 条摘要"。
     * 两者差值天然把最近的几条留在原文区，所以不需要额外设"保留最近 N 条"。
     */
    private fun maybeAutoSummarize() {
        val d = doc ?: return
        val cfg = SummaryConfig.load(appContext)
        if (!cfg.enabled || summarizing) return
        val upTo = summarizedUpTo()
        val range = SummaryPlanner.next(
            total = d.actions.size,
            summarizedUpTo = upTo,
            mode = if (cfg.mode == SummaryConfig.MODE_CHARS) {
                SummaryPlanner.TriggerMode.CHARS
            } else {
                SummaryPlanner.TriggerMode.COUNT
            },
            everyCount = cfg.everyCount,
            everyChars = cfg.everyChars,
            takeCount = cfg.takeCount,
            // 0：靠 everyCount - takeCount 的差额保护最近几条，见 SummaryConfig 注释
            keepRecent = 0,
            charsOf = { i ->
                val a = d.actions.getOrNull(i)
                (a?.user?.length ?: 0) + (a?.ai?.length ?: 0)
            },
        ) ?: return
        startSummary(range, cfg)
    }

    /**
     * 手动摘要（右上角入口）：不看阈值，直接压缩"最早未摘要的 N 条"。
     *
     * 用户可以拿它当"我现在就想省上下文"的开关，也可以先点一次看看效果。
     */
    fun summarizeNow() {
        val d = doc ?: return
        if (summarizing) {
            sink.onToast("正在浓缩，稍等")
            return
        }
        val cfg = SummaryConfig.load(appContext)
        val upTo = summarizedUpTo()
        val pending = d.actions.size - upTo
        if (pending <= 0) {
            sink.onToast("没有可压缩的内容")
            return
        }
        val take = cfg.takeCount.coerceIn(1, pending)
        startSummary(upTo until (upTo + take), cfg)
    }

    /**
     * 起一次摘要任务。
     *
     * 失败一律静默降级（只 toast 一句）：摘要是"锦上添花"，绝不能因为
     * 中转不支持、超时、返回空，就让聊天卡住或丢消息。
     */
    private fun startSummary(range: IntRange, cfg: SummaryConfig) {
        val d = doc ?: return
        if (summarizing || range.isEmpty()) return
        summarizing = true
        sink.onSummaryState(true)

        // 已有摘要一起带上：滚动摘要必须承接前情，否则第 3 次摘要就丢了开头
        val prior = SummaryPlanner.effective(d.summaries, d.actions).first

        SummaryService.run(
            context = appContext,
            doc = d,
            range = range,
            cfg = cfg,
            prior = prior,
            presetId = summaryPresetId,
        ) { block ->
            summarizing = false
            sink.onSummaryState(false)
            val cur = doc
            // 摘要期间可能切了会话：结果只回写原会话，绝不串台
            if (cur == null || cur.id != d.id) return@run
            if (block == null) {
                sink.onToast("摘要失败（不影响聊天）")
                return@run
            }
            /*
             * 只落一条：fromIndex 已被 SummaryService 合并到最早起点。
             * 旧的在有效区间内被这条覆盖，无效残留（锚点坏掉的）顺手清掉。
             */
            val next = cur.copy(
                summaries = listOf(block),
                updatedAt = System.currentTimeMillis(),
            )
            doc = next
            SessionRepo.save(next)
            sink.onSummaryChanged()
            sink.onToast("已把前 ${range.count()} 条压缩成摘要")
        }
    }

    /** 用户改摘要正文（空串视为不改；要删请用 [clearSummaries]）。 */
    fun updateSummaryText(text: String) {
        val d = doc ?: return
        val blocks = SummaryPlanner.effective(d.summaries, d.actions).first
        if (blocks.isEmpty()) return
        val t = text.trim()
        if (t.isEmpty()) return
        val next = d.copy(
            summaries = listOf(blocks.first().copy(text = t)),
            updatedAt = System.currentTimeMillis(),
        )
        doc = next
        SessionRepo.save(next)
        sink.onSummaryChanged()
    }

    /** 清掉摘要，恢复"全文发给模型"。原文一直没删，所以随时可回退。 */
    fun clearSummaries() {
        val d = doc ?: return
        if (d.summaries.isEmpty()) return
        val next = d.copy(summaries = emptyList(), updatedAt = System.currentTimeMillis())
        doc = next
        SessionRepo.save(next)
        sink.onSummaryChanged()
        sink.onToast("已清除摘要，之后发送全文")
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
