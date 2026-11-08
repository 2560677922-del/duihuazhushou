package com.dbpg.duihuazhushou.chat.render.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置「通用前端卡渲染器」的回归测试。
 *
 * 只测「把卡片渲染出来」这一件事：认完整网页 / HTML 片段、补 head、统一围栏
 * 标注、幂等、不碰正文。**不测面板结构** —— 面板是预设正则与模板的产物，
 * 插件不该知道 `<summary>` / `<options>` 长什么样。
 */
class PluginCatalogTest {

    private val NL = "\n"
    private val meta = PluginRenderMeta(
        messageId = "a1",
        depth = 0,
        presetId = "Meowssiah_1.9.12",
        sessionId = "s1",
    )

    private val card = PluginCatalog.ALL.first { it.descriptor.id == "panel_data_adapter" }

    private val doc = listOf(
        "<!DOCTYPE html>",
        "<html><head><meta charset=\"utf-8\"></head>",
        "<body><div class=\"card\">状态栏</div></body></html>",
    ).joinToString(NL)

    private val fragment =
        "<div class=\"statusbar\"><details><summary>选项</summary>① 往前走</details></div>"

    /** 贴近预设 [13] 前端综合面板：body 里带 template 数据 + 生成折叠窗的脚本。 */
    private val panelDoc = listOf(
        "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">",
        "<title>小猫之神 · 神谕面板</title></head><body>",
        "<template id=\"raw-data\"><summary>遭遇战响应</summary>",
        "<options><option>① 举盾格挡</option><option>② 侧身闪避</option></options></template>",
        "<main class=\"oracle-panel\"><details id=\"summaryDetails\">",
        "<summary>遭遇战响应</summary></details></main>",
        "<script>(function(){parseAndRender();initToggle();})();</script>",
        "</body></html>",
    ).joinToString(NL)

    private fun fenced(lang: String, body: String) =
        listOf("```$lang", body, "```").joinToString(NL)

    @Test
    fun catalogIsBuiltInStableAndContractCompatible() {
        assertEquals("内置库只保留一个通用渲染器", 1, PluginCatalog.ALL.size)
        assertNotNull(PluginCatalog.byId("panel_data_adapter"))
        assertNull(PluginCatalog.byId("does_not_exist"))
        assertNull("早期的三个插件已合并，不应再出现在目录里",
            PluginCatalog.byId("frontend_card_strict"))
        assertNull(PluginCatalog.byId("frontend_card_loose"))
        assertTrue(PluginCatalog.ALL.all { it.descriptor.apiVersion == PLUGIN_API_VERSION })
        assertTrue("插件必须声明为全局作用域，换预设不用重装",
            PluginCatalog.ALL.all { it.descriptor.scope == PluginScope.Global })
    }

    @Test
    fun nonHtmlFenceIsRelabeledAndHardened() {
        // 预设把卡片标成 ```ui / ``` 或文档前带注释时，内置判定会失败并显示源码
        val raw = listOf("剧情前文。", "", fenced("ui", doc), "", "剧情后文。").joinToString(NL)
        assertTrue(card.claim(raw, meta))
        val out = card.render(raw, meta)!!
        assertTrue("围栏语言必须被统一成 html", out.contains("```html"))
        assertFalse(out.contains("```ui"))
        assertTrue("围栏外的正文不能丢", out.contains("剧情前文") && out.contains("剧情后文"))
        assertTrue(out.contains("状态栏"))
        assertTrue("必须补齐 createSrcContent 骨架", out.contains("--TH-viewport-height"))
        assertTrue(out.contains("name=\"viewport\""))
        assertTrue(out.contains("data-dhp-plugin"))
    }

    @Test
    fun standardHtmlFenceIsHardenedToo() {
        val raw = fenced("html", doc)
        assertTrue(card.claim(raw, meta))
        val out = card.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue(out.contains("--TH-viewport-height"))
        assertTrue(out.contains("dhp-render-plugin"))
        assertTrue("正文原样保留", out.contains("状态栏"))
    }

    @Test
    fun fragmentIsWrappedIntoDocument() {
        val raw = listOf("```", fragment, "```").joinToString(NL)
        assertTrue(card.claim(raw, meta))
        val out = card.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue("片段必须补成独立文档", out.contains("<!DOCTYPE html>"))
        assertTrue(out.contains("data-render-plugin=\"panel_data_adapter\""))
        assertTrue(out.contains("--TH-viewport-height"))
        assertTrue(out.contains("statusbar"))
        assertTrue(
            "闭合围栏必须独占一行，否则 render.js 的 FENCE 匹配不上、切不出 doc 段",
            out.contains("</html>" + NL + "```"),
        )
    }

    @Test
    fun rewritingIsIdempotent() {
        val raw = listOf("```", fragment, "```").joinToString(NL)
        val once = card.render(raw, meta)!!
        assertNull("对插件输出再跑一次必须放行，不能二次补文档", card.render(once, meta))
        assertFalse("幂等后再跑 claim 也不该认领", card.claim(once, meta))
    }

    @Test
    fun plainTextIsNeverClaimed() {
        val plain = "她抬起头，指尖还沾着夜露。「……要往前走吗？」"
        assertFalse(card.claim(plain, meta))
        assertNull(card.render(plain, meta))
    }

    /**
     * 预设正则产出、已经带 raw-data 与折叠窗脚本的卡片：加固只发生在 head，
     * body 必须逐字节保留 —— 折叠窗完全靠 body 里的模板与脚本跑起来。
     */
    @Test
    fun presetPanelCardKeepsScriptAndRawDataIntact() {
        val raw = listOf("她抬起头。「要往前走吗？」", "", fenced("html", panelDoc)).joinToString(NL)
        assertTrue(card.claim(raw, meta))
        val out = card.render(raw, meta)!!
        assertTrue("剧情正文不能丢", out.contains("要往前走吗"))
        assertTrue("<template id=\"raw-data\"> 必须原样保留", out.contains("<template id=\"raw-data\">"))
        assertTrue("选项标签必须原样保留", out.contains("<option>① 举盾格挡</option>"))
        assertTrue("生成折叠窗的 <script> 必须原样保留", out.contains("<script>(function(){parseAndRender()"))
        assertTrue(out.contains("oracle-panel"))
        assertTrue("head 里要补 GUARD", out.contains("--TH-viewport-height"))
        assertTrue("body 内容一字不少", out.endsWith("</body></html>\n```"))
        assertNull("加固后再跑必须放行", card.render(out, meta))
    }

    @Test
    fun commentPrefixedDocumentIsClaimed() {
        val withComment = "<!-- generated by preset -->" + NL + doc
        val raw = fenced("ui", withComment)
        assertTrue(card.claim(raw, meta))
        val out = card.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue("注释前缀必须保留", out.contains("generated by preset"))
        assertTrue(out.contains("状态栏"))
    }

    @Test
    fun probeReportsFenceStats() {
        val raw = listOf(fenced("ui", doc), "", fenced("", fragment)).joinToString(NL)
        val p = card.probe(raw)
        assertTrue("要报出围栏总数", p.contains("围栏 2 段"))
        assertTrue("要区分整页与片段", p.contains("整页 1") && p.contains("片段 1"))
        assertTrue("要报已加固数量", p.contains("已加固 0"))
    }
}
