package com.dbpg.stcore.render

/**
 * 渲染缓存键：一次渲染的"完整输入指纹"。
 *
 * 只有四维都相同，产物才能复用：
 *   - 哪一条消息（actionId）
 *   - 正文 + 图片列表（contentHash）
 *   - "怎么渲染"的配置（正则/宏/变量/思维链开关 → cfgHash）
 *   - 长什么样（字号/行距/字体/颜色/宽度 → lookHash）
 *
 * ★ 为什么不用"版本号 +1"：版本号在进程重启后会归零，磁盘里的旧产物
 *   会和新配置撞上同一个 key，出现"配置改了但界面没变"。必须用内容哈希。
 *
 * ★ 为什么 depth 默认 -1：`depth` 是"距最新一条的距离"，追加一条消息会让
 *   所有旧消息 depth +1，key 全变、缓存全废。只有存在 minDepth/maxDepth
 *   脚本时调用方才会把 depth 填进来（并且量化成桶）。
 */
data class RenderKey(
    val actionId: String,
    val contentHash: Long,
    val cfgHash: Int,
    val lookHash: Int,
    val depth: Int = -1,
) {
    /** 给 SQLite / LruCache 用的稳定字符串。 */
    fun stable(): String = "$actionId:$contentHash:$cfgHash:$lookHash:$depth"
}

/**
 * 轻量哈希工具（stcore 不依赖 Android，也不用密码学哈希）。
 *
 * 对长正文用 `String.hashCode() ^ length`：Java 的 hashCode 是 O(n) 但常数很小，
 * 2 万字约 20 微秒；SHA-256 同样文本要 200 微秒以上，而这条路径每个流式片段都会走。
 * 碰撞代价只是"显示旧内容"，加上长度一起参与后概率可以忽略。
 */
object Hashing {

    /** FNV-1a 64：适合配置片段这种短字符串，快且分布均匀。 */
    fun fnv(vararg parts: String?): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        val prime = 1099511628211L
        for (p in parts) {
            if (p == null) {
                h = h xor 0x2dL
                h *= prime
                continue
            }
            for (c in p) {
                h = h xor c.code.toLong()
                h *= prime
            }
            h = h xor 0x1fL
            h *= prime
        }
        return h
    }

    /** 长文本指纹：hashCode + 长度 + 首尾各 64 字符，足够区分。 */
    fun text(s: String): Long {
        if (s.isEmpty()) return 0L
        var h = s.hashCode().toLong() and 0xFFFFFFFFL
        h = h shl 21
        h = h xor s.length.toLong()
        // 首尾采样：防止 hashCode 碰撞时长度也相同（比如只有中间一字之差）
        val n = minOf(64, s.length)
        for (i in 0 until n) {
            h = h * 31 + s[i].code
            h = h * 31 + s[s.length - 1 - i].code
        }
        return h
    }

    fun intOf(vararg parts: String?): Int {
        val v = fnv(*parts)
        return (v xor (v ushr 32)).toInt()
    }
}
