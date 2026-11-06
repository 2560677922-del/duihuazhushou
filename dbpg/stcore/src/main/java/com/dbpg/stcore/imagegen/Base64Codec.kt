package com.dbpg.stcore.imagegen

/**
 * 纯 Kotlin 的 Base64 解码器（标准 / URL-safe 都吃）。
 *
 * 为什么不直接用 java.util.Base64：
 *   java.util.Base64 是 Android API 26 才有的，而本项目 minSdk = 24。
 *   在 24/25 上用它会在运行期直接 NoClassDefFoundError。
 *   这里自己实现一份，任何 API 都能跑，也方便做 JVM 单测。
 *
 * 容忍度刻意做高（应对各种中转站）：
 *   - 忽略所有空白字符（换行 / 空格），很多接口把长 base64 按行折行返回
 *   - URL-safe 的 '-' '_' 自动映射回 '+' '/'
 *   - 末尾 '=' 可有可无（缺 padding 也能解）
 */
object Base64Codec {

    private fun decodeChar(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }

    /** 解码；遇到非法字符抛 IllegalArgumentException。 */
    fun decode(input: String): ByteArray {
        val sb = StringBuilder(input.length)
        for (c in input) if (!c.isWhitespace()) sb.append(c)
        val s = sb.toString().trimEnd('=')
        if (s.isEmpty()) return ByteArray(0)

        val out = ByteArray(s.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        var idx = 0
        for (c in s) {
            val v = decodeChar(c)
            if (v < 0) throw IllegalArgumentException("非法 base64 字符: '$c'")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(idx)
    }

    /** 是否像一段 base64（只做字符集与长度校验，不做解码）。 */
    fun isLikelyBase64(input: String, minLen: Int = 32): Boolean {
        var n = 0
        for (c in input) {
            when {
                c.isWhitespace() -> continue
                decodeChar(c) >= 0 -> n++
                c == '=' -> n++
                else -> return false
            }
        }
        return n >= minLen
    }
}
