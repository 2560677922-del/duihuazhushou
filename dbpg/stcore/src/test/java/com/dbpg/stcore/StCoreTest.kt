package com.dbpg.stcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariablesTest {
    @Test
    fun `readVariables 只读展开 getvar hasvar`() {
        val t = VariableTables(session = mutableMapOf("hp" to "80"))
        assertEquals("血量80", readVariables("血量{{getvar::hp}}", t))
        assertEquals("存在true", readVariables("存在{{hasvar::hp}}", t))
        assertEquals("缺失false", readVariables("缺失{{hasvar::mp}}", t))
    }

    @Test
    fun `readVariables 写宏在只读路径被抹空`() {
        val t = VariableTables()
        assertEquals("设完", readVariables("{{setvar::x::1}}设完", t))
        assertEquals("{}", t.session.toString())
    }

    @Test
    fun `applyVariableWrites 执行 setvar addvar incvar`() {
        val t = VariableTables(session = mutableMapOf("lv" to "5"))
        val (out, nt) = applyVariableWrites("{{addvar::lv::3}}->{{getvar::lv}}", t)
        assertEquals("->8", out)
        assertEquals("8", nt.session["lv"])
    }

    @Test
    fun `变量宏不做嵌套解析`() {
        // {{getvar::{{char}}}} 这种嵌套：参数含花括号整条不匹配，原样保留
        val t = VariableTables(session = mutableMapOf("char" to "x"))
        assertTrue(readVariables("{{getvar::{{char}}}}", t).contains("{{getvar"))
    }
}

class MacroTest {
    @Test
    fun `applyMacros 已知替换 未知清空`() {
        val ctx = MacroContext(char = "Hero", user = "Me")
        assertEquals("你是Hero", applyMacros("你是{{char}}", ctx))
        // web macro.ts：未知宏清空成空串（不原样保留，避免把 {{random}} 之类带进提示词）
        assertEquals("", applyMacros("{{unknown}}", ctx))
    }

    @Test
    fun `random 从候选里取 不残留花括号`() {
        val ctx = MacroContext()
        val out = applyMacros("选项：{{random::红,绿,蓝}}", ctx)
        assertTrue(out.startsWith("选项："))
        assertTrue(out in listOf("选项：红", "选项：绿", "选项：蓝"))
        assertFalse(out.contains("{{"))
    }

    @Test
    fun `pick 同文稳定 不同文可不同`() {
        val ctx = MacroContext()
        val a = applyMacros("{{pick::甲,乙,丙}}{{pick::甲,乙,丙}}", ctx)
        val b = applyMacros("{{pick::甲,乙,丙}}{{pick::甲,乙,丙}}", ctx)
        // 同一段文本重复渲染（滚动进出屏幕）不能换值
        assertEquals(a, b)
        assertFalse(a.contains("{{"))
    }

    @Test
    fun `roll 支持骰点与修正`() {
        val ctx = MacroContext()
        val out = applyMacros("命中 {{roll::1d1+5}}", ctx)
        assertEquals("命中 6", out)
    }

    @Test
    fun `注释宏整段删除 trim 吃掉换行`() {
        val ctx = MacroContext()
        assertEquals("前后", applyMacros("前{{// 作者备注 }}后", ctx))
        assertEquals("甲乙", applyMacros("甲{{trim}}\n乙", ctx))
    }

    @Test
    fun `时间与换行宏`() {
        val ctx = MacroContext()
        val out = applyMacros("{{date}} {{time}}", ctx)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(out))
        assertEquals("A\nB", applyMacros("A{{newline}}B", ctx))
    }
}

class ReasoningTest {
    @Test
    fun `成对标记整段剥离`() {
        val r = stripReasoning("前言<thinking>草稿</thinking>正文")
        assertEquals("前言正文", r.text)
        assertTrue(r.stripped)
        assertTrue(r.reasoning.contains("草稿"))
    }

    @Test
    fun `未闭合标记从起点剥到结尾`() {
        val r = stripReasoning("前言<thinking>没闭合正文")
        assertEquals("前言", r.text)
    }

    @Test
    fun `通用分界标记`() {
        val r = stripReasoning("分析过程\n正文：\n实际内容")
        assertEquals("实际内容", r.text.trim())
    }
}

class RegexEngineTest {
    private fun script(
        find: String, replace: String, placement: List<Int> = listOf(REGEX_PLACEMENT.AI_OUTPUT),
        disabled: Boolean = false, trimStrings: List<String>? = null,
        markdownOnly: Boolean = false, promptOnly: Boolean = false,
        sub: Int = SUBSTITUTE_REGEX.NONE, minDepth: Int? = null, maxDepth: Int? = null,
    ) = RawScript(
        id = null, scriptName = null, name = null, findRegex = find, replaceString = replace,
        placement = placement, disabled = disabled, trimStrings = trimStrings,
        markdownOnly = markdownOnly, promptOnly = promptOnly, substituteRegex = sub,
        minDepth = minDepth, maxDepth = maxDepth,
    )

    @Test
    fun `$0 与 match 等价 命名分组 全局替换`() {
        val s1 = script("/(\\w+)=(\\d+)/g", "<$0>")
        val s2 = script("/(\\w+)=(\\d+)/g", "<{{match}}>")
        val msg = listOf(ChatMessage(Role.ASSISTANT, "a=1 b=2"))
        val (o1) = runRegex(msg, listOf(s1))
        val (o2) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "a=1 b=2")), listOf(s2))
        assertEquals("<a=1> <b=2>", o1[0].content)
        assertEquals("<a=1> <b=2>", o2[0].content)
    }

    @Test
    fun `trimStrings 按分组内容去字`() {
        val s = script("id=(\\d+)", "$1", trimStrings = listOf("1"))
        val (o) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "x id=123 y")), listOf(s))
        assertEquals("x 23 y", o[0].content)
    }

    @Test
    fun `双 true 脚本 显示跳过 提示词执行`() {
        // Meowssiah [12]：删 <options>。显示侧必须留着给面板正则，提示词侧才删。
        val s = script("/(<options>[\\s\\S]*?<\\/options>)/g", "",
            markdownOnly = true, promptOnly = true)
        val raw = "前<options>A</options>后"

        val (display, _) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, raw)), listOf(s),
            RegexEngineOptions(forDisplay = true),
        )
        assertEquals("前<options>A</options>后", display[0].content)

        val (prompt, _) = runRegex(listOf(ChatMessage(Role.ASSISTANT, raw)), listOf(s))
        assertEquals("前后", prompt[0].content)
    }

    @Test
    fun `纯显示脚本不进提示词`() {
        val s = script("/A/", "B", markdownOnly = true, promptOnly = false)
        val (prompt, _) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "A")), listOf(s))
        assertEquals("A", prompt[0].content)
    }

    @Test
    fun `替换串宏展开`() {
        val s = script("name", "{{char}}")
        val (o) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, "name")), listOf(s),
            RegexEngineOptions(macroContext = MacroContext(char = "Hero")),
        )
        assertEquals("Hero", o[0].content)
    }

    @Test
    fun `substituteRegex RAW 把宏塞进 find`() {
        val s = script("/{{char}}/g", "X", sub = SUBSTITUTE_REGEX.RAW)
        val (o) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, "Hero")), listOf(s),
            RegexEngineOptions(macroContext = MacroContext(char = "Hero")),
        )
        assertEquals("X", o[0].content)
    }

    @Test
    fun `substituteRegex ESCAPED 转义宏值`() {
        val s = script("/{{char}}\\d/g", "Y", sub = SUBSTITUTE_REGEX.ESCAPED)
        val (o) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, "A.B1")), listOf(s),
            RegexEngineOptions(macroContext = MacroContext(char = "A.B")),
        )
        assertEquals("Y", o[0].content)
    }

    @Test
    fun `placement 仅 AI_OUTPUT 不碰 user`() {
        val s = script("x", "Z")
        val msgs = listOf(
            ChatMessage(Role.USER, "x"),
            ChatMessage(Role.ASSISTANT, "x"),
        )
        val (o) = runRegex(msgs, listOf(s))
        assertEquals("x", o[0].content)      // user 不动
        assertEquals("Z", o[1].content)      // assistant 改
    }

    @Test
    fun `forDisplay 跳过仅 promptOnly 脚本`() {
        val s = script("x", "Z", promptOnly = true)
        val (o) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, "x")), listOf(s),
            RegexEngineOptions(forDisplay = true),
        )
        assertEquals("x", o[0].content) // 被跳过
    }

    @Test
    fun `非全局只换第一处 全局换全部`() {
        val first = script("/a/", "A")      // 无 g → 只换第一处
        val all = script("/a/g", "A")       // 有 g → 换全部
        val (o1) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "banana")), listOf(first))
        val (o2) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "banana")), listOf(all))
        assertEquals("bAnana", o1[0].content)
        assertEquals("bAnAnA", o2[0].content)
    }

    @Test
    fun `深度范围 minDepth`() {
        val s = script("x", "Z", minDepth = 1)
        // depthOverride=0 → 跳; depthOverride=2 → 命中
        // 注意：runRegex 非 dry 会原地改 m.content，两次调用必须用各自独立的消息对象，
        // 否则后一次命中会顺手把前一次"跳过"的结果也改成 Z（和 web 行为一致：每次传新列表）。
        val (skip) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "x")), listOf(s), RegexEngineOptions(depthOverride = 0))
        val (hit) = runRegex(listOf(ChatMessage(Role.ASSISTANT, "x")), listOf(s), RegexEngineOptions(depthOverride = 2))
        assertEquals("x", skip[0].content)
        assertEquals("Z", hit[0].content)
    }
}

class ImageMarkerTest {
    @Test
    fun `stripImageMarkers 去掉标记`() {
        val out = stripImageMarkers("hello [[IMG: cat]] world")
        assertFalse(out.contains("[[IMG"))
    }

    @Test
    fun `parseImageMarkers 计算段落序号`() {
        val markers = parseImageMarkers("para1\n\n[[IMG: cat]]")
        assertEquals(1, markers.size)
        assertEquals("cat", markers[0].description)
        assertEquals(0, markers[0].markdownIndex)
    }

    @Test
    fun `buildContentBlocks 文本与图片交替 合并相邻文本`() {
        val img = NativeImage(id = "i1", dataUrl = "data:image/png;base64,AAA", description = "cat")
        val blocks = buildContentBlocks("hello\n\n[[IMG: cat]]", listOf(img))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Text)
        assertTrue(blocks[1] is ContentBlock.Image)
        assertEquals("hello", (blocks[0] as ContentBlock.Text).text.trim())
    }

    @Test
    fun `buildContentBlocks 合并相邻文本块不被切碎`() {
        val blocks = buildContentBlocks("a\n\nb")
        assertEquals(1, blocks.size)
        assertEquals("a\n\nb", (blocks[0] as ContentBlock.Text).text)
    }
}

class TransformForDisplayTest {

    @Test
    fun `transformForDisplay 一站式：剥思维链加正则加变量`() {
        val scripts = listOf(
            RawScript(
                id = null, scriptName = null, name = null, findRegex = "bad", replaceString = "",
                placement = listOf(REGEX_PLACEMENT.AI_OUTPUT), disabled = false, trimStrings = null,
                markdownOnly = false, promptOnly = false, substituteRegex = SUBSTITUTE_REGEX.NONE,
                minDepth = null, maxDepth = null,
            ),
        )
        val vars = VariableTables(session = mutableMapOf("hp" to "50"))
        val blocks = transformForDisplay(
            "<thinking>草稿</thinking>生命{{getvar::hp}}", emptyList(), scripts, vars,
            MacroContext(), hideReasoning = true, reasoningCutMarker = null,
        )
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        assertTrue(text.contains("生命50"))
        assertFalse(text.contains("草稿"))
    }

    @Test
    fun `显示侧展开宏 未知宏保留`() {
        val blocks = transformForDisplay(
            "{{char}}：{{random::甲,乙}} / {{myCustomMacro}}", emptyList(), emptyList(), null,
            MacroContext(char = "小猫"), hideReasoning = false, reasoningCutMarker = null,
        )
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        assertTrue(text.startsWith("小猫："))
        assertTrue(text.contains("甲") || text.contains("乙"))
        // 不认识的宏不能凭空消失
        assertTrue(text.contains("{{myCustomMacro}}"))
    }
}
