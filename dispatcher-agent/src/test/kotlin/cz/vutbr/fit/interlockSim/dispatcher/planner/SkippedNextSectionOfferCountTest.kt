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

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Issue #1302 step 1 (record, #992 F8): a cycle that emits anything — one `no_op` is enough —
 * counts as acted and skips the rule-based fallback, so a NEXT SECTION hop the cycle message
 * offered and the LLM did not request waits for a later cycle. [KoogAgentPlanAdapter] counts those
 * skipped offers on every cycle ending that skips the fallback and reports them on
 * [TickRecord.nextSectionOffers]. The count is reporting only: no decision, emission or history
 * entry may change because of it.
 */
@DisplayName("Skipped NEXT SECTION offers are counted on fallback-skipping cycle ends (#1302)")
class SkippedNextSectionOfferCountTest {
	private companion object {
		const val TRAIN = "Train #4"
		const val OTHER_TRAIN = "Train #7"
		const val ORIGIN = "zB"
		const val TARGET = "doA1"
	}

	/** The train approaches [ORIGIN] with no path set up: NEXT SECTION offers [ORIGIN] → [TARGET]. */
	private val trainApproachingZb =
		BlockInputObservation(
			blockId = "kB",
			towardSemaphoreName = ORIGIN,
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = TRAIN,
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false,
			candidateTargets = semaphoreCandidates(TARGET)
		)

	private fun position(trainId: String): TrainPositionReading =
		TrainPositionReading(
			trainId = trainId,
			velocity = 5.0,
			acceleration = 0.0,
			totalDistance = 0.0,
			frontSectionName = null
		)

	/** One active train with one offered NEXT SECTION hop. */
	private val offeringObservation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY.copy(simTime = 100.0, trainPositions = listOf(position(TRAIN))),
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = listOf(trainApproachingZb)
		)

	private fun adapter(
		koogAgent: KoogDispatchAgent,
		sinkHolder: SinkHolder,
		fallback: Dispatcher = mockk(),
		cycleHistory: CycleHistory = CycleHistory(capacity = 0),
		counter: NextSectionOfferCounter = NextSectionOfferCounter.LIVE,
		inferenceTimeoutMs: Long = 30_000L
	): KoogAgentPlanAdapter =
		KoogAgentPlanAdapter(
			agentFactory =
				mockk<KoogAgentFactory>().also { factory ->
					coEvery { factory.createAgent(any()) } returns koogAgent
				},
			context = mockk<DefaultSimulationContext>(),
			fallbackDispatcher = fallback,
			inferenceTimeout = java.time.Duration.ofMillis(inferenceTimeoutMs),
			commandQueue = ActuatorCommandQueue(),
			sinkHolder = sinkHolder,
			cycleHistory = cycleHistory,
			nextSectionOfferCounter = counter
		)

	/** The expected count: [offered] hops, [skipped] trains left unrequested, [standing] of them at a signal. */
	private fun offers(
		offered: Int,
		vararg skipped: String,
		standing: Int = 0
	): NextSectionOfferCount =
		NextSectionOfferCount(offered = offered, skippedTrainIds = skipped.toSet(), skippedStandingAtSignal = standing)

	/** An agent whose cycle emits [actions] through [sinkHolder], then returns nothing. */
	private fun emittingAgent(
		sinkHolder: SinkHolder,
		vararg actions: DispatchAction
	): KoogDispatchAgent =
		mockk<KoogDispatchAgent>().also { agent ->
			coEvery { agent.decideAsync(any()) } coAnswers {
				actions.forEach { sinkHolder.emit(it) }
				emptyList()
			}
		}

	private fun planOnce(
		planAdapter: KoogAgentPlanAdapter,
		observation: DispatchObservation = offeringObservation
	): Pair<List<DispatchDecision>, TickRecord> {
		val recorded = mutableListOf<TickRecord>()
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })
		val decisions = runBlocking { planAdapter.plan(observation) }
		assertThat(recorded).hasSize(1)
		return decisions to recorded.single()
	}

	@Test
	@DisplayName("one no_op and one offered NEXT SECTION: the offer is counted as skipped")
	fun noOpWithOneOfferCountsOne() {
		val sinkHolder = SinkHolder()
		val fallback = mockk<Dispatcher>()
		val (decisions, record) =
			planOnce(adapter(emittingAgent(sinkHolder, DispatchAction.NoOp), sinkHolder, fallback))

		assertThat(decisions).isEmpty()
		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(record.nextSectionOffers).isEqualTo(offers(1, TRAIN))
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	@DisplayName("a train with an accepted request_route is never counted, whatever its target")
	fun acceptedRequestIsNotCounted() {
		val sinkHolder = SinkHolder()
		// Another target than the offered one still counts as accepted: whatever the apply
		// outcome, the LLM did act on this train this cycle.
		val request = DispatchAction.RequestRoute(TRAIN, ORIGIN, "someOtherSignal")
		val (_, record) = planOnce(adapter(emittingAgent(sinkHolder, request), sinkHolder))

		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_ACTIONS)
		assertThat(record.nextSectionOffers).isEqualTo(offers(1))
	}

	@Test
	@DisplayName("a request for another train, or another action for this train, does not accept the offer")
	fun otherActionsDoNotAcceptTheOffer() {
		val sinkHolder = SinkHolder()
		val (_, record) =
			planOnce(
				adapter(
					emittingAgent(
						sinkHolder,
						DispatchAction.RequestRoute(OTHER_TRAIN, ORIGIN, TARGET),
						DispatchAction.ApproveTrain(TRAIN)
					),
					sinkHolder
				)
			)

		assertThat(record.nextSectionOffers).isEqualTo(offers(1, TRAIN))
	}

	@Test
	@DisplayName("a failure after a partial emission counts the skipped offer too")
	fun partialEmissionFailureCounts() {
		val sinkHolder = SinkHolder()
		val koogAgent =
			mockk<KoogDispatchAgent>().also { agent ->
				coEvery { agent.decideAsync(any()) } coAnswers {
					sinkHolder.emit(DispatchAction.NoOp)
					throw IllegalStateException("tool loop broke")
				}
			}
		val fallback = mockk<Dispatcher>()
		val (decisions, record) = planOnce(adapter(koogAgent, sinkHolder, fallback))

		assertThat(decisions).isEmpty()
		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(record.nextSectionOffers).isEqualTo(offers(1, TRAIN))
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	@DisplayName("a silent cycle with every train moving with a path ahead counts zero skipped")
	fun silentAllMovingCountsZero() {
		val sinkHolder = SinkHolder()
		val observation =
			DispatchObservation(
				snapshot =
					SimulationSnapshot.EMPTY.copy(
						simTime = 100.0,
						trainPositions = listOf(position(TRAIN)),
						trainPerceptions =
							listOf(
								TrainPerceptionReading(
									trainId = TRAIN,
									signalAheadName = ORIGIN,
									signalAheadAspect = null,
									distanceToSignalAheadMetres = 100.0,
									currentSpeedLimitMps = 20.0,
									velocity = 5.0,
									acceleration = 0.0,
									totalDistance = 0.0,
									frontSectionName = null,
									destinationInOutName = "B",
									scheduledArrivalTime = 0.0,
									isDwelling = false
								)
							)
					),
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)
		val (_, record) = planOnce(adapter(emittingAgent(sinkHolder), sinkHolder), observation)

		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_SILENT_ALL_MOVING)
		assertThat(record.nextSectionOffers).isEqualTo(offers(0))
	}

	@Test
	@DisplayName("a fallback cycle carries no count: the fallback was not skipped")
	fun fallbackCycleCarriesNoCount() {
		val sinkHolder = SinkHolder()
		val fallback = mockk<Dispatcher>()
		coEvery { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val koogAgent =
			mockk<KoogDispatchAgent>().also { agent ->
				coEvery { agent.decideAsync(any()) } throws IllegalStateException("no answer")
			}
		val (_, record) = planOnce(adapter(koogAgent, sinkHolder, fallback))

		assertThat(record.outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(record.nextSectionOffers).isNull()
	}

	@Test
	@DisplayName("a counter that throws records no count and leaves the cycle unchanged")
	fun throwingCounterRecordsNull() {
		val sinkHolder = SinkHolder()
		val throwing = NextSectionOfferCounter { _, _ -> throw IllegalStateException("counter broke") }
		val (decisions, record) =
			planOnce(adapter(emittingAgent(sinkHolder, DispatchAction.NoOp), sinkHolder, counter = throwing))

		assertThat(decisions).isEmpty()
		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(record.nextSectionOffers).isNull()
	}

	private fun perception(
		trainId: String,
		velocity: Double,
		signalAheadName: String?
	): TrainPerceptionReading =
		TrainPerceptionReading(
			trainId = trainId,
			signalAheadName = signalAheadName,
			signalAheadAspect = null,
			distanceToSignalAheadMetres = 100.0,
			currentSpeedLimitMps = 20.0,
			velocity = velocity,
			acceleration = 0.0,
			totalDistance = 0.0,
			frontSectionName = null,
			destinationInOutName = "B",
			scheduledArrivalTime = 0.0,
			isDwelling = false
		)

	/**
	 * The #895 shape F8 names: a train standing at a STOP signal whose hop is offered and skipped.
	 * Same predicate as the cycle message's "standing at signal" clause; a moving train with a
	 * signal ahead, and a train without a perception, are not standing.
	 */
	@Test
	@DisplayName("a skipped train standing at a signal is counted apart, a moving one is not")
	fun standingAtSignalCountedOnlyForStandingTrains() {
		val otherInput =
			trainApproachingZb.copy(
				blockId = "kA",
				towardSemaphoreName = "zA",
				ownerTrainId = OTHER_TRAIN,
				candidateTargets = semaphoreCandidates("doB1")
			)
		val observation =
			DispatchObservation(
				snapshot =
					SimulationSnapshot.EMPTY.copy(
						simTime = 100.0,
						trainPositions = listOf(position(TRAIN), position(OTHER_TRAIN)),
						trainPerceptions =
							listOf(
								perception(TRAIN, velocity = 0.0, signalAheadName = ORIGIN),
								perception(OTHER_TRAIN, velocity = 5.0, signalAheadName = "zA")
							)
					),
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = listOf(trainApproachingZb, otherInput)
			)
		val sinkHolder = SinkHolder()
		val (_, record) = planOnce(adapter(emittingAgent(sinkHolder, DispatchAction.NoOp), sinkHolder), observation)

		assertThat(record.nextSectionOffers).isEqualTo(
			offers(2, TRAIN, OTHER_TRAIN, standing = 1)
		)
	}

	@Test
	@DisplayName("a timeout after a partial emission counts through the partial-emission arm")
	fun timeoutAfterPartialEmissionCounts() {
		val sinkHolder = SinkHolder()
		val koogAgent =
			mockk<KoogDispatchAgent>().also { agent ->
				coEvery { agent.decideAsync(any()) } coAnswers {
					sinkHolder.emit(DispatchAction.NoOp)
					delay(500)
					emptyList()
				}
			}
		val fallback = mockk<Dispatcher>()
		val (decisions, record) = planOnce(adapter(koogAgent, sinkHolder, fallback, inferenceTimeoutMs = 50L))

		assertThat(decisions).isEmpty()
		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(record.nextSectionOffers)
			.isEqualTo(offers(1, TRAIN))
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	@DisplayName("a circuit-breaker skip carries no count: the fallback ran")
	fun breakerSkipCarriesNoCount() {
		val sinkHolder = SinkHolder()
		val fallback = mockk<Dispatcher>()
		coEvery { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val koogAgent =
			mockk<KoogDispatchAgent>().also { agent ->
				coEvery { agent.decideAsync(any()) } throws IllegalStateException("no answer")
			}
		val planAdapter =
			KoogAgentPlanAdapter(
				agentFactory =
					mockk<KoogAgentFactory>().also { factory ->
						coEvery { factory.createAgent(any()) } returns koogAgent
					},
				context = mockk<DefaultSimulationContext>(),
				fallbackDispatcher = fallback,
				commandQueue = ActuatorCommandQueue(),
				sinkHolder = sinkHolder,
				circuitBreaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
			)
		val recorded = mutableListOf<TickRecord>()
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(offeringObservation) }
		runBlocking { planAdapter.plan(offeringObservation) }

		assertThat(planAdapter.circuitBreaker.totalSkips).isEqualTo(1L)
		assertThat(recorded.map { it.outcome }).containsExactly(TickOutcome.RULE_FALLBACK, TickOutcome.RULE_FALLBACK)
		assertThat(recorded.map { it.nextSectionOffers }).containsExactly(null, null)
	}

	@Test
	@DisplayName("a silent cycle on an idle station carries no count")
	fun idleStationSilentCycleCarriesNoCount() {
		val sinkHolder = SinkHolder()
		val idle =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY.copy(simTime = 100.0),
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)
		val (_, record) = planOnce(adapter(emittingAgent(sinkHolder), sinkHolder), idle)

		assertThat(record.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(record.nextSectionOffers).isNull()
	}

	@Test
	@DisplayName("a silent non-idle cycle that consults the fallback carries no count")
	fun silentNonIdleCycleCarriesNoCount() {
		val sinkHolder = SinkHolder()
		val fallback = mockk<Dispatcher>()
		coEvery { fallback.decide(any()) } returns listOf(DispatchDecision.ReservePath(TRAIN, ORIGIN, TARGET))
		val (decisions, record) = planOnce(adapter(emittingAgent(sinkHolder), sinkHolder, fallback))

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath(TRAIN, ORIGIN, TARGET))
		assertThat(record.outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(record.nextSectionOffers).isNull()
	}

	/**
	 * The identical-decisions proof: the same two cycles run once with the live counter and once
	 * with a counter that measures nothing. Returned decisions, the agent's emissions, the tick
	 * outcomes and the history block the next cycle message renders (history enabled) are equal;
	 * only [TickRecord.nextSectionOffers] differs.
	 */
	@Test
	@DisplayName("decisions, emissions and the next-cycle history are identical with and without the counter")
	fun counterChangesNoDecision() {
		val withCounter = runTwoCycles(NextSectionOfferCounter.LIVE)
		val withoutCounter = runTwoCycles { _, _ -> null }

		assertThat(withCounter.decisions).isEqualTo(withoutCounter.decisions)
		assertThat(withCounter.emissions).isEqualTo(withoutCounter.emissions)
		assertThat(withCounter.outcomes).isEqualTo(withoutCounter.outcomes)
		assertThat(withCounter.historyBlock).isEqualTo(withoutCounter.historyBlock)
		assertThat(withCounter.counts)
			.containsExactly(
				offers(1, TRAIN),
				offers(1)
			)
		// The agent was handed the same observation both times: the counter reads, never rewrites.
		assertThat(withCounter.agentInputs).isEqualTo(withoutCounter.agentInputs)
		assertThat(withoutCounter.counts).containsExactly(null, null)
	}

	private data class TwoCycleRun(
		val decisions: List<List<DispatchDecision>>,
		val emissions: List<List<DispatchAction>>,
		val outcomes: List<TickOutcome>,
		val counts: List<NextSectionOfferCount?>,
		val historyBlock: String,
		val agentInputs: List<DispatchObservation>
	)

	/** Cycle 1 emits a `no_op`; cycle 2 requests the offered hop. History holds both. */
	private fun runTwoCycles(counter: NextSectionOfferCounter): TwoCycleRun {
		val sinkHolder = SinkHolder()
		val history = CycleHistory(capacity = 10)
		val perCycle =
			ArrayDeque(
				listOf(
					listOf<DispatchAction>(DispatchAction.NoOp),
					listOf<DispatchAction>(DispatchAction.RequestRoute(TRAIN, ORIGIN, TARGET))
				)
			)
		val agentInputs = mutableListOf<DispatchObservation>()
		val koogAgent =
			mockk<KoogDispatchAgent>().also { agent ->
				coEvery { agent.decideAsync(capture(agentInputs)) } coAnswers {
					perCycle.removeFirst().forEach { sinkHolder.emit(it) }
					emptyList()
				}
			}
		val planAdapter = adapter(koogAgent, sinkHolder, cycleHistory = history, counter = counter)
		val recorded = mutableListOf<TickRecord>()
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })
		val decisions = mutableListOf<List<DispatchDecision>>()
		val emissions = mutableListOf<List<DispatchAction>>()
		repeat(2) {
			decisions += runBlocking { planAdapter.plan(offeringObservation) }
			emissions += sinkHolder.emittedActionsThisCycle()
		}
		return TwoCycleRun(
			decisions = decisions,
			emissions = emissions,
			outcomes = recorded.map { it.outcome },
			counts = recorded.map { it.nextSectionOffers },
			historyBlock = history.renderPromptBlock(),
			agentInputs = agentInputs
		)
	}
}
