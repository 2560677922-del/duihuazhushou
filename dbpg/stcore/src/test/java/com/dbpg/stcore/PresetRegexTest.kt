package com.dbpg.stcore

import com.dbpg.stcore.imagegen.PresetRegex
import com.dbpg.stcore.imagegen.PresetRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设自带正则的执行层。
 *
 * 重点不是"正则能不能跑"，而是**坏规则不能连累好规则、不能把 system 洗没**。
 * v1.7.1 就是在这上面栽的：外部文件里的正则写法千奇百怪，一条编不过
 * 就让整份预设导入失败。
 */
class PresetRegexTest {

    private fun rule(find: String, replace: String = "") =
        PresetRule("t", "测试", find, replace)

    @Test
    fun `按顺序套用多条规则`() {
        val out = PresetRegex.apply(
            "{{char}} 站在 {{char}} 的门口",
            listOf(rule("\\{\\{char\\}\\}", "角色"), rule("门口", "窗前")),
        )
        assertEquals("角色 站在 角色 的窗前", out.text)
        assertEquals(2, out.applied)
        assertFalse(out.rolledBack)
    }

    @Test
    fun `坏正则只丢自己，其余照跑`() {
        val out = PresetRegex.apply(
            "abc {{char}}",
            listOf(
                rule("(", "坏"),   // 括号不闭合，编译不过
                rule("abc", "xyz"),
            ),
        )
        assertEquals("xyz {{char}}", out.text)
        assertEquals(1, out.applied)
        assertEquals(1, out.failed.size)
    }

    @Test
    fun `捕获组可以引用`() {
        val out = PresetRegex.apply("height 1024", listOf(rule("height (\\d+)", "尺寸\$1")))
        assertEquals("尺寸1024", out.text)
    }

    @Test
    fun `斜杠外壳里的 i 标志生效`() {
        // findRegex 在文件里常写成 `/abc/i`，外壳由 JsRegex 解析
        val out = PresetRegex.apply("ABC abc", listOf(rule("/abc/gi", "x")))
        assertEquals("x x", out.text)
    }

    @Test
    fun `把文本洗掉大半时整段回滚`() {
        // 真实场景：某条脚本写成 `(?s).*` + 替换成空，一条就能抹掉整个 system
        val src = "这是一段足够长的提示词，".repeat(20)
        val out = PresetRegex.apply(src, listOf(rule("(?s).*", "")))
        assertTrue(out.rolledBack)
        assertEquals(src, out.text)
    }

    @Test
    fun `没规则时原样返回`() {
        val out = PresetRegex.apply("abc", emptyList())
        assertEquals("abc", out.text)
        assertEquals(0, out.applied)
    }

    @Test
    fun `编译不过的规则 compiles 返回 false 而不是抛`() {
        assertFalse(rule("(").compiles())
        assertTrue(rule("/\\{\\{char\\}\\}/gi").compiles())
    }
}
