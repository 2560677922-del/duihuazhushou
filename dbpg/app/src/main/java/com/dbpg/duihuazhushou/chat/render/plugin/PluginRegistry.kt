package com.dbpg.duihuazhushou.chat.render.plugin

import android.content.Context
import android.util.Log

/**
 * 插件仲裁器：一条消息**最多一个** renderer 生效。
 *
 * 这是刻意从社区踩过的坑里抄来的不变量 —— 酒馆助手和小白X 同时开时，
 * 两者都扫同一个 ` ```html ` 代码块各自挂 iframe，再叠加全局 CSS 隐藏
 * `pre`，就会出现「渲染两次 / 闪烁 / 消失」。我们这里没有 DOM，插件也
 * 只能返回一串 HTML，所以仲裁天然成立：多个认领时只有优先级最高的生效。
 *
 * 没装插件 / 全 Pass / apiVersion 不匹配 / scope 不匹配 → 返回 null，
 * `ChatController.decideRender` 随即走原逻辑，效果逐字节不变。
 */
/**
 * 最近一次渲染的诊断快照（只在内存里，不落盘、不写会话）。
 *
 * ★ 给插件设置页的「渲染自检」用：以后预设换了写法、或怀疑插件没生效，
 *   不用猜、也不用连电脑抓日志 —— 设置页直接能看到「主 App 送进来的 rich
 *   长什么样、每个插件认没认领、最后产出多少字符」。
 */
data class RenderDiagnostic(
    val at: Long,
    val richLength: Int,
    val richHead: String,
    val htmlified: Boolean,
    val claims: List<Pair<String, Boolean>>,
    val winner: String?,
    val outputLength: Int?,
    val note: String,
    /** 插件自己报的结构探针（正文行数 / 选项行数 / 分隔线…），换预设排错用。 */
    val probe: String = "",
)

object PluginRegistry {

    private const val TAG = "RenderPlugins"
    private val lock = Any()

    @Volatile private var disposables: Map<String, Disposable> = emptyMap()

    /** 旧三插件 → 一体化插件的状态迁移只跑一次（进程内）。 */
    @Volatile private var migrated = false

    @Volatile var lastDiagnostic: RenderDiagnostic? = null
        private set

    /** 最近一次送进插件槽的原始 rich（只放内存）。设置页可一键复制，排错用。 */
    @Volatile var lastRich: String? = null
        private set

    private fun record(
        rich: String,
        claims: List<Pair<String, Boolean>>,
        winner: String?,
        outputLength: Int?,
        note: String,
        probe: String = "",
    ) {
        // 只截前 160 字符；换行画成 ⏎ 方便在设置页里读
        val head = rich.take(160).replace("\n", "⏎").replace("\r", "")
        lastRich = rich
        lastDiagnostic = RenderDiagnostic(
            at = System.currentTimeMillis(),
            richLength = rich.length,
            richHead = head,
            htmlified = rich.contains("<br", ignoreCase = true),
            claims = claims,
            winner = winner,
            outputLength = outputLength,
            note = note,
            probe = probe,
        )
    }

    /** 让每个启用的插件报一句结构探针，只保留非空的。 */
    private fun probeOf(live: List<RenderPlugin>, rich: String): String =
        live.mapNotNull { p ->
            runCatching { p.probe(rich) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "${p.descriptor.id} → $it" }
        }.joinToString("\n")

    /** 当前预设下、已安装且已启用且契约兼容的插件，按优先级降序。设置页也用它展示仲裁顺序。 */
    fun activeCandidates(ctx: Context, presetId: String): List<PluginDescriptor> {
        val installed = PluginStore.installed(ctx)
        if (installed.isEmpty()) return emptyList()
        val enabled = PluginStore.enabled(ctx)
        return PluginCatalog.ALL
            .map { it.descriptor }
            .filter {
                it.id in installed && it.id in enabled &&
                    it.apiVersion == PLUGIN_API_VERSION && it.scope.matches(presetId)
            }
            .sortedByDescending { it.priority }
    }

    /**
     * 返回插件产出的 HTML；null = 回退内置渲染。
     *
     * ★ 插件输出**不写回** RenderCache：缓存命中分支仍会重跑 decideRender，
     *   所以这里每次拿到的都是原始 rich，插件实现必须幂等（见 RenderPlugin.render）。
     */
    fun resolveHtml(ctx: Context, rich: String, meta: PluginRenderMeta): String? {
        if (!migrated) {
            runCatching { PluginStore.migrateLegacy(ctx) }.onFailure {
                Log.w(TAG, "插件安装状态迁移失败（不影响本次渲染）", it)
            }
            migrated = true
        }
        val installed = PluginStore.installed(ctx)
        if (installed.isEmpty()) {
            record(rich, emptyList(), null, null, "没有安装任何插件 → 内置渲染原样工作")
            return null                                 // 零插件：极快返回
        }
        val enabled = PluginStore.enabled(ctx)
        val live = PluginCatalog.ALL.filter { p ->
            val d = p.descriptor
            d.id in installed && d.id in enabled &&
                d.apiVersion == PLUGIN_API_VERSION && d.scope.matches(meta.presetId)
        }.sortedByDescending { it.descriptor.priority }
        if (live.isEmpty()) {
            record(rich, emptyList(), null, null, "插件都未启用 / 契约或范围不匹配 → 回退内置")
            return null
        }

        val failures = ArrayList<String>()
        val claims = live.associate { p ->
            p.descriptor.id to runCatching { p.claim(rich, meta) }.onFailure {
                failures.add("${p.descriptor.id}: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
                Log.w(TAG, "插件 ${p.descriptor.id} claim 失败，跳过", it)
            }.getOrDefault(false)
        }
        val claimList = claims.toList()
        val probe = probeOf(live, rich)
        val candidates = live.filter { claims[it.descriptor.id] == true }
        if (candidates.isEmpty()) {
            val note = if (failures.isEmpty()) {
                "全部放行 → 这条消息不是插件负责的写法"
            } else {
                "插件 claim 抛异常 → 已回退内置：${failures.joinToString("；")}"
            }
            record(rich, claimList, null, null, note, probe)
            return null
        }
        if (candidates.size > 1) {
            Log.w(
                TAG,
                "多个插件认领同一消息，仅最高优先级生效：${candidates.map { it.descriptor.id }}",
            )
        }
        val winner = candidates.first()
        ensureActive(winner)
        // 内置插件可能需要随 APK 打包的模板资源：注入只读取资源函数，
        // 不改动 ChatController 构造 meta 的任何一行。
        val enriched = meta.copy(readAsset = { path ->
            runCatching {
                ctx.applicationContext.assets.open(path).bufferedReader().use { it.readText() }
            }.getOrNull()
        })
        val out = runCatching { winner.render(rich, enriched) }.onFailure {
            Log.w(TAG, "插件 ${winner.descriptor.id} 渲染失败，回退内置", it)
        }.getOrNull()
        record(
            rich, claimList, winner.descriptor.id, out?.length,
            if (out == null) "渲染返回 null（模板读不到 / 无面板结构）→ 回退内置"
            else "已产出卡片，交给 WebView / iframe",
            probe,
        )
        return out
    }

    private fun ensureActive(plugin: RenderPlugin) {
        val id = plugin.descriptor.id
        synchronized(lock) {
            if (!disposables.containsKey(id)) {
                val d = runCatching { plugin.onActivate() }.getOrDefault(Disposable { })
                disposables = disposables + (id to d)
            }
        }
    }

    /** 设置页改动后调用：销毁已激活插件 + 丢掉状态内存缓存，下次渲染立即生效。 */
    fun onPluginsChanged(ctx: Context) {
        synchronized(lock) {
            disposables.values.forEach { runCatching { it.dispose() } }
            disposables = emptyMap()
        }
        PluginStore.invalidate()
    }
}
