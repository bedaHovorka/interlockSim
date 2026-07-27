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
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
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
	private fun observation(
		vararg queuedTrainIds: String,
		approvedTrainCount: Int = 0
	) = DispatchObservation(
		snapshot =
			SimulationSnapshot.EMPTY.copy(
				trainPositions =
					List(approvedTrainCount) { index ->
						TrainPositionReading(
							trainId = "active-$index",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					}
			),
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
	fun `decideAsync passes a prompt stating the current active train count and cap without an extra tool call`() {
		// Goal 10 SP2b.9 follow-up: the LLM was observed to stop admitting queued trains for many
		// cycles despite free capacity, partly because learning the active count required an extra
		// perception-tool round-trip before it could even evaluate the admission precondition.
		// Stating it directly in the per-cycle prompt lets a one-shot decision happen immediately.
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS

		runBlocking { agent.decideAsync(observation("T1", approvedTrainCount = 1)) }

		coVerify {
			aiAgent.run(
				match { prompt -> prompt.contains("Active (approved) trains right now: 1 / $cap") },
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
