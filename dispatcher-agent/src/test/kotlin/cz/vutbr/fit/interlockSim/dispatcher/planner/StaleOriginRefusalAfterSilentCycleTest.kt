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
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidates
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Issue #1365 (part 1 of #1355): the run counts the non-contiguous-origin refusals
 * ([ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS]) of the rule-based decisions that a silent LLM cycle
 * on a non-idle station emitted — decisions computed on the kept cycle observation.
 *
 * The production chain is wired without a simulation: [KoogAgentPlanAdapter] plans with a mocked
 * agent and reports its ticks to a [DefaultDispatcherRunRecorder]; the returned decisions are
 * posted to an [ActuatorCommandQueue] over a [CommandCorrelationMap] exactly as
 * `AgentLoopDriver` posts them; [refuseAll] stands in for `DispatchDecisionApplier`: it drains the
 * queue, correlates every decision and reports the refusal the interlocking would give.
 */
@DisplayName("Stale-origin refusals after a silent non-idle LLM cycle are counted per run (#1365)")
class StaleOriginRefusalAfterSilentCycleTest {
	private companion object {
		const val TRAIN = "Train #4"
		const val CYCLE_SIM_TIME = 100.0

		/** What the fallback asks on the cycle observation: the train approaches zB on kB. */
		val STALE_REQUEST = DispatchDecision.ReservePath(TRAIN, "zB", "doA1")
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

	/** Non-idle: one active train approaching zB with no path set up, so the fallback acts. */
	private val cycleObservation =
		DispatchObservation(
			snapshot =
				SimulationSnapshot.EMPTY.copy(
					simTime = CYCLE_SIM_TIME,
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
				),
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = listOf(trainOnKb)
		)

	private val correlationMap = CommandCorrelationMap()
	private val commandQueue = ActuatorCommandQueue(correlationMap = correlationMap)
	private val recorder =
		DefaultDispatcherRunRecorder(
			runId = "stale-origin-run",
			arm = DispatcherArm.LLM_TOOL_CALLING,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = "",
					seed = null
				)
		)

	/** The outcome of the cycle reported last — what `AgentLoopDriver` attributes a post with. */
	private var lastOutcome: TickOutcome? = null

	private fun adapter(
		koogAgent: KoogDispatchAgent,
		inferenceTimeout: Duration = Duration.ofSeconds(30)
	): KoogAgentPlanAdapter =
		KoogAgentPlanAdapter(
			agentFactory =
				mockk<KoogAgentFactory>().also { factory ->
					coEvery { factory.createAgent(any()) } returns koogAgent
				},
			context = mockk<DefaultSimulationContext>(),
			fallbackDispatcher = RuleBasedDispatcher(),
			inferenceTimeout = inferenceTimeout,
			commandQueue = commandQueue,
			sinkHolder = SinkHolder(),
			circuitBreaker = LlmCircuitBreaker()
		).also { planAdapter ->
			planAdapter.addTickListener(
				PlannerTickListener { record ->
					lastOutcome = record.outcome
					recorder.onTick(record)
				}
			)
		}

	private fun silentAgent(): KoogDispatchAgent =
		mockk<KoogDispatchAgent>().also { agent -> coEvery { agent.decideAsync(any()) } returns emptyList() }

	private fun timingOutAgent(): KoogDispatchAgent =
		mockk<KoogDispatchAgent>().also { agent ->
			coEvery { agent.decideAsync(any()) } coAnswers {
				delay(500)
				emptyList()
			}
		}

	/** One driver cycle: plan, then post the returned decisions as `AgentLoopDriver.runCycle` does. */
	private fun runCycle(planAdapter: KoogAgentPlanAdapter): List<DispatchDecision> {
		val decisions = runBlocking { planAdapter.plan(cycleObservation) }
		val outcome = lastOutcome
		commandQueue.postAll(decisions, outcome?.toActionAuthor ?: ActionAuthor.LLM, outcome?.name ?: "")
		return decisions
	}

	/** Stand-in for `DispatchDecisionApplier`: every queued decision is refused with [code]. */
	private fun refuseAll(code: ApplyFailureCode) {
		for (decision in commandQueue.drain()) {
			val correlation = correlationMap.correlate(decision)
			recorder.onActionOutcome(
				ActionOutcome(
					phase = ActionPhase.APPLIED_THEN_FAILED,
					rejection = null,
					applyFailure = code,
					authored =
						AuthoredAction(
							author = correlation?.author ?: ActionAuthor.LLM,
							reason = correlation?.reason ?: "",
							decisionKind = decision::class.simpleName ?: "Unknown",
							tickIndex = correlation?.tickIndex ?: -1L
						)
				)
			)
		}
	}

	private fun staleOriginRefusals(): Long? = recorder.snapshot().staleOriginRefusalsAfterSilentCycle

	@Test
	@DisplayName("a silent non-idle cycle followed by a stale-origin refusal of its decision is counted")
	fun silentCycleRefusalIsCounted() {
		val decisions = runCycle(adapter(silentAgent()))
		refuseAll(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)

		assertThat(decisions).containsExactly(STALE_REQUEST)
		assertThat(lastOutcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(staleOriginRefusals()).isEqualTo(1L)
	}

	@Test
	@DisplayName("a stale-origin refusal after a non-silent (timed-out) cycle is not counted")
	fun refusalAfterTimeoutIsNotCounted() {
		val decisions = runCycle(adapter(timingOutAgent(), inferenceTimeout = Duration.ofMillis(50)))
		refuseAll(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)

		assertThat(decisions).containsExactly(STALE_REQUEST)
		assertThat(lastOutcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(staleOriginRefusals()).isEqualTo(0L)
	}

	@Test
	@DisplayName("another apply failure of a silent cycle's decision is not a stale-origin refusal")
	fun otherApplyFailureIsNotCounted() {
		runCycle(adapter(silentAgent()))
		refuseAll(ApplyFailureCode.ALL_PATHS_BLOCKED)

		assertThat(staleOriginRefusals()).isEqualTo(0L)
	}

	@Test
	@DisplayName("a refusal of a decision posted by the cycle after the silent one is not counted")
	fun refusalOfTheNextCycleIsNotCounted() {
		val planAdapter = adapter(silentAgent())
		runCycle(planAdapter)
		refuseAll(ApplyFailureCode.ALL_PATHS_BLOCKED)
		// The next cycle times out; its fallback decision carries the next cycle's tick index.
		val next = adapter(timingOutAgent(), inferenceTimeout = Duration.ofMillis(50))
		runCycle(next)
		refuseAll(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)

		assertThat(staleOriginRefusals()).isEqualTo(0L)
	}

	@Test
	@DisplayName("each refused decision of a silent cycle counts, across several silent cycles")
	fun everySilentCycleRefusalCounts() {
		val planAdapter = adapter(silentAgent())
		runCycle(planAdapter)
		refuseAll(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)
		runCycle(planAdapter)
		refuseAll(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)

		assertThat(staleOriginRefusals()).isEqualTo(2L)
	}
}
