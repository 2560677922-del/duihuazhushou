package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.agent.AgentOverlay
import com.dbpg.duihuazhushou.agent.AgentRuntime
import com.dbpg.duihuazhushou.agent.AgentSettings
import com.dbpg.stcore.agent.TraceBus

/**
 * 智能体设置页：只回答三件事 —— 开没开、浮窗显不显示、哪些能力生效。
 *
 * ★ 能力清单**遍历注册表生成**，不硬编码：以后往 AgentRuntime 注册新 stage、
 *   新工具、新 intent，这里自动多一行，不用改这个页面。
 * ★ 全部代码构建，不新增 layout XML，也不动现有设置页结构。
 */
class AgentSettingsActivity : Activity() {

    private lateinit var stageBox: LinearLayout
    private lateinit var catalogBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.init(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Insets.applyTop(this)

        root.addView(TextView(this).apply {
            text = "返回"
            textSize = 14f
            setTextColor(Color.parseColor("#5A6B85"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "智能体（基础链路）"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A2230"))
            setPadding(dp(18), dp(4), dp(18), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "当前只跑通编排链路与过程记录；正文仍由主模型直出，行为与关闭时一致。"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7688"))
            setPadding(dp(18), dp(2), dp(18), dp(12))
        })

        stageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(section("总开关"))
        root.addView(
            switchRow(
                "启用智能体编排",
                "关掉即走确定性后端，逐字节回到改动前的生成链路；任一阶段失败也会自动退化。",
                AgentSettings.isAgentOn(this),
            ) { on -> AgentSettings.setAgentOn(this, on) },
        )
        root.addView(
            switchRow(
                "显示迷你浮窗",
                "折叠态只占一个小胶囊；点开看阶段、工具与调用过程。",
                AgentSettings.isOverlayOn(this),
            ) { on ->
                AgentSettings.setOverlayOn(this, on)
                if (on) AgentOverlay.attach(this) else AgentOverlay.detach()
            },
        )

        root.addView(section("能力开关（按阶段独立开合）"))
        root.addView(stageBox)
        refreshStages()

        root.addView(section("已注册能力（运行时清单）"))
        catalogBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(catalogBox)
        refreshCatalog()

        root.addView(TextView(this).apply {
            text = "清空过程记录"
            textSize = 13f
            setTextColor(Color.parseColor("#B0423A"))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { TraceBus.clear() }
        })
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#8A94A6"))
        setPadding(dpOf(18), dpOf(16), dpOf(18), dpOf(6))
    }

    private fun dpOf(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), dp(2), dp(12), dp(2)) }
            val texts = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this.context).apply {
                text = title; textSize = 14f; setTextColor(Color.parseColor("#1A2230"))
            })
            texts.addView(TextView(this.context).apply {
                text = desc; textSize = 11f; setTextColor(Color.parseColor("#7A8698"))
            })
            addView(texts)
            val sw = Switch(this.context).apply { isChecked = checked }
            sw.setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> onChange(on) }
            addView(sw)
        }
    }

    private fun refreshStages() {
        stageBox.removeAllViews()
        for (id in AgentRuntime.stages.ids()) {
            stageBox.addView(
                switchRow(
                    id,
                    AgentRuntime.stages.descriptionOf(id).ifBlank { "可插拔阶段" },
                    AgentSettings.isStageOn(this, id),
                ) { on -> AgentRuntime.setStageEnabled(this, id, on) },
            )
        }
    }

    private fun refreshCatalog() {
        catalogBox.removeAllViews()
        fun line(s: String) = TextView(this).apply {
            text = s
            textSize = 11.5f
            setTextColor(Color.parseColor("#3A4658"))
            setPadding(dpOf(18), dpOf(2), dpOf(18), dpOf(2))
        }
        catalogBox.addView(line("阶段：${AgentRuntime.stages.ids().joinToString()}"))
        catalogBox.addView(line("工具：${AgentRuntime.tools.names().joinToString()}"))
        catalogBox.addView(line("卡片动作：${AgentRuntime.intents.actions().joinToString()}"))
        catalogBox.addView(TextView(this).apply {
            text = "未知动作不会报错：记一条过程记录后忽略。以后插件注册新动作即可生效。"
            textSize = 11f
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(dpOf(18), dpOf(8), dpOf(18), dpOf(8))
        })
    }
}
