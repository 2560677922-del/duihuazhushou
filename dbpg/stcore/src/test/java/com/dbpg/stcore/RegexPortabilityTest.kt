package com.dbpg.stcore

import com.dbpg.stcore.imagegen.AutoImageSupport
import com.dbpg.stcore.imagegen.PresetBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则的**跨引擎可移植性**。
 *
 * 起因是一个非常难查的线上崩溃：
 *
 * ```
 * 出图出错：Syntax error in regexp pattern near index 23
 * ```
 *
 * 崩在 `AutoImageSupport.renderTemplate` 的
 * `Regex("\\{\\{\\s*contextLabel\\s*}}")` —— 末尾两个 `}` 没转义。
 *
 * 这个写法在**桌面 JVM 上完全合法**（未匹配的 `}` 当普通字符），所以
 * 323 条单测全绿；而 Android 的正则引擎（ART 里那套来自 Harmony 的实现）
 * 直接抛 `PatternSyntaxException`。也就是说：
 *
 * - 单测跑在 JVM 上，**永远抓不到这类问题**；
 * - 只要模板里带 `{{占位符}}`，真机上一次都出不来图。
 *
 * 所以这里不测"能不能匹配"，而是**直接检查模式字符串本身**：不许出现
 * 未转义的 `{` / `}`。这个检查跟引擎无关，JVM 上一样能判。
 *
 * 同类风险还有替换串：`replace(regex, "…")` 会把 `$` 当分组引用，
 * 剧情里出现「$100」就抛 `Illegal group reference`。`renderTemplate`
 * 现在改用 lambda 交字面值，下面两条用例盯着它。
 */
class RegexPortabilityTest {

    // ------------------------------------------------------------ 花括号检查

    /**
     * 找出模式里所有"裸花括号"的下标。
     *
     * 规则和 Android 的严格解析对齐：反斜杠转义的跳过、字符类 `[…]` 内的跳过、
     * `{n}` / `{n,}` / `{n,m}` 这类量词跳过，剩下的 `{` `}` 都算裸的。
     */
    private fun bareBraces(pattern: String): List<Int> {
        val bad = ArrayList<Int>()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '[' -> { inClass = true; i++; continue }
                c == ']' && inClass -> { inClass = false; i++; continue }
                inClass -> { i++; continue }
                c == '{' -> {
                    // 量词：{2} / {2,} / {2,5}
                    val rest = pattern.substring(i)
                    val q = Regex("^\\{\\d*(,\\d*)?}").find(rest)
                    if (q != null) { i += q.value.length; continue }
                    bad.add(i)
                }
                c == '}' -> bad.add(i)
            }
            i++
        }
        return bad
    }

    @Test
    fun `检查函数自己认得裸花括号`() {
        // 自检：如果这个函数写坏了，下面几条"全都没有裸花括号"的断言会变成假绿
        assertTrue(bareBraces("""\{\{\s*char\s*}}""").isNotEmpty())
        assertTrue(bareBraces("""a}b""").isNotEmpty())
        // 转义过的、量词、字符类里的，都不该报
        assertTrue(bareBraces("""\{\{\s*char\s*\}\}""").isEmpty())
        assertTrue(bareBraces("""[\s\S]{0,120}?""").isEmpty())
        assertTrue(bareBraces("""[{]""").isEmpty())
    }

    @Test
    fun `模板占位符正则没有裸花括号`() {
        // 这是线上崩溃的那三条。它们的 pattern 末尾必须是 \}\}
        val list = listOf(
            "contextLabel" to AutoImageSupport.RE_PH_LABEL,
            "contextText" to AutoImageSupport.RE_PH_TEXT,
            "artistTags" to AutoImageSupport.RE_PH_ARTIST,
        )
        list.forEach { (name, re) ->
            val bad = bareBraces(re.pattern)
            assertTrue(
                "占位符 $name 的正则有未转义的花括号（下标 $bad）：${re.pattern}",
                bad.isEmpty(),
            )
            assertTrue(
                "占位符 $name 应当以 \\}\\} 收尾：${re.pattern}",
                re.pattern.endsWith("\\}\\}"),
            )
        }
    }

    @Test
    fun `洗宏正则没有裸花括号`() {
        PresetBlocks.MACRO_PATTERNS.forEach { re ->
            val bad = bareBraces(re.pattern)
            assertTrue("洗宏正则有未转义的花括号（下标 $bad）：${re.pattern}", bad.isEmpty())
        }
    }

    // ------------------------------------------------------------ 替换串

    @Test
    fun `正文里的美元符号与反斜杠原样进 system`() {
        // 直接当 replacement 传的话，$100 会被当成"第 100 个分组"，直接崩
        val ctx = "她盯着账单：「这个月 $100，还差 $50。」路径 C:\\temp\\log"
        val (system, user) = AutoImageSupport.renderTemplate(
            template = "开头\n{{contextText}}\n结尾",
            contextText = ctx,
            contextLabel = "剧情片段",
        )
        assertTrue(system.contains("$100"))
        assertTrue(system.contains("C:\\temp\\log"))
        assertTrue(system.startsWith("开头"))
        assertTrue(system.endsWith("结尾"))
        assertTrue(user.contains("$100"))
    }

    @Test
    fun `占位符带空格也能替换`() {
        val (system, _) = AutoImageSupport.renderTemplate(
            template = "{{ contextText }}|{{ contextLabel }}",
            contextText = "雨夜",
            contextLabel = "场景",
        )
        assertEquals("雨夜|场景", system)
    }

    @Test
    fun `没给画师串时占位符被清空`() {
        val (system, _) = AutoImageSupport.renderTemplate(
            template = "A{{artistTags}}B",
            contextText = "",
            contextLabel = "场景",
            artistTags = "",
        )
        assertEquals("AB", system)
    }

    @Test
    fun `默认模板渲染后不残留占位符`() {
        // 默认模板只把 contextLabel 放 system，正文走 user —— 两段都要检查
        val (system, user) = AutoImageSupport.renderTemplate(
            template = "",
            contextText = "银发少女站在雨里",
            contextLabel = "剧情片段",
        )
        assertFalse(system.contains("{{"))
        assertFalse(system.contains("}}"))
        assertTrue(system.contains("剧情片段"))
        assertTrue(user.contains("银发少女站在雨里"))
    }
}
