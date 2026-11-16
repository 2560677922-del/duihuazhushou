package com.dbpg.duihuazhushou.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ImageLoader

/**
 * 图片库网格适配器（3 列）。
 *
 * 渲染很轻：只做「按格子宽度降采样解码 + 塞进 ImageView」，
 * 不做任何文件 IO 之外的处理；解码结果由 ImageLoader 的 LruCache 复用。
 *
 * ## 多选
 *
 * 长按任意一格进入多选（[setSelecting]），此后单击是"勾选"而不是"打开"。
 * 选中集合按文件绝对路径存：同一张图在两个目录里出现也只是两条记录，
 * 用路径比用下标稳（排序会变）。
 */
class GalleryAdapter(
    private val onClick: (GalleryImage) -> Unit,
    /** 长按：进入多选模式并选中当前这张 */
    private val onLongClick: (GalleryImage) -> Unit = {},
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    private val items = ArrayList<GalleryImage>()

    /** 每格解码目标宽度（px）。3 列时约等于屏宽/3，这里给到 360 已足够清晰。 */
    var targetWidthPx: Int = 360

    /** 是否处于多选模式（由 Activity 控制，改动后自己 notify）。 */
    var selecting: Boolean = false
        private set

    private val selected = LinkedHashSet<String>()

    fun submit(list: List<GalleryImage>) {
        items.clear()
        items.addAll(list)
        // 删掉的图不该留在选中集合里
        val alive = list.map { it.file.absolutePath }.toSet()
        selected.retainAll(alive)
        notifyDataSetChanged()
    }

    fun setSelecting(on: Boolean) {
        if (selecting == on) return
        selecting = on
        if (!on) selected.clear()
        notifyDataSetChanged()
    }

    fun isSelected(item: GalleryImage): Boolean = selected.contains(item.file.absolutePath)

    fun selectionCount(): Int = selected.size

    /** 返回切换后的选中状态。 */
    fun toggle(item: GalleryImage): Boolean {
        val key = item.file.absolutePath
        return if (selected.contains(key)) {
            selected.remove(key)
            false
        } else {
            selected.add(key)
            true
        }
    }

    fun selectedItems(): List<GalleryImage> =
        items.filter { selected.contains(it.file.absolutePath) }

    fun allSelected(): Boolean = items.isNotEmpty() && selected.size == items.size

    fun selectAll(on: Boolean) {
        selected.clear()
        if (on) items.forEach { selected.add(it.file.absolutePath) }
        notifyDataSetChanged()
    }

    /** 只刷新勾选状态，不重新解码缩略图（多选时点得很快）。 */
    fun refreshChecks() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false)
        return VH(
            v as android.widget.FrameLayout,
            v.findViewById(R.id.gallery_thumb),
            v.findViewById(R.id.gallery_check),
            v.findViewById(R.id.gallery_mask),
        )
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, items[position], full = true)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECK)) bind(holder, items[position], full = false)
        else super.onBindViewHolder(holder, position, payloads)
    }

    private fun bind(holder: VH, item: GalleryImage, full: Boolean) {
        if (full) {
            holder.image.setImageDrawable(null)
            ImageLoader.load(item.file.absolutePath, targetWidthPx)?.let { holder.image.setImageBitmap(it) }
        }
        val on = selected.contains(item.file.absolutePath)
        if (selecting) {
            holder.check.visibility = View.VISIBLE
            holder.check.text = if (on) "✓" else ""
            holder.check.setBackgroundResource(if (on) R.drawable.bg_check_on else R.drawable.bg_check_off)
            holder.mask.visibility = if (on) View.GONE else View.VISIBLE
        } else {
            holder.check.visibility = View.GONE
            holder.mask.visibility = View.GONE
        }
        holder.itemView.setOnClickListener {
            if (selecting) {
                toggle(item)
                refreshChecks()
            } else {
                onClick(item)
            }
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class VH(
        root: android.widget.FrameLayout,
        val image: SquareImageView,
        val check: TextView,
        val mask: View,
    ) : RecyclerView.ViewHolder(root)

    private companion object {
        const val PAYLOAD_CHECK = "check"
    }
}
