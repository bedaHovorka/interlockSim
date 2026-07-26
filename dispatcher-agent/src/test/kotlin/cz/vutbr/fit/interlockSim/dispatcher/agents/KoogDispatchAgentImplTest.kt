/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import ai.koog.agents.core.agent.AIAgent
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KoogDispatchAgentImpl] (SP2b.9, Issue #566).
 *
 * Covers the real Koog wiring: [KoogDispatchAgentImpl.decideAsync] must call the wrapped
 * `AIAgent.run(...)` exactly once per cycle and always return an empty decision list on success
 * (actuation already happens as actuator-tool side effects during the run), while letting any
 * thrown exception propagate — [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]
 * relies on that propagation to trigger its rule-based fallback.
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogDispatchAgentImplTest {
	private fun observation(vararg queuedTrainIds: String) =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = queuedTrainIds.map { QueuedTrainObservation(trainId = it, destinationInOutName = "exitA") },
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@Test
	fun `decideAsync returns empty list and calls agent run once on success`() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)

		val decisions = runBlocking { agent.decideAsync(observation("T1")) }

		assertThat(decisions).isEmpty()
		coVerify(exactly = 1) { aiAgent.run(any(), null) }
	}

	@Test
	fun `decideAsync passes a prompt mentioning queued train ids to agent run`() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)

		runBlocking { agent.decideAsync(observation("T1", "T2")) }

		coVerify {
			aiAgent.run(
				match { prompt -> prompt.contains("T1") && prompt.contains("T2") },
				null
			)
		}
	}

	@Test
	fun `decideAsync passes a prompt reminding to approve_train when trains are queued`() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)

		runBlocking { agent.decideAsync(observation("T1")) }

		coVerify {
			aiAgent.run(
				match { prompt -> prompt.contains("approve_train") },
				null
			)
		}
	}

	@Test
	fun `decideAsync propagates exceptions from agent run instead of swallowing them`() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } throws RuntimeException("boom")
		val agent = KoogDispatchAgentImpl(aiAgent)

		assertFailure { runBlocking { agent.decideAsync(observation("T1")) } }
			.isInstanceOf<RuntimeException>()
			.messageContains("boom")
	}
}
