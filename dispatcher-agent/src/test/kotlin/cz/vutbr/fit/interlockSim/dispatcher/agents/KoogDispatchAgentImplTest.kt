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
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
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

	// ── Issue #893 (phase beta, task B1): NEXT SECTION facts on active-train lines ────────────

	/**
	 * Builds an observation with a single active train, optionally carrying a matching
	 * [TrainPerceptionReading] (destination + standing-at-signal facts) and block-input lists
	 * ([NextHopResolver] reads those to compute the NEXT SECTION clause).
	 */
	private fun singleActiveTrainObservation(
		trainId: String = "Train #1",
		destination: String? = "A",
		velocity: Double = 5.0,
		signalAheadName: String? = null,
		innerBlockInputs: List<BlockInputObservation> = emptyList(),
		outerBlockInputs: List<BlockInputObservation> = emptyList()
	): DispatchObservation {
		val perceptions =
			if (destination != null) {
				listOf(
					TrainPerceptionReading(
						trainId = trainId,
						signalAheadName = signalAheadName,
						signalAheadAspect = null,
						distanceToSignalAheadMetres = 0.0,
						currentSpeedLimitMps = 0.0,
						velocity = velocity,
						acceleration = 0.0,
						totalDistance = 0.0,
						frontSectionName = null,
						destinationInOutName = destination,
						scheduledArrivalTime = 0.0,
						isDwelling = velocity == 0.0
					)
				)
			} else {
				emptyList()
			}
		return DispatchObservation(
			snapshot =
				SimulationSnapshot.EMPTY.copy(
					trainPositions =
						listOf(
							TrainPositionReading(
								trainId = trainId,
								velocity = velocity,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						),
					trainPerceptions = perceptions
				),
			unapprovedTrains = emptyList(),
			innerBlockInputs = innerBlockInputs,
			outerBlockInputs = outerBlockInputs
		)
	}

	private fun blockInput(
		towardSemaphoreName: String,
		toSeparatorName: String?,
		ownerTrainId: String?,
		isApproachingThisInput: Boolean = false,
		pathSetUpTowardThisInput: Boolean = false,
		pathAlreadyExtendedBeyond: Boolean = false,
		blockId: String = "blk"
	): BlockInputObservation =
		BlockInputObservation(
			blockId = blockId,
			towardSemaphoreName = towardSemaphoreName,
			toSeparatorName = toSeparatorName,
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput = isApproachingThisInput,
			pathSetUpTowardThisInput = pathSetUpTowardThisInput,
			pathAlreadyExtendedBeyond = pathAlreadyExtendedBeyond
		)

	@Test
	@DisplayName("a qualifying input renders NEXT SECTION with quoted, field-style endpoint names")
	fun decideAsyncRendersNextSectionWithQuotedFieldStyleEndpoints() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			singleActiveTrainObservation(
				trainId = "Train #1",
				innerBlockInputs =
					listOf(
						blockInput(
							towardSemaphoreName = "doB1",
							toSeparatorName = "doB2",
							ownerTrainId = "Train #1",
							isApproachingThisInput = true
						)
					)
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(match { prompt -> prompt.contains("from \"doB1\" to \"doB2\"") }, null)
		}
	}

	@Test
	@DisplayName("no owned input has a FREE next separator: wait wording, never \"blocked\" or \"occupied\"")
	fun decideAsyncRendersWaitWordingWithoutBlockedOrOccupied() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			singleActiveTrainObservation(
				trainId = "Train #1",
				innerBlockInputs =
					listOf(
						blockInput(
							towardSemaphoreName = "doB1",
							toSeparatorName = null,
							ownerTrainId = "Train #1",
							isApproachingThisInput = true
						)
					)
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(
				match { prompt ->
					prompt.contains("no section ahead is reservable this cycle") &&
						!prompt.contains("blocked", ignoreCase = true) &&
						!prompt.contains("occupied", ignoreCase = true)
				},
				null
			)
		}
	}

	@Test
	@DisplayName("path already extended beyond every owned input: needs-nothing wording with no route-request endpoints")
	fun decideAsyncRendersNeedsNothingWithNoEndpointsWhenAlreadyExtended() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			singleActiveTrainObservation(
				trainId = "Train #1",
				innerBlockInputs =
					listOf(
						blockInput(
							towardSemaphoreName = "doB1",
							toSeparatorName = "doB2",
							ownerTrainId = "Train #1",
							isApproachingThisInput = true,
							pathAlreadyExtendedBeyond = true
						)
					)
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(
				match { prompt ->
					prompt.contains("route already set — needs nothing this cycle.") &&
						!prompt.contains("NEXT SECTION") &&
						!prompt.contains("from \"doB1\" to \"doB2\"")
				},
				null
			)
		}
	}

	@Test
	@DisplayName("a queued train renders an approve-only line naming zero topology names")
	fun decideAsyncRendersQueuedTrainAsApproveOnlyWithZeroTopologyNames() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains =
					listOf(QueuedTrainObservation(trainId = "T9", destinationInOutName = "zZZ_no_leak")),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(
				match { prompt ->
					val queuedLine = prompt.lineSequence().first { it.contains("T9") }
					queuedLine.contains("needs approve_train only") && !queuedLine.contains("zZZ_no_leak")
				},
				null
			)
		}
	}

	@Test
	@DisplayName("no block id from the observation appears anywhere in the rendered train-list segment")
	fun decideAsyncNeverRendersABlockIdInTheTrainListSegment() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			singleActiveTrainObservation(
				trainId = "Train #1",
				innerBlockInputs =
					listOf(
						blockInput(
							towardSemaphoreName = "doB1",
							toSeparatorName = "doB2",
							ownerTrainId = "Train #1",
							isApproachingThisInput = true,
							blockId = "SECRET_BLOCK_ID"
						)
					)
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(match { prompt -> !prompt.contains("SECRET_BLOCK_ID") }, null)
		}
	}

	@Test
	@DisplayName("standing at a signal (velocity 0, signal ahead named) appends its clause before NEXT SECTION")
	fun decideAsyncAppendsStandingAtSignalClauseBeforeNextSection() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val obs =
			singleActiveTrainObservation(
				trainId = "Train #1",
				velocity = 0.0,
				signalAheadName = "doB1",
				innerBlockInputs =
					listOf(
						blockInput(
							towardSemaphoreName = "doB1",
							toSeparatorName = "doB2",
							ownerTrainId = "Train #1",
							isApproachingThisInput = true
						)
					)
			)

		runBlocking { agent.decideAsync(obs) }

		coVerify {
			aiAgent.run(
				match { prompt ->
					prompt.contains(
						"standing at signal \"doB1\" (STOP); NEXT SECTION to reserve: from \"doB1\" to \"doB2\""
					)
				},
				null
			)
		}
	}

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
	fun `decideAsync lists active train ids so the model never has to invent one`() {
		// Issue #847 round 2: with the perception tools gone, active trains were reported only as
		// a count, so the model had no id to use for a running train and invented one (a live run
		// produced trainId=4 and an approve_train for '1').
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)

		runBlocking { agent.decideAsync(observation(approvedTrainCount = 2)) }

		coVerify {
			aiAgent.run(
				match { prompt -> prompt.contains("- active-0 (active)") && prompt.contains("- active-1 (active)") },
				null
			)
		}
	}

	@Test
	fun `decideAsync never renders a train's block position, which is not a valid route endpoint`() {
		// Issue #847 round 2 regression guard. The first cut of the active-train list rendered
		// `front at <frontSectionName>` — a *block* name — and the model promptly reused those
		// block names as request_route endpoints (48 rejected calls naming block "k1" in one run),
		// plus 18 more copying the literal "unknown section" null-fallback string. Never put a name
		// the actuator tools cannot accept into the live message.
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent)
		val withBlockPosition =
			observation(approvedTrainCount = 1).let { base ->
				base.copy(
					snapshot =
						base.snapshot.copy(
							trainPositions = base.snapshot.trainPositions.map { it.copy(frontSectionName = "k1") }
						)
				)
			}

		runBlocking { agent.decideAsync(withBlockPosition) }

		coVerify {
			aiAgent.run(
				match { prompt -> !prompt.contains("k1") && !prompt.contains("unknown section") },
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
