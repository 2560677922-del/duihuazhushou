package com.dbpg.duihuazhushou.chat.render.plugin

/**
 * 内置插件库（随 APK 打包）。
 *
 * ★ 这里列出的插件**永远存在**，设置页没有「删除」操作，只有
 *   「安装 / 卸载」运行时挂载。以后预设换了写法、或者社区插件换了约定，
 *   在 [ALL] 里加一条新插件、卸载旧的即可 —— 主渲染管线不用动。
 *
 * ★ 内置插件只做**一件事**：把 ` ```html ` 卡片渲染成界面。
 *   判定对齐「酒馆助手」`is_frontend` 与「小白X」`shouldRenderCodeBlock`
 *   —— 认完整网页 / HTML 片段，不认任何预设的专有标签；
 *   渲染对齐 `createSrcContent` —— 补 charset / viewport / reset CSS /
 *   `--TH-viewport-height` / 横向溢出锁，包成自包含、脚本可运行的隔离文档。
 *
 * ★ 刻意不做：不解析 `<summary>` / `<options>` / `<comment>`，不生成面板，
 *   不猜散文。这些是**预设自己的显示正则与模板**的职责 —— 预设想用就用，
 *   我们只负责把它产出的卡片渲染出来。早期那层「散文 → 模板」的适配会把
 *   正文后半段一起吃进悄悄话，已整体删除。
 *
 * ★ 插件本体不碰正文、不碰正则、不碰请求体、不写全局状态。没装插件时
 *   `ChatController.decideRender` 的原有逻辑逐字节不变。
 */
object PluginCatalog {

    val ALL: List<RenderPlugin> = listOf(FrontendCardPlugin)

    fun byId(id: String): RenderPlugin? = ALL.firstOrNull { it.descriptor.id == id }
}

// ---------------------------------------------------------------- 卡片识别

internal data class FenceMatch(val range: IntRange, val lang: String, val body: String)

/**
 * 卡片识别 + 隔离文档构造，与 `assets/msg/render.js` 的 FENCE / DOC_MARK
 * 判定保持同一套语义，保证插件产出一定能被主 App 切成 doc 段走 iframe。
 */
internal object FenceAdapter {

    private val FENCE = Regex(
        """^[ \t]*```[ \t]*([a-zA-Z0-9_-]*)[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$""",
        RegexOption.MULTILINE,
    )
    private val DOC_MARK = Regex("""<!DOCTYPE\s+html|<html\b|<head\b""", RegexOption.IGNORE_CASE)
    private val DOC_CLOSE = Regex("""</html\s*>|</head\s*>|</body\s*>""", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("""<script\b""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HAS_VIEWPORT = Regex("""<meta\s+name=["']?viewport""", RegexOption.IGNORE_CASE)

    /** 对齐小白X shouldRenderCodeBlock 的片段起始标签集合。 */
    private val FRAGMENT_START = Regex(
        """^\s*(?:<!--[\s\S]*?-->\s*)*<(?:style|link|meta|svg|iframe|canvas|img|video|audio|picture|div|section|main|article|header|footer|nav|aside|p|span|button|input|textarea|select|label|ul|ol|li|table|thead|tbody|tr|td|th|form|figure|figcaption|details|summary|dialog|h[1-6])\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 加固标记。注入到文档 head 里，既是「这份卡片已被渲染器整理过」的
     * 幂等凭据，也让设置页 / 排查时能一眼看出是谁渲染的。
     */
    private const val MARK = "dhp-render-plugin"

    private const val VIEWPORT =
        """<meta name="viewport" content="width=device-width,initial-scale=1">"""

    /**
     * 对齐 createSrcContent 的 reset 层：vh 变量、盒模型、横滚锁、媒体上限。
     * 预设自己的 CSS 仍优先（后加载者胜），这里只兜底"没写就补"。
     */
    private val GUARD =
        "<style data-dhp-plugin>" +
            ":root{--TH-viewport-height:100vh}" +
            "*,*::before,*::after{box-sizing:border-box}" +
            "html,body{margin:0;padding:0;max-width:100%;width:100%;overflow-x:hidden}" +
            "img,table,video,canvas,svg,iframe,pre{max-width:100%}" +
            "</style>"

    fun all(rich: String): List<FenceMatch> =
        FENCE.findAll(rich).map { FenceMatch(it.range, it.groupValues[1], it.groupValues[2]) }.toList()

    /** 完整 HTML 文档：有文档标记且有闭合标签。 */
    fun isFullDoc(body: String): Boolean =
        DOC_MARK.containsMatchIn(body) && DOC_CLOSE.containsMatchIn(body)

    /** HTML 片段：以常见容器标签开头，或直接带 <script>。 */
    fun isFragment(body: String): Boolean =
        FRAGMENT_START.containsMatchIn(body) || SCRIPT.containsMatchIn(body)

    /** 已被本渲染器整理过 → 幂等放行，不再二次包裹。 */
    fun isHardened(body: String): Boolean = body.contains(MARK)

    /**
     * 完整文档加固：只往 `<head>` 注入 reset / vh / 标记，**不碰 body**。
     *
     * ★ 预设模板的 `<template id="raw-data">$1</template>` 和生成折叠窗的
     *   `<script>` 全在 body 里，必须原样保留 —— 折叠窗正是靠它们跑出来的。
     *   这里绝不做转义、绝不做标签白名单，只在 head 里补样式。
     */
    fun hardenDoc(body: String, pluginId: String): String {
        if (isHardened(body)) return body
        val mark = """<meta name="$MARK" content="$pluginId">"""
        val vp = if (HAS_VIEWPORT.containsMatchIn(body)) "" else VIEWPORT
        val payload = mark + vp + GUARD
        return when {
            HEAD_OPEN.containsMatchIn(body) ->
                HEAD_OPEN.replace(body) { m -> m.value + payload }
            HTML_OPEN.containsMatchIn(body) ->
                HTML_OPEN.replace(body) { m -> m.value + "<head>" + payload + "</head>" }
            else -> toDocument(body, pluginId)
        }
    }

    /**
     * 片段 → 独立文档。补骨架、锁横向溢出、提供 `--TH-viewport-height`
     * （对齐酒馆助手的 vh 改写，避免卡片高度无限增长）。
     */
    fun toDocument(body: String, pluginId: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            VIEWPORT +
            """<meta name="$MARK" content="$pluginId">""" +
            GUARD +
            "</head><body><div data-render-plugin=\"$pluginId\" style=\"width:100%\">" +
            body +
            "</div></body></html>"

    /**
     * 重建围栏块，语言统一标成 html。
     *
     * ★ 必须保证闭合 ``` 独占一行：render.js 的 FENCE 要求
     *   `^[ \t]*```[ \t]*$`。产出的文档以 `</html>` 结尾、不带换行时，
     *   若直接拼上去，闭合围栏会黏在 `</html>` 后面匹配失败，整段被当
     *   Markdown 显示（实测踩过）。
     */
    fun rebuild(body: String): String {
        val b = if (body.endsWith("\n")) body else body + "\n"
        return "```html\n$b```"
    }

    /**
     * 逐个围栏改写；transform 返回 null 表示该块不动。
     * 一块都没动则返回 null（= 插件 Pass，回退内置渲染）。
     */
    fun rewrite(rich: String, transform: (FenceMatch) -> String?): String? {
        val matches = all(rich)
        if (matches.isEmpty()) return null
        val sb = StringBuilder()
        var last = 0
        var changed = false
        for (f in matches) {
            val rep = transform(f) ?: continue
            sb.append(rich, last, f.range.first)
            sb.append(rep)
            last = f.range.last + 1
            changed = true
        }
        if (!changed) return null
        sb.append(rich, last, rich.length)
        return sb.toString()
    }
}

// ---------------------------------------------------------------- 内置插件

/**
 * 通用前端卡渲染器。
 *
 * 只做两件事，都是「把卡片渲染出来」必需的：
 *   ① 完整网页 → 往 head 补 viewport / reset / `--TH-viewport-height` / 横滚锁；
 *   ② HTML 片段 → 补成独立文档再交给 iframe。
 * 另外把非 html 的围栏标注统一成 ` ```html `（预设换了标注 / 文档前带注释时，
 * 内置判定会失败并显示成源码，这是社区插件存在的真正原因）。
 *
 * ★ 刻意不做的事：不解析 `<summary>` / `<options>` / `<comment>`，不生成
 *   面板，不猜散文。这些是**预设自己的显示正则与模板**的职责 —— 预设怎么写
 *   就怎么渲染，换预设也不用改这里。幂等靠 head 里的加固标记。
 */
private object FrontendCardPlugin : RenderPlugin {

    override val descriptor = PluginDescriptor(
        // id 沿用最早那个，老用户的安装 / 启用状态直接继续有效，不用重装
        id = "panel_data_adapter",
        name = "通用前端卡渲染器",
        convention = "``` 围栏里的 HTML：完整网页或片段（非 html 的围栏标注也认）",
        description = "只做一件事：把围栏里的 HTML 补好 viewport / reset / 高度约束，交给 iframe 沙箱渲染，页面里的脚本、折叠窗、选项按钮照常运行；顺手把非 html 的围栏标注统一成 html，避免被当成源码显示。不解析剧情、不生成面板、不认任何预设专有标签 —— 面板长什么样、有没有折叠，由预设自己的显示正则与模板决定。不改正则、不改请求体、不动正文；装上才生效，卸载立即回退。",
        version = "3.0.0",
        priority = 120,
    )

    override fun claim(rich: String, meta: PluginRenderMeta): Boolean =
        FenceAdapter.all(rich).any { f ->
            !FenceAdapter.isHardened(f.body) &&
                (FenceAdapter.isFullDoc(f.body) || FenceAdapter.isFragment(f.body))
        }

    override fun render(rich: String, meta: PluginRenderMeta): String? =
        FenceAdapter.rewrite(rich) { f ->
            when {
                FenceAdapter.isHardened(f.body) -> null
                FenceAdapter.isFullDoc(f.body) ->
                    FenceAdapter.rebuild(FenceAdapter.hardenDoc(f.body, descriptor.id))
                FenceAdapter.isFragment(f.body) ->
                    FenceAdapter.rebuild(FenceAdapter.toDocument(f.body, descriptor.id))
                else -> null
            }
        }

    /** 设置页自检用：这条消息里到底有几个围栏、几个整页 / 片段。 */
    override fun probe(rich: String): String {
        val all = FenceAdapter.all(rich)
        val full = all.count { FenceAdapter.isFullDoc(it.body) }
        val frag = all.count { FenceAdapter.isFragment(it.body) }
        val hard = all.count { FenceAdapter.isHardened(it.body) }
        return "围栏 ${all.size} 段 / 整页 $full / 片段 $frag / 已加固 $hard"
    }
}
