package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.app.AlertDialog
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.duihuazhushou.session.SessionBindingStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「本对话资产」配置：世界书 / 预设 / 正则组 / 角色 DNA，按对话各选各的。
 *
 * ## 语义（与资产页的关系）
 *   - 资产页的「启用」＝**这个资产在对话里可不可选**（可见性基准）；
 *   - 本对话勾选的集合才是这次生成真正用的；生效＝勾选 ∩ 资产页启用；
 *   - 资产页停用/删除后该项自动失效（读不到就当没选），再启用回来勾选还在。
 *
 * 世界书只管「用不用这本书」——书内条目怎么改、怎么触发全归书自己，
 * 绑定只存 id，换内容不需要重新配置对话。
 *
 * 没配置过的对话（绑定全空）跟随全局启用项，老行为不变。
 */
object ChatAssetsDialog {

    fun show(activity: Activity, sessionId: String, onSaved: () -> Unit = {}) {
        EngineBridge.fetch(activity, "getAssets") { a ->
            if (a == null) return@fetch
            val binding = SessionBindingStore.load(activity, sessionId)
            val entries = buildMainList(a, binding)
            AlertDialog.Builder(activity)
                .setTitle("本对话资产（一次配置长期留存）")
                .setItems(entries.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> pickWorldBooks(activity, sessionId, a, onSaved)
                        1 -> pickPreset(activity, sessionId, a, onSaved)
                        2 -> pickRegexGroups(activity, sessionId, a, onSaved)
                        else -> pickDna(activity, sessionId, onSaved)
                    }
                }
                .setNeutralButton("恢复跟随全局") { _, _ ->
                    SessionBindingStore.save(activity, sessionId, SessionBindingStore.Binding())
                    onSaved()
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun buildMainList(a: JSONObject, b: SessionBindingStore.Binding): List<String> {
        val wbCount = a.optJSONArray("worldBooks")?.length() ?: 0
        val rxCount = a.optJSONArray("regexGroups")?.length() ?: 0
        val dnaCount = ConnectionStore.snapshot("getImageStyle")?.optJSONArray("dnaList")?.length() ?: 0
        fun mark(configured: Boolean) = if (configured) "✓" else "·"
        return listOf(
            "${mark(b.worldBookIds != null)} 世界书（可多选，共 $wbCount 本启用）",
            "${mark(b.presetId != null)} 预设（单选）",
            "${mark(b.regexGroupIds != null)} 正则组（可多选，共 $rxCount 组启用）",
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
