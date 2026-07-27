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
	fun `decideAsync omits the approve_train reminder when no trains are queued`() {
		// Locks the `observation.unapprovedTrains.isNotEmpty()` false branch of buildUserPrompt:
		// with an empty queue, the per-cycle reminder must not appear (the LLM has nobody to
		// approve, and a stray reminder was observed to prompt it to hallucinate approve_train
		// calls for nonexistent train ids).
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val emptyObservation =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		runBlocking { agent.decideAsync(emptyObservation) }

		coVerify {
			aiAgent.run(
				match { prompt -> !prompt.contains("approve_train") && !prompt.contains("Reminder") },
				null
			)
		}
	}

	@Test
	fun `decideAsync passes a prompt stating the simTime of the dispatch cycle`() {
		// Locks the simTime line of buildUserPrompt (the cycle's only dynamic timestamp).
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val observationAtT =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY.copy(simTime = 42.5),
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		runBlocking { agent.decideAsync(observationAtT) }

		coVerify {
			aiAgent.run(match { prompt -> prompt.contains("simTime=42.5") }, null)
		}
	}

	@Test
	fun `decideAsync passes a prompt that does not offer a direct set-switches-or-signals action`() {
		// SP2b.9 review follow-up (PR #811 Minor #1): the SetSignalAspect/SetSwitchPosition tools
		// were removed (the LLM may not mutate switches/signals outside the reservation flow), so
		// the per-cycle user prompt must not instruct the LLM to "set switches/signals" as a
		// direct actuator action — that would contradict the system prompt and invite the LLM to
		// hallucinate the removed tool's call shape. Switch and signal aspects change as a side
		// effect of requesting/canceling routes, which the prompt must say instead.
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)

		runBlocking { agent.decideAsync(observation("T1")) }

		coVerify {
			aiAgent.run(
				match { prompt ->
					!prompt.contains("set switches/signals") &&
						prompt.contains("side effect of requesting")
				},
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
