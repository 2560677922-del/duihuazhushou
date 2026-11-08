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
object PluginRegistry {

    private const val TAG = "RenderPlugins"
    private val lock = Any()

    @Volatile private var disposables: Map<String, Disposable> = emptyMap()

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
        val installed = PluginStore.installed(ctx)
        if (installed.isEmpty()) return null            // 零插件：极快返回
        val enabled = PluginStore.enabled(ctx)
        val live = PluginCatalog.ALL.filter { p ->
            val d = p.descriptor
            d.id in installed && d.id in enabled &&
                d.apiVersion == PLUGIN_API_VERSION && d.scope.matches(meta.presetId)
        }.sortedByDescending { it.descriptor.priority }
        if (live.isEmpty()) return null

        val candidates = live.filter { p ->
            runCatching { p.claim(rich, meta) }.onFailure {
                Log.w(TAG, "插件 ${p.descriptor.id} claim 失败，跳过", it)
            }.getOrDefault(false)
        }
        if (candidates.isEmpty()) return null
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
        return runCatching { winner.render(rich, enriched) }.onFailure {
            Log.w(TAG, "插件 ${winner.descriptor.id} 渲染失败，回退内置", it)
        }.getOrNull()
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
