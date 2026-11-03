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
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.inOutCandidate
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidates
import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Issue #1343: the rule-based fallback that runs after a failed LLM cycle decides on the latest
 * published state, not on the cycle's own observation taken when the cycle started.
 *
 * The scenario mirrors the 2026-11-01 run: the cycle starts with `Train #4` on `kB` approaching
 * `zB`, so a fallback deciding on that observation asks `zB → doA1`. While the LLM call is in
 * flight the simulation keeps running: the train's earlier `zB → doA1` request is granted and it
 * moves onto `k1`, approaching `doA1`. A fallback deciding on the old observation repeats
 * `zB → doA1`, which the interlocking refuses as a non-contiguous origin. A fallback deciding on
 * the latest published state asks `doA1 → A` instead.
 *
 * The published state is served through the production composition
 * ([LatestObservationReader.fromPorts]) over a strict [NetworkPerceptionPort] mock that answers
 * only [NetworkPerceptionPort.snapshot]: any sim-thread capture request
 * ([NetworkPerceptionPort.captureSnapshot]) would throw, and [confirmVerified] proves none was
 * made (constraint C3 of `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`).
 */
@DisplayName("KoogAgentPlanAdapter post-failure fallback decides on the latest published state (#1343)")
class KoogAgentPlanAdapterLatestStateFallbackTest {
	private companion object {
		const val TRAIN = "Train #4"
		const val CYCLE_SIM_TIME = 100.0
		const val MOVED_SIM_TIME = 130.0

		/** What the fallback asks on the cycle's own (stale) observation. */
		val STALE_REQUEST = DispatchDecision.ReservePath(TRAIN, "zB", "doA1")

		/** What the fallback asks once the train has moved onto k1. */
		val FRESH_REQUEST = DispatchDecision.ReservePath(TRAIN, "doA1", "A")
	}

	private val trainOnKb =
		BlockInputObservation(
			blockId = "kB",
			towardSemaphoreName = "zB",
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = TRAIN,
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false,
			candidateTargets = semaphoreCandidates("doA1")
		)

	private val kbFree =
		BlockInputObservation(
			blockId = "kB",
			towardSemaphoreName = "zB",
			state = TrackFacility.State.FREE,
			ownerTrainId = null,
			isApproachingThisInput = false,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false
		)

	private val trainOnK1 =
		BlockInputObservation(
			blockId = "k1",
			towardSemaphoreName = "doA1",
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = TRAIN,
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false,
			candidateTargets = listOf(inOutCandidate("A", available = true))
		)

	private fun snapshotAt(simTime: Double): SimulationSnapshot =
		SimulationSnapshot.EMPTY.copy(
			simTime = simTime,
			trainPositions =
				listOf(
					TrainPositionReading(
						trainId = TRAIN,
						velocity = 5.0,
						acceleration = 0.0,
						totalDistance = 0.0,
						frontSectionName = null
					)
				)
		)

	/** The observation the driver handed to `plan()` when the cycle started. */
	private val cycleObservation =
		DispatchObservation(
			snapshot = snapshotAt(CYCLE_SIM_TIME),
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = listOf(trainOnKb)
		)

	/**
	 * Stand-in for the simulation thread's two published references: the perception snapshot
	 * (`snapshotCaptureHook`) and the per-tick dispatch-loop observation (`latestObservation`).
	 */
	private inner class PublishedState {
		@Volatile
		var snapshot: SimulationSnapshot = cycleObservation.snapshot

		@Volatile
		var tick: ShuntingLoop.TickObservation =
			ShuntingLoop.TickObservation(emptyList(), emptyList(), listOf(trainOnKb))

		/** Strict: answers only [NetworkPerceptionPort.snapshot]; a capture request throws. */
		val perceptionPort: NetworkPerceptionPort =
			mockk<NetworkPerceptionPort>().also { port -> every { port.snapshot() } answers { snapshot } }

		val reader: LatestObservationReader =
			LatestObservationReader.fromPorts(perceptionPort, DefaultDispatchLoopSensorPort { tick })

		/** The simulation advances while the LLM call is in flight: Train #4 is now on k1. */
		fun publishTrainMovedOntoK1() {
			snapshot = snapshotAt(MOVED_SIM_TIME)
			tick = ShuntingLoop.TickObservation(emptyList(), listOf(trainOnK1), listOf(kbFree))
		}
	}

	private fun adapter(
		koogAgent: KoogDispatchAgent,
		inferenceTimeout: Duration = Duration.ofSeconds(30),
		sinkHolder: SinkHolder = SinkHolder(),
		circuitBreaker: LlmCircuitBreaker = LlmCircuitBreaker(),
		agentFactory: KoogAgentFactory =
			mockk<KoogAgentFactory>().also { factory ->
				coEvery { factory.createAgent(any()) } returns koogAgent
			}
	): KoogAgentPlanAdapter =
		KoogAgentPlanAdapter(
			agentFactory = agentFactory,
			context = mockk<DefaultSimulationContext>(),
			fallbackDispatcher = RuleBasedDispatcher(),
			inferenceTimeout = inferenceTimeout,
			commandQueue = ActuatorCommandQueue(),
			sinkHolder = sinkHolder,
			circuitBreaker = circuitBreaker
		)

	/** An agent whose call outlives [timeout]; the train moves while it runs. */
	private fun timingOutAgent(published: PublishedState): KoogDispatchAgent =
		mockk<KoogDispatchAgent>().also { agent ->
			coEvery { agent.decideAsync(any()) } coAnswers {
				published.publishTrainMovedOntoK1()
				delay(500)
				emptyList()
			}
		}

	private val timeout: Duration = Duration.ofMillis(50)

	@Test
	@DisplayName("after a timeout, the fallback's request starts at the train's new position")
	fun timeoutFallbackStartsAtTheNewPosition() {
		val published = PublishedState()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = published.reader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(FRESH_REQUEST)
	}

	@Test
	@DisplayName("after an exception, the fallback's request starts at the train's new position")
	fun exceptionFallbackStartsAtTheNewPosition() {
		val published = PublishedState()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			published.publishTrainMovedOntoK1()
			throw IllegalStateException("tool call could not be parsed")
		}
		val planAdapter = adapter(koogAgent)
		planAdapter.latestObservationReader = published.reader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(FRESH_REQUEST)
	}

	@Test
	@DisplayName("the post-timeout fallback reads only the published snapshot and requests no sim-thread capture (C3)")
	fun postTimeoutFallbackRequestsNoCapture() {
		val published = PublishedState()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = published.reader

		runBlocking { planAdapter.plan(cycleObservation) }

		verify(exactly = 1) { published.perceptionPort.snapshot() }
		confirmVerified(published.perceptionPort)
	}

	@Test
	@DisplayName("the tick is still accounted at the cycle's own sim time, not the newer state's")
	fun tickAccountingKeepsTheCycleSimTime() {
		val published = PublishedState()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = published.reader
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(recorded.map { it.outcome to it.simTime })
			.containsExactly(TickOutcome.RULE_FALLBACK to CYCLE_SIM_TIME)
	}

	@Test
	@DisplayName("without a reader (the constructor default), the fallback decides on the cycle's own observation")
	fun withoutReaderTheCycleObservationIsUsed() {
		val published = PublishedState()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("a reader with nothing published yet leaves the whole cycle observation in place")
	fun nullReadKeepsTheCycleObservation() {
		val published = PublishedState()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = LatestObservationReader { null }

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("a published state older than the cycle's observation is ignored, never mixed in")
	fun olderPublishedStateIsIgnored() {
		val published = PublishedState()
		val older =
			DispatchObservation(
				snapshot = snapshotAt(CYCLE_SIM_TIME - 2.0),
				unapprovedTrains = emptyList(),
				innerBlockInputs = listOf(trainOnK1),
				outerBlockInputs = listOf(kbFree)
			)
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = LatestObservationReader { older }

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("a throwing reader is accounted like a throwing fallback: the tick is recorded first")
	fun throwingReaderStillRecordsTheTick() {
		val published = PublishedState()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(timingOutAgent(published), inferenceTimeout = timeout)
		planAdapter.latestObservationReader = LatestObservationReader { error("reader boom") }
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		assertFailure { runBlocking { planAdapter.plan(cycleObservation) } }.hasMessage("reader boom")
		assertThat(recorded.map { it.outcome }).containsExactly(TickOutcome.RULE_FALLBACK)
	}

	// ── Fallback causes that keep the cycle's own observation ──────────────────

	/** A reader that fails the test if a path that must keep the cycle's observation consults it. */
	private val forbiddenReader = LatestObservationReader { error("this fallback must not read the latest state") }

	@Test
	@DisplayName("a silent LLM answer on a non-idle station consults the fallback on the cycle's own observation")
	fun silentCycleKeepsTheCycleObservation() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val planAdapter = adapter(koogAgent)
		planAdapter.latestObservationReader = forbiddenReader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("a circuit-breaker skip decides on the cycle's own observation (no inference ran)")
	fun breakerSkipKeepsTheCycleObservation() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws IllegalStateException("boom")
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		val planAdapter = adapter(koogAgent, circuitBreaker = breaker)
		runBlocking { planAdapter.plan(cycleObservation) }
		planAdapter.latestObservationReader = forbiddenReader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("an agent-creation failure decides on the cycle's own observation (no inference ran)")
	fun agentCreationFailureKeepsTheCycleObservation() {
		val factory = mockk<KoogAgentFactory>()
		coEvery { factory.createAgent(any()) } throws IllegalStateException("model not found")
		val planAdapter = adapter(mockk(), agentFactory = factory)
		planAdapter.latestObservationReader = forbiddenReader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).containsExactly(STALE_REQUEST)
	}

	@Test
	@DisplayName("a timeout after a partial emission neither runs the fallback nor reads the latest state")
	fun partialEmissionSuppressesFallbackAndRead() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.NoOp)
			delay(500)
			emptyList()
		}
		val planAdapter = adapter(koogAgent, inferenceTimeout = timeout, sinkHolder = sinkHolder)
		planAdapter.latestObservationReader = forbiddenReader

		val decisions = runBlocking { planAdapter.plan(cycleObservation) }

		assertThat(decisions).isEmpty()
	}
}
