package com.dbpg.duihuazhushou.agent

import com.dbpg.stcore.agent.AgentOrchestrator
import com.dbpg.stcore.agent.AgentTool
import com.dbpg.stcore.agent.BackendRouter
import com.dbpg.stcore.agent.DeterministicBackend
import com.dbpg.stcore.agent.GenContext
import com.dbpg.stcore.agent.GenPlan
import com.dbpg.stcore.agent.GenRequest
import com.dbpg.stcore.agent.GenStage
import com.dbpg.stcore.agent.IntentBus
import com.dbpg.stcore.agent.StageRegistry
import com.dbpg.stcore.agent.ToolRegistry
import com.dbpg.stcore.agent.ToolResult
import com.dbpg.stcore.agent.TraceBus
import com.dbpg.stcore.agent.TraceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约回归：这一组用例守的是「不写死 + 可退化」，不是具体业务逻辑。
 * 以后替换 stage / 加工具 / 加 action，只要这些还成立，就不会把路堵死。
 */
class AgentContractsTest {

    private fun request(vararg extras: Pair<String, Any?>) = GenRequest(
        requestId = "r1", sessionId = "s1", userInput = "你好",
        messageCount = 3, extras = mutableMapOf(*extras),
    )

    @Test
    fun stageRegistryIsOrderedAndReplaceable() {
        val seen = mutableListOf<String>()
        val reg = StageRegistry()
        fun stage(id: String) = object : GenStage {
            override val id = id
            override fun run(c: GenContext) { seen.add(id) }
        }
        reg.register(stage("a")); reg.register(stage("b")); reg.register(stage("c"))
        reg.setEnabled("b", false)
        val orch = AgentOrchestrator(reg, ToolRegistry { }) { }
        orch.plan(request())
        assertEquals(listOf("a", "c"), seen)
        // 同 id 再注册视为替换，不报错
        reg.register(stage("a"))
        assertEquals(listOf("a", "b", "c"), reg.ids())
    }

    @Test
    fun exportKeysFlowIntoPlanWithoutWhitelist() {
        val reg = StageRegistry()
        reg.register(object : GenStage {
            override val id = "demo"
            override fun run(c: GenContext) {
                c.export("agentTools", listOf("echo"))
                c["export.someFuturePluginField"] = mapOf("any" to 1)
            }
        })
        val plan = AgentOrchestrator(reg, ToolRegistry { }) { }.plan(request())
        assertEquals(listOf("echo"), plan.extras["agentTools"])
        assertEquals(mapOf("any" to 1), plan.extras["someFuturePluginField"])
    }

    @Test
    fun failingStageRollsBackAndDoesNotBreakGeneration() {
        val events = mutableListOf<TraceEvent>()
        val reg = StageRegistry()
        reg.register(object : GenStage {
            override val id = "bad"
            override fun run(c: GenContext) {
                c.export("leaked", "should-not-appear")
                throw RuntimeException("boom")
            }
        })
        reg.register(object : GenStage {
            override val id = "good"
            override fun run(c: GenContext) { c.export("ok", 1) }
        })
        val plan = AgentOrchestrator(reg, ToolRegistry { }) { events.add(it) }.plan(request())
        assertEquals(1, plan.extras["ok"])
        assertFalse(plan.extras.containsKey("leaked"))
        assertTrue(events.any { it.type == TraceEvent.STAGE_FALLBACK && it.stage == "bad" })
        assertTrue(events.any { it.type == TraceEvent.AGENT_PLAN })
    }

    @Test
    fun unknownToolIsNotAnException() {
        val events = mutableListOf<TraceEvent>()
        val tools = ToolRegistry { events.add(it) }
        val ctx = GenContext(request(), trace = { events.add(it) })
        val r = tools.invoke("not_registered", ctx, emptyMap())
        assertFalse(r.ok)
        assertTrue(events.any { it.type == TraceEvent.TOOL_UNKNOWN })
    }

    @Test
    fun toolFailureDegradesAndStillReturnsResult() {
        val events = mutableListOf<TraceEvent>()
        val tools = ToolRegistry { events.add(it) }
        tools.register(object : AgentTool {
            override val name = "explode"
            override fun invoke(c: GenContext, args: Map<String, Any?>): ToolResult =
                throw IllegalStateException("tool down")
        })
        val r = tools.invoke("explode", GenContext(request(), trace = { events.add(it) }), emptyMap())
        assertFalse(r.ok)
        assertTrue(events.any { it.type == TraceEvent.TOOL_FALLBACK })
    }

    @Test
    fun unknownIntentIsIgnoredNotRejected() {
        val events = mutableListOf<TraceEvent>()
        val bus = IntentBus { events.add(it) }
        val handled = bus.dispatch("some.future.plugin.action", mapOf("x" to 1))
        assertFalse(handled)
        assertTrue(events.any { it.type == TraceEvent.INTENT_UNHANDLED })
        // 注册后立刻生效，bus 本身不用改
        bus.register("some.future.plugin.action") { true }
        assertTrue(bus.dispatch("some.future.plugin.action", emptyMap()))
    }

    @Test
    fun intentHandlerExceptionDoesNotEscape() {
        val bus = IntentBus { }
        bus.register("bad") { throw RuntimeException("handler down") }
        // 不抛异常即通过
        bus.dispatch("bad", emptyMap())
    }

    @Test
    fun backendRouterFallsBackWhenAgentThrows() {
        val events = mutableListOf<TraceEvent>()
        val router = BackendRouter(
            deterministic = DeterministicBackend { events.add(it) },
            agent = object : com.dbpg.stcore.agent.GenBackend {
                override fun plan(r: GenRequest): GenPlan = throw RuntimeException("agent down")
            },
            enabled = { true },
            trace = { events.add(it) },
        )
        val plan = router.plan(request())
        assertTrue(plan.extras.isEmpty())
        assertTrue(events.any { it.type == TraceEvent.BACKEND_DETERMINISTIC })
        assertTrue(events.any { it.type == TraceEvent.STAGE_FALLBACK && it.stage == "router" })
    }

    @Test
    fun switchOffProducesSamePlanAsDeterministic() {
        val events = mutableListOf<TraceEvent>()
        val router = BackendRouter(
            deterministic = DeterministicBackend { events.add(it) },
            agent = AgentOrchestrator(StageRegistry(), ToolRegistry { }) { events.add(it) },
            enabled = { false },
            trace = { events.add(it) },
        )
        router.plan(request())
        assertTrue(events.any { it.type == TraceEvent.BACKEND_DETERMINISTIC })
        assertTrue(events.none { it.type == TraceEvent.AGENT_BEGIN })
    }

    @Test
    fun traceBusKeepsHistoryForLateSubscribers() {
        TraceBus.clear()
        repeat(5) { i -> TraceBus.emit(TraceEvent.of("test.$i")) }
        val h = TraceBus.history()
        assertEquals(5, h.size)
        assertEquals(1L, h.first().seq)
        assertEquals(5L, h.last().seq)
    }
}
