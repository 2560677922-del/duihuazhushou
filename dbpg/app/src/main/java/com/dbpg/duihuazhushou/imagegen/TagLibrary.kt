package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import org.json.JSONArray

/**
 * 原生标签库：直接读 APK 里随包发布的 novelai-tags.json
 * （assets/www/tags/，与网页版同一份数据文件），**不走引擎桥**。
 *
 * 数据是一次性全量载入内存的紧凑数组（几万条、几百 KB），
 * 搜索就是线性过滤，手机上毫秒级，没有必要建索引。
 */
object TagLibrary {

    data class Tag(val tag: String, val zh: String, val group: String)

    private var tags: List<Tag> = emptyList()

    /** 启动时调一次；读失败不抛（标签搜索是锦上添花，不能因为它挡生图）。 */
    fun load(context: Context) {
        if (tags.isNotEmpty()) return
        try {
            context.assets.open("www/tags/novelai-tags.json").bufferedReader().use { r ->
                val arr = JSONArray(r.readText())
                val out = ArrayList<Tag>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Tag(
                            tag = o.optString("tag"),
                            zh = o.optString("zh"),
                            group = o.optString("group"),
                        ),
                    )
                }
                tags = out
            }
        } catch (_: Throwable) {
            tags = emptyList()
        }
    }

    fun size(): Int = tags.size

    /** 关键词过滤：命中 tag / 中文 / 分组；空串返回前面的常用标签。 */
    fun search(query: String, limit: Int = 40): List<Tag> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return tags.take(limit)
        return tags.filter {
            it.tag.lowercase().contains(q) || it.zh.contains(q) || it.group.contains(q)
        }.take(limit)
    }
}
