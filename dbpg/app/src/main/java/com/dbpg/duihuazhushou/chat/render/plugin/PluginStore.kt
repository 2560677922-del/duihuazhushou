package com.dbpg.duihuazhushou.chat.render.plugin

import android.content.Context

/**
 * 插件的「运行时挂载状态」。
 *
 * ★★ 只存两件事：哪些插件被**安装**了、哪些被**启用**了。
 *    这里**没有删除插件的 API** —— 插件本体在 [PluginCatalog]，随 APK 打包，
 *    删不掉也不需要删。用户点「卸载」只是把 id 从 installed 里移走，
 *    插件仍在库里，随时能再点「安装」挂回来。
 *
 * 状态放独立的 SharedPreferences：不动 Appearance、不动会话、不动网页
 * IndexedDB，也不往现有的配置文件里塞字段。
 */
object PluginStore {

    private const val PREF = "dhp_render_plugins"
    private const val K_INSTALLED = "installed"
    private const val K_ENABLED = "enabled"
    private const val SEP = "\u0001"

    /** 一体化插件的 id（沿用最早那个，老用户的安装状态直接继续有效）。 */
    private const val MERGED_ID = "panel_data_adapter"

    /** 合并前的两个前端卡插件。 */
    private val LEGACY_IDS = setOf("frontend_card_strict", "frontend_card_loose")

    @Volatile private var installedCache: Set<String>? = null
    @Volatile private var enabledCache: Set<String>? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun decode(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet()
        else raw.split(SEP).filter { it.isNotBlank() }.toSet()

    private fun encode(set: Set<String>): String = set.sorted().joinToString(SEP)

    fun installed(ctx: Context): Set<String> {
        installedCache?.let { return it }
        val v = decode(prefs(ctx).getString(K_INSTALLED, ""))
        installedCache = v
        return v
    }

    fun enabled(ctx: Context): Set<String> {
        enabledCache?.let { return it }
        val v = decode(prefs(ctx).getString(K_ENABLED, ""))
        enabledCache = v
        return v
    }

    /** 安装 = 挂到运行时并默认启用。插件本体不受影响。 */
    fun install(ctx: Context, id: String) {
        val i = installed(ctx) + id
        val e = enabled(ctx) + id
        installedCache = i
        enabledCache = e
        prefs(ctx).edit()
            .putString(K_INSTALLED, encode(i))
            .putString(K_ENABLED, encode(e))
            .apply()
    }

    /** 卸载 = 从运行时移出（★ 不是删除插件本体，之后可再安装）。 */
    fun uninstall(ctx: Context, id: String) {
        val i = installed(ctx) - id
        val e = enabled(ctx) - id
        installedCache = i
        enabledCache = e
        prefs(ctx).edit()
            .putString(K_INSTALLED, encode(i))
            .putString(K_ENABLED, encode(e))
            .apply()
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        if (id !in installed(ctx)) return
        val e = if (on) enabled(ctx) + id else enabled(ctx) - id
        enabledCache = e
        prefs(ctx).edit().putString(K_ENABLED, encode(e)).apply()
    }

    /** 状态被外部改动后调用（设置页 / 跨进程），丢掉内存缓存。 */
    fun invalidate() {
        installedCache = null
        enabledCache = null
    }

    /**
     * 旧版把渲染能力拆成三个插件，合并成一个后做一次状态迁移：
     * 只要装过其中任意一个，就自动把一体化插件补进已安装列表。
     *
     * ★ 幂等、只写一次：没装过旧插件 / 已经装了新插件时直接返回。
     *   这样老用户覆盖安装 APK 后不会看到「插件全没了」。
     */
    fun migrateLegacy(ctx: Context) {
        val p = prefs(ctx)
        val installed = decode(p.getString(K_INSTALLED, ""))
        val legacy = installed.filter { it in LEGACY_IDS }
        if (legacy.isEmpty()) return
        val drop = legacy.toSet()
        val enabled = decode(p.getString(K_ENABLED, ""))
        val wasEnabled = legacy.any { it in enabled }
        val nextInstalled = (installed - drop) + MERGED_ID
        val nextEnabled = if (wasEnabled) (enabled - drop) + MERGED_ID else enabled - drop
        installedCache = nextInstalled
        enabledCache = nextEnabled
        p.edit()
            .putString(K_INSTALLED, encode(nextInstalled))
            .putString(K_ENABLED, encode(nextEnabled))
            .apply()
    }
}
