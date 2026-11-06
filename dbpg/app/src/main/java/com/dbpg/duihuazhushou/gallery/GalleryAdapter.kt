package com.dbpg.duihuazhushou.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ImageLoader

/**
 * 图片库网格适配器（3 列）。
 *
 * 渲染很轻：只做「按格子宽度降采样解码 + 塞进 ImageView」，
 * 不做任何文件 IO 之外的处理；解码结果由 ImageLoader 的 LruCache 复用。
 */
class GalleryAdapter(
    private val onClick: (GalleryImage) -> Unit,
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    private val items = ArrayList<GalleryImage>()

    /** 每格解码目标宽度（px）。3 列时约等于屏宽/3，这里给到 360 已足够清晰。 */
    var targetWidthPx: Int = 360

    fun submit(list: List<GalleryImage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false)
        return VH(v as android.widget.FrameLayout, v.findViewById(R.id.gallery_thumb))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.image.setImageDrawable(null)
        ImageLoader.load(item.file.absolutePath, targetWidthPx)?.let { holder.image.setImageBitmap(it) }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class VH(root: android.widget.FrameLayout, val image: SquareImageView) : RecyclerView.ViewHolder(root)
}
