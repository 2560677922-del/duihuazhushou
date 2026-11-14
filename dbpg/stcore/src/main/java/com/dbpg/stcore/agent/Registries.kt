package com.dbpg.stcore.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 四个注册表 + 一条事件总线。全部是**运行时可增删**的，没有编译期白名单。
 *
 * 为什么用注册表而不是 when/枚举：以后加一个 stage、一个工具、一个卡片
 * action、一种浮窗面板，都不该回头改编排器或 UI 的分发逻辑。插件（内置或
 * 以后外部的）只要 register 一下就生效。
 */

/** 有序阶段表：注册顺序即执行顺序；同 id 再注册视为替换（不报错）。 */
class StageRegistry {
    private val ordered = ArrayList<String>()
    private val stages = LinkedHashMap<String, GenStage>()
    private val enabled = LinkedHashSet<String>()

    @Synchronized
    fun register(stage: GenStage, on: Boolean = true) {
        if (!stages.containsKey(stage.id)) ordered.add(stage.id)
        stages[stage.id] = stage
        if (on) enabled.add(stage.id) else enabled.remove(stage.id)
    }

    @Synchronized
    fun unregister(id: String) {
        stages.remove(id); ordered.remove(id); enabled.remove(id)
    }

    /** 能力 flag：按能力单独开合，不是「总开关一刀切」。 */
    @Synchronized
    fun setEnabled(id: String, on: Boolean) {
        if (on) enabled.add(id) else enabled.remove(id)
    }

    @Synchronized
    fun isEnabled(id: String) = enabled.contains(id)

    @Synchronized
    fun ids(): List<String> = ordered.toList()

    @Synchronized
    fun active(): List<GenStage> = ordered.mapNotNull { id ->
        stages[id]?.takeIf { enabled.contains(id) }
    }

    @Synchronized
    fun descriptionOf(id: String) = stages[id]?.description.orEmpty()
}

/** 工具表。未知工具返回 ok=false，不抛异常、不中断编排。 */
class ToolRegistry(private val trace: (TraceEvent) -> Unit) {
    private val tools = LinkedHashMap<String, AgentTool>()

    @Synchronized
    fun register(tool: AgentTool) { tools[tool.name] = tool }

    @Synchronized
    fun unregister(name: String) { tools.remove(name) }

    @Synchronized
    fun names(): List<String> = tools.keys.toList()

    @Synchronized
    fun descriptionOf(name: String) = tools[name]?.description.orEmpty()

    fun invoke(name: String, ctx: GenContext, args: Map<String, Any?>): ToolResult {
        val tool = synchronized(this) { tools[name] }
        if (tool == null) {
            trace(TraceEvent.of(TraceEvent.TOOL_UNKNOWN, label = name, detail = "未注册的工具，已忽略"))
            return ToolResult(false, detail = "unknown tool: $name")
        }
        val t0 = System.currentTimeMillis()
        trace(TraceEvent.of(TraceEvent.TOOL_CALL, label = name, extras = mapOf("args" to args)))
        return try {
            val r = tool.invoke(ctx, args)
            trace(
                TraceEvent.of(
                    TraceEvent.TOOL_RESULT, label = name,
                    detail = if (r.ok) r.detail else "失败：${r.detail}",
                    durationMs = System.currentTimeMillis() - t0,
                ),
            )
            r
        } catch (t: Throwable) {
            // 工具坏了不该让整轮生成挂掉：记一条 trace，退化继续
            trace(
                TraceEvent.of(
                    TraceEvent.TOOL_FALLBACK, label = name,
                    detail = t.toString(), durationMs = System.currentTimeMillis() - t0,
                ),
            )
            ToolResult(false, detail = t.toString())
        }
    }
}

/**
 * 卡片 intent 总线。
 *
 * ★ 未知 action **不拒绝**：记一条 trace 然后返回 false。卡片来自模型输出，
 *   以后出现什么新 action 不该因为「没在白名单里」就报错弹窗。
 *   handler 自己抛异常也不扩散，同样记 trace 后继续。
 */
class IntentBus(private val trace: (TraceEvent) -> Unit) {
    private val handlers = LinkedHashMap<String, MutableList<(Map<String, Any?>) -> Boolean>>()

    @Synchronized
    fun register(action: String, handler: (Map<String, Any?>) -> Boolean): () -> Unit {
        handlers.getOrPut(action) { ArrayList() }.add(handler)
        return { synchronized(this) { handlers[action]?.remove(handler) } }
    }

    @Synchronized
    fun actions(): List<String> = handlers.keys.toList()

    fun dispatch(action: String, payload: Map<String, Any?>): Boolean {
        val list = synchronized(this) { handlers[action]?.toList().orEmpty() }
        trace(TraceEvent.of(TraceEvent.INTENT, label = action, extras = payload))
        if (list.isEmpty()) {
            trace(TraceEvent.of(TraceEvent.INTENT_UNHANDLED, label = action, detail = "无处理器，已忽略（不视为错误）"))
            return false
        }
        var handled = false
        for (h in list) {
            try {
                if (h(payload)) handled = true
            } catch (t: Throwable) {
                trace(TraceEvent.of(TraceEvent.INTENT_UNHANDLED, label = action, detail = "处理器异常：${t}"))
            }
        }
        return handled
    }
}

/**
 * 过程事件总线：进程内单例，环形缓冲保留最近 [capacity] 条。
 *
 * 保留历史很关键——浮窗可能在事件发出之后才被展开/才 attach 到新 Activity，
 * 订阅时要能立刻补上已经发生过的过程。
 */
object TraceBus {
    private const val DEFAULT_CAPACITY = 400
    private val buffer = ArrayDeque<TraceEvent>()
    private val listeners = CopyOnWriteArrayList<(TraceEvent) -> Unit>()
    private var seq: Long = 0L

    @Synchronized
    fun emit(event: TraceEvent): TraceEvent {
        val stamped = event.copy(seq = ++seq)
        buffer.addLast(stamped)
        while (buffer.size > DEFAULT_CAPACITY) buffer.removeFirst()
        listeners.forEach { runCatching { it(stamped) } }
        return stamped
    }

    @Synchronized
    fun history(): List<TraceEvent> = buffer.toList()

    @Synchronized
    fun clear() { buffer.clear() }

    fun subscribe(listener: (TraceEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
