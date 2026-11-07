package com.dbpg.duihuazhushou.assets

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.NativeBridge
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.settings.EngineBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 资产页（「资产」tab 落地）：角色 / 预设 / 世界书 / 正则 四类集中在这，
 * 与网页版资产页同构 —— 快捷切换 + 导入导出 + 世界书条目展开阅读。
 *
 * 数据在引擎 assets store（ST 兼容层原样），经 window.__dhpApi 读写：
 *   getAssets()                清单（预设含 prompts / 世界书含 entries / 正则组含 scripts）
 *   command setAssets          快捷切换（传完整目标 id 集合，正则组会同步 group.enabled）
 *   command importAsset        导入 ST 的 .json（importAnyJson 统一识别）
 *   command exportAsset        导出 → 原生落盘（ACTION_CREATE_DOCUMENT）
 *   wb*Entry                   世界书条目增删改
 *   preset*Prompt              预设子条目增删改与**单条启停**（web 侧同步 prompt_order）
 *   regex*Script               正则组内脚本增删改与**单条启停**（写 scripts[].disabled）
 *
 * 三类资产都是「组头展开 → 子条目卡片（快捷开关 + 点开编辑 + 长按删除）」的同构交互。
 * 配置类数据已搬原生 ConnectionStore，但 ST 兼容层（预设/世界书/正则/角色卡）
 * 仍留在引擎，这里不复制一份，避免两边状态打架。
 */
class AssetsHomeActivity : Activity() {

    private var tab = TAB_WORLDBOOK

    /** 世界书/条目展开态。 */
    private val openBooks = mutableSetOf<String>()
    private val openEntries = mutableSetOf<String>()

    private var lastAssets: JSONObject? = null
    private var pendingExportUri: Uri? = null

    private val apiListener = NativeBridge.ApiResultListener { json -> onApiResult(json) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assets_home)
        Insets.applyTop(this)

        bindTabs()
        findViewById<TextView>(R.id.assets_import).setOnClickListener { pickImportFile() }
        findViewById<TextView>(R.id.assets_export).setOnClickListener { exportCurrent() }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setApiResultListener(apiListener)
        load()
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setApiResultListener(null)
    }

    // ------------------------------------------------------------ tab

    private fun bindTabs() {
        val tabs = mapOf(
            R.id.tab_character to TAB_CHARACTER,
            R.id.tab_preset to TAB_PRESET,
            R.id.tab_worldbook to TAB_WORLDBOOK,
            R.id.tab_regex to TAB_REGEX,
            R.id.tab_dna to TAB_DNA,
        )
        for ((id, t) in tabs) {
            findViewById<TextView>(id).setOnClickListener {
                tab = t
                lastAssets?.let { render(it) } ?: load()
            }
        }
    }

    private fun paintTabs() {
        val active = 0xFF2F66D8.toInt()
        val idle = 0xFF5A6B85.toInt()
        val map = mapOf(
            R.id.tab_character to TAB_CHARACTER,
            R.id.tab_preset to TAB_PRESET,
            R.id.tab_worldbook to TAB_WORLDBOOK,
            R.id.tab_regex to TAB_REGEX,
            R.id.tab_dna to TAB_DNA,
        )
        for ((id, t) in map) {
            val v = findViewById<TextView>(id)
            v.setTextColor(if (t == tab) active else idle)
            v.setTypeface(null, if (t == tab) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ------------------------------------------------------------ 数据

    private fun load() {
        EngineBridge.fetch(this, "getAssets") { obj ->
            if (obj == null) {
                findViewById<TextView>(R.id.assets_status).text = "引擎还没就绪，稍等几秒重进"
                return@fetch
            }
            lastAssets = obj
            render(obj)
        }
    }

    private fun render(a: JSONObject) {
        paintTabs()
        val status = findViewById<TextView>(R.id.assets_status)
        val box = findViewById<LinearLayout>(R.id.assets_box)
        box.removeAllViews()

        when (tab) {
            TAB_CHARACTER -> {
                val count = (a.optJSONArray("presets")?.length() ?: 0) // 占位避免误判
                status.text = "角色卡的导入、浏览与启用在原生卡库"
                box.addView(actionCard("🎭  打开角色卡库", "导入 .png / .json 卡 · 分段浏览设定 · 换当前角色") {
                    startActivity(
                        Intent(this, com.dbpg.duihuazhushou.character.CharacterListActivity::class.java),
                    )
                })
            }
            TAB_PRESET -> {
                val arr = a.optJSONArray("presets") ?: JSONArray()
                var total = 0
                for (i in 0 until arr.length()) total += arr.optJSONObject(i)?.optJSONArray("prompts")?.length() ?: 0
                status.text = "共 ${arr.length()} 个预设 / $total 条子条目；点名称展开，每条可单独开关、编辑"
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    renderPreset(box, a, p)
                }
                if (arr.length() == 0) box.addView(emptyCard("还没有预设", "点右上角「导入」选 ST 的预设 .json"))
            }
            TAB_REGEX -> {
                val arr = a.optJSONArray("regexGroups") ?: JSONArray()
                var total = 0
                for (i in 0 until arr.length()) total += arr.optJSONObject(i)?.optJSONArray("scripts")?.length() ?: 0
                status.text = "共 ${arr.length()} 个正则组 / $total 条脚本；点名称展开，逐条开关、编辑"
                for (i in 0 until arr.length()) {
                    renderRegexGroup(box, a, arr.getJSONObject(i))
                }
                if (arr.length() == 0) box.addView(emptyCard("还没有正则组", "点右上角「导入」选 ST 的正则 .json"))
            }
            TAB_DNA -> {
                renderDna(box, status)
                return
            }
            else -> renderWorldBooks(a, box, status)
        }
    }

    // ------------------------------------------------------------ 角色 DNA（英文外观锚定，生图时给 LLM 当人物参考）

    /**
     * DNA 独立于世界书/预设：它是"视觉层"的固定参数（发色瞳色服装等英文标签），
     * 只在生图时生效。每条可单独启用（启用＝对话里可选），对话在「本对话资产」里勾。
     */
    private fun renderDna(box: LinearLayout, status: TextView) {
        // DNA 存在原生配置（ConnectionStore），走本地快照取，不经过 web 引擎
        EngineBridge.fetch(this, "getImageStyle") { obj ->
            if (obj == null) {
                status.text = "读取 DNA 库失败"
                return@fetch
            }
            val arr = obj.optJSONArray("dnaList") ?: JSONArray()
            status.text = "共 ${arr.length()} 条 DNA；生图时把勾选的外观标签拼进提示词（对话里在「本对话资产」勾选）"
            box.removeAllViews()
            box.addView(actionCard("＋ 新建 DNA", "写英文外观标签，如 1girl, silver hair, red eyes") {
                promptEditDna(null)
            })
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                renderDnaCard(box, d)
            }
            if (arr.length() == 0) {
                box.addView(emptyCard("还没有 DNA", "DNA 锚定角色外观，同一角色每张图长一个样"))
            }
        }
    }

    private fun renderDnaCard(box: LinearLayout, d: JSONObject) {
        val uid = d.optString("_uid")
        val name = d.optString("name").ifBlank { "未命名" }
        val tags = d.optString("tags")
        val enabled = d.optBoolean("enabled", true)
        val open = uid in openEntries
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        head.addView(TextView(this).apply {
            text = "🧬 $name"
            setTextColor(if (enabled) 0xFF1A2230.toInt() else 0xFF9AA4B2.toInt())
            textSize = 14f
            setOnClickListener {
                if (uid in openEntries) openEntries.remove(uid) else openEntries.add(uid)
                lastAssets?.let { render(it) } ?: load()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chipToggle(if (enabled) "☑ 启用" else "☐ 停用", enabled) {
            EngineBridge.command(
                JSONObject().put("cmd", "dnaSave").put("uid", uid)
                    .put("name", name).put("enabled", !enabled),
            )
            load()
        })
        head.addView(arrow(open))
        box.addView(head, cardParams())

        if (!open) return
        // 展开后：标签预览 + 编辑/删除
        box.addView(TextView(this).apply {
            text = "外观标签：${tags.ifBlank { "（空）" }}"
            setTextColor(0xFF66707F.toInt())
            textSize = 12f
            setPadding(dp(16), dp(4), dp(16), dp(4))
        })
        box.addView(actionRow("✏️ 编辑") { promptEditDna(d) })
        box.addView(actionRow("🗑️ 删除") {
            AlertDialog.Builder(this)
                .setTitle("删除 DNA")
                .setMessage("「$name」将被删除，不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    EngineBridge.command(JSONObject().put("cmd", "dnaRemove").put("uid", uid))
                    load()
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    /** DNA 编辑器：名称 + 英文外观标签 + 备注。复用通用 editDialog。 */
    private fun promptEditDna(existing: JSONObject?) {
        editDialog(
            if (existing == null) "新建 DNA" else "编辑 DNA",
            listOf(
                "名称" to (existing?.optString("name") ?: ""),
                "外观标签（发色瞳色服装等，逗号分隔）" to (existing?.optString("tags") ?: ""),
                "备注" to (existing?.optString("note") ?: ""),
            ),
        ) { values ->
            val name = values[0].trim()
            if (name.isEmpty()) {
                toast("名字不能为空")
                return@editDialog
            }
            EngineBridge.command(
                JSONObject().put("cmd", "dnaSave")
                    .put("uid", existing?.optString("_uid") ?: "")
                    .put("name", name)
                    .put("tags", values[1])
                    .put("note", values[2])
                    .put("enabled", existing?.optBoolean("enabled", true) ?: true),
            )
            load()
        }
    }

    // ------------------------------------------------------------ 预设（子条目展开/编辑/新建/删除）

    /**
     * 预设：组头可展开；组内每条 prompt 都有**独立的启用开关**，点整行进编辑器，
     * 长按删除。之前这里只有「整组启用 + 点行编辑名称/内容」，用户反馈
     * 「子条目还是没有加单独的开关、点都点不开」，所以重做成与世界书同构的两层卡片。
     *
     * ST 预设的 enabled 有两处权威来源（prompts[].enabled 与 prompt_order 里的
     * order[].enabled），开关命令在 web 侧 syncPromptEnabled 里同步写两处，
     * 原生只发命令，兼容层语义不下沉到这一层。
     */
    private fun renderPreset(box: LinearLayout, a: JSONObject, p: JSONObject) {
        val id = p.optString("id")
        val name = p.optString("name").ifBlank { "未命名" }
        val current = p.optBoolean("current")
        val prompts = p.optJSONArray("prompts") ?: JSONArray()
        val open = openBooks.contains("preset:$id")

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                if (open) openBooks.remove("preset:$id") else openBooks.add("preset:$id")
                lastAssets?.let { render(it) }
            }
            setOnLongClickListener { exportAsset(id); true }
        }
        head.addView(TextView(this).apply {
            text = (if (current) "✓ " else "") + name
            setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
            textSize = 14f
            if (current) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "${prompts.length()} 条"
            setTextColor(0xFF66707F.toInt())
            textSize = 11f
        })
        // 整组启用：点名称只是展开，启用点这里
        head.addView(TextView(this).apply {
            text = if (current) "使用中" else "启用"
            setTextColor(0xFF2F66D8.toInt())
            textSize = 12f
            setTypeface(typeface, if (current) Typeface.BOLD else Typeface.NORMAL)
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                commandSetAssets(JSONObject().put("presetId", if (current) "" else id))
                toast(if (current) "已停用" else "已启用这个预设")
            }
        })
        head.addView(arrow(open))
        box.addView(head, cardParams())

        if (!open) return

        box.addView(actionRow("＋ 新建子条目") {
            EngineBridge.command(JSONObject().put("cmd", "presetAddPrompt").put("presetId", id))
            toast("已新建，展开后可编辑")
        })

        for (j in 0 until prompts.length()) {
            val it0 = prompts.getJSONObject(j)
            val identifier = it0.optString("identifier")
            val enabled = it0.optBoolean("enabled", true)
            val system = it0.optBoolean("system", true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { editPresetPromptDialog(id, it0) }
                setOnLongClickListener { confirmDeletePrompt(id, identifier, it0.optString("name")); true }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(TextView(this).apply {
                text = if (enabled) "🟢 " else "⛔ "
                textSize = 12f
            })
            line.addView(TextView(this).apply {
                text = it0.optString("name").ifBlank { identifier }
                setTextColor(0xFF1A2230.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply { text = "✏️"; textSize = 12f })
            row.addView(line)
            row.addView(TextView(this).apply {
                text = it0.optString("content").ifBlank { "（空内容，点开编辑）" }
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            // 快捷开关：不展开也能一条条关，且不会误触整行的编辑器
            val quick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            quick.addView(chipToggle(
                if (enabled) "☑ 已启用" else "☐ 已停用", enabled,
            ) {
                EngineBridge.command(
                    JSONObject().put("cmd", "presetSavePrompt")
                        .put("presetId", id).put("identifier", identifier)
                        .put("prompt", JSONObject().put("enabled", !enabled)),
                )
            })
            quick.addView(chipToggle(
                if (system) "🔵 系统提示词" else "👤 角色提示词", true,
            ) {
                EngineBridge.command(
                    JSONObject().put("cmd", "presetSavePrompt")
                        .put("presetId", id).put("identifier", identifier)
                        .put("prompt", JSONObject().put("system", !system)),
                )
            })
            row.addView(quick)
            box.addView(row, cardParams())
        }
    }

    private fun confirmDeletePrompt(presetId: String, identifier: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除子条目")
            .setMessage(name.ifBlank { identifier })
            .setPositiveButton("删除") { _, _ ->
                EngineBridge.command(
                    JSONObject().put("cmd", "presetRemovePrompt")
                        .put("presetId", presetId).put("identifier", identifier),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 预设子条目完整编辑器：名称 / 内容 / 角色 / 三个开关，保存走部分更新。 */
    private fun editPresetPromptDialog(presetId: String, p: JSONObject) {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        newLabel(box, "名称")
        val name = newInput(box, p.optString("name"))
        newLabel(box, "内容（支持 {{char}} 等宏）")
        val content = newInput(box, p.optString("content"), multiline = true)
        newLabel(box, "角色")
        val roles = arrayOf("系统 system", "用户 user", "AI assistant")
        val role = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, roles)
            setSelection(
                when (p.optString("role")) {
                    "user" -> 1
                    "assistant" -> 2
                    else -> 0
                },
            )
        }
        box.addView(role)
        newLabel(box, "这条提示词的开关")
        val enabled = newSwitchRow(box, "启用（关掉就不进提示词）", p.optBoolean("enabled", true))
        val system = newSwitchRow(box, "系统提示词 system_prompt", p.optBoolean("system", true))
        val marker = newSwitchRow(box, "标记为 marker", p.optBoolean("marker"))

        AlertDialog.Builder(ctx)
            .setTitle("编辑子条目")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val patch = JSONObject()
                    .put("name", name.text?.toString().orEmpty())
                    .put("content", content.text?.toString().orEmpty())
                    .put(
                        "role", when (role.selectedItemPosition) {
                            1 -> "user"
                            2 -> "assistant"
                            else -> "system"
                        },
                    )
                    .put("enabled", enabled.isChecked)
                    .put("system", system.isChecked)
                    .put("marker", marker.isChecked)
                EngineBridge.command(
                    JSONObject().put("cmd", "presetSavePrompt")
                        .put("presetId", presetId)
                        .put("identifier", p.optString("identifier"))
                        .put("prompt", patch),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 正则组（组内脚本逐条管理）

    /**
     * 正则：与预设同构的两层卡片。组开关沿用 setAssets 的完整 id 集合语义
     * （web 侧会同步 group.enabled），组内每条脚本的开关写 scripts[].disabled。
     */
    private fun renderRegexGroup(box: LinearLayout, a: JSONObject, g: JSONObject) {
        val id = g.optString("id")
        val name = g.optString("name").ifBlank { "未命名" }
        val current = g.optBoolean("current") && g.optBoolean("enabled", true)
        val scripts = g.optJSONArray("scripts") ?: JSONArray()
        val open = openBooks.contains("regex:$id")

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                if (open) openBooks.remove("regex:$id") else openBooks.add("regex:$id")
                lastAssets?.let { render(it) }
            }
            setOnLongClickListener { exportAsset(id); true }
        }
        head.addView(TextView(this).apply {
            text = (if (current) "✓ " else "") + name
            setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
            textSize = 14f
            if (current) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "${scripts.length()} 条"
            setTextColor(0xFF66707F.toInt())
            textSize = 11f
        })
        head.addView(TextView(this).apply {
            text = if (current) "已启用" else "启用"
            setTextColor(0xFF2F66D8.toInt())
            textSize = 12f
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                val want = JSONArray()
                val cur = a.optJSONArray("regexGroups") ?: JSONArray()
                val turnOn = !current
                for (j in 0 until cur.length()) {
                    val o = cur.getJSONObject(j)
                    if (o.optBoolean("current") && o.optString("id") != id) want.put(o.optString("id"))
                    if (o.optString("id") == id && turnOn) want.put(id)
                }
                commandSetAssets(JSONObject().put("regexGroupIds", want))
            }
        })
        head.addView(arrow(open))
        box.addView(head, cardParams())

        if (!open) return

        box.addView(actionRow("＋ 新建正则脚本") {
            EngineBridge.command(JSONObject().put("cmd", "regexAddScript").put("groupId", id))
            toast("已新建（默认关闭，填好正则再打开）")
        })

        for (j in 0 until scripts.length()) {
            val s = scripts.getJSONObject(j)
            val sid = s.optString("id")
            val on = !s.optBoolean("disabled")
            val find = s.optString("findRegex")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { editRegexScriptDialog(id, s) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@AssetsHomeActivity)
                        .setTitle("删除正则脚本")
                        .setMessage(s.optString("name").ifBlank { sid })
                        .setPositiveButton("删除") { _, _ ->
                            EngineBridge.command(
                                JSONObject().put("cmd", "regexRemoveScript")
                                    .put("groupId", id).put("scriptId", sid),
                            )
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(TextView(this).apply {
                text = if (on) "🟢 " else "⛔ "
                textSize = 12f
            })
            line.addView(TextView(this).apply {
                text = s.optString("name").ifBlank { sid }
                setTextColor(0xFF1A2230.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply { text = "✏️"; textSize = 12f })
            row.addView(line)
            row.addView(TextView(this).apply {
                text = buildString {
                    append("查找：").append(find.ifBlank { "（还没填正则）" })
                    val rep = s.optString("replaceString")
                    if (rep.isNotBlank()) append("  →  ").append(rep.take(40))
                }
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            val quick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            quick.addView(chipToggle(if (on) "☑ 生效" else "☐ 停用", on) {
                EngineBridge.command(
                    JSONObject().put("cmd", "regexSaveScript")
                        .put("groupId", id).put("scriptId", sid)
                        .put("script", JSONObject().put("disabled", on)),
                )
            })
            val scope = when {
                s.optBoolean("markdownOnly") && !s.optBoolean("promptOnly") -> "仅显示"
                s.optBoolean("promptOnly") && !s.optBoolean("markdownOnly") -> "仅提示词"
                else -> "显示 + 提示词"
            }
            quick.addView(TextView(this).apply {
                text = "🎯 $scope"
                setTextColor(0xFF5A6B85.toInt())
                textSize = 11f
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
            row.addView(quick)
            box.addView(row, cardParams())
        }
    }

    /** 正则脚本编辑器：ST 常用字段，placement / minDepth 等保持原值不动。 */
    private fun editRegexScriptDialog(groupId: String, s: JSONObject) {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        newLabel(box, "脚本名称")
        val name = newInput(box, s.optString("name"))
        newLabel(box, "查找正则 findRegex")
        val find = newInput(box, s.optString("findRegex"), multiline = true)
        newLabel(box, "替换文本 replaceString（$1 引用捕获组）")
        val replace = newInput(box, s.optString("replaceString"), multiline = true)
        newLabel(box, "作用范围")
        val scopes = arrayOf("显示 + 提示词（两者都作用）", "仅显示 markdownOnly", "仅提示词 promptOnly")
        val scope = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, scopes)
            setSelection(
                when {
                    s.optBoolean("markdownOnly") && !s.optBoolean("promptOnly") -> 1
                    s.optBoolean("promptOnly") && !s.optBoolean("markdownOnly") -> 2
                    else -> 0
                },
            )
        }
        box.addView(scope)
        newLabel(box, "脚本开关")
        val enabled = newSwitchRow(box, "启用这条正则", !s.optBoolean("disabled"))
        val runOnEdit = newSwitchRow(box, "编辑消息时也运行", s.optBoolean("runOnEdit"))
        val substitute = newSwitchRow(box, "替换文本里的 $ 走正则替换", s.optInt("substituteRegex") != 0)

        AlertDialog.Builder(ctx)
            .setTitle("编辑正则脚本")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val patch = JSONObject()
                    .put("name", name.text?.toString().orEmpty())
                    .put("findRegex", find.text?.toString().orEmpty())
                    .put("replaceString", replace.text?.toString().orEmpty())
                    .put("markdownOnly", scope.selectedItemPosition == 1)
                    .put("promptOnly", scope.selectedItemPosition == 2)
                    .put("disabled", !enabled.isChecked)
                    .put("runOnEdit", runOnEdit.isChecked)
                    .put("substituteRegex", if (substitute.isChecked) 1 else 0)
                EngineBridge.command(
                    JSONObject().put("cmd", "regexSaveScript")
                        .put("groupId", groupId)
                        .put("scriptId", s.optString("id"))
                        .put("script", patch),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 卡片通用控件

    /** 展开箭头。 */
    private fun arrow(open: Boolean): TextView = TextView(this).apply {
        text = if (open) "▾" else "▸"
        setTextColor(0xFF8A94A6.toInt())
        textSize = 14f
        setPadding(dp(8), 0, 0, 0)
    }

    /** 卡片间距：新建 View 还没 parent，必须在 addView 时传 LayoutParams 才生效。 */
    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }

    /** 行内快捷开关 chip（带自己的点击，不冒泡到整行编辑器）。 */
    private fun chipToggle(label: String, on: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (on) 0xFF2F66D8.toInt() else 0xFF8A94A6.toInt())
            textSize = 11f
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun newLabel(box: LinearLayout, t: String) {
        box.addView(TextView(this).apply {
            text = t
            setTextColor(0xFF5A6B85.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun newInput(box: LinearLayout, value: String, multiline: Boolean = false): EditText =
        EditText(this).apply {
            setText(value)
            setTextSize(13f)
            setTextColor(0xFF1A2230.toInt())
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (multiline) {
                minLines = 4
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }.also { box.addView(it) }

    private fun newSwitchRow(box: LinearLayout, text: String, checked: Boolean): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            setText(text)
            setTextColor(0xFF1A2230.toInt())
            textSize = 13f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = checked }
        row.addView(sw)
        box.addView(row)
        return sw
    }

    private fun actionRow(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            setTextColor(0xFF2F66D8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_soft)
            setPadding(0, dp(10), 0, dp(10))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
            setOnClickListener { onClick() }
        }

    /**
     * 通用编辑对话框：labels/values 成对出现，最后一个当大输入框。
     * 确认后回调 values（与 labels 同序），命令由调用方发。
     */
    private fun editDialog(
        title: String,
        fields: List<Pair<String, String>>,
        onConfirm: (List<String>) -> Unit,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val inputs = fields.map { (label, value) ->
            box.addView(TextView(this).apply {
                text = label
                setTextColor(0xFF5A6B85.toInt())
                textSize = 12f
                setPadding(0, dp(10), 0, dp(2))
            })
            EditText(this).apply {
                setText(value)
                setTextSize(13f)
                setTextColor(0xFF1A2230.toInt())
                background = getDrawable(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                if (label == "内容") minLines = 6
                box.addView(this)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("保存") { _, _ -> onConfirm(inputs.map { it.text?.toString() ?: "" }) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 世界书条目完整编辑器：ST 的触发字段全集。
     *
     * 覆盖：标题/备注、主关键词、次要关键词、内容、常驻（蓝灯）、
     * 选择性触发 + 触发逻辑、启用、概率 + 启用概率、深度、插入位置、角色。
     * 这些字段引擎（assembler）已经在用，这里补齐的是"能改"。
     */
    private fun editEntryDialog(bookId: String, e: JSONObject) {
        fun keysOf(arr: JSONArray?): String =
            (0 until (arr?.length() ?: 0)).joinToString(",") { arr!!.optString(it) }

        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        fun label(t: String) {
            box.addView(TextView(ctx).apply {
                text = t
                setTextColor(0xFF5A6B85.toInt())
                textSize = 12f
                setPadding(0, dp(10), 0, dp(2))
            })
        }
        fun input(value: String, multiline: Boolean = false, numeric: Boolean = false): EditText =
            EditText(ctx).apply {
                setText(value)
                setTextSize(13f)
                setTextColor(0xFF1A2230.toInt())
                background = getDrawable(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                if (multiline) minLines = 5
                inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                else if (multiline) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else android.text.InputType.TYPE_CLASS_TEXT
            }.also { box.addView(it) }

        label("标题 / 备注")
        val comment = input(e.optString("comment"))
        label("主关键词（逗号分隔，命中即触发）")
        val keys = input(keysOf(e.optJSONArray("keys")))
        label("次要关键词（选择性触发时与主关键词配合）")
        val secondary = input(keysOf(e.optJSONArray("secondary")))
        label("内容")
        val content = input(e.optString("content"), multiline = true)

        fun switchRow(text: String, checked: Boolean): Switch {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(ctx).apply {
                setText(text)
                setTextColor(0xFF1A2230.toInt())
                textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(ctx).apply { isChecked = checked }
            row.addView(sw)
            box.addView(row)
            return sw
        }

        label("触发方式")
        val constant = switchRow("常驻（蓝灯：不靠关键词，一直注入）", e.optBoolean("constant"))
        val enabled = switchRow("启用此条目", e.optBoolean("enabled", true))

        label("选择性触发逻辑（仅次要关键词生效时）")
        val logicNames = arrayOf("与任意一个（AND ANY）", "非所有（NOT ALL）", "非任何（NOT ANY）", "与所有（AND ALL）")
        val logic = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, logicNames,
            )
            setSelection((e.optInt("selectiveLogic", 0)).coerceIn(0, 3))
        }
        box.addView(logic)

        label("插入位置")
        val posNames = arrayOf(
            "角色定义之前", "角色定义之后", "示例消息之前", "示例消息之后",
            "聊天记录顶部", "聊天记录底部", "@D 按深度插入",
        )
        val position = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, posNames,
            )
            setSelection((e.optInt("position", 0)).coerceIn(0, 6))
        }
        box.addView(position)

        label("插入深度（position=@D 时生效，0=最后一条）")
        val depth = input(e.optInt("depth", 4).toString(), numeric = true)

        label("角色（该条以谁的身份插入）")
        val roleNames = arrayOf("默认（系统）", "系统", "用户", "AI")
        val role = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, roleNames,
            )
            setSelection((e.optInt("role", -1) + 1).coerceIn(0, 3))
        }
        box.addView(role)

        label("触发概率（%）")
        val probability = input(e.optInt("probability", 100).toString(), numeric = true)
        val useProbability = switchRow("启用概率（按概率决定是否触发）", e.optBoolean("useProbability"))

        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }

        AlertDialog.Builder(ctx)
            .setTitle("编辑条目")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val entry = JSONObject()
                    .put("comment", comment.text?.toString() ?: "")
                    .put("keys", keys.text?.toString() ?: "")
                    .put("secondary", secondary.text?.toString() ?: "")
                    .put("content", content.text?.toString() ?: "")
                    .put("constant", constant.isChecked)
                    .put("enabled", enabled.isChecked)
                    .put("selectiveLogic", logic.selectedItemPosition)
                    .put("position", position.selectedItemPosition)
                    .put("depth", depth.text?.toString()?.toIntOrNull() ?: 4)
                    .put("role", role.selectedItemPosition - 1)
                    .put("probability", probability.text?.toString()?.toIntOrNull() ?: 100)
                    .put("useProbability", useProbability.isChecked)
                EngineBridge.command(
                    JSONObject()
                        .put("cmd", "wbSaveEntry")
                        .put("bookId", bookId)
                        .put("uid", e.optInt("uid"))
                        .put("entry", entry),
                )
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 世界书

    private fun renderWorldBooks(a: JSONObject, box: LinearLayout, status: TextView) {
        val arr = a.optJSONArray("worldBooks") ?: JSONArray()
        status.text = "共 ${arr.length()} 本世界书，点书名展开条目；长按导出"
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val id = b.optString("id")
            val name = b.optString("name").ifBlank { "未命名" }
            val current = b.optBoolean("current")
            val entries = b.optJSONArray("entries") ?: JSONArray()
            val open = openBooks.contains(id)

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                setOnClickListener {
                    if (open) openBooks.remove(id) else openBooks.add(id)
                    lastAssets?.let { render(it) }
                }
            }
            head.addView(TextView(this).apply {
                text = (if (current) "✓ " else "") + name
                setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
                textSize = 14f
                if (current) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = "${entries.length()} 条"
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
            })
            head.addView(TextView(this).apply {
                text = if (open) "▾" else "▸"
                setTextColor(0xFF8A94A6.toInt())
                textSize = 14f
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(head)

            val toggle = TextView(this).apply {
                text = if (current) "停用这本书" else "启用这本书"
                setTextColor(0xFF5A6B85.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.bg_btn_soft)
                setPadding(0, dp(9), 0, dp(9))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
                setOnClickListener {
                    val want = JSONArray()
                    val cur = a.optJSONArray("worldBooks") ?: JSONArray()
                    for (j in 0 until cur.length()) {
                        val o = cur.getJSONObject(j)
                        if (o.optBoolean("current") && o.optString("id") != id) want.put(o.optString("id"))
                    }
                    if (!current) want.put(id)
                    commandSetAssets(JSONObject().put("worldBookIds", want))
                }
            }
            box.addView(toggle)

        if (!open) continue

        // 新建条目
        box.addView(
            actionRow("＋ 新建条目") {
                EngineBridge.command(
                    JSONObject().put("cmd", "wbAddEntry").put("bookId", id),
                )
                openEntries.add("$id:${Int.MAX_VALUE}") // 无意义占位，刷新后看得到新条目
                lastAssets?.let { render(it) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                val ekey = "$id:${e.optInt("uid")}"
                val entryOpen = openEntries.contains(ekey)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = getDrawable(R.drawable.bg_btn_soft)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
                }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(TextView(this).apply {
                    text = (if (e.optBoolean("constant")) "🔵 " else "🟢 ") + if (!e.optBoolean("enabled")) "⛔" else ""
                    textSize = 12f
                })
                line.addView(TextView(this).apply {
                    text = e.optString("comment").ifBlank {
                        val keys = e.optJSONArray("keys") ?: JSONArray()
                        (0 until keys.length()).joinToString("、") { keys.optString(it) }.ifBlank { "(无标题条目)" }
                    }
                    setTextColor(0xFF1A2230.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(TextView(this).apply {
                    text = if (entryOpen) "▾" else "▸"
                    setTextColor(0xFF8A94A6.toInt())
                    textSize = 13f
                })
                row.addView(line)

                val keys = e.optJSONArray("keys") ?: JSONArray()
                val sec = e.optJSONArray("secondary") ?: JSONArray()
                if (keys.length() > 0 || sec.length() > 0) {
                    row.addView(TextView(this).apply {
                        text = buildString {
                            append("关键词：")
                            append((0 until keys.length()).joinToString("、") { keys.optString(it) })
                            if (sec.length() > 0) {
                                append("（次要：")
                                append((0 until sec.length()).joinToString("、") { sec.optString(it) })
                                append("）")
                            }
                        }
                        setTextColor(0xFF66707F.toInt())
                        textSize = 11f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                // 快捷开关：展开前也能直接切换「启用」与「常驻/触发」
                val quick = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                }
                quick.addView(TextView(this).apply {
                    text = if (e.optBoolean("enabled")) "☑ 已启用" else "☐ 已停用"
                    setTextColor(if (e.optBoolean("enabled")) 0xFF2F66D8.toInt() else 0xFF8A94A6.toInt())
                    textSize = 11f
                    background = getDrawable(R.drawable.bg_btn_soft)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setOnClickListener {
                        EngineBridge.command(
                            JSONObject().put("cmd", "wbSaveEntry").put("bookId", id)
                                .put("uid", e.optInt("uid"))
                                .put("entry", JSONObject().put("enabled", !e.optBoolean("enabled"))),
                        )
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                quick.addView(TextView(this).apply {
                    text = if (e.optBoolean("constant")) "🔵 常驻" else "🟢 关键词触发"
                    setTextColor(0xFF2F66D8.toInt())
                    textSize = 11f
                    background = getDrawable(R.drawable.bg_btn_soft)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setOnClickListener {
                        EngineBridge.command(
                            JSONObject().put("cmd", "wbSaveEntry").put("bookId", id)
                                .put("uid", e.optInt("uid"))
                                .put("entry", JSONObject().put("constant", !e.optBoolean("constant"))),
                        )
                    }
                })
                row.addView(quick)

                if (entryOpen) {
                    row.addView(TextView(this).apply {
                        text = e.optString("content").ifBlank { "（空条目）" }
                        setTextColor(0xFF3A4353.toInt())
                        textSize = 12.5f
                        setLineSpacing(0f, 1.25f)
                        setPadding(0, dp(8), 0, 0)
                    })
                    // 编辑 / 删除：展开态下的操作行
                    val ops = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                    }
                    ops.addView(TextView(this).apply {
                        text = "✏️ 编辑"
                        setTextColor(0xFF2F66D8.toInt())
                        textSize = 12f
                        background = getDrawable(R.drawable.bg_btn_soft)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, dp(8))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 })
                    ops.addView(TextView(this).apply {
                        text = "🗑️ 删除"
                        setTextColor(0xFFC24141.toInt())
                        textSize = 12f
                        background = getDrawable(R.drawable.bg_btn_soft)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, dp(8))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    ops.getChildAt(0).setOnClickListener {
                        editEntryDialog(id, e)
                    }
                    ops.getChildAt(1).setOnClickListener {
                        AlertDialog.Builder(this@AssetsHomeActivity)
                            .setTitle("删除条目")
                            .setMessage(e.optString("comment").ifBlank { "uid ${e.optInt("uid")}" })
                            .setPositiveButton("删除") { _, _ ->
                                EngineBridge.command(
                                    JSONObject()
                                        .put("cmd", "wbRemoveEntry")
                                        .put("bookId", id)
                                        .put("uid", e.optInt("uid")),
                                )
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    row.addView(ops)
                }
                row.setOnClickListener {
                    if (entryOpen) openEntries.remove(ekey) else openEntries.add(ekey)
                    lastAssets?.let { render(it) }
                }
                box.addView(row)
            }
        }
        if (arr.length() == 0) box.addView(emptyCard("还没有世界书", "点右上角「导入」选 ST 的世界书 .json"))
    }

    // ------------------------------------------------------------ 通用控件

    private fun assetRow(
        data: JSONObject,
        titleOf: (JSONObject) -> String,
        currentOf: (JSONObject) -> Boolean,
        currentLabel: String,
        actionLabel: String,
        subOf: (JSONObject) -> String = { "" },
        onUse: () -> Unit,
        onExport: () -> Unit,
    ): View {
        val current = currentOf(data)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            setOnClickListener { onUse() }
            setOnLongClickListener { onExport(); true }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = (if (current) "✓ " else "") + titleOf(data)
            setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF1A2230.toInt())
            textSize = 14f
            if (current) setTypeface(typeface, Typeface.BOLD)
        })
        val sub = subOf(data)
        if (sub.isNotBlank() && sub != titleOf(data)) {
            col.addView(TextView(this).apply {
                text = sub
                setTextColor(0xFF66707F.toInt())
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
        }
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = if (current) currentLabel else actionLabel
            setTextColor(if (current) 0xFF2F66D8.toInt() else 0xFF66707F.toInt())
            textSize = 12f
        })
        return card
    }

    private fun actionCard(title: String, sub: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { onClick() }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF1A2230.toInt())
            textSize = 15f
        })
        card.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF66707F.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    private fun emptyCard(title: String, sub: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(16), dp(14), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF5A6B85.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF9AA4B2.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    // ------------------------------------------------------------ 导入 / 导出

    private fun pickImportFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            startActivityForResult(intent, REQ_IMPORT)
        } catch (t: Throwable) {
            toast("打不开文件选择器：${t.message}")
        }
    }

    /** 导出当前 tab 正在使用的那个资产（世界书/预设取 current，正则取全部已启用的第一个）。 */
    private fun exportCurrent() {
        val a = lastAssets ?: run { toast("还没读到资产数据"); return }
        val id: String? = when (tab) {
            TAB_PRESET -> firstId(a.optJSONArray("presets")) { it.optBoolean("current") }
            TAB_WORLDBOOK -> firstId(a.optJSONArray("worldBooks")) { it.optBoolean("current") }
            TAB_REGEX -> firstId(a.optJSONArray("regexGroups")) { it.optBoolean("current") }
            else -> null
        }
        if (id == null) {
            toast("当前分类没有正在使用的资产")
            return
        }
        exportAsset(id)
    }

    private fun firstId(arr: JSONArray?, pred: (JSONObject) -> Boolean): String? {
        arr ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (pred(o)) return o.optString("id")
        }
        return null
    }

    private fun exportAsset(id: String) {
        EngineBridge.command(JSONObject().put("cmd", "exportAsset").put("id", id))
    }

    private fun createExportFile(name: String) {
        try {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "$safe.json")
            }
            startActivityForResult(intent, REQ_EXPORT)
        } catch (t: Throwable) {
            toast("打不开保存对话框：${t.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_IMPORT -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (t: Throwable) {
                    toast("读取文件失败：${t.message}"); return
                }
                if (text.isBlank()) { toast("文件是空的"); return }
                toast("导入中…")
                EngineBridge.command(JSONObject().put("cmd", "importAsset").put("raw", text))
            }
            REQ_EXPORT -> {
                val json = pendingExportJson
                if (json.isNullOrEmpty()) { toast("没有可写的内容"); return }
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    toast("已导出")
                } catch (t: Throwable) {
                    toast("写文件失败：${t.message}")
                } finally {
                    pendingExportJson = null
                }
            }
        }
        pendingExportUri = null
    }

    private var pendingExportJson: String? = null

    // ------------------------------------------------------------ 结果回调

    private fun onApiResult(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("kind")) {
                "setAssets" -> {
                    if (obj.optBoolean("ok")) load() else toast("切换失败：${obj.optString("error")}")
                }
                "wbSaveEntry", "wbAddEntry", "wbRemoveEntry",
                "presetSavePrompt", "presetAddPrompt", "presetRemovePrompt",
                "regexSaveScript", "regexAddScript", "regexRemoveScript" -> {
                    if (obj.optBoolean("ok")) load()
                    else toast("操作失败：${obj.optString("error")}")
                }
                "importAsset" -> {
                    if (obj.optBoolean("ok")) {
                        toast("已导入 ${obj.optInt("imported", 0)} 项")
                        load()
                    } else {
                        toast("导入失败：${obj.optString("error")}")
                    }
                }
                "exportAsset" -> {
                    if (obj.optBoolean("ok")) {
                        pendingExportJson = obj.optString("json")
                        createExportFile(obj.optString("name").ifBlank { "asset" })
                    } else {
                        toast("导出失败：${obj.optString("error")}")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun commandSetAssets(body: JSONObject) {
        EngineBridge.command(body.put("cmd", "setAssets"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        const val TAB_CHARACTER = "character"
        const val TAB_PRESET = "preset"
        const val TAB_WORLDBOOK = "worldbook"
        const val TAB_REGEX = "regex"
        const val TAB_DNA = "dna"
        const val REQ_IMPORT = 2001
        const val REQ_EXPORT = 2002
    }
}
