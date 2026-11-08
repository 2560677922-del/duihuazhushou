package com.dbpg.duihuazhushou.chat.render.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置「通用前端卡渲染器」插件的回归测试。
 *
 * 重点验证三件事：
 *   1. 插件能把各种写法（非标准围栏 / HTML 片段 / 注释前缀）整理成内置
 *      render.js 认得的 ` ```html ` 隔离文档，并补齐 createSrcContent 骨架；
 *   2. 真实预设折叠窗模板里的 `<template id="raw-data">` / `<script>` 原样
 *      保留（折叠窗正是靠它们跑出来的），body 不被改写；
 *   3. 改写幂等（插件输出不写回缓存，缓存命中会对原始 rich 再跑一次）；
 *      纯文本 / 零插件路径逐字节不变。
 */
class PluginCatalogTest {

    private val NL = "\n"
    private val meta = PluginRenderMeta(
        messageId = "a1",
        depth = 0,
        presetId = "Meowssiah_1.9.12",
        sessionId = "s1",
    )

    private val strict = PluginCatalog.ALL.first { it.descriptor.id == "frontend_card_strict" }
    private val loose = PluginCatalog.ALL.first { it.descriptor.id == "frontend_card_loose" }
    private val panel = PluginCatalog.ALL.first { it.descriptor.id == "panel_data_adapter" }

    /** 模拟 PluginRegistry 注入的 assets 读取：单测用 classpath 里的真实模板。 */
    private val assetMeta = meta.copy(readAsset = { path ->
        javaClass.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
    })

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
        assertEquals(3, PluginCatalog.ALL.size)
        assertNotNull(PluginCatalog.byId("frontend_card_strict"))
        assertNotNull(PluginCatalog.byId("frontend_card_loose"))
        assertNotNull(PluginCatalog.byId("panel_data_adapter"))
        assertNull(PluginCatalog.byId("does_not_exist"))
        assertTrue(PluginCatalog.ALL.all { it.descriptor.apiVersion == PLUGIN_API_VERSION })
        assertTrue("严格插件优先级必须高于宽松插件（仲裁靠它）",
            strict.descriptor.priority > loose.descriptor.priority)
    }

    @Test
    fun strictRewritesNonHtmlFenceAndKeepsSurroundingProse() {
        val raw = listOf("剧情前文。", "", fenced("ui", doc), "", "剧情后文。").joinToString(NL)
        assertTrue(strict.claim(raw, meta))
        val out = strict.render(raw, meta)!!
        assertTrue("围栏语言必须被统一成 html", out.contains("```html"))
        assertFalse(out.contains("```ui"))
        assertTrue("围栏外的正文不能丢", out.contains("剧情前文") && out.contains("剧情后文"))
        assertTrue(out.contains("状态栏"))
        assertTrue("必须补齐 createSrcContent 骨架", out.contains("--TH-viewport-height"))
        assertTrue(out.contains("name=\"viewport\""))
        assertTrue(out.contains("data-dhp-plugin"))
    }

    @Test
    fun strictHardensStandardHtmlFenceToo() {
        // 标准写法也要做加固（vh 变量 / 横滚锁），不再只是放行 —— 这正是渲染器的活
        val raw = fenced("html", doc)
        assertTrue(strict.claim(raw, meta))
        val out = strict.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue(out.contains("--TH-viewport-height"))
        assertTrue(out.contains("dhp-render-plugin"))
        assertTrue("正文原样保留", out.contains("状态栏"))
    }

    @Test
    fun looseWrapsHtmlFragmentIntoDocument() {
        val raw = listOf("```", fragment, "```").joinToString(NL)
        assertFalse("完整文档才算严格插件的活", strict.claim(raw, meta))
        assertTrue(loose.claim(raw, meta))
        val out = loose.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue("片段必须补成独立文档", out.contains("<!DOCTYPE html>"))
        assertTrue(out.contains("data-render-plugin=\"frontend_card_loose\""))
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
        val once = loose.render(raw, meta)!!
        assertNull("对插件输出再跑一次必须放行，不能二次补文档", loose.render(once, meta))
        assertNull("严格插件也不能对加固结果二次动手", strict.render(once, meta))
        assertEquals(once, loose.render(once, meta) ?: once)
        assertFalse("幂等后再跑 claim 也不该认领", loose.claim(once, meta))
    }

    @Test
    fun realPanelTemplateKeepsScriptAndRawDataIntact() {
        // 这是「折叠窗能不能出来」的关键：模板数据与脚本必须逐字节保留
        val raw = listOf("她抬起头。「要往前走吗？」", "", fenced("html", panelDoc)).joinToString(NL)
        assertTrue(strict.claim(raw, meta))
        val out = strict.render(raw, meta)!!
        assertTrue("剧情正文不能丢", out.contains("要往前走吗"))
        assertTrue("<template id=\"raw-data\"> 必须原样保留", out.contains("<template id=\"raw-data\">"))
        assertTrue("选项标签必须原样保留", out.contains("<option>① 举盾格挡</option>"))
        assertTrue("生成折叠窗的 <script> 必须原样保留", out.contains("<script>(function(){parseAndRender()"))
        assertTrue(out.contains("oracle-panel"))
        // 加固只发生在 head：head 之后应该出现 GUARD，body 里的标签一个不少
        assertTrue(out.contains("--TH-viewport-height"))
        // 幂等
        assertNull(strict.render(out, meta))
    }

    @Test
    fun commentPrefixedDocumentIsClaimed() {
        // 文档前带生成注释时，内置 DOC_START 失败、会显示成源码，插件要接住
        val withComment = "<!-- generated by preset -->" + NL + doc
        val raw = fenced("ui", withComment)
        assertTrue(strict.claim(raw, meta))
        val out = strict.render(raw, meta)!!
        assertTrue(out.contains("```html"))
        assertTrue(out.contains("generated by preset"))
        assertTrue(out.contains("状态栏"))
    }

    @Test
    fun plainTextIsNeverClaimed() {
        val plain = "她抬起头，指尖还沾着夜露。「……要往前走吗？」"
        assertFalse(strict.claim(plain, meta))
        assertFalse(loose.claim(plain, meta))
        assertNull(strict.render(plain, meta))
        assertNull(loose.render(plain, meta))
    }

    @Test
    fun scopeGuardsAgainstWrongPreset() {
        assertTrue(PluginScope.Global.matches(""))
        assertTrue(PluginScope.Global.matches("Meowssiah_1.9.12"))
        val scoped = PluginScope.Presets(setOf("Meowssiah_1.9.12"))
        assertTrue(scoped.matches("Meowssiah_1.9.12"))
        assertFalse("换预设必须自动不匹配 → 回退内置", scoped.matches("OtherPreset"))
        assertFalse("预设 id 为空时不该误命中", scoped.matches(""))
    }

    // ---------------------------------------------------------- 面板数据适配器

    /** 贴近用户实测截图：模型把 summary / comment / options 全写成散文。 */
    private val prosePanel = listOf(
        "你把手机随手扔回柔软的地毯上，发出一声沉闷的「扑通」声。",
        "你仰起头，看着白色的天花板，大腿上的白丝在昏暗的光线里泛着一层淡淡的、犹如珍珠般的哑光。",
        "",
        "---",
        "",
        "本段剧情发生在午后的一间小卧室里。详细描写了神宫雏首次出场的外貌特征：幼态娇憨的鹅蛋脸、八字刘海，以及身穿宽大草莓卫衣与白丝袜的穿着细节，突出了她娇小与肉感并存的反差感。雏在房间里整理丝袜，随后手机收到青梅竹马发来的游戏邀约，最后她放下手机，仰面靠在地板上稍作休息。",
        "😏 拿起手机，打开前置摄像头，稍微调整一下卫衣",
        "😏 突然传来敲门声，伴随着母亲喊你出去帮忙",
        "😏 觉得地板有点凉，你双手撑着地毯站起身，去厨房看看",
        "小猫之神严格按照您的要求，描写了超多超级细致的动作和环境喵！",
        "(◕ᴗ•́و✿)◇ 下一步你想怎么走？快选一个，或者自己写回复喵！",
    ).joinToString(NL)

    @Test
    fun panelAdapterClaimsProseButNotHtmlCards() {
        assertTrue("散文式面板必须被认领", panel.claim(prosePanel, meta))
        assertFalse("没有 HTML 卡片时前端卡渲染器应当放行", strict.claim(prosePanel, meta))
        assertFalse(loose.claim(prosePanel, meta))
        assertFalse("纯正文没有分隔线不该认领", panel.claim("她抬起头。「要往前走吗？」", meta))
        assertFalse(
            "预设正则已正常吐出 HTML 卡片时必须 Pass，不能重复包裹",
            panel.claim(fenced("html", panelDoc), meta),
        )
    }

    @Test
    fun panelAdapterFillsPresetTemplateWithStructuredTags() {
        assertNull("读不到模板资源必须 Pass（零影响）", panel.render(prosePanel, meta))
        val out = panel.render(prosePanel, assetMeta)!!
        assertTrue(out.contains("```html"))
        assertFalse("模板占位符必须被替换掉", out.contains("__DHP_PANEL_DATA__"))
        assertTrue("剧情正文必须留在卡片之外", out.contains("犹如珍珠般的哑光"))
        assertTrue("预设模板的 raw-data 容器必须保留", out.contains("<template id=\"raw-data\">"))
        assertTrue("生成折叠窗的 <script> 必须原样保留", out.contains("<script>"))
        assertTrue("必须补出 summary 标签", out.contains("<summary>"))
        assertTrue("必须补出 options 标签", out.contains("<options>"))
        assertTrue(
            "三个散文选项都要变成 <option>",
            out.contains("<option>拿起手机") && out.contains("<option>突然传来敲门声") &&
                out.contains("<option>觉得地板有点凉"),
        )
        assertFalse("emoji 前缀要剥掉", out.contains("<option>😏"))
        assertTrue("闭合围栏必须独占一行", out.contains("</html>" + NL + "```"))
        assertFalse("幂等：产出已含 HTML 卡片，再跑不该认领", panel.claim(out, meta))
        // 把真实 Kotlin 产物落盘，供真浏览器端到端验证「折叠窗真的渲染出来」
        runCatching {
            java.io.File("/workspace/st-lab/plugin-slot/kotlin_panel_output.txt").writeText(out)
        }
    }

    /** 实测：同一条消息里已有「思维链折叠 2.5」吐出的整页 HTML 折叠栏卡片。 */
    private val foldCard = fenced("", listOf(
        "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">",
        "<title>小猫之神 - 折叠栏</title></head><body>",
        "<details><summary>点我收起喵~</summary><div>设定与要求回忆分析</div></details>",
        "</body></html>",
    ).joinToString(NL))

    @Test
    fun panelAdapterClaimsProseAlongsideOtherHtmlCards() {
        // 回归：旧实现「消息里有任意 HTML 卡片就 Pass」，会被思维链折叠栏挡住，
        // 于是已启用的面板适配器对整条消息什么都不做，只剩正文 + 一条横线。
        val rich = listOf(foldCard, "", prosePanel).joinToString(NL)
        assertTrue("已有的折叠栏卡片不该挡住散文面板适配", panel.claim(rich, meta))
        val out = panel.render(rich, assetMeta)!!
        assertTrue("已有的折叠栏卡片必须原样保留", out.contains("<title>小猫之神 - 折叠栏</title>"))
        assertTrue("剧情正文必须留在卡片之外", out.contains("犹如珍珠般的哑光"))
        assertTrue("必须补出面板卡片", out.contains("```html") && out.contains("<options>"))
        assertFalse("幂等：产出已有面板卡片，再跑不该认领", panel.claim(out, meta))
    }

    @Test
    fun panelAdapterClaimsProseWithoutAnyDivider() {
        // 用户实测：复制出来的模型输出里根本没有 ----，横线是渲染层画的。
        // 适配不能把 --- 当硬前提，要靠「剧情小总结 + 连续选项 + 结尾吐槽」认面板。
        val noDivider = listOf(
            "你把手机随手扔回柔软的地毯上，发出一声沉闷的「扑通」声。",
            "你仰起头，看着白色的天花板，大腿上的白丝在昏暗的光线里泛着一层淡淡的、犹如珍珠般的哑光。",
            "",
            "本段剧情发生在午后的一间小卧室里。详细描写了神宫雏首次出场的外貌特征：幼态娇憨的脸蛋、八字刘海，以及身穿宽大草莓卫衣与白丝袜的穿着细节，突出了她娇小与肉感并存的反差感。最后她放下手机，仰面靠在地板上稍作休息。",
            "😏 拿起手机，打开前置摄像头，稍微调整一下卫衣",
            "😂 门外突然传来敲门声，伴随着母亲喊你出去帮忙",
            "🥺 觉得地板有点凉，你双手撑着地毯站起身",
            "喵呼——！写完啦写完啦喵！快把草莓味小鱼干交出来吧喵！",
            "(๑•̀ㅂ•́)و✧ 下一步你想怎么走？快选一个，或者自己写回复喵！",
        ).joinToString(NL)
        assertTrue("无分隔线但结构完整的散文面板必须认领", panel.claim(noDivider, meta))
        val out = panel.render(noDivider, assetMeta)!!
        assertTrue(out.contains("```html"))
        assertTrue("正文必须保留", out.contains("犹如珍珠般的哑光"))
        assertTrue("剧情小总结要进模板", out.contains("<summary>"))
        assertTrue("三个选项都要进模板", out.contains("<option>拿起手机") &&
            out.contains("<option>门外突然传来敲门声") && out.contains("<option>觉得地板有点凉"))
        assertFalse("幂等", panel.claim(out, meta))
        // 最贴近用户实测：同一条消息里已有思维链折叠栏 + 输出里根本没有 ----
        // 落盘给真浏览器端到端验证（折叠栏与折叠窗两个 iframe 都要正常渲染）
        runCatching {
            val withFold = listOf(foldCard, "", noDivider).joinToString(NL)
            java.io.File("/workspace/st-lab/plugin-slot/kotlin_fixture_usercase.txt")
                .writeText(panel.render(withFold, assetMeta)!!)
        }
    }

    @Test
    fun dashesInsideExistingFenceDoNotSplitTheCard() {
        // 已有卡片内部也可能出现 ---（CSS / 分隔装饰），不能拿它当面板分界
        val cardWithDash = fenced("", listOf(
            "<!DOCTYPE html><html><head><title>折叠栏</title></head><body>",
            "---",
            "<div>折叠内容</div></body></html>",
        ).joinToString(NL))
        val tail = listOf(
            "本段剧情发生在午后的一间小卧室里。详细描写了神宫雏首次出场的外貌特征，最后她放下手机稍作休息。",
            "😏 拿起手机，打开前置摄像头",
            "😂 门外突然传来敲门声",
            "喵呼——！写完啦写完啦喵！快把草莓味小鱼干交出来吧喵！",
        ).joinToString(NL)
        val rich = listOf(cardWithDash, "", "你仰起头，看着白色的天花板。", "", "---", "", tail).joinToString(NL)
        assertTrue(panel.claim(rich, meta))
        val out = panel.render(rich, assetMeta)!!
        assertTrue("已有卡片必须完整保留（含内部 --- 与闭合标签）",
            out.contains("---") && out.contains("<div>折叠内容</div></body></html>"))
        assertTrue("闭合围栏仍要独占一行", out.contains("</html>" + NL + "```"))
    }

    @Test
    fun panelAdapterPassesWhenNoPanelStructure() {
        val noDivider = "剧情正文。" + NL + "😏 选项一" + NL + "😏 选项二"
        assertFalse("两行符号但没有任何总结/吐槽，不该误认成面板", panel.claim(noDivider, meta))
        assertNull(panel.render(noDivider, assetMeta))
    }
}
