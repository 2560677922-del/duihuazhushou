package com.dbpg.duihuazhushou.chat

import com.dbpg.stcore.MacroContext
import com.dbpg.stcore.RawScript
import com.dbpg.stcore.VariableTables
import com.dbpg.stcore.buildScript
import org.json.JSONArray
import org.json.JSONObject

/**
 * 显示变换所需的配置（正则脚本 / 变量表 / 宏上下文 / 思维链开关）。
 *
 * 这些数据仍然由网页管理（预设、世界书、角色卡都在那边），但**只读一次**，
 * 不再像以前那样每条消息轮询整个快照 —— 会话数据已经在原生了，
 * 这里只拿"怎么渲染"。
 *
 * 放在独立文件而不是塞进 Activity：解析逻辑与界面无关，
 * 以后设置页要预览效果也能直接复用。
 */
data class DisplayConfig(
    val hideReasoning: Boolean = false,
    val reasoningCutMarker: String = "",
    val scripts: List<RawScript> = emptyList(),
    val variables: VariableTables = VariableTables(),
    val macroContext: MacroContext = MacroContext(),
) {
    companion object {

        fun empty(): DisplayConfig = DisplayConfig()

        fun parse(json: String?): DisplayConfig {
            val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return empty()
            val scripts = mutableListOf<RawScript>()
            o.optJSONArray("scripts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    runCatching { scripts.add(buildScript(jsonToMap(s))) }
                }
            }
            val mc = o.optJSONObject("macroContext")
            val macro = MacroContext(
                char = mc?.optString("char")?.notEmpty(),
                user = mc?.optString("user")?.notEmpty(),
                persona = mc?.optString("persona")?.notEmpty(),
                description = mc?.optString("description")?.notEmpty(),
                scenario = mc?.optString("scenario")?.notEmpty(),
                original = mc?.optString("original")?.notEmpty(),
                mesExample = mc?.optString("mes_example")?.notEmpty(),
                chat = mc?.optString("chat")?.notEmpty(),
                currentMessage = mc?.optString("currentMessage")?.notEmpty(),
                lastMessage = mc?.optString("lastMessage")?.notEmpty(),
            )
            return DisplayConfig(
                hideReasoning = o.optBoolean("hideReasoning", false),
                reasoningCutMarker = o.optString("reasoningCutMarker", ""),
                scripts = scripts,
                variables = parseVariables(o.optJSONObject("variables")),
                macroContext = macro,
            )
        }

        private fun parseVariables(o: JSONObject?): VariableTables {
            val session = mutableMapOf<String, String>()
            val global = mutableMapOf<String, String>()
            o?.optJSONObject("session")?.let { fillVars(it, session) }
            o?.optJSONObject("global")?.let { fillVars(it, global) }
            return VariableTables(session, global)
        }

        private fun fillVars(o: JSONObject, out: MutableMap<String, String>) {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k, "")
            }
        }

        /** org.json 递归转 Map，喂给 stcore.buildScript。 */
        private fun jsonToMap(o: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = jsonToObj(o.get(k))
            }
            return map
        }

        private fun jsonToObj(v: Any?): Any? = when (v) {
            is JSONObject -> jsonToMap(v)
            is JSONArray -> List(v.length()) { i -> jsonToObj(v.get(i)) }
            is Int, is Boolean, is String -> v
            is Double -> if (v % 1.0 == 0.0) v.toInt() else v
            else -> v?.toString()
        }

        private fun String.notEmpty(): String? = if (isNotEmpty()) this else null
    }
}
