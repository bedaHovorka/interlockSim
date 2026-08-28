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
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [KoogAgentPlanAdapter] (SP2b.9, Issue #566).
 *
 * ## SP2c.8 (Issue #831) — admission safety net deleted
 *
 * The admission safety net (`maybeForceAdmission`) has been deleted. These tests verify the
 * P3 principle: no non-LLM component originates a [DispatchDecision.ApproveTrain] during an
 * LLM run. With a no-op LLM (acting via tools but emitting no decisions), zero admission
 * decisions are force-posted to the command queue.
 *
 * @since Issue #566 (SP2b.9 — Goal 10); SP2c.6 (#829) simplifies the empty-decisions branch;
 *   SP2c.8 (#831) deletes the admission safety net
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
		inferenceTimeout: Duration = Duration.ofSeconds(30),
		commandQueue: ActuatorCommandQueue = ActuatorCommandQueue(),
		sinkHolder: SinkHolder = SinkHolder()
	): KoogAgentPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		val context = mockk<DefaultSimulationContext>()
		return KoogAgentPlanAdapter(
			agentFactory,
			context,
			fallback,
			inferenceTimeout,
			commandQueue,
			sinkHolder
		)
	}

	private fun observationWithQueue(
		unapprovedTrains: List<QueuedTrainObservation>,
		approvedTrainCount: Int
	): DispatchObservation =
		DispatchObservation(
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
			unapprovedTrains = unapprovedTrains,
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

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
	fun `empty decisions always invoke the rule-based fallback when no tool emitted (SP2c6)`() {
		// SP2c.6 (#829): the actuator tools emit to the SinkHolder seam, not to a return value
		// observed here. An empty decision list from decideAsync with NO tool emission means the
		// LLM truly did nothing this cycle → fall back. The emission counter (not the return value)
		// is now the load-bearing signal.
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 1) { fallback.decide(any()) }
	}

	@Test
	fun `an empty decision list with a tool emission skips the fallback (SP2c6 SinkHolder counter)`() {
		// SP2c.6 (#829): when the LLM acts via its actuator tools, the decisions are already posted
		// to the queue through sinkHolder.current; decideAsync still returns empty. The per-cycle
		// emission counter must detect this and skip the fallback — otherwise the rule engine's
		// own decisions double-dispatch to the same queue. This is the regression the counter
		// restores (the pre-#829 queue-actuation-side-effect heuristic went stale once tools were
		// rewired to SinkHolder, making every cycle 100% fallback).
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			// The LLM called an actuator tool during its cycle (emission already posted via the
			// queue-posting wrapper in production; here a bare sinkHolder with NO_OP default still
			// records the emission so the counter detects it).
			sinkHolder.emit(DispatchAction.ApproveTrain("T-1"))
			emptyList()
		}
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback, sinkHolder = sinkHolder).plan(observation) }

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

	// ── Idle-station classification (Issue #834) ──────────────────────────────

	/**
	 * Issue #834: the project owner reported a correct "nothing to do" LLM cycle on an empty
	 * station being mis-scored as a rule-based-fallback run failure
	 * (`fallback: reason=EMPTY_NO_TOOLS ... ollamaSuccessRate=27%`). An idle station — no
	 * approved (active) trains and no unapproved (queued) trains — with no LLM emissions must be
	 * reported as [TickOutcome.LLM_NO_OP] and the fallback dispatcher must never be consulted.
	 */
	@Test
	@DisplayName("idle station (no active or queued trains) with no LLM emissions reports LLM_NO_OP, not RULE_FALLBACK")
	fun `idle station with no LLM emissions reports LLM_NO_OP and skips fallback`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val idleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 0)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(idleObservation) }

		assertThat(result).isEmpty()
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	/**
	 * Production-faithful guard for Issue #927: with a REAL [RuleBasedDispatcher] as the fallback
	 * (the production wiring — see ExampleRegistry), a silent LLM cycle on a non-idle station where
	 * the rule oracle genuinely finds nothing to do must report [TickOutcome.LLM_SILENT_NONACTIONABLE].
	 *
	 * [RuleBasedDispatcher.decide] honours the [Dispatcher] contract "never empty" by returning
	 * `listOf(NoAction)` when nothing is actionable, so the classification must be on actionable
	 * content — not list emptiness. This is the test that the original `isEmpty()` predicate failed:
	 * against a contract-compliant dispatcher the non-actionable branch was unreachable.
	 */
	@Test
	@DisplayName("real RuleBasedDispatcher finding nothing actionable reports LLM_SILENT_NONACTIONABLE")
	fun `real RuleBasedDispatcher finding nothing actionable reports LLM_SILENT_NONACTIONABLE`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		// Real production fallback — no mock. An active train with no queued trains and no block
		// inputs gives RuleBasedDispatcher nothing to admit or advance → listOf(NoAction).
		val fallback: Dispatcher = RuleBasedDispatcher()
		val recorded = mutableListOf<TickRecord>()
		val nonIdleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(nonIdleObservation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_SILENT_NONACTIONABLE)
	}

	/**
	 * Issue #927: a silent LLM cycle on a non-idle station used to always report
	 * [TickOutcome.RULE_FALLBACK], even when the fallback dispatcher itself found nothing legal
	 * to do — mis-scoring a genuinely non-actionable tick as a dispatch failure. When
	 * `fallbackDispatcher.decide()` returns only [DispatchDecision.NoAction] (the
	 * contract-compliant "nothing actionable" signal — `decide()` is never empty), the tick must
	 * be reported as [TickOutcome.LLM_SILENT_NONACTIONABLE] instead.
	 */
	@Test
	@DisplayName("non-idle station, no emissions, fallback finds nothing actionable reports LLM_SILENT_NONACTIONABLE")
	fun `non-idle station with no LLM emissions and a nothing-actionable fallback reports LLM_SILENT_NONACTIONABLE`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()
		// Contract-compliant "nothing actionable": decide() returns listOf(NoAction), never empty.
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val nonIdleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(nonIdleObservation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_SILENT_NONACTIONABLE)
		coVerify(exactly = 1) { fallback.decide(any()) }
	}

	/**
	 * Regression guard (Issue #927): when the fallback dispatcher DOES find and dispatch
	 * something actionable on a silent non-idle cycle, that remains a genuine miss reported as
	 * [TickOutcome.RULE_FALLBACK] — the split only kicks in when the fallback also finds nothing
	 * actionable.
	 */
	@Test
	@DisplayName("non-idle station, no emissions, fallback finds an actionable decision still reports RULE_FALLBACK")
	fun `non-idle station with no LLM emissions and an actionable fallback still reports RULE_FALLBACK`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallbackDecisions = listOf(DispatchDecision.ApproveTrain("T-1"))
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions
		val recorded = mutableListOf<TickRecord>()
		val nonIdleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(nonIdleObservation) }

		assertThat(result).containsExactly(DispatchDecision.ApproveTrain("T-1"))
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		coVerify(exactly = 1) { fallback.decide(any()) }
	}

	/**
	 * Production-faithful guard for Issue #927: with a REAL [RuleBasedDispatcher] as the fallback,
	 * a silent LLM cycle on a non-idle station where the rule oracle DOES find something
	 * actionable (a queued train to admit) must report [TickOutcome.RULE_FALLBACK] — a genuine
	 * miss the LLM should have caught.
	 */
	@Test
	@DisplayName("non-idle station, no emissions, real RuleBasedDispatcher admits a queued train reports RULE_FALLBACK")
	fun `non-idle station with a queued train and a real RuleBasedDispatcher reports RULE_FALLBACK`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		// Real production fallback. A queued train with free capacity → RuleBasedDispatcher admits
		// it (ApproveTrain), so the fallback found something actionable → genuine miss.
		val fallback: Dispatcher = RuleBasedDispatcher()
		val recorded = mutableListOf<TickRecord>()
		val nonIdleObservation =
			observationWithQueue(
				unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
				approvedTrainCount = 0
			)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(nonIdleObservation) }

		assertThat(result).containsExactly(DispatchDecision.ApproveTrain("Train #1"))
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
	}

	/**
	 * [SimulationSnapshot.EMPTY] is the pre-first-capture sentinel — it carries no train
	 * positions and therefore *looks* idle without being a real idle tick (e.g. the very first
	 * cycle before [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.captureSnapshot] has
	 * ever run on the kDisco thread). A cycle observing it must NOT be scored as a successful
	 * no-op ([TickOutcome.LLM_NO_OP]); the [isIdleStation] sentinel guard routes it through the
	 * fallback. Under #927, when the fallback also finds nothing actionable (only
	 * [DispatchDecision.NoAction]), the tick is reported as [TickOutcome.LLM_SILENT_NONACTIONABLE]
	 * — still not a success, so the #834 guard's intent (never score the sentinel as LLM_NO_OP)
	 * is preserved.
	 */
	@Test
	@DisplayName("SimulationSnapshot.EMPTY sentinel with no emissions is not scored as LLM_NO_OP (guard)")
	fun `EMPTY sentinel snapshot with no emissions is not scored as LLM_NO_OP`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions
		val recorded = mutableListOf<TickRecord>()
		// `observation` (the class-level fixture) uses SimulationSnapshot.EMPTY with no queued
		// trains — structurally idle-looking but the pre-first-capture sentinel.
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_SILENT_NONACTIONABLE)
		coVerify(exactly = 1) { fallback.decide(any()) }
	}

	/**
	 * `SinkHolder.tryEmit` counts [DispatchAction.NoOp] as "acted" so the fallback correctly does
	 * not run on top of it (see [SinkHolder]'s KDoc), but an emission set consisting *only* of
	 * `no_op` is a no-op tick, not an action tick — it must be reported as [TickOutcome.LLM_NO_OP],
	 * not [TickOutcome.LLM_ACTIONS] (Issue #834, required change 2).
	 */
	@Test
	@DisplayName("an explicit no_op-only emission reports LLM_NO_OP, not LLM_ACTIONS")
	fun `only NoOp emissions report LLM_NO_OP`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.NoOp)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(observation) }

		assertThat(result).isEmpty()
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	@DisplayName("a no_op emission alongside a real action still reports LLM_ACTIONS")
	fun `NoOp plus a real action reports LLM_ACTIONS`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.NoOp)
			sinkHolder.emit(DispatchAction.ApproveTrain("T-1"))
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_ACTIONS)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	/**
	 * P3 — no non-LLM component originates a [DispatchDecision.ApproveTrain] during an LLM run
	 * (SP2c.8, Issue #831 — admission safety net deleted).
	 *
	 * The old `maybeForceAdmission()` would have force-posted [DispatchDecision.ApproveTrain]
	 * after every successful LLM cycle. With it removed, the command queue stays empty even
	 * when the LLM emits a no_op (via tools) while trains are queued and capacity is free.
	 */
	@Test
	@DisplayName("P3 — no non-LLM component originates ApproveTrain when LLM acts via tools (SP2c.8 #831)")
	fun `P3 LLM tool emission does not cause force-approval of queued trains`() {
		val commandQueue = ActuatorCommandQueue()
		val sinkHolder = SinkHolder()
		val fallback = mockk<Dispatcher>()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			// LLM emits a no_op via tool — actedThisCycle() returns true, success path taken
			sinkHolder.emit(DispatchAction.NoOp)
			emptyList()
		}
		val observation =
			observationWithQueue(
				unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
				approvedTrainCount = 0
			)

		runBlocking {
			adapter(koogAgent, fallback, commandQueue = commandQueue, sinkHolder = sinkHolder)
				.plan(observation)
		}

		// No ApproveTrain was force-posted — maybeForceAdmission is deleted (P3)
		assertThat(commandQueue.drain()).isEmpty()
		// LLM acted via tools → fallback was NOT invoked
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	// ── tickListener (SP2c.20 follow-up, Issue #843) ──────────────────────────

	@Test
	@DisplayName("tickListener fires TickOutcome.LLM_ACTIONS when the LLM acts via tools")
	fun `tickListener fires LLM_ACTIONS on tool emission success`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.ApproveTrain("T-1"))
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_ACTIONS)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	@DisplayName("tickListener fires LLM_SILENT_NONACTIONABLE on the empty-no-tools fallback when nothing is actionable")
	fun `tickListener fires LLM_SILENT_NONACTIONABLE on empty-no-tools fallback with nothing actionable`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_SILENT_NONACTIONABLE)
	}

	@Test
	@DisplayName("tickListener fires TickOutcome.RULE_FALLBACK on timeout fallback")
	fun `tickListener fires RULE_FALLBACK on timeout fallback`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, Duration.ofMillis(50))
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
	}

	@Test
	@DisplayName("tickListener fires TickOutcome.RULE_FALLBACK on exception fallback")
	fun `tickListener fires RULE_FALLBACK on exception fallback`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws RuntimeException("boom")
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
	}

	/**
	 * Issue #927 review (tick-accounting ordering): for TIMEOUT/EXCEPTION the tick must be
	 * reported BEFORE [fallbackDispatcher.decide] is called, so a fallback that itself throws
	 * cannot drop the cycle from tick accounting. The fallback's exception still propagates out
	 * of [plan], but the RULE_FALLBACK tick is recorded first — the failure mode where accurate
	 * accounting matters most.
	 */
	@Test
	@DisplayName("exception fallback whose decide() also throws still records the RULE_FALLBACK tick before propagating")
	fun `exception fallback that also throws still records the tick before propagating`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws RuntimeException("llm boom")
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } throws RuntimeException("fallback boom")
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		assertFailure { runBlocking { planAdapter.plan(observation) } }
			.isInstanceOf(RuntimeException::class.java)
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
	}

	@Test
	@DisplayName("tickListener does not disturb cycleListener/MeasuringPlanAdapter — both fire independently")
	fun `tickListener and cycleListener both fire without interfering`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.ApproveTrain("T-1"))
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val planAdapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder)

		var cycleListenerFired = false
		planAdapter.cycleListener =
			object : PlannerCycleListener {
				override fun onLlmSuccess(simTime: Double) {
					cycleListenerFired = true
				}

				override fun onFallback(
					reason: FallbackReason,
					simTime: Double
				) {
				}
			}
		val recorded = mutableListOf<TickRecord>()
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(cycleListenerFired).isTrue()
		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_ACTIONS)
	}

	/**
	 * Issue #847 round 4 (R4-2): agent creation used to sit **outside** `plan()`'s `try`, so a
	 * failure inside [KoogAgentFactory.createAgent] — which runs `OllamaModelPrewarmer.warmUp`, i.e.
	 * real network I/O — escaped `plan()` entirely.
	 *
	 * That mattered far more than a lost cycle: the exception propagated out of
	 * `AgentLoopDriver.runCycle()` into a bare `while (isSimActive())` loop running on a daemon
	 * thread with no uncaught-exception handler, killing the dispatcher for the rest of the run
	 * while the simulation ticked on to its requested end time and still exited 0.
	 *
	 * A creation failure must behave like every other LLM failure: counted as a fallback, reported
	 * to both listeners, and answered with rule-based decisions.
	 */
	@Test
	fun `agent creation failure falls back to rule-based instead of escaping plan`() {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } throws IllegalStateException("Ollama unreachable")
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions
		val planAdapter =
			KoogAgentPlanAdapter(
				agentFactory,
				mockk<DefaultSimulationContext>(),
				fallback,
				Duration.ofSeconds(30),
				ActuatorCommandQueue(),
				SinkHolder()
			)
		val fallbackReasons = mutableListOf<FallbackReason>()
		planAdapter.cycleListener =
			object : PlannerCycleListener {
				override fun onLlmSuccess(simTime: Double) = Unit

				override fun onFallback(
					reason: FallbackReason,
					simTime: Double
				) {
					fallbackReasons.add(reason)
				}
			}
		val recorded = mutableListOf<TickRecord>()
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		val result = runBlocking { planAdapter.plan(observation) }

		assertThat(result, "decisions returned").isEqualTo(fallbackDecisions)
		assertThat(fallbackReasons, "fallback reasons recorded").containsExactly(FallbackReason.EXCEPTION)
		assertThat(recorded, "tick records").hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
	}

	/**
	 * A creation failure must not poison the adapter: the next cycle retries. Without this, one
	 * transient Ollama hiccup at startup would demote a whole 600 s measurement run to rule-based.
	 */
	@Test
	fun `a later cycle retries agent creation after an earlier failure`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } throws IllegalStateException("transient") andThen koogAgent
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns emptyList()
		val planAdapter =
			KoogAgentPlanAdapter(
				agentFactory,
				mockk<DefaultSimulationContext>(),
				fallback,
				Duration.ofSeconds(30),
				ActuatorCommandQueue(),
				SinkHolder()
			)

		val first = runBlocking { planAdapter.plan(observation) }
		val second = runBlocking { planAdapter.plan(observation) }

		assertThat(first, "first cycle uses the rule-based fallback").isEmpty()
		assertThat(second, "second cycle reaches the LLM").containsExactly(DispatchDecision.NoAction)
	}

	// ── Cycle latency (Issue #834, SP2c.11) ────────────────────────────────────
	//
	// Every reported TickRecord must carry a non-null, non-negative latencyMs — including the
	// timeout ending, where the elapsed time IS the deadline (a real, interesting measurement,
	// not a missing one). Tests assert only `>= 0` / non-null, never a tight bound, per the
	// no-clock-flakiness rule.

	@Test
	@DisplayName("a successful LLM_ACTIONS cycle reports a non-null, non-negative latency")
	fun `successful cycle reports non-null non-negative latency`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.ApproveTrain("T-1"))
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_ACTIONS)
		assertThat(recorded.first().latencyMs).isNotNull()
		assertThat(recorded.first().latencyMs!!).isGreaterThanOrEqualTo(0L)
	}

	@Test
	@DisplayName("an idle-station LLM_NO_OP cycle reports a non-null, non-negative latency")
	fun `idle no-op cycle reports non-null non-negative latency`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()
		val recorded = mutableListOf<TickRecord>()
		val idleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 0)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(idleObservation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_NO_OP)
		assertThat(recorded.first().latencyMs).isNotNull()
		assertThat(recorded.first().latencyMs!!).isGreaterThanOrEqualTo(0L)
	}

	@Test
	@DisplayName("a non-idle empty-no-tools LLM_SILENT_NONACTIONABLE cycle reports a non-null, non-negative latency")
	fun `non-idle empty-no-tools fallback cycle reports non-null non-negative latency`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val nonIdleObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(nonIdleObservation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.LLM_SILENT_NONACTIONABLE)
		assertThat(recorded.first().latencyMs).isNotNull()
		assertThat(recorded.first().latencyMs!!).isGreaterThanOrEqualTo(0L)
	}

	@Test
	@DisplayName("a timed-out cycle reports a non-null, non-negative latency — the measured elapsed time IS the deadline")
	fun `timed-out cycle reports non-null non-negative latency`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback, Duration.ofMillis(50))
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(recorded.first().latencyMs).isNotNull()
		assertThat(recorded.first().latencyMs!!).isGreaterThanOrEqualTo(0L)
	}

	@Test
	@DisplayName("an exception thrown during inference reports a non-null, non-negative latency")
	fun `exception during inference reports non-null non-negative latency`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws RuntimeException("boom")
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val recorded = mutableListOf<TickRecord>()
		val planAdapter = adapter(koogAgent, fallback)
		planAdapter.addTickListener(PlannerTickListener { recorded.add(it) })

		runBlocking { planAdapter.plan(observation) }

		assertThat(recorded).hasSize(1)
		assertThat(recorded.first().outcome).isEqualTo(TickOutcome.RULE_FALLBACK)
		assertThat(recorded.first().latencyMs).isNotNull()
		assertThat(recorded.first().latencyMs!!).isGreaterThanOrEqualTo(0L)
	}

	@Test
	@DisplayName("a slower cycle reports a greater or equal latency than a faster one (relative ordering, no tight bound)")
	fun `slower cycle reports greater or equal latency than a faster one`() {
		val fastAgent = mockk<KoogDispatchAgent>()
		coEvery { fastAgent.decideAsync(any()) } returns emptyList()
		val slowAgent = mockk<KoogDispatchAgent>()
		coEvery { slowAgent.decideAsync(any()) } coAnswers {
			delay(80)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val fastObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)
		val slowObservation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 1)

		val fastRecorded = mutableListOf<TickRecord>()
		val fastAdapter = adapter(fastAgent, fallback)
		fastAdapter.addTickListener(PlannerTickListener { fastRecorded.add(it) })
		runBlocking { fastAdapter.plan(fastObservation) }

		val slowRecorded = mutableListOf<TickRecord>()
		val slowAdapter = adapter(slowAgent, fallback)
		slowAdapter.addTickListener(PlannerTickListener { slowRecorded.add(it) })
		runBlocking { slowAdapter.plan(slowObservation) }

		assertThat(slowRecorded.first().latencyMs!!)
			.isGreaterThanOrEqualTo(fastRecorded.first().latencyMs!!)
	}
}
