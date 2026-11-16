package com.dbpg.duihuazhushou.chat.render.plugin

import android.content.Context

/**
 * 插件的「运行时挂载状态」。
 *
 * ★★ 双清单：出厂清单 [PluginCatalog.BUILTIN_IDS] 随 APK 编译、恒在且只读；
 *    这里只存**用户可写的两份状态** —— 哪些被挂载（installed）、哪些被启用（enabled）。
 *    没有删除插件本体的 API：点「卸载」只是把 id 从 installed 移走，随时能装回。
 *    测试期没有云端库 / 发布页，[restoreBuiltins] 是兜底，保证怎么卸都下得回来。
 *
 * 状态放独立的 SharedPreferences：不动 Appearance、不动会话、不动网页
 * IndexedDB，也不往现有的配置文件里塞字段。
 */
object PluginStore {

    private const val PREF = "dhp_render_plugins"
    private const val K_INSTALLED = "installed"
    private const val K_ENABLED = "enabled"
    /** 「内置插件已全部登记」标记：升级 APK 新增内置插件时靠它补装一次。 */
    private const val K_SEEDED = "seeded_v2"
    private const val SEP = "\u0001"

    /** 一体化插件的 id（沿用最早那个，老用户的安装状态直接继续有效）。 */
    private const val MERGED_ID = "panel_data_adapter"

    /** 合并前的两个前端卡插件。 */
    private val LEGACY_IDS = setOf("frontend_card_strict", "frontend_card_loose")

    /**
     * 已封印的功能 id。
     *
     * 老用户的 pref 里一定还留着 [PromptPresetPlugin.ID]（v1.4 起默认登记），
     * 出厂清单留空只是"不再登记"，读的时候还得把它们剔掉 —— 否则升级后
     * 插件页看不到它、出图却还在注入，是最难查的那种"半生效"。
     */
    private val SEALED_IDS = setOf("image_preset")

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
        val p = prefs(ctx)
        val raw = p.getString(K_INSTALLED, null)
        if (raw == null) {
            /*
             * 首次运行（新装 / 清过数据）：内置插件全部挂上并启用。
             *
             * ★ 只认「键不存在」。用户主动把插件全卸完时存的是空串，
             *   那是明确意图，这里绝不能偷偷装回去。
             *   生图预设插件走同一条路 —— 装上就能在生图页选预设，
             *   而不是「装了 APK 却什么都没有」。
             */
            val ids = builtinIds()
            p.edit()
                .putString(K_INSTALLED, encode(ids))
                .putString(K_ENABLED, encode(ids))
                .putBoolean(K_SEEDED, true)
                .apply()
            installedCache = ids
            enabledCache = ids
            return ids
        }
        val v = decode(raw).filterNotTo(mutableSetOf()) { it in SEALED_IDS }
        if (v.size != decode(raw).size) {
            // 封印前的残留：顺手清掉，免得每次启动都要过滤一遍
            p.edit().putString(K_INSTALLED, encode(v)).apply()
        }
        if (!p.getBoolean(K_SEEDED, false)) {
            /*
             * 老用户升级：pref 里已经有渲染插件，但从来没有「生图预设」这个 id。
             * 一次性把当前全部内置插件补进已挂载/已启用，并打上标记 ——
             * 不打这个标记的话，用户装了新 APK 会看不到预设选择，
             * 还以为是功能没做（上一版就是这么被反馈的）。
             *
             * ★ 只补一次。之后用户主动卸载生成图插件，不会被这里偷偷装回去。
             */
            val ids = builtinIds()
            val nextInstalled = v + ids
            val nextEnabled = decode(p.getString(K_ENABLED, "")) + ids
            p.edit()
                .putString(K_INSTALLED, encode(nextInstalled))
                .putString(K_ENABLED, encode(nextEnabled))
                .putBoolean(K_SEEDED, true)
                .apply()
            installedCache = nextInstalled
            enabledCache = nextEnabled
            return nextInstalled
        }
        installedCache = v
        return v
    }

    fun enabled(ctx: Context): Set<String> {
        enabledCache?.let { return it }
        val raw = prefs(ctx).getString(K_ENABLED, "")
        val v = decode(raw).filterNotTo(mutableSetOf()) { it in SEALED_IDS }
        if (v.size != decode(raw).size) {
            prefs(ctx).edit().putString(K_ENABLED, encode(v)).apply()
        }
        enabledCache = v
        return v
    }

    /** 安装 = 挂到运行时并默认启用。插件本体不受影响。 */
    fun install(ctx: Context, id: String) {
        // 封印中的功能不给装：否则恢复出厂 / 手动安装能绕过封印
        if (id in SEALED_IDS) return
        val i = installed(ctx) + id
        val e = enabled(ctx) + id
        installedCache = i
        enabledCache = e
        prefs(ctx).edit()
            .putString(K_INSTALLED, encode(i))
            .putString(K_ENABLED, encode(e))
            .apply()
    }

    /** 卸载 = 从运行时移出（★ 不是删除插件本体，之后可再安装 / 一键恢复出厂）。 */
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

    /**
     * 恢复出厂：把所有内置插件重新挂载并启用。
     *
     * ★ 为什么需要：测试期没有云端插件库、没有发布页，插件本体编译进 APK，
     *   「卸载」本身只是移出运行时、随时能单个装回；但用户可能连续卸完、
     *   或状态文件被清后找不到入口。这个一键恢复以 [PluginCatalog.BUILTIN_IDS]
     *   为准，保证怎么点都下得回来。幂等，且不会动将来的外部插件。
     */
    fun restoreBuiltins(ctx: Context) {
        // 封印中的不恢复（builtinIds 里也没有，这里再兜一层）
        val builtins = builtinIds() - SEALED_IDS
        val i = installed(ctx) + builtins
        val e = enabled(ctx) + builtins
        installedCache = i
        enabledCache = e
        prefs(ctx).edit()
            .putString(K_INSTALLED, encode(i))
            .putString(K_ENABLED, encode(e))
            .apply()
    }

    /**
     * 全部内置插件 id = 渲染插件 + 生图插件。
     *
     * 两类插件共用这一套挂载状态（一个 Set 装 id），所以「恢复出厂」必须两边都管：
     * 只恢复渲染插件的话，用户卸掉生图预设后再点恢复，那个开关还是回不来。
     */
    private fun builtinIds(): Set<String> =
        PluginCatalog.BUILTIN_IDS + com.dbpg.duihuazhushou.imagegen.PromptPresetCatalog.BUILTIN_IDS

    /** 内置插件恒在、不可删除；外部插件将来才可能有真正的删除语义。 */
    fun isBuiltin(id: String): Boolean = id in builtinIds()

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
