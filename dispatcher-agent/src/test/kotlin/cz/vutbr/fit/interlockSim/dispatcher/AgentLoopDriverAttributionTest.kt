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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerTickListener
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.planner.TimeoutNoOpCause
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.testutil.emptySnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

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
		every { koogAdapter.addTickListener(capture(tickListenerSlot)) } returns Unit
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
		every { koogAdapter.addTickListener(capture(tickListenerSlot)) } returns Unit
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
		every { koogAdapter.addTickListener(capture(tickListenerSlot)) } returns Unit
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

	/**
	 * An LLM-backed planner whose [KoogAgentPlanAdapter.plan] reports [outcomes] one per cycle
	 * (`null`: the cycle reports nothing) and returns one fresh decision per cycle (Issue #1477).
	 */
	private class UnreportedCycleRig(
		vararg outcomes: TickOutcome?
	) {
		val decisions = outcomes.indices.map { DispatchDecision.ApproveTrain("T$it") }
		val correlationMap = CommandCorrelationMap()
		val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
		val driver: AgentLoopDriver

		init {
			val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
			every { perceptionPort.snapshot() } returnsMany outcomes.indices.map { emptySnapshot(it + 1.0) }
			val koogAdapter = mockk<KoogAgentPlanAdapter>(relaxed = true)
			val tickListenerSlot = slot<PlannerTickListener>()
			every { koogAdapter.addTickListener(capture(tickListenerSlot)) } returns Unit
			var cycle = 0
			coEvery { koogAdapter.plan(any()) } coAnswers {
				val index = cycle++
				outcomes[index]?.let { tickListenerSlot.captured.onTick(TickRecord(it, index + 1.0)) }
				listOf(decisions[index])
			}
			driver =
				AgentLoopDriver(
					perceptionPort = perceptionPort,
					planner = koogAdapter,
					commandQueue = commandQueue,
					controller = mockk<SimulationController>(relaxed = true),
					plannerTickSource = koogAdapter
				)
		}
	}

	@Test
	@DisplayName("a cycle that reports no tick outcome does not reuse the previous cycle's author (Issue #1477)")
	fun unreportedCycleDoesNotReusePreviousAuthor() {
		val rig = UnreportedCycleRig(TickOutcome.LLM_ACTIONS, null)

		runBlocking { rig.driver.runCycle() }
		assertThat(rig.commandQueue.drain()).containsExactly(rig.decisions[0])

		assertFailure { runBlocking { rig.driver.runCycle() } }.isInstanceOf<IllegalStateException>()
		assertThat(rig.commandQueue.drain(), name = "batch of the unreported cycle").isEmpty()
		assertThat(authorFor(rig.correlationMap, rig.decisions[1]), name = "registered author").isNull()
	}

	@Test
	@DisplayName(
		"an LLM-backed cycle with no tick outcome fails at ERROR, the driver loop goes on, " +
			"and the batch is not posted (Issue #1477)"
	)
	fun unreportedCycleFailsLoudlyAndTheLoopGoesOn() {
		val rig = UnreportedCycleRig(null, TickOutcome.LLM_ACTIONS)
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		val loopLogger = LoggerFactory.getLogger(AgentDriverLoop::class.java) as LogbackLogger
		loopLogger.addAppender(appender)
		var cycles = 0
		val loop = AgentDriverLoop(isActive = { cycles++ < 2 }, runCycle = rig.driver::runCycle)
		try {
			runBlocking { loop.run() }
		} finally {
			loopLogger.detachAppender(appender)
		}

		assertThat(loop.cycleCount, name = "cycles attempted").isEqualTo(2L)
		assertThat(loop.failureCount, name = "failed cycles").isEqualTo(1)
		assertThat(loop.stoppedByFailures).isFalse()
		assertThat(loop.lastFailure).isNotNull().isInstanceOf<IllegalStateException>()
		assertThat(appender.list.filter { it.level == Level.ERROR }, name = "ERROR lines").hasSize(1)
		// Only the second, reported cycle posted its batch.
		assertThat(rig.commandQueue.drain()).containsExactly(rig.decisions[1])
		assertThat(authorFor(rig.correlationMap, rig.decisions[1])).isEqualTo(ActionAuthor.LLM)
	}
}
