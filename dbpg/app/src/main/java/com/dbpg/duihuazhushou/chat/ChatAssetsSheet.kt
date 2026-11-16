package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.character.CharacterFile
import com.dbpg.duihuazhushou.character.CharacterJson
import com.dbpg.duihuazhushou.character.CharacterListActivity
import com.dbpg.duihuazhushou.character.CharacterRepository
import com.dbpg.duihuazhushou.character.PersonaStore
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.session.SessionBindingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「本对话资产」一页式面板。
 *
 * ## 为什么重做
 *
 * 原来是一层列表 → 点「正则组」→ 弹二级对话框 → 关掉 → 再点「资产选择」→ 点「预设」…
 * 用户的原话是「我点完正则还要再点一下资产选择然后再点预设」，配置一次要点十几次。
 *
 * 现在一个窗口铺开所有分区，每项就地勾选，最后点一次「保存」全部落盘。
 * 分区顺序按使用频率：扮演谁 → 我 → 预设 → 世界书 → 正则 → DNA。
 *
 * ## 语义（与资产页「启用」的关系，沿用旧实现）
 *
 *   - 资产页「启用」＝ 这个资产在对话里**可不可选**；
 *   - 本面板勾选 = 这次生成真正用的；
 *   - 生效 = 勾选 ∩ 资产页启用。
 *
 * 某分区「全选」会被存成 `null`（跟随全局）：以后资产页新加的条目自动生效，
 * 不用回来重新勾一遍 —— 这也是老用户没配置过的默认行为。
 */
object ChatAssetsSheet {

    private const val C_TEXT = 0xFF1A2230.toInt()
    private const val C_SUB = 0xFF5A6B85.toInt()
    private const val C_HINT = 0xFF8A94A6.toInt()
    private const val C_ACCENT = 0xFF2F66D8.toInt()

    fun show(activity: Activity, sessionId: String, onSaved: () -> Unit = {}) {
        EngineBridge.fetch(activity, "getAssets") { assets ->
            if (assets == null) return@fetch
            if (activity.isFinishing || activity.isDestroyed) return@fetch
            render(activity, sessionId, assets, onSaved)
        }
    }

    // ------------------------------------------------------------ 状态

    /** 面板里正在编辑的一份绑定（保存前只存在内存里）。 */
    private class Draft(b: SessionBindingStore.Binding) {
        var characterId: String? = b.characterId
        var characterName: String? = b.characterName
        /** 选了本地卡但还没导入引擎：保存时才走 ensureCharacter 拿 uid */
        var pendingCard: CharacterFile? = null
        var personaId: String? = b.personaId
        var presetId: String? = b.presetId
        var worldBooks: MutableSet<String>? = b.worldBookIds?.toMutableSet()
        var regexes: MutableSet<String>? = b.regexGroupIds?.toMutableSet()
        var dnas: MutableSet<String>? = b.dnaIds?.toMutableSet()
        /**
         * 这个对话单独用的模型（`llm.configs[]._uid`）。
         *
         * 本面板不编辑它，但**必须原样带回去**：Binding 是整体覆盖写，
         * 少带一个字段就等于把这个会话选的模型清成"跟随全局"。
         */
        var apiConfigId: String? = b.apiConfigId
    }

    private fun render(
        activity: Activity,
        sessionId: String,
        assets: JSONObject,
        onSaved: () -> Unit,
    ) {
        val draft = Draft(SessionBindingStore.load(activity, sessionId))

        val worldBooks = enabledItems(assets.optJSONArray("worldBooks"), "current")
        val presets = enabledItems(assets.optJSONArray("presets"), "enabled")
        val regexes = enabledItems(assets.optJSONArray("regexGroups"), "current")
        val dnas = enabledItems(
            ConnectionStore.snapshot("getImageStyle")?.optJSONArray("dnaList"), "enabled",
        )
        val personas = PersonaStore.list(activity)
        val cards = runCatching {
            CharacterRepository(activity).list().filter { it.card != null }
        }.getOrDefault(emptyList())

        val dp = { v: Int -> Math.round(v * activity.resources.displayMetrics.density) }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        // 小屏上不能让面板顶到状态栏：给个上限，底部按钮永远点得到。
        // 外面再套一层固定高度的 FrameLayout：AlertDialog 会给自定义 view
        // 重新套布局参数，直接让 ScrollView 带高度是不生效的。
        val cap = minOf(dp(420), (activity.resources.displayMetrics.heightPixels * 0.62f).toInt())
        val scroll = ScrollView(activity).apply { addView(box) }
        val holder = android.widget.FrameLayout(activity).apply {
            addView(
                scroll,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, cap,
                ),
            )
        }

        fun chip(label: String, on: Boolean, w: Int): TextView = TextView(activity).apply {
            text = (if (on) "✓ " else "") + label
            textSize = 12.5f
            setTextColor(if (on) 0xFFFFFFFF.toInt() else C_SUB)
            background = chipBg(on, dp)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
                topMargin = dp(6)
            }
        }

        /** 重建整块内容：选择变了就整体重绘，状态永远和 draft 一致。 */
        fun rebuild() {
            box.removeAllViews()

            box.addView(hintText(activity, dp, "勾选 = 本对话使用。全部勾选的分类会自动跟随资产页，以后新加的条目直接生效。"))

            // ---- 扮演谁（角色卡，单选）
            section(box, activity, dp, "AI 扮演谁（角色卡）", "${cards.size} 张在卡库")
            // 第一项是"不指定"（null）；其余按名字认（本地卡的 uid 还没生成，没法按 id 认）
            box.addView(row(activity, dp, "不指定（只按预设 / 世界书演）", draft.characterName == null, false) {
                draft.characterId = null
                draft.characterName = null
                draft.pendingCard = null
                rebuild()
            })
            for (c in cards) {
                val on = draft.characterName == c.displayName
                box.addView(row(activity, dp, c.displayName, on, false) {
                    draft.pendingCard = c
                    draft.characterId = null
                    draft.characterName = c.displayName
                    rebuild()
                })
            }
            if (cards.isEmpty()) {
                box.addView(linkRow(activity, dp, "卡库还没有卡 · 去角色卡库新建或导入 ›") {
                    activity.startActivity(Intent(activity, CharacterListActivity::class.java))
                })
            }

            // ---- 我（用户角色，单选）
            section(box, activity, dp, "我在这故事里是谁（用户角色）", "只影响 {{user}} 的名字与人设")
            box.addView(row(activity, dp, "跟随全局（${PersonaStore.active(activity)?.name ?: "未设置"}）", draft.personaId == null, false) {
                draft.personaId = null
                rebuild()
            })
            for (p in personas) {
                box.addView(row(activity, dp, p.name, draft.personaId == p.id, false) {
                    draft.personaId = p.id
                    rebuild()
                })
            }

            // ---- 预设（单选）
            section(box, activity, dp, "预设", "${presets.size} 套可启用")
            box.addView(row(activity, dp, "跟随全局默认", draft.presetId == null, false) {
                draft.presetId = null
                rebuild()
            })
            for ((id, name) in presets) {
                box.addView(row(activity, dp, name, draft.presetId == id, false) {
                    draft.presetId = id
                    rebuild()
                })
            }
            if (presets.isEmpty()) box.addView(emptyRow(activity, dp, "资产页还没有启用的预设"))

            // ---- 世界书（多选，两列 chip）
            val wbSel = draft.worldBooks ?: worldBooks.map { it.first }.toSet()
            section(box, activity, dp, "世界书", "共 ${worldBooks.size} 本 · 已选 ${wbSel.size}")
            box.addView(
                chipWrap(activity, dp, worldBooks, wbSel, emptyText = "资产页还没有启用的世界书") { id, on ->
                    val cur = (draft.worldBooks ?: worldBooks.map { it.first }.toSet()).toMutableSet()
                    if (on) cur.add(id) else cur.remove(id)
                    draft.worldBooks = cur
                    rebuild()
                },
            )

            // ---- 正则组（多选）
            val rxSel = draft.regexes ?: regexes.map { it.first }.toSet()
            section(box, activity, dp, "正则组", "共 ${regexes.size} 组 · 已选 ${rxSel.size}")
            box.addView(
                chipWrap(activity, dp, regexes, rxSel, emptyText = "资产页还没有启用的正则组") { id, on ->
                    val cur = (draft.regexes ?: regexes.map { it.first }.toSet()).toMutableSet()
                    if (on) cur.add(id) else cur.remove(id)
                    draft.regexes = cur
                    rebuild()
                },
            )

            // ---- 角色 DNA（多选，生图时生效）
            val dnaSel = draft.dnas ?: dnas.map { it.first }.toSet()
            section(box, activity, dp, "角色 DNA", "生图时锁人物外观 · 已选 ${dnaSel.size}")
            box.addView(
                chipWrap(activity, dp, dnas, dnaSel, emptyText = "还没有角色 DNA") { id, on ->
                    val cur = (draft.dnas ?: dnas.map { it.first }.toSet()).toMutableSet()
                    if (on) cur.add(id) else cur.remove(id)
                    draft.dnas = cur
                    rebuild()
                },
            )
        }

        rebuild()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("本对话资产")
            .setView(holder)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("全部跟随全局", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                save(activity, sessionId, draft, worldBooks, regexes, dnas, onSaved) { dialog.dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                // 只清资产引用，保留这个会话单独选的模型（它不属于"资产"）
                SessionBindingStore.save(
                    activity, sessionId,
                    SessionBindingStore.Binding(apiConfigId = draft.apiConfigId),
                )
                onSaved()
                dialog.dismiss()
                Toast.makeText(activity, "已恢复跟随全局", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------ 保存

    /**
     * 落盘。选了本地卡时要先过一趟引擎拿 uid（同名复用），
     * 所以这里可能异步；失败就留下对话框让用户重试，不要悄悄存半截。
     */
    private fun save(
        activity: Activity,
        sessionId: String,
        draft: Draft,
        worldBooks: List<Pair<String, String>>,
        regexes: List<Pair<String, String>>,
        dnas: List<Pair<String, String>>,
        onSaved: () -> Unit,
        onDone: () -> Unit,
    ) {
        fun binding(cardUid: String?, cardName: String?) = SessionBindingStore.Binding(
            characterId = cardUid,
            characterName = cardName,
            personaId = draft.personaId,
            presetId = draft.presetId,
            // 全选存 null = 跟随全局：资产页以后新增的条目会自动进来
            worldBookIds = fullOrNull(draft.worldBooks, worldBooks),
            regexGroupIds = fullOrNull(draft.regexes, regexes),
            dnaIds = fullOrNull(draft.dnas, dnas),
            // 面板里不能改模型，但整体覆盖写必须把它带回去，否则等于清空
            apiConfigId = draft.apiConfigId,
        )

        val card = draft.pendingCard
        if (card == null) {
            SessionBindingStore.save(activity, sessionId, binding(draft.characterId, draft.characterName))
            onSaved()
            onDone()
            return
        }
        val json = card.card?.let { CharacterJson.toV2(it) }
        if (json == null) {
            SessionBindingStore.save(activity, sessionId, binding(null, null))
            onSaved()
            onDone()
            return
        }
        GenBridge.ensureCharacter(json) { res ->
            if (activity.isFinishing || activity.isDestroyed) return@ensureCharacter
            val uid = res?.optString("uid").orEmpty()
            if (uid.isBlank()) {
                AlertDialog.Builder(activity)
                    .setMessage("这张卡没能导入引擎（${res?.optString("error").orEmpty()}）。再试一次，或去角色卡库重新保存。")
                    .setPositiveButton("知道了", null)
                    .show()
                return@ensureCharacter
            }
            SessionBindingStore.save(activity, sessionId, binding(uid, card.displayName))
            onSaved()
            onDone()
        }
    }

    /** 全选 → null（跟随全局）；部分选 → 显式集合；一个都没选 → 空列表（明确不用）。 */
    private fun fullOrNull(sel: MutableSet<String>?, all: List<Pair<String, String>>): List<String>? {
        val list = sel?.toList() ?: return null
        if (all.isNotEmpty() && list.toSet() == all.map { it.first }.toSet()) return null
        return list
    }

    // ------------------------------------------------------------ 视图小件

    private fun section(parent: LinearLayout, a: Activity, dp: (Int) -> Int, title: String, hint: String) {
        parent.addView(TextView(a).apply {
            text = title
            textSize = 13.5f
            setTextColor(C_TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        })
        if (hint.isNotBlank()) {
            parent.addView(TextView(a).apply {
                text = hint
                textSize = 10.5f
                setTextColor(C_HINT)
                setPadding(0, dp(2), 0, dp(2))
            })
        }
    }

    private fun hintText(a: Activity, dp: (Int) -> Int, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 11f
        setTextColor(C_HINT)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(4), 0, 0)
    }

    /** 一行可点的选项：单选画 ●/○，多选画 ✓/空。 */
    private fun row(
        a: Activity,
        dp: (Int) -> Int,
        label: String,
        checked: Boolean,
        multi: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(a).apply {
        text = (if (checked) (if (multi) "✓ " else "● ") else (if (multi) "○ " else "○ ")) + label
        textSize = 13f
        setTextColor(if (checked) C_ACCENT else C_TEXT)
        background = rowBg(checked, dp)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun linkRow(a: Activity, dp: (Int) -> Int, label: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            text = label
            textSize = 12f
            setTextColor(C_ACCENT)
            setPadding(dp(2), dp(8), dp(2), dp(4))
            setOnClickListener { onClick() }
        }

    private fun emptyRow(a: Activity, dp: (Int) -> Int, label: String): TextView = TextView(a).apply {
        text = label
        textSize = 11.5f
        setTextColor(C_HINT)
        setPadding(dp(2), dp(6), dp(2), dp(4))
    }

    /** 多选分类：两列 chip，点一次即时反映（父级 rebuild 会重画整块）。 */
    private fun chipWrap(
        a: Activity,
        dp: (Int) -> Int,
        items: List<Pair<String, String>>,
        selected: Set<String>,
        emptyText: String,
        onToggle: (String, Boolean) -> Unit,
    ): View {
        if (items.isEmpty()) return emptyRow(a, dp, emptyText)
        val wrap = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        var line: LinearLayout? = null
        for ((i, item) in items.withIndex()) {
            if (i % 2 == 0) {
                line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(line)
            }
            val on = selected.contains(item.first)
            val cell = TextView(a).apply {
                text = (if (on) "✓ " else "") + item.second
                textSize = 12f
                setTextColor(if (on) 0xFFFFFFFF.toInt() else C_SUB)
                background = chipBg(on, dp)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                    topMargin = dp(6)
                }
                setOnClickListener { onToggle(item.first, !on) }
            }
            line?.addView(cell)
        }
        return wrap
    }

    private fun chipBg(on: Boolean, dp: (Int) -> Int) = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        if (on) {
            setColor(C_ACCENT)
        } else {
            setColor(0xFFF1F4F9.toInt())
            setStroke(dp(1), 0xFFE1E7F0.toInt())
        }
    }

    private fun rowBg(checked: Boolean, dp: (Int) -> Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        if (checked) {
            setColor(0xFFEAF1FF.toInt())
            setStroke(dp(1), 0xFFBFD4FF.toInt())
        } else {
            setColor(Color.TRANSPARENT)
        }
    }

    /**
     * 只取「资产页启用」的条目：世界书/正则看 current 标志，预设/DNA 看 enabled。
     *
     * ★ 主键字段三种都要认：引擎侧资产（世界书/预设/正则）用 `id` / `uid`，
     *   而角色 DNA 存在**原生** [ConnectionStore] 的 `image.dnaList` 里，主键是
     *   `_uid`（与 [ConnectionStore.dnaTags] 比对的是同一个字段）。
     *   以前这里只认 `id`/`uid`，DNA 全部被判成空 id 丢掉，面板上永远显示
     *   「还没有角色 DNA」—— 这就是用户报的「聊天里 DNA 显示不正常」。
     */
    private fun enabledItems(arr: JSONArray?, flag: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val on = if (flag == "enabled") o.optBoolean("enabled", true) else o.optBoolean("current")
            if (!on) continue
            val id = o.optString("_uid")
                .ifBlank { o.optString("uid") }
                .ifBlank { o.optString("id") }
            val name = o.optString("name").ifBlank { "未命名" }
            if (id.isNotEmpty()) out.add(id to name)
        }
        return out
    }
}
