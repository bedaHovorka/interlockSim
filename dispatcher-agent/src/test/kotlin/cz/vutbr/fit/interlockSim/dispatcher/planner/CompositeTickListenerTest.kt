/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.testutil.emptySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [CompositeTickListener], reached through [KoogAgentPlanAdapter.addTickListener]
 * (Issue #713, Task 9 — traffic-simulation-expert's rejection of the original Task 9 plan).
 *
 * ## The defect this pins
 *
 * `KoogAgentPlanAdapter.tickListener` used to be a single nullable slot. [AgentLoopDriver] claims
 * it unconditionally in its `init` block, so a caller that registered a listener before
 * constructing the driver had it silently discarded — the exact mechanism Issue #843 fixed once
 * already (every per-run JSON reported `totalTicks = 0`). Moving [MeasuringPlanAdapter] off the
 * legacy two-callback cycle-listener slot (deleted in Issue #713) onto the single
 * `KoogAgentPlanAdapter.tickListener` slot would recreate it. These tests exist so that regression cannot happen silently again.
 *
 * The construction helper below mirrors the one in `KoogAgentPlanAdapterTest`, and the second
 * test mirrors the `AgentLoopDriver` construction pattern used by `AgentLoopDriverAttributionTest`
 * / `AgentLoopDriverTickRecordingTest` — a real [KoogAgentPlanAdapter], not a mock, so the
 * multicast is exercised end to end rather than merely asserted against a stub.
 */
class CompositeTickListenerTest {
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	private fun koogAdapter(
		koogAgent: KoogDispatchAgent,
		fallback: Dispatcher,
		inferenceTimeout: Duration = Duration.ofSeconds(30),
		cycleHistory: CycleHistory = CycleHistory(capacity = 0)
	): KoogAgentPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		val context = mockk<DefaultSimulationContext>()
		return KoogAgentPlanAdapter(
			agentFactory,
			context,
			fallback,
			inferenceTimeout,
			ActuatorCommandQueue(),
			SinkHolder(),
			cycleHistory
		)
	}

	@Test
	@DisplayName("two listeners on one adapter both receive every tick")
	fun `two listeners on one adapter both receive every tick`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		val adapter = koogAdapter(koogAgent, fallback)

		val seenByA = mutableListOf<TickOutcome>()
		val seenByB = mutableListOf<TickOutcome>()
		adapter.addTickListener(PlannerTickListener { seenByA += it.outcome })
		adapter.addTickListener(PlannerTickListener { seenByB += it.outcome })

		runBlocking {
			adapter.plan(observation)
			adapter.plan(observation)
		}

		assertThat(seenByA).isEqualTo(listOf(TickOutcome.LLM_ACTIONS, TickOutcome.LLM_ACTIONS))
		assertThat(seenByB).isEqualTo(listOf(TickOutcome.LLM_ACTIONS, TickOutcome.LLM_ACTIONS))
	}

	@Test
	@DisplayName("a listener registered before AgentLoopDriver still receives ticks")
	fun `a listener registered before AgentLoopDriver still receives ticks`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		val adapter = koogAdapter(koogAgent, fallback)

		val seenByEarly = mutableListOf<TickOutcome>()
		// Registered BEFORE AgentLoopDriver is constructed — this is the production wiring
		// order (ExampleRegistry builds MeasuringPlanAdapter/callers first, then
		// wireDispatcherAgent constructs AgentLoopDriver last). AgentLoopDriver's own `init`
		// listener must join this one, not replace it.
		adapter.addTickListener(PlannerTickListener { seenByEarly += it.outcome })

		val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
		every { perceptionPort.snapshot() } returns emptySnapshot(1.0)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = adapter,
				commandQueue = ActuatorCommandQueue(),
				controller = mockk<SimulationController>(relaxed = true),
				plannerTickSource = adapter
			)

		runBlocking { driver.runCycle() }

		assertThat(seenByEarly).isEqualTo(listOf(TickOutcome.LLM_ACTIONS))
	}

	@Test
	@DisplayName("a throwing delegate does not starve the delegates registered after it")
	fun `a throwing delegate does not starve the delegates registered after it`() {
		val composite = CompositeTickListener()
		val seen = mutableListOf<TickOutcome>()
		composite.addListener(PlannerTickListener { error("boom") })
		composite.addListener(PlannerTickListener { seen += it.outcome })

		composite.onTick(TickRecord(TickOutcome.LLM_ACTIONS, 1.0))

		assertThat(seen).isEqualTo(listOf(TickOutcome.LLM_ACTIONS))
	}

	@Test
	@DisplayName("a delegate that throws CancellationException propagates it")
	fun `a delegate that throws CancellationException propagates it`() {
		val composite = CompositeTickListener()
		val seen = mutableListOf<TickOutcome>()
		composite.addListener(PlannerTickListener { throw CancellationException("cancelled") })
		composite.addListener(PlannerTickListener { seen += it.outcome })

		assertFailure { composite.onTick(TickRecord(TickOutcome.LLM_ACTIONS, 1.0)) }
			.isInstanceOf(CancellationException::class)
		assertThat(seen).isEqualTo(emptyList<TickOutcome>())
	}

	@Test
	@DisplayName("a throwing listener cannot break plan(): it returns, history records, outcome is unaffected")
	fun `a throwing listener cannot break plan`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		val history = CycleHistory(capacity = 5)
		val adapter = koogAdapter(koogAgent, fallback, cycleHistory = history)

		val seenAfter = mutableListOf<TickOutcome>()
		adapter.addTickListener(PlannerTickListener { error("listener failure") })
		adapter.addTickListener(PlannerTickListener { seenAfter += it.outcome })

		runBlocking { adapter.plan(observation) }

		assertThat(history.snapshot().map { it.outcome }).isEqualTo(listOf(TickOutcome.LLM_ACTIONS))
		assertThat(seenAfter).isEqualTo(listOf(TickOutcome.LLM_ACTIONS))
		coVerify(exactly = 0) { fallback.decide(any()) }
	}
}
