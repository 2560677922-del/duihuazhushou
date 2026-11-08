package com.dbpg.duihuazhushou.chat.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整页文档块里的脚本保留、片段里的脚本剥掉」的回归测试。
 *
 * 背景：预设的「前端综合面板」正则会吐出带 <script> 的 32KB 整页 HTML 卡片，
 * 选项按钮全靠脚本动态生成。旧版清洗把它一刀切剥掉，卡片只剩空壳、选项变裸文本，
 * 用户看到的就是「正则没生效」。
 */
class HtmlSanitizerTest {

    private val NL = System.lineSeparator()

    private val doc = listOf(
        "<!DOCTYPE html>",
        "<html><head><meta charset=\"utf-8\"><style>body{color:red}</style>",
        "<script>window.parent.postMessage({height:120},'*')</script></head>",
        "<body><div class=\"card\">神谕面板</div></body></html>",
    ).joinToString(NL)

    @Test
    fun fencedHtmlDocumentKeepsItsScript() {
        val raw = listOf(
            "剧情前文。", "", "```html", doc, "```", "", "剧情后文。",
        ).joinToString(NL)
        val out = HtmlSanitizer.clean(raw, false)
        assertTrue("文档块里的 <script> 必须保留（卡片靠它生成选项）", out.contains("<script>"))
        assertTrue(out.contains("神谕面板"))
        assertTrue(out.contains("剧情前文"))
        assertTrue(out.contains("剧情后文"))
    }

    @Test
    fun bareFullDocumentKeepsItsScript() {
        val out = HtmlSanitizer.clean(doc, false)
        assertTrue(out.contains("<script>"))
    }

    @Test
    fun fragmentStillStripsScriptsAndEventHandlers() {
        val raw = "<div onclick=\"alert(1)\">正文<script>alert(2)</script></div>"
        val out = HtmlSanitizer.clean(raw, false)
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("onclick"))
        assertTrue(out.contains("正文"))
    }

    @Test
    fun plainCodeFenceMentioningHtmlIsNotProtected() {
        val raw = listOf(
            "正文提到 <html> 标签：", "", "```", "<html>示例代码块",
            "<script>不应该被保留</script>", "```", "",
        ).joinToString(NL)
        val out = HtmlSanitizer.clean(raw, false)
        assertFalse("普通代码块不该被当成整页文档保护", out.contains("<script>"))
    }

    @Test
    fun allowScriptKeepsEverything() {
        val raw = "<div onclick=\"x\"><script>ok()</script></div>"
        val out = HtmlSanitizer.clean(raw, true)
        assertTrue(out.contains("<script>"))
        assertTrue(out.contains("onclick"))
    }
}
