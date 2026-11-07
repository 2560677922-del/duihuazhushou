package com.dbpg.duihuazhushou.assets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
 *   getAssets()                清单（预设/世界书含条目/正则）
 *   command setAssets          快捷切换（传完整目标 id 集合）
 *   command importAsset        导入 ST 的 .json（importAnyJson 统一识别）
 *   command exportAsset        导出 → 原生落盘（ACTION_CREATE_DOCUMENT）
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
        )
        for ((id, t) in tabs) {
            findViewById<TextView>(id).setOnClickListener {
                tab = t
                lastAssets?.let { render(it) } ?: load()
            }
        }
    }

    private fun paintTabs() {
        val active = 0xFF7FB2FF.toInt()
        val idle = 0xFF8FA6C8.toInt()
        val map = mapOf(
            R.id.tab_character to TAB_CHARACTER,
            R.id.tab_preset to TAB_PRESET,
            R.id.tab_worldbook to TAB_WORLDBOOK,
            R.id.tab_regex to TAB_REGEX,
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
                status.text = "共 ${arr.length()} 个预设，点一下启用（再点当前 = 保持）"
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    box.addView(assetRow(p, titleOf = { it.optString("name").ifBlank { "未命名" } },
                        currentOf = { it.optBoolean("current") },
                        currentLabel = "使用中", actionLabel = "启用",
                        onUse = { commandSetAssets(JSONObject().put("presetId", p.optString("id"))) },
                        onExport = { exportAsset(p.optString("id")) }))
                }
                if (arr.length() == 0) box.addView(emptyCard("还没有预设", "点右上角「导入」选 ST 的预设 .json"))
            }
            TAB_REGEX -> {
                val arr = a.optJSONArray("regexGroups") ?: JSONArray()
                status.text = "共 ${arr.length()} 个正则组，点一下切换启用；长按导出"
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    box.addView(assetRow(g, titleOf = { it.optString("name").ifBlank { "未命名" } },
                        currentOf = { it.optBoolean("current") },
                        currentLabel = "已启用", actionLabel = "启用",
                        subOf = { it.optString("name") + " · " + it.optInt("scriptCount") + " 条脚本" },
                        onUse = {
                            val want = JSONArray()
                            val cur = a.optJSONArray("regexGroups") ?: JSONArray()
                            val id = g.optString("id")
                            val turnOn = !g.optBoolean("current")
                            for (j in 0 until cur.length()) {
                                val o = cur.getJSONObject(j)
                                if (o.optBoolean("current") && o.optString("id") != id) want.put(o.optString("id"))
                                if (o.optString("id") == id && turnOn) want.put(id)
                            }
                            commandSetAssets(JSONObject().put("regexGroupIds", want))
                        },
                        onExport = { exportAsset(g.optString("id")) }))
                }
                if (arr.length() == 0) box.addView(emptyCard("还没有正则组", "点右上角「导入」选 ST 的正则 .json"))
            }
            else -> renderWorldBooks(a, box, status)
        }
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
                setTextColor(if (current) 0xFF7FB2FF.toInt() else 0xFFE6EAF2.toInt())
                textSize = 14f
                if (current) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = "${entries.length()} 条"
                setTextColor(0xFF6C7A90.toInt())
                textSize = 11f
            })
            head.addView(TextView(this).apply {
                text = if (open) "▾" else "▸"
                setTextColor(0xFF5E6E85.toInt())
                textSize = 14f
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(head)

            val toggle = TextView(this).apply {
                text = if (current) "停用这本书" else "启用这本书"
                setTextColor(0xFF8FA6C8.toInt())
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
                    setTextColor(0xFFE6EAF2.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(TextView(this).apply {
                    text = if (entryOpen) "▾" else "▸"
                    setTextColor(0xFF5E6E85.toInt())
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
                        setTextColor(0xFF6C7A90.toInt())
                        textSize = 11f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                if (entryOpen) {
                    row.addView(TextView(this).apply {
                        text = e.optString("content").ifBlank { "（空条目）" }
                        setTextColor(0xFFB9C4D6.toInt())
                        textSize = 12.5f
                        setLineSpacing(0f, 1.25f)
                        setPadding(0, dp(8), 0, 0)
                    })
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
            setTextColor(if (current) 0xFF7FB2FF.toInt() else 0xFFE6EAF2.toInt())
            textSize = 14f
            if (current) setTypeface(typeface, Typeface.BOLD)
        })
        val sub = subOf(data)
        if (sub.isNotBlank() && sub != titleOf(data)) {
            col.addView(TextView(this).apply {
                text = sub
                setTextColor(0xFF6C7A90.toInt())
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
        }
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = if (current) currentLabel else actionLabel
            setTextColor(if (current) 0xFF7FB2FF.toInt() else 0xFF6C7A90.toInt())
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
            setTextColor(0xFFE6EAF2.toInt())
            textSize = 15f
        })
        card.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF6C7A90.toInt())
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
            setTextColor(0xFF8FA6C8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = sub
            setTextColor(0xFF4E5A6B.toInt())
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
        const val REQ_IMPORT = 2001
        const val REQ_EXPORT = 2002
    }
}
