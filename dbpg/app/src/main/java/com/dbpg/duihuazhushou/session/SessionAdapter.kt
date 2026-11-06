package com.dbpg.duihuazhushou.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dbpg.duihuazhushou.R

/**
 * 会话列表适配器。
 *
 * 只做展示与点击回调；数据来自 [SessionSummary]（网页摘要），
 * 不持有 messages，也不会因为会话变长而变重。
 */
class SessionAdapter(
    private val onClick: (SessionSummary) -> Unit,
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private val items = ArrayList<SessionSummary>()

    fun submit(list: List<SessionSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.displayTitle
        holder.subtitle.text = subtitleOf(item)
        holder.current.visibility = if (item.current) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private fun subtitleOf(item: SessionSummary): String {
        val count = if (item.messageCount > 0) "${item.messageCount} 条消息" else "空会话"
        val time = relativeTime(item.updatedAt)
        return if (time.isBlank()) count else "$count · $time"
    }

    /** 粗略相对时间，够列表用；不引 DateTimeFormatter，minSdk 24 也不用脱糖。 */
    private fun relativeTime(ts: Long): String {
        if (ts <= 0L) return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
                String.format(
                    "%d-%02d-%02d",
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                )
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.session_title)
        val subtitle: TextView = v.findViewById(R.id.session_subtitle)
        val current: TextView = v.findViewById(R.id.session_current)
    }
}
