package com.dbpg.duihuazhushou.gallery

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/**
 * 正方形 ImageView：宽度由网格决定，高度跟随宽度。
 *
 * 网格里如果直接写死高度，不同屏宽会拉伸变形；用 onMeasure 强制 1:1 最省事，
 * 也不用引入 ConstraintLayout。
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
