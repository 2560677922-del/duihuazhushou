package com.dbpg.stcore.agent

/**
 * 两种后端 + 编排器。
 *
 * ★ 关键约定：**确定性后端和 Agent 后端产出同一个 [GenPlan]**。下游（真正发
 *   请求的 GenBridge）不关心 plan 是谁算出来的，所以「可开关」不会变成两套
 *   bug、两套回归。这也是后面做 A/B 对比的前提。
 *
 * ★ 可退化：单个 stage 抛异常时，orchestrator 把 ctx 回滚到该 stage 执行前的
 *   快照，记一条 fallback trace，继续跑后面的 stage。任一能力挂掉最多损失
 *   它自己的产出，不会连带正文生成失败。
 */

/** 确定性后端：不编排，产出一个空 plan。等价于改动前的现有行为。 */
class DeterministicBackend(
    private val trace: (TraceEvent) -> Unit = { TraceBus.emit(it) },
) : GenBackend {
    override fun plan(request: GenRequest): GenPlan {
        trace(TraceEvent.of(TraceEvent.BACKEND_DETERMINISTIC, detail = "直通现有生成链路"))
        return GenPlan()
    }
}

/**
 * 按顺序跑注册进来的 stage。
 *
 * [toolRegistry] 只是让 stage 能取到工具表（stage 自己决定要不要调、调哪个），
 * 编排器**不预设**「第几步该调工具」——这是留给下一迭代的自由度。
 */
class AgentOrchestrator(
    private val stages: StageRegistry,
    private val toolRegistry: ToolRegistry,
    private val trace: (TraceEvent) -> Unit = { TraceBus.emit(it) },
) : GenBackend {

    override fun plan(request: GenRequest): GenPlan {
        val t0 = System.currentTimeMillis()
        val ctx = GenContext(request, trace)
        // stage 想拿工具表就取这个约定键，不需要编排器传参
        ctx["__tools"] = toolRegistry
        trace(
            TraceEvent.of(
                TraceEvent.AGENT_BEGIN,
                detail = "阶段 ${stages.active().size} 个 / 工具 ${toolRegistry.names().size} 个",
            ),
        )

        for (stage in stages.active()) {
            val before = ctx.snapshot()
            val s0 = System.currentTimeMillis()
            trace(TraceEvent.of(TraceEvent.STAGE_START, stage = stage.id, label = stage.description))
            try {
                stage.run(ctx)
                trace(
                    TraceEvent.of(
                        TraceEvent.STAGE_DONE, stage = stage.id,
                        durationMs = System.currentTimeMillis() - s0,
                    ),
                )
            } catch (t: Throwable) {
                ctx.restore(before)
                trace(
                    TraceEvent.of(
                        TraceEvent.STAGE_FALLBACK, stage = stage.id,
                        detail = t.toString(), durationMs = System.currentTimeMillis() - s0,
                    ),
                )
            }
        }

        // 约定：任何 stage 写 `export.xxx` 或 ctx.export(...) 都会进 plan.extras。
        // 键名不枚举，后续插件塞什么就透传什么。plan 在这一刻才生成，
        // 保证失败 stage 已导出的字段连同 store 一起被回滚掉了。
        val plan = GenPlan()
        for (key in ctx.keys()) {
            if (key.startsWith("export.")) plan.put(key.removePrefix("export."), ctx[key])
        }
        trace(
            TraceEvent.of(
                TraceEvent.AGENT_PLAN,
                detail = "产出 ${plan.extras.size} 个扩展字段",
                durationMs = System.currentTimeMillis() - t0,
                extras = plan.extras.toMap(),
            ),
        )
        return plan
    }
}

/**
 * 后端选择器：开关在这里，而不是散落在各 stage 里。
 *
 * 关 = 确定性；开 = Agent。两者返回同类型，调用方零分支。
 * 另提供 [fallback]：Agent 整体出问题时退回确定性，保证正文永远发得出去。
 */
class BackendRouter(
    private val deterministic: GenBackend,
    private val agent: GenBackend,
    private val enabled: () -> Boolean,
    private val trace: (TraceEvent) -> Unit = { TraceBus.emit(it) },
) : GenBackend {
    override fun plan(request: GenRequest): GenPlan = try {
        if (enabled()) agent.plan(request) else deterministic.plan(request)
    } catch (t: Throwable) {
        trace(
            TraceEvent.of(
                TraceEvent.STAGE_FALLBACK, stage = "router",
                detail = "Agent 后端整体失败，退化确定性：${t}",
            ),
        )
        runCatching { deterministic.plan(request) }.getOrDefault(GenPlan())
    }
}
