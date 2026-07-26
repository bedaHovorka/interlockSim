/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [KoogAgentPlanAdapter] (SP2b.9, Issue #566) — in particular the fallback
 * semantics fix: an empty decision list from a successful LLM cycle must be returned as-is, not
 * treated as a failure that triggers [Dispatcher] fallback (which would double-dispatch alongside
 * the LLM's own tool-driven actuation — see the class KDoc).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogAgentPlanAdapterTest {
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	private fun adapter(
		koogAgent: KoogDispatchAgent,
		fallback: Dispatcher,
		inferenceTimeout: Duration = Duration.ofSeconds(30)
	): KoogAgentPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		val context = mockk<DefaultSimulationContext>()
		return KoogAgentPlanAdapter(agentFactory, context, fallback, inferenceTimeout)
	}

	@Test
	fun `non-empty decisions from the LLM are returned as-is and fallback is not invoked`() {
		val decisions = listOf(DispatchDecision.NoAction)
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns decisions
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `empty decisions from a successful LLM cycle are returned as-is, fallback is not invoked`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).isEmpty()
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `timeout invokes the fallback dispatcher and returns its result`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result =
			runBlocking { adapter(koogAgent, fallback, Duration.ofMillis(50)).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 1) { koogAgent.decideAsync(any()) }
	}

	@Test
	fun `a thrown exception invokes the fallback dispatcher and returns its result`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws RuntimeException("boom")
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	fun `a thrown CancellationException propagates and fallback is not invoked`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws CancellationException("cancelled")
		val fallback = mockk<Dispatcher>()

		assertFailure { runBlocking { adapter(koogAgent, fallback).plan(observation) } }
			.isInstanceOf<CancellationException>()
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `TimeoutCancellationException from withTimeout is caught, not re-thrown as a bare CancellationException`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)

		// Should NOT throw: TimeoutCancellationException (a CancellationException subtype) must
		// be handled by the earlier, more specific catch block, not re-thrown by the generic one.
		val result =
			runBlocking { adapter(koogAgent, fallback, Duration.ofMillis(50)).plan(observation) }
		assertThat(result).containsExactly(DispatchDecision.NoAction)
	}
}
