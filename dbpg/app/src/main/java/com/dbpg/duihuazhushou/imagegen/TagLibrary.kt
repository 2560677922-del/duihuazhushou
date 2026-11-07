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

    data class Tag(val tag: String, val zh: String, val group: String, val sub: String = "")

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
                            sub = o.optString("sub"),
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

    /** 一级分类（按固定顺序，附库里实际有的其它分组）。 */
    fun groups(): List<String> {
        if (tags.isEmpty()) return TagCategories.order
        val present = tags.map { it.group }.filter { it.isNotBlank() }.distinct()
        val ordered = TagCategories.order.filter { present.contains(it) }
        return ordered + present.filterNot { TagCategories.order.contains(it) }
    }

    /** 某个一级分类下的二级分类代号（去重、保持出现顺序）。 */
    fun subsOf(group: String): List<String> {
        if (group.isBlank()) return emptyList()
        return tags.asSequence()
            .filter { it.group == group && it.sub.isNotBlank() }
            .map { it.sub }.distinct().toList()
    }

    /**
     * 组合过滤：关键词 + 一级分类 + 二级分类。
     * 空串/空分类 = 不限制；query 为空时按库内顺序取前 limit 条。
     */
    fun search(
        query: String,
        group: String = "",
        sub: String = "",
        limit: Int = 40,
    ): List<Tag> {
        val q = query.trim().lowercase()
        return tags.asSequence()
            .filter { group.isBlank() || it.group == group }
            .filter { sub.isBlank() || it.sub == sub }
            .filter {
                q.isEmpty() || it.tag.lowercase().contains(q) || it.zh.contains(q)
            }
            .take(limit)
            .toList()
    }
}

/**
 * 分类体系（与网页 src/lib/tagSubZh.ts 保持同一套中文名与顺序）。
 *
 * 一级 = JSON 里的 group；二级 = sub（英文代号，界面显示中文）。
 * 这套映射在网页端只维护一份，原生这里抄一份 —— 两边改一处要同步，
 * 否则界面会露出 outfit / hair_color 这种英文代号。
 */
object TagCategories {
    val order = listOf("服装", "角色扮演", "外表", "动作", "场景", "动物", "风格")

    private val subZh = mapOf(
        "outfit" to "整装", "top" to "上装", "bottom" to "下装", "headwear" to "头饰",
        "accents" to "配饰", "legwear" to "腿袜", "footwear" to "鞋履", "eyewear" to "眼镜",
        "underwear" to "内衣", "material" to "材质", "pattern" to "花纹", "cosplay" to "cosplay",
        "hair_style" to "发型", "hair_color" to "发色", "eye_color" to "瞳色",
        "eye_features" to "眼睛", "face" to "面部", "body" to "身体", "skin" to "皮肤",
        "ears" to "耳朵", "tail" to "尾巴", "wings" to "翅膀", "horns" to "角",
        "demographics" to "人口属性", "identity" to "身份", "character" to "角色",
        "pose" to "姿态", "gesture" to "手势", "interaction" to "互动", "expression" to "表情",
        "camera" to "镜头", "motion" to "动态", "combat" to "战斗", "lewd" to "性感",
        "physiological" to "生理反应", "holding" to "手持物", "effect" to "画面效果",
        "setting" to "场景", "indoor" to "室内", "outdoor" to "室外", "nature" to "自然",
        "props" to "道具", "furniture" to "家具", "food" to "食物", "weapon" to "武器",
        "sky" to "天空", "weather" to "天气", "atmosphere" to "氛围",
        "animal" to "动物", "creature" to "生物", "quality" to "画质", "style" to "画风",
        "layout" to "构图", "color" to "颜色", "lighting" to "光照", "background" to "背景",
        "medium" to "媒介", "preset" to "提示词预设", "anotomy" to "身体", "anatomy" to "身体",
        "hair" to "毛发", "eyes" to "眼睛", "misc" to "其它", "SM/BDSM" to "SM/BDSM",
    )

    fun subLabel(sub: String): String {
        if (sub.isEmpty()) return ""
        if (Regex("[\\u4e00-\\u9fff]").containsMatchIn(sub)) return sub
        return subZh[sub] ?: sub
    }
}
