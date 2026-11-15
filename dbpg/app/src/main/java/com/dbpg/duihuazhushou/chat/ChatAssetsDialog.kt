package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.app.AlertDialog
import com.dbpg.duihuazhushou.character.PersonaStore
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.session.SessionBindingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「本对话资产」配置：世界书 / 预设 / 正则组 / 角色卡 / 用户角色 / DNA，按对话各选各的。
 *
 * ## 语义（与资产页的关系）
 *   - 资产页的「启用」＝**这个资产在对话里可不可选**（可见性基准）；
 *   - 本对话勾选的集合才是这次生成真正用的；生效＝勾选 ∩ 资产页启用；
 *   - 资产页停用/删除后该项自动失效（读不到就当没选），再启用回来勾选还在。
 *
 * 世界书只管「用不用这本书」——书内条目怎么改、怎么触发全归书自己，
 * 绑定只存 id，换内容不需要重新配置对话。
 *
 * 没配置过的对话（绑定全空）跟随全局启用项。
 *
 * 角色卡与用户角色是后加的：单人聊天以前没有选卡入口，空会话会被自动套上
 * 「网页当前角色」，用户想换只能去资产页改全局。现在对话自己记，
 * 生成时按请求带，web 不再回退全局（见 ChatController.beginGen 的 noCharacter）。
 */
object ChatAssetsDialog {

    fun show(activity: Activity, sessionId: String, onSaved: () -> Unit = {}) {
        EngineBridge.fetch(activity, "getAssets") { a ->
            if (a == null) return@fetch
            val binding = SessionBindingStore.load(activity, sessionId)
            val entries = buildMainList(activity, a, binding)
            AlertDialog.Builder(activity)
                .setTitle("本对话资产（一次配置长期留存）")
                .setItems(entries.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> pickWorldBooks(activity, sessionId, a, onSaved)
                        1 -> pickPreset(activity, sessionId, a, onSaved)
                        2 -> pickRegexGroups(activity, sessionId, a, onSaved)
                        3 -> pickCharacter(activity, sessionId, a, onSaved)
                        4 -> pickPersona(activity, sessionId, onSaved)
                        else -> pickDna(activity, sessionId, onSaved)
                    }
                }
                .setNeutralButton("全部恢复跟随全局") { _, _ ->
                    SessionBindingStore.save(activity, sessionId, SessionBindingStore.Binding())
                    onSaved()
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun buildMainList(
        activity: Activity,
        a: JSONObject,
        b: SessionBindingStore.Binding,
    ): List<String> {
        val wbCount = a.optJSONArray("worldBooks")?.length() ?: 0
        val rxCount = a.optJSONArray("regexGroups")?.length() ?: 0
        val dnaCount = ConnectionStore.snapshot("getImageStyle")?.optJSONArray("dnaList")?.length() ?: 0
        fun mark(configured: Boolean) = if (configured) "✓" else "·"
        val charName = b.characterName?.takeIf { it.isNotBlank() }
        val personaName = b.personaId?.let { id ->
            PersonaStore.list(activity).firstOrNull { it.id == id }?.name
        }
        return listOf(
            "${mark(b.worldBookIds != null)} 世界书（可多选，共 $wbCount 本启用）",
            "${mark(b.presetId != null)} 预设（单选）",
            "${mark(b.regexGroupIds != null)} 正则组（可多选，共 $rxCount 组启用）",
            "${mark(b.characterId != null)} 角色卡（AI 扮演谁${if (charName != null) "：$charName" else ""}）",
            "${mark(b.personaId != null)} 用户角色（我在这故事里是谁${if (personaName != null) "：$personaName" else ""}）",
            "${mark(b.dnaIds != null)} 角色 DNA（可多选，共 $dnaCount 条启用）",
        )
    }

    // ------------------------------------------------------------ 世界书

    private fun pickWorldBooks(
        activity: Activity,
        sessionId: String,
        a: JSONObject,
        onSaved: () -> Unit,
    ) {
        val books = enabledItems(a.optJSONArray("worldBooks"), "current")
        if (books.isEmpty()) {
            warnEmpty(activity)
            return
        }
        val names = Array(books.size) { i -> books[i].second }
        val b = SessionBindingStore.load(activity, sessionId)
        // 没配置过＝全部勾上（跟随全局）；配置过＝按绑定来
        val checked = BooleanArray(books.size) { i ->
            b.worldBookIds?.contains(books[i].first) ?: true
        }
        AlertDialog.Builder(activity)
            .setTitle("本对话引用哪些世界书？")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val ids = books.filterIndexed { i, _ -> checked[i] }.map { it.first }
                SessionBindingStore.save(
                    activity, sessionId,
                    SessionBindingStore.load(activity, sessionId).copy(worldBookIds = ids),
                )
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 预设

    private fun pickPreset(
        activity: Activity,
        sessionId: String,
        a: JSONObject,
        onSaved: () -> Unit,
    ) {
        val presets = enabledItems(a.optJSONArray("presets"), "enabled")
        if (presets.isEmpty()) {
            warnEmpty(activity)
            return
        }
        val labels = arrayOf("跟随全局默认") + presets.map { it.second }.toTypedArray()
        val b = SessionBindingStore.load(activity, sessionId)
        val current = (b.presetId?.let { id -> presets.indexOfFirst { it.first == id } } ?: -1) + 1
        AlertDialog.Builder(activity)
            .setTitle("本对话用哪个预设？")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SessionBindingStore.save(
                    activity, sessionId,
                    SessionBindingStore.load(activity, sessionId)
                        .copy(presetId = if (which == 0) "" else presets[which - 1].first),
                )
                dialog.dismiss()
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 正则组

    private fun pickRegexGroups(
        activity: Activity,
        sessionId: String,
        a: JSONObject,
        onSaved: () -> Unit,
    ) {
        val groups = enabledItems(a.optJSONArray("regexGroups"), "current")
        if (groups.isEmpty()) {
            warnEmpty(activity)
            return
        }
        val names = Array(groups.size) { i -> groups[i].second }
        val b = SessionBindingStore.load(activity, sessionId)
        val checked = BooleanArray(groups.size) { i ->
            b.regexGroupIds?.contains(groups[i].first) ?: true
        }
        AlertDialog.Builder(activity)
            .setTitle("本对话启用哪些正则组？")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val ids = groups.filterIndexed { i, _ -> checked[i] }.map { it.first }
                SessionBindingStore.save(
                    activity, sessionId,
                    SessionBindingStore.load(activity, sessionId).copy(regexGroupIds = ids),
                )
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 角色 / 用户角色

    /**
     * 快捷入口：只弹角色这一层（⋯ 菜单里直接可达）。
     *
     * 单人聊天以前没有选卡的地方 —— 新建会话会被自动套上「网页当前角色」，
     * 用户想换只能回资产页改全局再回来。现在对话自己记，换对话各归各。
     */
    fun showCharacter(activity: Activity, sessionId: String, onSaved: () -> Unit = {}) {
        EngineBridge.fetch(activity, "getAssets") { a ->
            if (a == null) return@fetch
            AlertDialog.Builder(activity)
                .setTitle("本对话的角色")
                .setItems(
                    arrayOf(
                        "AI 扮演谁（角色卡）",
                        "我在这故事里是谁（用户角色）",
                    ),
                ) { _, which ->
                    if (which == 0) pickCharacter(activity, sessionId, a, onSaved)
                    else pickPersona(activity, sessionId, onSaved)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 角色卡单选。空会话不再自动套全局卡，这里选谁就是谁。 */
    private fun pickCharacter(
        activity: Activity,
        sessionId: String,
        a: JSONObject,
        onSaved: () -> Unit,
    ) {
        val chars = ArrayList<Pair<String, String>>()
        a.optJSONArray("characters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) {
                    chars.add(id to o.optString("name").ifBlank { "未命名角色" })
                }
            }
        }
        if (chars.isEmpty()) {
            warnEmpty(activity)
            return
        }
        val labels = arrayOf("不指定（只按预设 / 世界书演）") + chars.map { it.second }
        val b = SessionBindingStore.load(activity, sessionId)
        val current = (b.characterId?.let { id -> chars.indexOfFirst { it.first == id } } ?: -1) + 1
        AlertDialog.Builder(activity)
            .setTitle("这个对话由谁扮演？")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val old = SessionBindingStore.load(activity, sessionId)
                val next = if (which == 0) {
                    old.copy(characterId = null, characterName = null)
                } else {
                    old.copy(characterId = chars[which - 1].first, characterName = chars[which - 1].second)
                }
                SessionBindingStore.save(activity, sessionId, next)
                dialog.dismiss()
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用户角色单选（只影响 {{user}} 名字与人设块）。 */
    private fun pickPersona(
        activity: Activity,
        sessionId: String,
        onSaved: () -> Unit,
    ) {
        val personas = PersonaStore.list(activity)
        if (personas.isEmpty()) {
            AlertDialog.Builder(activity)
                .setMessage("还没有用户角色。去「资产 → 我的角色」建一个，再回来选。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val globalName = PersonaStore.active(activity)?.name ?: "未设置"
        val labels = arrayOf("跟随全局（$globalName）") + personas.map { it.name }
        val b = SessionBindingStore.load(activity, sessionId)
        val current = (b.personaId?.let { id -> personas.indexOfFirst { it.id == id } } ?: -1) + 1
        AlertDialog.Builder(activity)
            .setTitle("你在这个对话里是谁？")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val next = if (which == 0) {
                    SessionBindingStore.load(activity, sessionId).copy(personaId = null)
                } else {
                    SessionBindingStore.load(activity, sessionId)
                        .copy(personaId = personas[which - 1].id)
                }
                SessionBindingStore.save(activity, sessionId, next)
                dialog.dismiss()
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 角色 DNA

    private fun pickDna(activity: Activity, sessionId: String, onSaved: () -> Unit) {
        val obj = ConnectionStore.snapshot("getImageStyle") ?: return
        val arr = obj.optJSONArray("dnaList") ?: JSONArray()
        val items = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            if (!d.optBoolean("enabled", true)) continue
            items.add(d.optString("_uid") to d.optString("name"))
        }
        if (items.isEmpty()) {
            warnEmpty(activity)
            return
        }
        val names = Array(items.size) { i -> items[i].second }
        val b = SessionBindingStore.load(activity, sessionId)
        val checked = BooleanArray(items.size) { i -> b.dnaIds?.contains(items[i].first) ?: true }
        AlertDialog.Builder(activity)
            .setTitle("本对话用哪些角色 DNA？（生图时生效）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val ids = items.filterIndexed { i, _ -> checked[i] }.map { it.first }
                SessionBindingStore.save(
                    activity, sessionId,
                    SessionBindingStore.load(activity, sessionId).copy(dnaIds = ids),
                )
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 工具

    /** 只取「资产页启用」的条目：世界书/正则看 current 标志，预设/DNA 看 enabled。 */
    private fun enabledItems(arr: JSONArray?, flag: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val on = if (flag == "enabled") o.optBoolean("enabled", true) else o.optBoolean("current")
            if (!on) continue
            val id = o.optString("id").ifBlank { o.optString("uid") }
            val name = o.optString("name").ifBlank { "未命名" }
            if (id.isNotEmpty()) out.add(id to name)
        }
        return out
    }

    private fun warnEmpty(activity: Activity) {
        AlertDialog.Builder(activity)
            .setMessage("资产页里还没有启用的条目，先去资产页启用再来配置。")
            .setPositiveButton("知道了", null)
            .show()
    }
}
