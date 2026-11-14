package com.dbpg.duihuazhushou.agent

import android.content.Context

/**
 * 智能体的少量持久化开关。
 *
 * ★ 只存「开关」，不存 stage / tool / action 的清单——清单全在注册表里，
 *   运行时可增删，设置页也是遍历注册表生成的。以后加能力不用改这个文件。
 *
 * 默认：Agent 开启（基础链路可立刻验证，但内置 stage 只观测、不改正文，
 * 行为与改动前等价）、浮窗开启（不点开就只占一个小胶囊）。
 */
object AgentSettings {
    private const val PREF = "dhp_agent"
    private const val K_AGENT = "enabled"
    private const val K_OVERLAY = "overlay"
    private const val K_STAGE_PREFIX = "stage."

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isAgentOn(ctx: Context) = prefs(ctx).getBoolean(K_AGENT, true)
    fun setAgentOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_AGENT, on).apply()

    fun isOverlayOn(ctx: Context) = prefs(ctx).getBoolean(K_OVERLAY, true)
    fun setOverlayOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_OVERLAY, on).apply()

    /** 未记录过的 stage 默认启用；注册表新增的 stage 会自动出现在设置页。 */
    fun isStageOn(ctx: Context, id: String) =
        prefs(ctx).getBoolean(K_STAGE_PREFIX + id, true)

    fun setStageOn(ctx: Context, id: String, on: Boolean) =
        prefs(ctx).edit().putBoolean(K_STAGE_PREFIX + id, on).apply()
}
