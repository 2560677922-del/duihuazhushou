package com.dbpg.duihuazhushou.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

/**
 * 角色卡列表适配器。
 *
 * 只负责"把 CharacterFile 画成一屏"，点击回给 Activity 处理。
 * 头像从内存字节解码后做二次降采样，避免 2K 立绘把列表顶爆。
 */
class CharacterAdapter(
    private val onClick: (CharacterFile) -> Unit,
) : RecyclerView.Adapter<CharacterAdapter.Holder>() {

    private val items = ArrayList<CharacterFile>()

    fun submit(list: List<CharacterFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_character, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName
        val card = item.card
        holder.subtitle.text = card?.subtitle.orEmpty()
        holder.subtitle.visibility = if (holder.subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val tags = card?.tags.orEmpty().take(3)
        holder.tags.text = tags.joinToString("  ") { "#$it" }
        holder.tags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE

        // 解析失败的卡仍然展示（名字用文件名），标一个醒目的提示，方便用户删掉
        holder.badge.visibility = if (card == null) View.VISIBLE else View.GONE

        val avatar = card?.avatarBytes?.let { decodeAvatar(it) }
        if (avatar != null) {
            holder.avatar.setImageBitmap(avatar)
        } else {
            holder.avatar.setImageResource(R.drawable.ic_avatar_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    /** 两步降采样：先读尺寸，再按 128px 目标解码，长图也不会 OOM。 */
    private fun decodeAvatar(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 128) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.char_avatar)
        val name: TextView = v.findViewById(R.id.char_name)
        val subtitle: TextView = v.findViewById(R.id.char_subtitle)
        val tags: TextView = v.findViewById(R.id.char_tags)
        val badge: TextView = v.findViewById(R.id.char_badge)
    }
}
