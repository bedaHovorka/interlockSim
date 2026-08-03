/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerTickListener
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.planner.TimeoutNoOpCause
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [AgentLoopDriver] per-cycle attribution tests (SP2c.20 follow-up, Issue #843).
 *
 * Before this fix, [AgentLoopDriver.runCycle] always called `commandQueue.postAll(decisions)`
 * with no author/reason, so [CommandCorrelationMap.register]'s default
 * ([ActionAuthor.LLM]) silently mislabelled every cycle — including pure rule-based runs and
 * LLM cycles that actually fell back to the deterministic dispatcher. This is the exact class
 * of bug #843 exists to make unmissable (a deterministic component's dispatching work counted
 * as the LLM's).
 */
@DisplayName("AgentLoopDriver — per-cycle attribution (SP2c.20 follow-up)")
class AgentLoopDriverAttributionTest {
	private fun emptySnapshot(simTime: Double): SimulationSnapshot =
		SimulationSnapshot(
			simTime = simTime,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = emptyList()
		)

	private fun authorFor(
		correlationMap: CommandCorrelationMap,
		decision: DispatchDecision
	): ActionAuthor? = correlationMap.correlate(decision)?.author

	@Test
	@DisplayName("RuleBasedPlanAdapter-shaped planner (no plannerTickSource) attributes RULE_BASED")
	fun ruleBasedPlannerAttributesRuleBased() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val planner = mockk<DispatcherPlanner>(relaxed = true)
		val decision = DispatchDecision.ApproveTrain("T1")
		coEvery { planner.plan(any()) } returns listOf(decision)
		val correlationMap = CommandCorrelationMap()
		val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
		val controller = mockk<SimulationController>(relaxed = true)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = planner,
				commandQueue = commandQueue,
				controller = controller
				// plannerTickSource omitted (null default) — pure rule-based wiring.
			)

		runBlocking { driver.runCycle() }

		assertThat(authorFor(correlationMap, decision)).isEqualTo(ActionAuthor.RULE_BASED)
	}

	@Test
	@DisplayName("LLM_ACTIONS tick outcome attributes ActionAuthor.LLM")
	fun llmActionsAttributesLlm() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val koogAdapter = mockk<KoogAgentPlanAdapter>(relaxed = true)
		val tickListenerSlot = slot<PlannerTickListener>()
		every { koogAdapter.tickListener = capture(tickListenerSlot) } returns Unit
		val decision = DispatchDecision.ApproveTrain("T1")
		coEvery { koogAdapter.plan(any()) } coAnswers {
			tickListenerSlot.captured.onTick(TickRecord(TickOutcome.LLM_ACTIONS, 1.0))
			listOf(decision)
		}
		val correlationMap = CommandCorrelationMap()
		val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
		val controller = mockk<SimulationController>(relaxed = true)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = koogAdapter,
				commandQueue = commandQueue,
				controller = controller,
				plannerTickSource = koogAdapter
			)

		runBlocking { driver.runCycle() }

		assertThat(authorFor(correlationMap, decision)).isEqualTo(ActionAuthor.LLM)
	}

	@Test
	@DisplayName("RULE_FALLBACK tick outcome attributes ActionAuthor.RULE_FALLBACK")
	fun ruleFallbackAttributesRuleFallback() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val koogAdapter = mockk<KoogAgentPlanAdapter>(relaxed = true)
		val tickListenerSlot = slot<PlannerTickListener>()
		every { koogAdapter.tickListener = capture(tickListenerSlot) } returns Unit
		val decision = DispatchDecision.ApproveTrain("T1")
		coEvery { koogAdapter.plan(any()) } coAnswers {
			tickListenerSlot.captured.onTick(TickRecord(TickOutcome.RULE_FALLBACK, 1.0))
			listOf(decision)
		}
		val correlationMap = CommandCorrelationMap()
		val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
		val controller = mockk<SimulationController>(relaxed = true)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = koogAdapter,
				commandQueue = commandQueue,
				controller = controller,
				plannerTickSource = koogAdapter
			)

		runBlocking { driver.runCycle() }

		assertThat(authorFor(correlationMap, decision)).isEqualTo(ActionAuthor.RULE_FALLBACK)
	}

	@Test
	@DisplayName("TIMEOUT_NOOP tick outcome attributes ActionAuthor.TIMEOUT_NOOP")
	fun timeoutNoopAttributesTimeoutNoop() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val koogAdapter = mockk<KoogAgentPlanAdapter>(relaxed = true)
		val tickListenerSlot = slot<PlannerTickListener>()
		every { koogAdapter.tickListener = capture(tickListenerSlot) } returns Unit
		val decision = DispatchDecision.ApproveTrain("T1")
		coEvery { koogAdapter.plan(any()) } coAnswers {
			tickListenerSlot.captured.onTick(
				TickRecord(TickOutcome.TIMEOUT_NOOP, 1.0, TimeoutNoOpCause.DEADLINE_MISS)
			)
			listOf(decision)
		}
		val correlationMap = CommandCorrelationMap()
		val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
		val controller = mockk<SimulationController>(relaxed = true)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = koogAdapter,
				commandQueue = commandQueue,
				controller = controller,
				plannerTickSource = koogAdapter
			)

		runBlocking { driver.runCycle() }

		assertThat(authorFor(correlationMap, decision)).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
	}
}
