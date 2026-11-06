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
import com.dbpg.duihuazhushou.richtext.RichTextRenderer
import com.dbpg.stcore.richtext.RichTextParser

/**
 * 聊天列表适配器（RecyclerView）。
 *
 * 只做渲染：文本 → RichTextRenderer（Spannable），图片 → 本地路径解码，气泡 → 对齐+配色。
 * 不碰网络、不碰会话逻辑，数据全部来自 [ChatItem]。
 *
 * 顺滑三要点：
 *   1. ListAdapter + DiffUtil：只更新变化的条目，流式刷新不整列表重绘
 *   2. ViewHolder 复用：气泡背景每次重建（避免共享 Drawable 被染色）
 *   3. 文本宽度用 TextView.maxWidth 封顶（LinearLayout 的 maxWidth 不生效）
 */
class ChatAdapter(private val bubbleMaxWidthPx: Int) : ListAdapter<ChatItem, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem) = oldItem == newItem
        }
        private const val COLOR_USER = 0xFF2A4B7C.toInt()
        private const val COLOR_AI = 0xFF232A3A.toInt()
        private const val COLOR_SYS = 0xFF2E3440.toInt()
    }

    class VH(root: FrameLayout, val bubble: LinearLayout, val text: TextView, val images: LinearLayout) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return VH(
            v as FrameLayout,
            v.findViewById(R.id.bubble),
            v.findViewById(R.id.msg_text),
            v.findViewById(R.id.msg_images),
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

        // 2) 文本：maxWidth 封顶 + Spannable 渲染
        holder.text.maxWidth = bubbleMaxWidthPx
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
                holder.images.addView(iv)
            }
        }
    }

    private fun roundedBackground(color: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * density
            setColor(color)
        }
}
