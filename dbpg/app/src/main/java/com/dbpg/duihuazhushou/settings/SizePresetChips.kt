package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.stcore.imagegen.ImageEngineSupport

/**
 * 生图「图片大小」快捷档位的共用渲染器。
 *
 * 三个页面都要这一排档位（标签生图 / 对话生图设置 / 生图风格），
 * 最早各写一份横向滚动 chip，结果 1216×832 这类靠后的档位要滑好几下
 * 才看得见。这里改成**三列网格一次性全铺开**，9 个小图档位三行看全，
 * 三个页面共用同一份选中态与点击语义。
 *
 * 只列小图（全是 8 的倍数）：省额度、出图快，大图交给专业工具。
 * 容器必须是 `orientation=vertical` 的 LinearLayout，每行由本对象动态生成。
 */
object SizePresetChips {

    /** 每行几个。360dp 宽的手机屏放 3 个刚好，不换行也不挤。 */
    private const val PER_ROW = 3

    /**
     * @param box     垂直 LinearLayout 容器
     * @param curW/curH 当前尺寸，用来高亮
     * @param onPick  点选回调，调用方负责落盘并刷新
     */
    fun render(
        activity: Activity,
        box: LinearLayout,
        curW: Int,
        curH: Int,
        onPick: (w: Int, h: Int) -> Unit,
    ) {
        box.removeAllViews()
        val dp: (Int) -> Int = { Math.round(it * activity.resources.displayMetrics.density) }
        var row: LinearLayout? = null
        for ((i, preset) in ImageEngineSupport.SIZE_PRESETS.withIndex()) {
            val (label, w, h) = preset
            if (i % PER_ROW == 0) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                box.addView(row)
            }
            val active = w == curW && h == curH
            row?.addView(TextView(activity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF5A6B85.toInt())
                background = activity.getDrawable(
                    if (active) R.drawable.bg_chip_active else R.drawable.bg_btn_soft,
                )
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                    bottomMargin = dp(6)
                }
                setOnClickListener { onPick(w, h) }
            })
        }
    }
}
