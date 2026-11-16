package com.dbpg.duihuazhushou.imagegen

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.stcore.imagegen.PresetRule

/**
 * 「提示词预设」的设置弹窗 —— 这套东西的**唯一**操作界面。
 *
 * ```
 * 预设  Cemini Novel En ▾            ＋ 导入      🗑
 * 注入范围  [仅破甲段] [全部段]
 * ─────────────────────────────────────────────
 * 提示词段   2/15 勾选 · 972 字
 * ☑ ⚙️NSFW                                   275 字
 * ☐ 🤖NovelAI                                695 字
 * ─────────────────────────────────────────────
 * 正则脚本   2/3 开
 * ☑ 角色名替换   \{\{char\}\}  →  角色
 * ☐ 去 <page> 包裹
 * ```
 *
 * ## 为什么单开一个弹窗
 *
 * 一套酒馆预设动辄 15 段 + 十几条正则，展开就是两三屏。塞进「对话生图」
 * 配置页，会把「图片大小」「风格」「采样器」这些**每次出图都要碰**的项
 * 挤到屏幕外面 —— 主页面只该留一行摘要，点进来才是这一堆。
 *
 * ## 改动即时落盘
 *
 * 段勾选、正则开关、注入范围都不等"保存"按钮：写盘是几十 KB 的小文件，
 * 走 [PromptPresetStore] 的后台线程；而用户随时可能按返回键，攒着不写
 * 只会丢状态。关掉弹窗只是回主页面刷新摘要。
 */
object PromptPresetDialog {

    private const val C_TITLE = 0xFF1A2230.toInt()
    private const val C_SUB = 0xFF8A94A6.toInt()
    private const val C_ACCENT = 0xFF2F66D8.toInt()
    private const val C_WARN = 0xFFC0392B.toInt()

    fun show(activity: Activity, onChanged: () -> Unit = {}) {
        var uid = PromptPresetStore.load(activity).resolved()?.uid ?: ""

        // 勾选状态在弹窗生命周期里唯一权威，避免和 Store 的不可变快照来回对账
        val picked = HashSet<String>()
        val rulesOn = HashSet<String>()

        fun syncFromStore() {
            val p = PromptPresetStore.load(activity).presets.firstOrNull { it.uid == uid }
            picked.clear()
            rulesOn.clear()
            p?.segments?.filter { it.on }?.forEach { picked.add(it.id) }
            p?.rules?.filter { it.on }?.forEach { rulesOn.add(it.id) }
        }

        syncFromStore()

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 2), dp(activity, 16), dp(activity, 10))
        }
        val scroll = ScrollView(activity).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }

        fun render() {
            val keepY = scroll.scrollY
            body.removeAllViews()

            val st = PromptPresetStore.load(activity)
            val p = st.presets.firstOrNull { it.uid == uid } ?: st.resolved()
            if (p != null) uid = p.uid

            body.addView(headerRow(activity, p, onPick = {
                AlertDialog.Builder(activity)
                    .setTitle("选择预设")
                    .setItems(
                        st.presets.map { (if (it.uid == uid) "● " else "○ ") + it.name }
                            .toTypedArray(),
                    ) { _, which ->
                        uid = st.presets[which].uid
                        PromptPresetStore.setUsePreset(activity, true)
                        PromptPresetStore.setPresetId(activity, uid)
                        syncFromStore()
                        render()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }, onImport = {
                PromptPresetImportUi.start(activity) {
                    // 导入完会自动选中新的那套
                    uid = PromptPresetStore.load(activity).resolved()?.uid ?: uid
                    syncFromStore()
                    render()
                    onChanged()
                }
            }, onDelete = {
                if (p == null) return@headerRow
                AlertDialog.Builder(activity)
                    .setTitle("删除「${p.name}」？")
                    .setMessage("只删这条预设，跟资产页、跟聊天预设都没关系。")
                    .setPositiveButton("删除") { _, _ ->
                        PromptPresetStore.remove(activity, p.uid)
                        uid = PromptPresetStore.load(activity).resolved()?.uid ?: ""
                        syncFromStore()
                        render()
                        onChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }))

            if (p == null) {
                body.addView(hint(activity, "还没有预设。点上面的「＋ 导入」选一个 JSON 文件，或者粘贴一段破甲文本。"))
                return
            }

            // ---- 注入范围
            body.addView(sectionTitle(activity, "注入范围"))
            val chips = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 2), 0, dp(activity, 6))
            }
            for (only in booleanArrayOf(true, false)) {
                val on = st.jailbreakOnly == only
                chips.addView(TextView(activity).apply {
                    text = if (only) "仅破甲段" else "全部段"
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF5A6B85.toInt())
                    background = chipBg(activity, on)
                    setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(activity, 6) }
                    setOnClickListener {
                        if (st.jailbreakOnly == only) return@setOnClickListener
                        PromptPresetStore.setJailbreakOnly(activity, only)
                        render()
                        onChanged()
                    }
                })
            }
            body.addView(chips)

            // ---- 段
            val segs = p.segments
            if (segs.isEmpty()) {
                body.addView(sectionTitle(activity, "提示词正文"))
                body.addView(
                    hint(
                        activity,
                        "这套预设没有段结构（手动编辑过，或者是 v1.7 之前导入的）。" +
                            "现在注入的是：\n\n" + p.body(st.jailbreakOnly).take(600) +
                            if (p.body(st.jailbreakOnly).length > 600) "\n…" else "",
                    ),
                )
            } else {
                val onCount = segs.count { it.id in picked }
                body.addView(sectionTitle(activity, "提示词段   $onCount/${segs.size} 勾选"))
                body.addView(
                    hint(activity, "勾哪几段就拼哪几段。默认只勾破甲相关的 —— 写作预设里大半段在教模型写小说。"),
                )
                segs.forEach { seg ->
                    body.addView(
                        checkRow(
                            activity = activity,
                            checked = seg.id in picked,
                            title = seg.title.ifBlank { seg.id },
                            meta = "${seg.chars} 字" + (if (seg.jailbreak) " · 破甲" else ""),
                            onToggle = { on ->
                                if (on) picked.add(seg.id) else picked.remove(seg.id)
                                PromptPresetStore.setSelection(activity, uid, picked, rulesOn)
                                render()
                                onChanged()
                            },
                        ),
                    )
                }
            }

            // ---- 正则
            val rules = p.rules
            body.addView(sectionTitle(activity, "正则脚本   ${rules.count { it.id in rulesOn }}/${rules.size} 开"))
            if (rules.isEmpty()) {
                body.addView(hint(activity, "这套预设没带正则脚本。"))
            } else {
                body.addView(
                    hint(
                        activity,
                        "预设自带的正则，在 system 拼好之后整段跑一遍（酒馆也是这么做的）。" +
                            "某条写坏了不会影响导入，只会在出图时被跳过。",
                    ),
                )
                rules.forEach { rule ->
                    val ok = PresetRule(rule.id, rule.name, rule.find, rule.replace).compiles()
                    body.addView(
                        checkRow(
                            activity = activity,
                            checked = rule.id in rulesOn,
                            title = rule.label,
                            meta = if (ok) rule.find.take(80) else "正则编译失败，运行时会跳过",
                            warn = !ok,
                            onToggle = { on ->
                                if (on) rulesOn.add(rule.id) else rulesOn.remove(rule.id)
                                PromptPresetStore.setSelection(activity, uid, picked, rulesOn)
                                render()
                                onChanged()
                            },
                        ),
                    )
                }
            }

            body.addView(
                hint(
                    activity,
                    "预设只管「剧情 → 标签」这一步。画风、负面、采样、尺寸仍由「风格」决定，两边互不影响。",
                ),
            )

            scroll.post { scroll.scrollTo(0, keepY) }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("提示词预设")
            .setView(scroll)
            .setPositiveButton("完成") { _, _ -> onChanged() }
            .create()

        dialog.setOnShowListener {
            // 高度按屏幕给足，否则 AlertDialog 会按内容缩成一条
            val h = (activity.resources.displayMetrics.heightPixels * 0.80).toInt()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
        dialog.show()
        render()
    }

    // ------------------------------------------------------------ 行

    private fun headerRow(
        a: Activity,
        p: PromptPresetStore.Preset?,
        onPick: () -> Unit,
        onImport: () -> Unit,
        onDelete: () -> Unit,
    ) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(TextView(a).apply {
            text = if (p == null) "未选择预设 ▾" else "${p.name} ▾"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C_TITLE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(a, 8), 0, dp(a, 8))
            setOnClickListener { onPick() }
        })
        addView(actionButton(a, "＋ 导入", onImport))
        if (p != null) addView(actionButton(a, "🗑", onDelete))
    }

    private fun actionButton(a: Activity, label: String, onClick: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 12f
        setTextColor(C_ACCENT)
        gravity = Gravity.CENTER
        setPadding(dp(a, 10), dp(a, 8), dp(a, 6), dp(a, 8))
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(a: Activity, text: String) = TextView(a).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C_SUB)
        setPadding(0, dp(a, 12), 0, dp(a, 4))
    }

    private fun hint(a: Activity, text: String) = TextView(a).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(C_SUB)
        setPadding(0, dp(a, 2), 0, dp(a, 6))
        setLineSpacing(dp(a, 2).toFloat(), 1f)
    }

    /**
     * 一行「勾选 + 标题 + 备注」。
     *
     * [CheckBox] 本身不接点击（`isClickable = false`），整行发包 —— 手指落在
     * 标题上也能勾，不然行宽一大半是点不动的。
     */
    private fun checkRow(
        activity: Activity,
        checked: Boolean,
        title: String,
        meta: String,
        warn: Boolean = false,
        onToggle: (Boolean) -> Unit,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(activity, 2), 0, dp(activity, 2))

        val cb = CheckBox(activity).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        addView(cb)

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(activity).apply {
            text = title
            textSize = 12.5f
            setTextColor(C_TITLE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (meta.isNotBlank()) {
            col.addView(TextView(activity).apply {
                text = meta
                textSize = 10f
                setTextColor(if (warn) C_WARN else C_SUB)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 1), 0, 0)
            })
        }
        addView(col)

        setOnClickListener {
            cb.isChecked = !cb.isChecked
            onToggle(cb.isChecked)
        }
    }

    private fun chipBg(a: Activity, on: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(a, 9).toFloat()
        if (on) {
            setColor(C_ACCENT)
        } else {
            setColor(0xFFF4F6FA.toInt())
            setStroke(dp(a, 1), 0xFFE1E7F0.toInt())
        }
    }

    private fun dp(a: Activity, v: Int): Int =
        Math.round(v * a.resources.displayMetrics.density)

    @Suppress("unused")
    private fun toast(a: Activity, msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
}
