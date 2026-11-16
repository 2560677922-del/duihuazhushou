package com.dbpg.stcore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单枚插图标记的移除（「移除占位符」那条链路的纯逻辑）。
 *
 * 这里守的是一件事：**只删该删的那一枚**。多删一个字符、误删别的图、
 * 或者把正文里正常的方括号一起吃进去，在界面上都表现为"AI 少说了一句话"，
 * 极难回溯。
 */
class ImageMarkerStripTest {

    @Test
    fun `删掉唯一的标记，前后正文保留`() {
        val src = "她推开门。\n\n[[IMG: 银发少女站在雨里]]\n\n雨很大。"
        val out = stripImageMarker(src, "银发少女站在雨里")
        assertEquals("她推开门。\n\n雨很大。", out)
    }

    @Test
    fun `同描述多枚时只删指定序号`() {
        val src = "[[IMG: 雨]]\n\n中间一段\n\n[[IMG: 雨]]"
        assertEquals("中间一段\n\n[[IMG: 雨]]", stripImageMarker(src, "雨", 0))
        assertEquals("[[IMG: 雨]]\n\n中间一段", stripImageMarker(src, "雨", 1))
    }

    @Test
    fun `描述对不上就原样返回`() {
        val src = "[[IMG: 晴天]]\n\n正文"
        assertEquals(src, stripImageMarker(src, "雨天"))
    }

    @Test
    fun `没有标记的正文不受影响`() {
        val src = "普通正文，没有插图。"
        assertEquals(src, stripImageMarker(src, "随便"))
    }

    @Test
    fun `全角括号也能认`() {
        val src = "前\n\n［［IMG: 雨夜］］\n\n后"
        assertEquals("前\n\n后", stripImageMarker(src, "雨夜"))
    }

    @Test
    fun `删完不留多余空行`() {
        val src = "一\n\n[[IMG: 图]]\n\n\n\n二"
        val out = stripImageMarker(src, "图")
        assertEquals("一\n\n二", out)
    }

    @Test
    fun `只删目标，别的标记还在`() {
        val src = "[[IMG: A]]\n\n[[IMG: B]]\n\n[[IMG: C]]"
        assertEquals("[[IMG: B]]\n\n[[IMG: C]]", stripImageMarker(src, "A"))
    }

    @Test
    fun `marker 描述里的正则特殊字符不当模式`() {
        // 描述来自模型，可能带括号/点号；必须按字面比较
        val src = "[[IMG: a.b (测试)]]"
        assertEquals("", stripImageMarker(src, "a.b (测试)"))
    }
}
