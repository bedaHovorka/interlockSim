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
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeChannel
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the "OUTCOMES OF YOUR PREVIOUS ACTIONS" block that [KoogDispatchAgentImpl.buildUserPrompt]
 * renders from a drained [AppliedOutcomeFeed] (Issue #893, phase beta, task B0).
 *
 * ## Why this closes a real gap
 *
 * Before task B0, [AppliedOutcomeChannel] (populated by
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] on the kDisco simulation thread)
 * had exactly one drainer in the whole codebase:
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector.captureOnSimThread],
 * which production code never calls — only tests do, via the `DispatchTickLoop` path. On the live
 * path ([cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] ->
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] -> [KoogDispatchAgentImpl]),
 * apply-time failures (e.g. `ALL_PATHS_BLOCKED`, 173 occurrences across the #847 baseline runs)
 * were therefore invisible to the model, which kept re-emitting the same refused command. These
 * tests drive [KoogDispatchAgentImpl] exactly as the live path does — through [decideAsync] with a
 * mocked `AIAgent`, capturing the rendered prompt — and assert the block's presence, content, and
 * drain-once lifecycle.
 *
 * @since Issue #893 (phase beta, task B0)
 */
@DisplayName("KoogDispatchAgentImpl renders OUTCOMES OF YOUR PREVIOUS ACTIONS (Issue #893 phase beta, task B0)")
class KoogDispatchAgentImplPromptTest {
	private fun emptyObservation(): DispatchObservation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	/**
	 * Builds a [KoogDispatchAgentImpl] wrapping a mocked `AIAgent` that records every prompt it is
	 * called with (in call order) instead of talking to a real LLM.
	 */
	private fun agentCapturingPrompts(outcomeFeed: AppliedOutcomeFeed?): Pair<KoogDispatchAgentImpl, MutableList<String>> {
		val aiAgent = mockk<AIAgent<String, String>>()
		val prompts = mutableListOf<String>()
		coEvery { aiAgent.run(any(), null) } answers {
			prompts.add(firstArg())
			"done"
		}
		return KoogDispatchAgentImpl(aiAgent, outcomeFeed = outcomeFeed) to prompts
	}

	// ── AC1: drain-once ─────────────────────────────────────────────────────

	@Test
	@DisplayName("a Blocked outcome appears once with trainId/endpoints/reason, then is gone (drain-once)")
	fun blockedOutcomeAppearsOnceThenGone() {
		val channel = AppliedOutcomeChannel()
		channel.publish(
			AppliedOutcome.Blocked(
				trainId = "T-block",
				fromEndpointName = "doA1",
				toEndpointName = "A",
				attemptedPaths = 2,
				id = CommandId(1L),
				tickIndex = 1L
			)
		)
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }
		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(2)
		assertThat(prompts[0]).contains("OUTCOMES OF YOUR PREVIOUS ACTIONS")
		assertThat(prompts[0]).contains("T-block")
		assertThat(prompts[0]).contains("(doA1 -> A)")
		assertThat(prompts[0]).contains("REFUSED")
		// drain-once: nothing new was published, so the second cycle's prompt carries no block at all.
		assertThat(prompts[1]).doesNotContain("OUTCOMES OF YOUR PREVIOUS ACTIONS")
	}

	// ── AC2: OriginNotContiguous reason rendered verbatim ──────────────────────

	@Test
	@DisplayName("OriginNotContiguous outcome renders the kernel's reason verbatim")
	fun originNotContiguousReasonRenderedVerbatim() {
		val reason =
			"Route origin 'doA1' is not contiguous with train 'T-2': the train holds or occupies " +
				"1 block(s), none of which is bounded by 'doA1'. Legal origins for this train are: zB."
		val channel = AppliedOutcomeChannel()
		channel.publish(
			AppliedOutcome.OriginNotContiguous(
				trainId = "T-2",
				fromEndpointName = "doA1",
				toEndpointName = "A",
				reason = reason,
				id = CommandId(1L),
				tickIndex = 1L
			)
		)
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).contains(reason)
	}

	// ── AC3: Conflicted omits the raw block id ─────────────────────────────────

	@Test
	@DisplayName("Conflicted outcome never names the raw block id, but does name the owning train")
	fun conflictedOutcomeOmitsBlockId() {
		val channel = AppliedOutcomeChannel()
		channel.publish(
			AppliedOutcome.Conflicted(
				trainId = "T-3",
				fromEndpointName = "doB1",
				toEndpointName = "doB2",
				blockName = "W7",
				existingOwner = "T-9",
				id = CommandId(1L),
				tickIndex = 1L
			)
		)
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).doesNotContain("W7")
		assertThat(prompts[0]).contains("T-9")
		assertThat(prompts[0]).contains("conflict")
	}

	// ── AC4: empty drain -> block absent entirely ──────────────────────────────

	@Test
	@DisplayName("an empty channel renders no OUTCOMES block at all (no bare header)")
	fun emptyChannelRendersNoBlock() {
		val channel = AppliedOutcomeChannel()
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).doesNotContain("OUTCOMES OF YOUR PREVIOUS ACTIONS")
	}

	@Test
	@DisplayName("no outcomeFeed wired at all renders no OUTCOMES block (pre-task-B0 callers unaffected)")
	fun noFeedWiredRendersNoBlock() {
		val (agent, prompts) = agentCapturingPrompts(null)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).doesNotContain("OUTCOMES OF YOUR PREVIOUS ACTIONS")
	}

	// ── AC5: determinism ────────────────────────────────────────────────────

	@Test
	@DisplayName("two identical published outcome sets render byte-identical OUTCOMES blocks with stable ordering")
	fun identicalPublishedSetsRenderByteIdenticalBlocks() {
		fun publishSameOutcomes(channel: AppliedOutcomeChannel) {
			channel.publish(
				AppliedOutcome.Blocked(
					trainId = "T-1",
					fromEndpointName = "doA1",
					toEndpointName = "A",
					attemptedPaths = 2,
					id = CommandId(1L),
					tickIndex = 1L
				)
			)
			channel.publish(
				AppliedOutcome.Conflicted(
					trainId = "T-2",
					fromEndpointName = "doB1",
					toEndpointName = "doB2",
					blockName = "W7",
					existingOwner = "T-9",
					id = CommandId(2L),
					tickIndex = 1L
				)
			)
		}

		fun outcomesBlockFor(channel: AppliedOutcomeChannel): String {
			val (agent, prompts) = agentCapturingPrompts(channel)
			runBlocking { agent.decideAsync(emptyObservation()) }
			return prompts[0]
				.substringAfter("OUTCOMES OF YOUR PREVIOUS ACTIONS")
				.substringBefore("Dispatch cycle")
		}

		val channelA = AppliedOutcomeChannel()
		publishSameOutcomes(channelA)
		val blockA = outcomesBlockFor(channelA)

		val channelB = AppliedOutcomeChannel()
		publishSameOutcomes(channelB)
		val blockB = outcomesBlockFor(channelB)

		assertThat(blockA).isEqualTo(blockB)
		// Sanity: the block is non-trivial (both outcomes rendered, in publish order).
		assertThat(blockA.indexOf("T-1") in 0 until blockA.indexOf("T-2")).isEqualTo(true)
	}

	// ── AC6: Released renders the current tool name, not the retired one (Issue #893
	// final-review wave, F1) ──────────────────────────────────────────────────

	/**
	 * `release_route` was retired in SP2c.6 (Issue #829); the four-tool actuator surface is
	 * `approve_train, request_route, cancel_route, no_op` (see `CancelRouteTool` KDoc). The
	 * [AppliedOutcome.Released] branch of [KoogDispatchAgentImpl]'s outcomes-block renderer
	 * still spelled out the retired name, so a model reading its own action history would see
	 * a tool name it can no longer call.
	 */
	@Test
	@DisplayName("a Released outcome (anyReleased=true) names the current tool cancel_route, not release_route")
	fun releasedOutcomeAnyReleasedTrueNamesCancelRoute() {
		val channel = AppliedOutcomeChannel()
		channel.publish(
			AppliedOutcome.Released(
				trainId = "T-rel",
				anyReleased = true,
				id = CommandId(1L),
				tickIndex = 1L
			)
		)
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).contains("cancel_route for \"T-rel\": applied.")
		assertThat(prompts[0]).doesNotContain("release_route")
	}

	@Test
	@DisplayName("a Released outcome (anyReleased=false) names the current tool cancel_route, not release_route")
	fun releasedOutcomeAnyReleasedFalseNamesCancelRoute() {
		val channel = AppliedOutcomeChannel()
		channel.publish(
			AppliedOutcome.Released(
				trainId = "T-rel",
				anyReleased = false,
				id = CommandId(1L),
				tickIndex = 1L
			)
		)
		val (agent, prompts) = agentCapturingPrompts(channel)

		runBlocking { agent.decideAsync(emptyObservation()) }

		assertThat(prompts).hasSize(1)
		assertThat(prompts[0]).contains("cancel_route for \"T-rel\": applied — no reservation was held.")
		assertThat(prompts[0]).doesNotContain("release_route")
	}
}
