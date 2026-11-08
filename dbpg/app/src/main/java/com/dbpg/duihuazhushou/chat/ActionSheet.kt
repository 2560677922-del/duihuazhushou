package com.dbpg.duihuazhushou.chat

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.appearance.ThemePalette
import com.dbpg.duihuazhushou.appearance.ThemeTokens
import com.dbpg.duihuazhushou.appearance.blendOver
import com.dbpg.duihuazhushou.appearance.withAlpha

/**
 * 底部操作面板（聊天里所有的「一组操作」都走这里）。
 *
 * ## 为什么不用系统 PopupMenu / AlertDialog
 *
 * 系统那两套的底色、圆角、字号全由系统主题决定，和这个 App 的外观系统完全脱钩：
 * 深色主题 + 自定义背景图下，点「⋯ 操作」会从屏幕中间弹出一张白纸 —— 就是用户说的
 * 「太素了，出戏」。而且系统列表不能带说明文字，选项一多就分不清「编辑这条回复」
 * 和「编辑你的话」。
 *
 * 这里统一成：底部滑入、圆角面板、图标 + 标题 + 一行说明、按压有水波。
 * **所有颜色都来自 [ThemeTokens]**，换主题、换底色、换字体都会跟着变。
 *
 * ## 两种形态
 *
 * - [show]：分组的操作列表（会话菜单、消息操作）
 * - [confirm]：一句话 + 两个按钮（分支、删除这类不可逆/会跳走的操作）
 */
object ActionSheet {

    /** 危险操作（删除）：用固定的红，不跟主题色走 —— 换主题不该把"删除"变成蓝色。 */
    private val DANGER = 0xFFD9534F.toInt()

    data class Item(
        val icon: String,
        val title: String,
        val desc: String = "",
        val danger: Boolean = false,
        val onClick: () -> Unit,
    )

    /** 一组操作。[label] 为空表示不显示组标题（单组场景，比如消息操作）。 */
    data class Section(val label: String = "", val items: List<Item>)

    // ------------------------------------------------------------ 列表形态

    fun show(
        ctx: Context,
        cfg: AppearanceConfig,
        title: String,
        subtitle: String = "",
        sections: List<Section>,
    ) {
        val t = ThemePalette.tokensOf(cfg)
        val dialog = baseDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_action_sheet, null)
        dialog.setContentView(view)
        val tf = ThemePalette.fontTypeface(cfg.fontFamily)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        view.setBackground(round(ctx, panelOf(t), 24f))
        view.setPadding(0, dp(10f), 0, dp(16f))

        view.findViewById<View>(R.id.sheet_handle).setBackground(
            round(ctx, withAlpha(t.textFaint, 0.45f), 2f),
        )

        view.findViewById<TextView>(R.id.sheet_title).apply {
            text = title
            setTypeface(tf)
            setTextColor(t.text)
        }
        view.findViewById<TextView>(R.id.sheet_subtitle).apply {
            if (subtitle.isBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = subtitle
                setTypeface(tf)
                setTextColor(t.textDim)
            }
        }

        val box = view.findViewById<LinearLayout>(R.id.sheet_container)
        for (section in sections) {
            if (section.label.isNotBlank()) {
                box.addView(
                    TextView(ctx).apply {
                        text = section.label
                        textSize = 11f
                        setTypeface(tf)
                        setTextColor(t.textFaint)
                        setPadding(dp(12f), dp(12f), 0, dp(4f))
                    },
                )
            }
            for (item in section.items) box.addView(row(ctx, t, tf, item, dialog))
        }

        view.findViewById<TextView>(R.id.sheet_cancel).apply {
            text = "取消"
            setTypeface(tf)
            setTextColor(t.textDim)
            background = ripple(ctx, round(ctx, t.panelSoft, 16f), withAlpha(t.text, 0.10f), 16f)
            setOnClickListener { dialog.dismiss() }
        }

        // 长菜单（主菜单十几条）不能把取消按钮顶出屏幕
        val scroll = view.findViewById<ScrollView>(R.id.sheet_scroll)
        view.post {
            val max = (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
            if (scroll.height > max) {
                scroll.layoutParams = scroll.layoutParams.also { it.height = max }
            }
        }

        present(dialog, ctx)
    }

    // ------------------------------------------------------------ 确认形态

    fun confirm(
        ctx: Context,
        cfg: AppearanceConfig,
        title: String,
        message: String,
        okText: String = "确定",
        cancelText: String = "取消",
        danger: Boolean = false,
        onOk: () -> Unit,
    ) {
        val t = ThemePalette.tokensOf(cfg)
        val dialog = baseDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_confirm_sheet, null)
        dialog.setContentView(view)
        val tf = ThemePalette.fontTypeface(cfg.fontFamily)

        view.setBackground(round(ctx, panelOf(t), 24f))
        view.findViewById<View>(R.id.confirm_handle).setBackground(
            round(ctx, withAlpha(t.textFaint, 0.45f), 2f),
        )
        view.findViewById<TextView>(R.id.confirm_title).apply {
            text = title
            setTypeface(tf)
            setTextColor(t.text)
        }
        view.findViewById<TextView>(R.id.confirm_message).apply {
            text = message
            setTypeface(tf)
            setTextColor(t.textDim)
        }
        val accent = if (danger) DANGER else t.accent
        view.findViewById<TextView>(R.id.confirm_cancel).apply {
            text = cancelText
            setTypeface(tf)
            setTextColor(t.textDim)
            background = ripple(ctx, round(ctx, t.panelSoft, 16f), withAlpha(t.text, 0.10f), 16f)
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.confirm_ok).apply {
            text = okText
            setTypeface(tf)
            setTextColor(t.onAccent)
            background = ripple(ctx, round(ctx, accent, 16f), withAlpha(Color.WHITE, 0.18f), 16f)
            setOnClickListener {
                dialog.dismiss()
                onOk()
            }
        }
        present(dialog, ctx)
    }

    // ------------------------------------------------------------ 内部

    private fun row(
        ctx: Context,
        t: ThemeTokens,
        tf: android.graphics.Typeface,
        item: Item,
        dialog: Dialog,
    ): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_action_row, null, false)
        view.background = ripple(ctx, round(ctx, Color.TRANSPARENT, 16f), withAlpha(t.text, 0.08f), 16f)

        view.findViewById<TextView>(R.id.row_icon).apply {
            text = item.icon
            background = round(ctx, t.accentSoft, 14f)
        }
        view.findViewById<TextView>(R.id.row_title).apply {
            text = item.title
            setTypeface(tf)
            setTextColor(if (item.danger) DANGER else t.text)
        }
        view.findViewById<TextView>(R.id.row_desc).apply {
            if (item.desc.isBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = item.desc
                setTypeface(tf)
                setTextColor(t.textDim)
            }
        }
        view.setOnClickListener {
            dialog.dismiss()
            item.onClick()
        }
        // 行高下限：只有标题时也不能扁成一条缝
        view.minimumHeight = dp(56f)
        return view
    }

    /** 面板不透明化：直接叠在聊天背景上时，半透明会让文字发灰、边缘发脏。 */
    private fun panelOf(t: ThemeTokens): Int = blendOver(t.panel, t.base)

    private fun baseDialog(ctx: Context): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        return d
    }

    private fun present(dialog: Dialog, ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val width = minOf((dm.widthPixels * 0.94f).toInt(), (520 * dm.density).toInt())
        /*
         * 动画与遮罩必须在 show() 之前设：
         * windowAnimations 只在窗口首次 attach 时读取，放在 onShow 里设会错过首帧，
         * 表现是"第一次弹出没有动画、第二次才有"这种诡异现象。
         * 宽度则两个时机都设 —— 部分 ROM 会忽略 show 之前的 setLayout。
         */
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply {
                dimAmount = 0.45f
                windowAnimations = R.style.SheetAnim
            }
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun round(ctx: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            setColor(color)
        }

    /**
     * 按压水波。
     *
     * 第三个参数（mask）必须给：content 是透明圆角，不指定 mask 的话
     * 系统按 content 的 alpha 算水波范围，结果是"点了没有任何反馈"。
     */
    private fun ripple(ctx: Context, content: Drawable, color: Int, radiusDp: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color), content, round(ctx, Color.WHITE, radiusDp))
}
