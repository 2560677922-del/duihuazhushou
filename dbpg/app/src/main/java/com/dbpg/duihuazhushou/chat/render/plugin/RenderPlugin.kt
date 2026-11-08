package com.dbpg.duihuazhushou.chat.render.plugin

/**
 * 渲染插件契约（P0）。
 *
 * ★ 定位：这是一个**可装卸的通用前端卡渲染器**插槽，抄的是社区渲染插件
 *   （酒馆助手 / 小白X）那层「把模型吐出的 HTML 卡片渲染成界面」的兼容能力。
 *   预设把折叠窗 / 状态栏 / 选项面板写成什么样（` ```html ` 整页、非标准
 *   围栏、HTML 片段、带注释前缀……）是会变的。把这套识别 + 隔离文档构造
 *   放在插件里，以后换了写法就卸载旧的、内置一个新的，主渲染管线一行不改。
 *
 * ★ 边界：插件只产出一串**可渲染的 HTML**，交给现有 WebView / iframe 渲染。
 *   它不改请求体、不改正则、不写全局状态；正文原样保留在卡片之外。
 *   「没装插件」时 `ChatController.decideRender` 的原有逻辑逐字节不变 ——
 *   装上才生效、卸载即回退，绝不干扰主 App 自身的运行。
 *
 * ★ 插件本体随 APK 打包在 [PluginCatalog]，**永不被删除**。
 *   设置页的「安装 / 卸载」只是把插件挂到运行时 / 从运行时移出；
 *   卸载后它仍在插件库里，随时能装回来（没有云端库，也没有发布页）。
 */
const val PLUGIN_API_VERSION = 1

/** 一条消息交给插件时的上下文，只读。 */
data class PluginRenderMeta(
    val messageId: String,
    /** 距最新一条的深度，0 = 最新。 */
    val depth: Int,
    /** 会话绑定的预设 id，空 = 跟随全局默认。 */
    val presetId: String,
    val sessionId: String,
    /**
     * 只读 assets 的取资源函数，由 [PluginRegistry] 注入（默认空实现）。
     * 内置插件可以借此带上随 APK 打包的预设模板，而**不需要改插件接口**、
     * 也不需要碰 ChatController —— 主链路保持零改动。
     */
    val readAsset: (String) -> String? = { null },
)

/** 插件作用范围。换预设时自动不匹配 → 回退内置，不会串味。 */
sealed class PluginScope {
    object Global : PluginScope()
    data class Presets(val ids: Set<String>) : PluginScope()

    fun matches(presetId: String): Boolean = when (this) {
        is Global -> true
        is Presets -> presetId.isNotBlank() && presetId in ids
    }

    val label: String
        get() = when (this) {
            is Global -> "全局"
            is Presets -> "仅指定预设：" + ids.joinToString("、")
        }
}

data class PluginDescriptor(
    val id: String,
    val name: String,
    /** 一句话说明它认领什么写法，设置页主标题下面那行。 */
    val convention: String,
    /** 详细解释：为什么需要它、装了会怎样、卸了会怎样。 */
    val description: String,
    val version: String,
    val apiVersion: Int = PLUGIN_API_VERSION,
    /** 数字大者优先；多个插件认领同一条消息时只有最高的生效。 */
    val priority: Int,
    val scope: PluginScope = PluginScope.Global,
)

interface RenderPlugin {
    val descriptor: PluginDescriptor

    /** 认领：看这条消息是不是自己负责的写法。false = Pass 给内置渲染。 */
    fun claim(rich: String, meta: PluginRenderMeta): Boolean

    /**
     * 产出交给 WebView 的 HTML。返回 null = Pass（回退内置）。
     *
     * ★ 必须幂等：插件输出**不写回缓存**，缓存命中时会对原始 rich 再跑一次
     *   （`ChatController.renderCached` 命中分支仍会重跑 decideRender）。
     *   所以这里不能做「越跑越长」的累加式改写。
     */
    fun render(rich: String, meta: PluginRenderMeta): String?

    /** 激活时返回的销毁闭包，卸载时调用。P0 的内置插件无状态。 */
    fun onActivate(): Disposable = Disposable { }

    /**
     * 只读诊断：为什么认领 / 为什么不认领。设置页「渲染自检」直接展示。
     * 默认空串；不认识结构（比如卡片渲染器）的插件不用实现。
     */
    fun probe(rich: String): String = ""
}

fun interface Disposable {
    fun dispose()
}
