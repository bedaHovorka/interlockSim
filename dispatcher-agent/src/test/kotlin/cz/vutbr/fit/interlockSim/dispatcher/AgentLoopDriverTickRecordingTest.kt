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
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerTickListener
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
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
 * [AgentLoopDriver] tick-forwarding tests (Issue #847 round 4, finding R4-5).
 *
 * ## The gap
 *
 * `DispatcherRunRecorder.onTick` fills `totalTicks`, `ticksByOutcome` and `timeoutNoOpByCause` —
 * every tick-shaped field in the per-run JSON that #847's sweep and #846's aggregator consume, and
 * the field the snapshot's own `init` invariant (`ticksByOutcome.values.sum() == totalTicks`) is
 * written against. It had **no production caller**.
 *
 * The only `PlannerTickListener` installed in production was [AgentLoopDriver]'s own, which stores
 * the outcome for author attribution and forwards it nowhere. Worse, the driver *overwrites*
 * `plannerTickSource.tickListener` in its `init`, so a caller that installed its own listener before
 * constructing the driver had it silently discarded — there was no seam to wire a recorder onto at
 * all.
 *
 * These tests pin the seam: the driver keeps its own attribution behaviour **and** forwards every
 * record to an optional observer.
 */
@DisplayName("AgentLoopDriver — tick records reach the run recorder")
class AgentLoopDriverTickRecordingTest {
	private fun emptySnapshot(simTime: Double): SimulationSnapshot =
		SimulationSnapshot(
			simTime = simTime,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = emptyList()
		)

	@Test
	@DisplayName("every planner tick record is forwarded to the observer")
	fun tickRecordsAreForwarded() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val koogAdapter = mockk<KoogAgentPlanAdapter>(relaxed = true)
		val tickListenerSlot = slot<PlannerTickListener>()
		every { koogAdapter.tickListener = capture(tickListenerSlot) } returns Unit
		coEvery { koogAdapter.plan(any()) } coAnswers {
			tickListenerSlot.captured.onTick(TickRecord(TickOutcome.LLM_ACTIONS, 1.0))
			listOf(DispatchDecision.ApproveTrain("T1"))
		}
		val recorded = mutableListOf<TickRecord>()

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = koogAdapter,
				commandQueue = ActuatorCommandQueue(),
				controller = mockk<SimulationController>(relaxed = true),
				plannerTickSource = koogAdapter,
				onTickRecord = { recorded += it }
			)

		runBlocking { driver.runCycle() }

		assertThat(recorded.map { it.outcome }, "forwarded outcomes")
			.containsExactly(TickOutcome.LLM_ACTIONS)
	}

	/**
	 * The forwarding must not disturb the attribution the driver already derives from the same
	 * record — SP2c.20 (#843) exists because deterministic work was once counted as the LLM's.
	 */
	@Test
	@DisplayName("forwarding leaves per-cycle author attribution unchanged")
	fun forwardingPreservesAttribution() {
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

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = koogAdapter,
				commandQueue = ActuatorCommandQueue(correlationMap = correlationMap),
				controller = mockk<SimulationController>(relaxed = true),
				plannerTickSource = koogAdapter,
				onTickRecord = { }
			)

		runBlocking { driver.runCycle() }

		assertThat(correlationMap.correlate(decision)?.author, "attributed author")
			.isEqualTo(ActionAuthor.RULE_FALLBACK)
	}

	/**
	 * A rule-based run has no [KoogAgentPlanAdapter], so no tick records exist to forward. The
	 * observer must simply never fire rather than the wiring failing — the rule-based arm is #847's
	 * baseline and runs through the same code path.
	 */
	@Test
	@DisplayName("a run with no planner tick source forwards nothing and still works")
	fun noTickSourceForwardsNothing() {
		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)
		val planner = mockk<DispatcherPlanner>(relaxed = true)
		coEvery { planner.plan(any()) } returns listOf(DispatchDecision.ApproveTrain("T1"))
		val recorded = mutableListOf<TickRecord>()

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = planner,
				commandQueue = ActuatorCommandQueue(),
				controller = mockk<SimulationController>(relaxed = true),
				onTickRecord = { recorded += it }
			)

		val ran = runBlocking { driver.runCycle() }

		assertThat(ran, "cycle ran").isEqualTo(true)
		assertThat(recorded, "forwarded records").isEmpty()
	}
}
