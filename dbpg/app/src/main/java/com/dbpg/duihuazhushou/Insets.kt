package com.dbpg.duihuazhushou

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/**
 * 状态栏安全区。
 *
 * Android 15 起系统强制"边到边"（targetSdk 35+），Android 16/17 连
 * windowOptOutEdgeToEdgeEnforcement 都不认了 —— 界面会顶到状态栏下面，
 * 全面屏手机上顶栏被时间/信号压住。
 *
 * 主题里已经尽量 opt-out，这里再做一层兜底：给 setContentView 的第一个
 * 子 View 增加状态栏高度的 padding。如果系统没走边到边，inset 是 0，
 * 不会重复加高。
 */
object Insets {

    /** 上下都要避让（聊天页：顶栏避状态栏、输入区避手势条）。 */
    fun applyAll(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val child: View = if (content.childCount > 0) content.getChildAt(0) else return
        val baseTop = child.paddingTop
        val baseBottom = child.paddingBottom
        child.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, baseBottom + bottom)
            insets
        }
        child.requestApplyInsets()
    }

    fun applyTop(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val child: View = if (content.childCount > 0) content.getChildAt(0) else return
        val baseTop = child.paddingTop
        child.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        child.requestApplyInsets()
    }
}
