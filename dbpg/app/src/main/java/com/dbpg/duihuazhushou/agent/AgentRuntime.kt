package com.dbpg.duihuazhushou.agent

import android.content.Context
import com.dbpg.duihuazhushou.DhpApp
import com.dbpg.stcore.agent.AgentOrchestrator
import com.dbpg.stcore.agent.AgentTool
import com.dbpg.stcore.agent.BackendRouter
import com.dbpg.stcore.agent.DeterministicBackend
import com.dbpg.stcore.agent.GenBackend
import com.dbpg.stcore.agent.GenContext
import com.dbpg.stcore.agent.GenRequest
import com.dbpg.stcore.agent.GenStage
import com.dbpg.stcore.agent.IntentBus
import com.dbpg.stcore.agent.StageRegistry
import com.dbpg.stcore.agent.ToolRegistry
import com.dbpg.stcore.agent.ToolResult
import com.dbpg.stcore.agent.TraceBus
import com.dbpg.stcore.agent.TraceEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能体运行时的**装配点**：把注册表、后端、intent 处理器接起来。
 *
 * ★ 这里刻意只做「注册」，不做「编排逻辑」。基础链路里内置的几个 stage/tool
 *   都是占位实现（只观测、不改正文），下一迭代要接真正的记忆检索、变量结算、
 *   模型决定工具调用时，**替换对应 id 的注册项即可**——GenBridge、浮窗、
 *   设置页、前端一行都不用改。
 *
 * ★ 为什么后端选择放在 stcore 的 [BackendRouter]：两种后端产出同一个 GenPlan，
 *   开关和退化有唯一落点，不会散成两套分支。
 */
object AgentRuntime {

    val stages = StageRegistry()
    val tools: ToolRegistry by lazy { ToolRegistry { TraceBus.emit(it) } }
    val intents: IntentBus by lazy { IntentBus { TraceBus.emit(it) } }

    private val backend: GenBackend by lazy {
        BackendRouter(
            deterministic = DeterministicBackend { TraceBus.emit(it) },
            agent = AgentOrchestrator(stages, tools) { TraceBus.emit(it) },
            enabled = { AgentSettings.isAgentOn(DhpApp.instance) },
            trace = { TraceBus.emit(it) },
        )
    }

    @Volatile
    private var initialized = false

    /** 幂等：Application / 任意页面都可以调，重复调用没有副作用。 */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true

        // ---- 内置阶段（占位，全部可替换；id 是唯一的替换锚点）----
        stages.register(object : GenStage {
            override val id = "context.snapshot"
            override val description = "记录本次生成的上下文规模（占位）"
            override fun run(c: GenContext) {
                c["snapshot.messages"] = c.request.messageCount
                c["snapshot.session"] = c.request.sessionId
            }
        })

        stages.register(object : GenStage {
            override val id = "variables.bridge"
            override val description = "变量结算预留位（当前透传请求里的变量）"
            override fun run(c: GenContext) {
                // 不预设变量结构：请求里带什么就原样透传给生成请求。
                c.request.extras["variables"]?.let { c.export("agentVariables", it) }
            }
        })

        stages.register(object : GenStage {
            override val id = "tools.catalog"
            override val description = "把当前可用工具表暴露给生成请求（占位）"
            override fun run(c: GenContext) {
                @Suppress("UNCHECKED_CAST")
                val reg = c["__tools"] as? ToolRegistry
                c.export("agentTools", reg?.names().orEmpty())
            }
        })

        // ---- 内置工具（占位；真工具以后 register 同名即可覆盖）----
        tools.register(object : AgentTool {
            override val name = "echo"
            override val description = "链路自检：原样回显参数"
            override fun invoke(c: GenContext, args: Map<String, Any?>): ToolResult =
                ToolResult(true, args, "echo ok")
        })
        tools.register(object : AgentTool {
            override val name = "history.tail"
            override val description = "取最近 N 条消息摘要（占位）"
            override fun invoke(c: GenContext, args: Map<String, Any?>): ToolResult {
                val n = (args["n"] as? Number)?.toInt() ?: 3
                @Suppress("UNCHECKED_CAST")
                val list = c.request.extras["messages"] as? List<*>
                val tail = list?.takeLast(n)?.map { it.toString().take(120) }.orEmpty()
                return ToolResult(true, tail, "取回 ${tail.size} 条")
            }
        })

        // ---- 内置 intent（卡片 / 插件的通用入口）----
        intents.register("openPanel") { payload -> AgentOverlay.showPanel(payload); true }
        intents.register("trace") { payload ->
            TraceBus.emit(
                TraceEvent.of(
                    TraceEvent.INTENT, label = payload["label"]?.toString().orEmpty(),
                    detail = payload["detail"]?.toString().orEmpty(), extras = payload,
                ),
            )
            true
        }
        intents.register("generate") { payload ->
            // 真正的发送在 HtmlMessageView.Bridge 里就地完成（沿用 onSend），
            // 这里只留一个可观测点；以后想换成走编排器，改这一处即可。
            val text = payload["text"]?.toString().orEmpty()
            TraceBus.emit(TraceEvent.of(TraceEvent.INTENT, label = "generate", detail = "${text.length} 字"))
            false
        }

        // 应用持久化的能力 flag；注册表里以后新增的 stage 自动按默认启用。
        for (id in stages.ids()) stages.setEnabled(id, AgentSettings.isStageOn(ctx, id))
    }

    /** GenBridge 调它拿 plan；关掉开关时返回空 plan，行为与改动前一致。 */
    fun plan(request: GenRequest) = runCatching { backend.plan(request) }
        .onFailure {
            TraceBus.emit(
                TraceEvent.of(TraceEvent.STAGE_FALLBACK, stage = "runtime", detail = it.toString()),
            )
        }
        .getOrDefault(com.dbpg.stcore.agent.GenPlan())

    fun setStageEnabled(ctx: Context, id: String, on: Boolean) {
        stages.setEnabled(id, on)
        AgentSettings.setStageOn(ctx, id, on)
    }

    // ------------------------------------------------------------ JSON 互转

    /** JSONObject → 开放 Map；数组、嵌套对象都保留，不做字段白名单。 */
    fun toMap(json: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val it = json.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = when (val v = json.opt(k)) {
                is JSONObject -> toMap(v)
                is JSONArray -> toList(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
        return out
    }

    private fun toList(arr: JSONArray): List<Any?> = (0 until arr.length()).map { i ->
        when (val v = arr.opt(i)) {
            is JSONObject -> toMap(v)
            is JSONArray -> toList(v)
            JSONObject.NULL -> null
            else -> v
        }
    }

    /** 把 plan.extras 里的任意值转成 JSONObject 能接受的类型。 */
    fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o ->
            v.forEach { (k, x) -> o.put(k.toString(), toJsonValue(x)) }
        }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }
}
