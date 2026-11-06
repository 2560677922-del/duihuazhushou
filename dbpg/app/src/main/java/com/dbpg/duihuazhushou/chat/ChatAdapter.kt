package com.dbpg.duihuazhushou.chat

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.richtext.RichTextRenderer
import com.dbpg.stcore.richtext.RichTextParser

/**
 * 聊天列表适配器（RecyclerView）。
 *
 * 只做渲染 + 把点击回调出去：文本 → RichTextRenderer（Spannable），
 * 图片 → 本地路径解码，版本条/操作入口 → 回调给 Activity。
 * 不碰网络、不碰会话逻辑，数据全部来自 [ChatItem]。
 *
 * 顺滑三要点：
 *   1. ListAdapter + DiffUtil：只更新变化的条目，流式刷新不整列表重绘
 *   2. ViewHolder 复用：气泡背景每次重建（避免共享 Drawable 被染色）
 *   3. 文本宽度用 TextView.maxWidth 封顶（LinearLayout 的 maxWidth 不生效）
 */
class ChatAdapter(
    private var config: AppearanceConfig,
    private val screenWidthPx: Int,
    private val listener: Listener,
) : ListAdapter<ChatItem, ChatAdapter.VH>(DIFF) {

    /** 一张/一组配图被点击 */
    interface Listener {
        fun onImageClick(item: ChatItem, path: String)
        fun onVariantSwitch(item: ChatItem, delta: Int)
        fun onActionMenu(item: ChatItem, anchor: View)
    }

    /** 气泡最大宽度：按屏宽百分比算，改设置不用重建 Adapter。 */
    private val bubbleMaxWidthPx: Int
        get() = (screenWidthPx * config.bubbleWidthPercent / 100).coerceAtLeast(screenWidthPx / 3)

    /** 从设置页回来时调用：立刻套用新的字号/行距/宽度。 */
    fun updateConfig(cfg: AppearanceConfig) {
        config = cfg
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem == newItem
        }
        private const val COLOR_USER = 0xFF2A4B7C.toInt()
        private const val COLOR_AI = 0xFF232A3A.toInt()
        private const val COLOR_SYS = 0xFF2E3440.toInt()
        private const val COLOR_DIM = 0xFF7F8DA6.toInt()
    }

    class VH(
        root: FrameLayout,
        val bubble: LinearLayout,
        val text: TextView,
        val images: LinearLayout,
        val meta: LinearLayout,
        val verOlder: TextView,
        val verNo: TextView,
        val verNewer: TextView,
        val more: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return VH(
            v as FrameLayout,
            v.findViewById(R.id.bubble),
            v.findViewById(R.id.msg_text),
            v.findViewById(R.id.msg_images),
            v.findViewById(R.id.msg_meta),
            v.findViewById(R.id.msg_ver_older),
            v.findViewById(R.id.msg_ver_no),
            v.findViewById(R.id.msg_ver_newer),
            v.findViewById(R.id.msg_more),
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, getItem(position))
    }

    private fun bind(holder: VH, item: ChatItem) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val isUser = item.role == "user"
        val isSystem = item.role == "system"

        // 1) 对齐 + 气泡配色
        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when {
            isUser -> Gravity.END
            isSystem -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        holder.bubble.layoutParams = lp
        holder.bubble.background = roundedBackground(
            when {
                isUser -> COLOR_USER
                isSystem -> COLOR_SYS
                else -> COLOR_AI
            },
            density,
        )

        // 2) 文本：maxWidth 封顶 + Spannable 渲染（字号/行距跟随外观设置）
        holder.text.maxWidth = bubbleMaxWidthPx
        holder.text.textSize = config.textSizeSp
        holder.text.setLineSpacing(0f, config.lineSpacing)
        val content = if (item.streaming) item.content + "▍" else item.content
        if (content.isBlank()) {
            holder.text.visibility = View.GONE
        } else {
            holder.text.visibility = View.VISIBLE
            RichTextRenderer.applyTo(holder.text, RichTextParser.parse(content))
        }

        // 3) 图片：复用前先清空，再按本地路径加载（LruCache 命中秒开）
        holder.images.removeAllViews()
        if (item.imagePaths.isEmpty()) {
            holder.images.visibility = View.GONE
        } else {
            holder.images.visibility = View.VISIBLE
            for (path in item.imagePaths) {
                val iv = ImageView(ctx)
                val lpp = LinearLayout.LayoutParams(bubbleMaxWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                lpp.topMargin = (4 * density).toInt()
                iv.layoutParams = lpp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.adjustViewBounds = true
                ImageLoader.load(path, bubbleMaxWidthPx)?.let { iv.setImageBitmap(it) }
                // 点图放大：以前这里没有监听，图片只能"看着"，点不动
                iv.setOnClickListener { listener.onImageClick(item, path) }
                holder.images.addView(iv)
            }
        }

        // 4) 版本条 / 操作入口
        val hasVariants = !isUser && item.variantCount > 1
        val canOperate = item.actionId.isNotEmpty() && !isSystem
        if (!hasVariants && !canOperate) {
            holder.meta.visibility = View.GONE
        } else {
            holder.meta.visibility = View.VISIBLE
            holder.verOlder.visibility = if (hasVariants) View.VISIBLE else View.GONE
            holder.verNewer.visibility = if (hasVariants) View.VISIBLE else View.GONE
            holder.verNo.visibility = if (hasVariants) View.VISIBLE else View.GONE
            if (hasVariants) {
                // 显示成「第 n / 共 m 版」，1 是最新
                holder.verNo.text = "${item.variantIndex + 1}/${item.variantCount}"
                holder.verOlder.alpha = if (item.variantIndex < item.variantCount - 1) 1f else 0.3f
                holder.verNewer.alpha = if (item.variantIndex > 0) 1f else 0.3f
                holder.verOlder.setOnClickListener {
                    if (item.variantIndex < item.variantCount - 1) listener.onVariantSwitch(item, +1)
                }
                holder.verNewer.setOnClickListener {
                    if (item.variantIndex > 0) listener.onVariantSwitch(item, -1)
                }
            }
            holder.more.visibility = if (canOperate) View.VISIBLE else View.GONE
            holder.more.setTextColor(COLOR_DIM)
            holder.more.setOnClickListener { listener.onActionMenu(item, it) }
        }
    }

    private fun roundedBackground(color: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * density
            setColor(color)
        }
}
