package com.dbpg.stcore.agent

/**
 * 生成编排层的开放契约。
 *
 * ★ 设计原则（这一层刻意「不写死」）：
 *   1. 所有结构都带 **extras / 开放 Map**，新字段不用改数据类、不用改调用方；
 *      stage 想往生成请求里塞东西，只要 `ctx["export.xxx"] = value`。
 *   2. stage / tool / intent / panel 全部是**注册表**，运行时可增删；
 *      新增能力不改 orchestrator、不改 GenBridge、不改浮窗。
 *   3. 未知的东西**不拒绝**：未知 action 记一条 trace 后忽略，未知工具返回
 *      ok=false 而不是抛异常，未知面板类型降级成通用渲染。这里要的是
 *      「失败可退化」，不是「提前校验把路堵死」。
 *   4. 本文件是纯 Kotlin（stcore 无 Android 依赖），能直接跑 JVM 单测。
 *
 * ★ 现阶段定位：**基础链路**。确定性后端与 Agent 后端产出同一个 [GenPlan]，
 *   正文仍由主模型直出、仍走原有生成通道；编排只负责阶段编排、工具调用
 *   骨架、过程可观测。真正的「模型决定调哪个工具」留作下一迭代——那时只要
 *   替换/新增一个 stage，下面这套契约一行都不用动。
 */

/** 一次生成请求里，编排层关心的最小快照。其余一律走 [extras]。 */
data class GenRequest(
    val requestId: String,
    val sessionId: String,
    val userInput: String,
    val messageCount: Int,
    val presetId: String = "",
    val characterId: String = "",
    val configId: String = "",
    val extras: MutableMap<String, Any?> = mutableMapOf(),
)

/**
 * 两种后端唯一的共同产物。
 *
 * [extras] 会被合并进实际生成请求；前端不认识的多余字段自然忽略，
 * 所以「编排层先加字段、前端以后再认」这种演进不会造成不兼容。
 */
data class GenPlan(
    val extras: MutableMap<String, Any?> = mutableMapOf(),
    val notes: MutableList<String> = mutableListOf(),
) {
    fun put(key: String, value: Any?): GenPlan = apply { extras[key] = value }
    fun note(text: String): GenPlan = apply { notes.add(text) }
}

/** 结构化过程事件。浮窗只读这几个字段，新 [type] 自动显示，不需要改 UI。 */
data class TraceEvent(
    val seq: Long = 0L,
    val atMs: Long = System.currentTimeMillis(),
    val type: String,
    val stage: String = "",
    val label: String = "",
    val detail: String = "",
    val durationMs: Long = -1L,
    val extras: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** 常用类型只是**约定**，不是枚举白名单；调用方可以随便写新 type。 */
        const val AGENT_BEGIN = "agent.begin"
        const val AGENT_PLAN = "agent.plan"
        const val STAGE_START = "stage.start"
        const val STAGE_DONE = "stage.done"
        const val STAGE_FALLBACK = "stage.fallback"
        const val TOOL_CALL = "tool.call"
        const val TOOL_RESULT = "tool.result"
        const val TOOL_UNKNOWN = "tool.unknown"
        const val TOOL_FALLBACK = "tool.fallback"
        const val BACKEND_DETERMINISTIC = "backend.deterministic"
        const val GEN_DELTA = "gen.delta"
        const val GEN_DONE = "gen.done"
        const val GEN_ERROR = "gen.error"
        const val GEN_STOP = "gen.stop"
        const val INTENT = "intent"
        const val INTENT_UNHANDLED = "intent.unhandled"
        const val PANEL = "panel"

        fun of(
            type: String,
            stage: String = "",
            label: String = "",
            detail: String = "",
            durationMs: Long = -1L,
            extras: Map<String, Any?> = emptyMap(),
        ) = TraceEvent(
            type = type, stage = stage, label = label,
            detail = detail, durationMs = durationMs, extras = extras,
        )
    }
}

/**
 * 编排上下文：一个**开放**的键值袋 + 一个 trace 出口。
 *
 * stage 之间靠约定键名协作（`export.*` 会被自动收进 [GenPlan]），不靠固定字段，
 * 所以两个互不认识的 stage / 插件也能组合。
 *
 * ★ 刻意的单一数据源：这里**没有**独立的 plan 对象。[export] 只是写
 *   `store["export.$key"]` 的语法糖，最终 plan 由编排器在所有 stage 跑完后
 *   从 store 收集生成。这样 [snapshot] / [restore] 一并覆盖了「导出字段」——
 *   一个 stage 抛异常前导出的东西会被完整回滚，不会污染最终请求。
 */
class GenContext(
    val request: GenRequest,
    val trace: (TraceEvent) -> Unit,
    values: Map<String, Any?> = emptyMap(),
) {
    private val store = LinkedHashMap<String, Any?>(values)

    operator fun get(key: String): Any? = store[key]
    operator fun set(key: String, value: Any?) { store[key] = value }
    fun contains(key: String) = store.containsKey(key)
    fun keys(): Set<String> = store.keys.toSet()
    fun snapshot(): Map<String, Any?> = LinkedHashMap(store)
    fun restore(from: Map<String, Any?>) {
        store.clear()
        store.putAll(from)
    }

    /** 给生成请求注入一个扩展字段（等价于 `ctx["export.$key"] = value`）。 */
    fun export(key: String, value: Any?) { this["export.$key"] = value }
}

/** 可插拔阶段。抛异常不算致命：orchestrator 会回滚到它执行前的快照并继续。 */
interface GenStage {
    val id: String
    val description: String get() = ""
    fun run(ctx: GenContext)
}

/** 声明式工具。args 结构不校验，工具自己按需取；访问整个 [GenContext] 以便组合。 */
interface AgentTool {
    val name: String
    val description: String get() = ""
    fun invoke(ctx: GenContext, args: Map<String, Any?>): ToolResult
}

data class ToolResult(
    val ok: Boolean,
    val output: Any? = null,
    val detail: String = "",
)

/** 两种后端只负责产出同一个 [GenPlan]，下游一行不改。 */
interface GenBackend {
    fun plan(request: GenRequest): GenPlan
}
