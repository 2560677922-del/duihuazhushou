package com.dbpg.duihuazhushou.engine

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.GenBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 引擎设置：**只负责"启用哪个"**。
 *
 * 预设、世界书、正则、角色卡的内容全都以 SillyTavern 兼容格式存在引擎侧；
 * 原生这边不复制一份，只把"我要用这个"的指令发过去（[GenBridge.setActive]）。
 *
 * 这么分的理由很实际：
 *   - 预设经常几万字、世界书几十条，复制进原生就要处理"两边改了两边不一致"；
 *   - 用户在网页资产页改完设置，回原生这里是同一个真相，不用同步；
 *   - 原生只存 id（在 store 里），换设备/重装后重新选一次即可。
 *
 * 界面刻意做得像"勾选清单"而不是编辑器 —— 编辑仍在网页资产页做。
 */
class EngineSettingsActivity : Activity() {

    private lateinit var presetsBox: LinearLayout
    private lateinit var charactersBox: LinearLayout
    private lateinit var worldBooksBox: LinearLayout
    private lateinit var hint: TextView

    private var catalog: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_settings)
        Insets.applyTop(this)

        presetsBox = findViewById(R.id.engine_presets)
        charactersBox = findViewById(R.id.engine_characters)
        worldBooksBox = findViewById(R.id.engine_worldbooks)
        hint = findViewById(R.id.engine_hint)

        findViewById<TextView>(R.id.engine_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.engine_reload).setOnClickListener { load() }

        load()
    }

    private fun load() {
        hint.text = "正在读取引擎资产…"
        GenBridge.fetchAssetCatalog { json ->
            if (isFinishing || isDestroyed) return@fetchAssetCatalog
            val obj = runCatching { JSONObject(json ?: "{}") }.getOrNull()
            if (obj == null || !obj.has("presets")) {
                hint.text = "引擎还没准备好（网页未加载完），稍后点右上角「重载」"
                return@fetchAssetCatalog
            }
            catalog = obj
            render()
        }
    }

    private fun render() {
        val c = catalog ?: return
        val cur = c.optJSONObject("current") ?: JSONObject()
        val presetId = cur.optString("presetId", "")
        val charId = cur.optString("characterId", "")
        val wbIds = cur.optJSONArray("worldBookIds").toStringSet()

        val presets = c.optJSONArray("presets") ?: JSONArray()
        val chars = c.optJSONArray("characters") ?: JSONArray()
        val books = c.optJSONArray("worldBooks") ?: JSONArray()

        hint.text = when {
            presets.length() == 0 -> "还没有导入预设。请先在网页「资产」页导入一个 ST 预设 JSON。"
            chars.length() == 0 -> "还没有角色卡。可以先在网页「角色」页导入。"
            else -> "共 ${presets.length()} 个预设 · ${chars.length()} 张角色卡 · ${books.length()} 本世界书"
        }

        fill(presetsBox, presets, single = true, selected = setOf(presetId)) { id, checked ->
            if (!checked) return@fill
            GenBridge.setActive(JSONObject().put("presetId", id).toString())
            cur.put("presetId", id)
            render()
        }

        fill(charactersBox, chars, single = true, selected = setOf(charId)) { id, checked ->
            if (!checked) return@fill
            GenBridge.setActive(JSONObject().put("characterId", id).toString())
            cur.put("characterId", id)
            render()
        }

        fill(worldBooksBox, books, single = false, selected = wbIds) { id, checked ->
            val next = wbIds.toMutableSet().apply { if (checked) add(id) else remove(id) }
            GenBridge.setActive(JSONObject().put("worldBookIds", JSONArray(next.toList())).toString())
            cur.put("worldBookIds", JSONArray(next.toList()))
            render()
        }
    }

    /**
     * 往容器里铺一组勾选项。
     *
     * @param single true = 单选（点新的自动取消旧的），false = 多选
     */
    private fun fill(
        box: LinearLayout,
        items: JSONArray,
        single: Boolean,
        selected: Set<String>,
        onToggle: (id: String, checked: Boolean) -> Unit,
    ) {
        box.removeAllViews()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val cb = CheckBox(this).apply {
                text = o.optString("name", id)
                isChecked = selected.contains(id)
                textSize = 14f
                setTextColor(0xFFD6DCE8.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            cb.setOnClickListener {
                onToggle(id, cb.isChecked)
            }
            box.addView(cb)
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = HashSet<String>(length())
        for (i in 0 until length()) out.add(optString(i, ""))
        out.remove("")
        return out
    }
}
