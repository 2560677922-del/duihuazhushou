package com.dbpg.duihuazhushou.chat.render

/**
 * 消息 HTML 的清洗。
 *
 * 威胁模型：消息正文来自模型输出 —— 也就是**不可信输入**。它可以被预设/角色卡
 * 引导成任意字符串，包括 `<script>`、`onerror=`、`javascript:` 链接。
 * WebView 开了 JS（marked 和高度回传都要用），所以这些东西会真的执行。
 *
 * 行为：
 *   - 默认（`allowScript = false`）剥掉 `<script>` / `<iframe>` / `on*` 事件属性 /
 *     `javascript:` 链接。视觉上什么都不影响 —— 这类卡本来就依赖 ST 的扩展
 *     才能跑，原生侧本来也没有对应的宿主。
 *   - 用户在外观设置里打开「允许美化脚本」时原样放行，给需要状态栏/按钮的卡用。
 *
 * 刻意不做的事：不去解析 DOM、不做完整白名单。角色卡美化用到大量自定义标签，
 * 白名单只会把正常内容一起删掉。这里只掐"能执行代码"的那几个口子。
 */
object HtmlSanitizer {

    private val SCRIPT_BLOCK = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_TAG = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val IFRAME_BLOCK = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe\\s*>", RegexOption.IGNORE_CASE)
    private val IFRAME_TAG = Regex("<iframe\\b[^>]*/?>", RegexOption.IGNORE_CASE)

    /** `onclick="..."` / `onerror='...'` / `onload=alert(1)` 三种写法都要吃掉。 */
    private val EVENT_ATTR = Regex(
        "\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE,
    )

    /** `href="javascript:..."` —— 点一下就执行，且不会被上面的规则覆盖。 */
    private val JS_URL = Regex(
        "\\s(href|src)\\s*=\\s*(\"\\s*javascript:[^\"]*\"|'\\s*javascript:[^']*')",
        RegexOption.IGNORE_CASE,
    )

    fun clean(html: String, allowScript: Boolean): String {
        if (allowScript) return html
        var out = html
        out = SCRIPT_BLOCK.replace(out, "")
        out = SCRIPT_TAG.replace(out, "")
        out = IFRAME_BLOCK.replace(out, "")
        out = IFRAME_TAG.replace(out, "")
        out = EVENT_ATTR.replace(out, "")
        out = JS_URL.replace(out, "")
        return out
    }
}
