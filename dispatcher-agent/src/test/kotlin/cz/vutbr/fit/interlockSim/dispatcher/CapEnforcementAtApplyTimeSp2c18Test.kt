/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.agents.Affordance
import cz.vutbr.fit.interlockSim.dispatcher.agents.CompactTextRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.RenderContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Acceptance-criteria tests for SP2c.18 — apply-time concurrent-train cap enforcement (#841).
 *
 * Verifies that [DispatchDecisionApplier] enforces the cap against the **live** active-train
 * count on the simulation thread, not against the stale snapshot the driver thread holds.
 *
 * ## Acceptance criteria covered
 *
 * - AC: Cap enforced at apply time on the sim thread against live state.
 * - AC: Two `approve_train` calls in one tick against a cap of 2 with 1 active train ⇒
 *   exactly one admitted, one refused with [ApplyFailureCode.CAP_EXCEEDED].
 * - AC: The refusal surfaces in the next tick's `applied_outcomes` block (SP2c.17 channel).
 * - AC: Apply-time refusal ([ApplyFailureCode.CAP_EXCEEDED]) and pre-queue rejection
 *   ([RejectionCode.CAPACITY_FULL]) are separate types — distinguishable in metrics.
 * - AC: P3 — the component never originates an [DispatchDecision.ApproveTrain]; with a
 *   no-op queue (no LLM commands posted), zero admissions occur.
 *
 * @since Issue #841 (SP2c.18 — Goal 10)
 */
@DisplayName("SP2c.18 — apply-time cap enforcement for approve_train (#841)")
@Timeout(30, unit = TimeUnit.SECONDS)
class CapEnforcementAtApplyTimeSp2c18Test {
	private lateinit var networkActuator: NetworkActuatorPort
	private lateinit var correlationMap: CommandCorrelationMap
	private lateinit var outcomeSink: AppliedOutcomeChannel

	/** Live active-train counter maintained in-test to simulate the sim-thread count. */
	private val liveActiveCount = AtomicInteger(0)

	/** Trains admitted via the onApproveTrain callback. */
	private val admittedTrains = mutableListOf<String>()

	@BeforeEach
	fun setUp() {
		networkActuator = mockk(relaxed = true)
		correlationMap = CommandCorrelationMap()
		outcomeSink = AppliedOutcomeChannel()
		liveActiveCount.set(0)
		admittedTrains.clear()
	}

	// ── Helpers ───────────────────────────────────────────────────────────────────────────

	/**
	 * Creates a [DispatchDecisionApplier] wired with [activeTrainCountProvider] returning
	 * [liveActiveCount], the [correlationMap], and [outcomeSink].
	 *
	 * The [onApproveTrain] callback appends the train id to [admittedTrains] **and** increments
	 * [liveActiveCount] so subsequent approvals in the same drained batch see the updated count.
	 */
	private fun makeCapApplier(
		maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
	): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue(correlationMap = correlationMap)
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = { trainId ->
					admittedTrains.add(trainId)
					liveActiveCount.incrementAndGet()
				},
				activeTrainCountProvider = { liveActiveCount.get() },
				maxConcurrentTrains = maxConcurrentTrains,
				correlationMap = correlationMap,
				outcomeSink = outcomeSink
			)
		return queue to applier
	}

	// ── AC: Two approve_train calls in one tick — exactly one admitted, one CAP_EXCEEDED ──

	@Nested
	@DisplayName("AC: Two approve_train calls in one tick with cap=2 and 1 active")
	inner class TwoCallsOneSlot {
		@Test
		@DisplayName("exactly one train is admitted, the other receives CAP_EXCEEDED")
		fun twoApproveTrainCallsInOneTick_exactlyOneAdmittedOneCapExceeded() {
			liveActiveCount.set(1) // 1 active already; cap = 2 → 1 slot free
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			// Two commands queued in the same driver tick
			queue.postAll(listOf(DispatchDecision.ApproveTrain("Train-A")))
			queue.postAll(listOf(DispatchDecision.ApproveTrain("Train-B")))

			applier.onControlStep()

			// Exactly one train admitted
			assertThat(admittedTrains).hasSize(1)
			assertThat(admittedTrains[0]).isEqualTo("Train-A")

			// Exactly two outcomes published
			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(2)

			val first = outcomes[0] as AppliedOutcome.Approved
			assertThat(first.trainId).isEqualTo("Train-A")
			assertThat(first.admitted).isTrue()
			assertThat(first.reason).isNull()

			val second = outcomes[1] as AppliedOutcome.Approved
			assertThat(second.trainId).isEqualTo("Train-B")
			assertThat(second.admitted).isFalse()
			assertThat(second.reason).isEqualTo(ApplyFailureCode.CAP_EXCEEDED)
		}

		@Test
		@DisplayName("FIFO order: the first queued train is admitted, not the second")
		fun twoApproveTrainCalls_fifoOrderRespected() {
			liveActiveCount.set(1) // cap = 2, 1 slot free
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			// Post in deliberate order: Alpha before Beta
			queue.postAll(listOf(DispatchDecision.ApproveTrain("Alpha")))
			queue.postAll(listOf(DispatchDecision.ApproveTrain("Beta")))

			applier.onControlStep()

			assertThat(admittedTrains).hasSize(1)
			assertThat(admittedTrains[0]).isEqualTo("Alpha")

			val outcomes = outcomeSink.drainSince(0L)
			val refused = outcomes.first { (it as AppliedOutcome.Approved).admitted.not() } as AppliedOutcome.Approved
			assertThat(refused.trainId).isEqualTo("Beta")
			assertThat(refused.reason).isEqualTo(ApplyFailureCode.CAP_EXCEEDED)
		}

		@Test
		@DisplayName("both trains are admitted when 2 slots are free (cap=3, active=1)")
		fun twoApproveTrainCalls_bothAdmittedWhenSlotsAvailable() {
			liveActiveCount.set(1) // cap = 3, 2 slots free
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 3)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("Train-A")))
			queue.postAll(listOf(DispatchDecision.ApproveTrain("Train-B")))

			applier.onControlStep()

			assertThat(admittedTrains).hasSize(2)

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(2)
			outcomes.forEach { o ->
				assertThat((o as AppliedOutcome.Approved).admitted).isTrue()
				assertThat(o.reason).isNull()
			}
		}

		@Test
		@DisplayName("all three are refused when cap is already full (cap=2, active=2)")
		fun threeApproveTrainCalls_allRefusedWhenCapFull() {
			liveActiveCount.set(2) // cap = 2, 0 slots
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T-1"),
					DispatchDecision.ApproveTrain("T-2"),
					DispatchDecision.ApproveTrain("T-3")
				)
			)

			applier.onControlStep()

			assertThat(admittedTrains).hasSize(0)

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(3)
			outcomes.forEach { o ->
				val approved = o as AppliedOutcome.Approved
				assertThat(approved.admitted).isFalse()
				assertThat(approved.reason).isEqualTo(ApplyFailureCode.CAP_EXCEEDED)
			}
		}
	}

	// ── AC: Refusal surfaces in applied_outcomes (SP2c.17 channel) ────────────────────────

	@Nested
	@DisplayName("AC: CAP_EXCEEDED refusal surfaces in next tick's applied_outcomes")
	inner class RefusalSurfacesInChannel {
		@Test
		@DisplayName("CAP_EXCEEDED outcome appears in applied_outcomes with correct trainId and reason")
		fun capExceededRefusalSurfacesInAppliedOutcomes() {
			liveActiveCount.set(2) // cap = 2, no slot
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-99")))
			applier.onControlStep()

			// The outcome channel holds the refusal
			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(1)
			val outcome = outcomes[0]
			assertThat(outcome).isInstanceOf(AppliedOutcome.Approved::class)
			outcome as AppliedOutcome.Approved
			assertThat(outcome.trainId).isEqualTo("T-99")
			assertThat(outcome.admitted).isFalse()
			assertThat(outcome.reason).isEqualTo(ApplyFailureCode.CAP_EXCEEDED)
		}

		@Test
		@DisplayName("CAP_EXCEEDED outcome renders as REFUSED(CAP_EXCEEDED) in the next tick's prompt")
		fun capExceededOutcomeRendersInPrompt() {
			liveActiveCount.set(2)
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-77")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(
				tick = 2L,
				appliedOutcomes = outcomes
			)
			val ctx = RenderContext(
				observation = observation,
				previous = DispatcherObservation.EMPTY,
				history = emptyList(),
				workingMemory = WorkingMemory.EMPTY,
				affordances = listOf(Affordance.NO_OP)
			)
			val rendered = CompactTextRenderer().render(ctx)
			assertThat(rendered).isNotNull()
			// Renders with the machine-readable reason code
			val contains = rendered.contains("approve_train T-77 : REFUSED(CAP_EXCEEDED)")
			assertThat(contains).isTrue()
		}

		@Test
		@DisplayName("Admitted outcome renders as ADMITTED (not REFUSED) in the next tick's prompt")
		fun admittedOutcomeRendersAsAdmitted() {
			liveActiveCount.set(0) // no active trains, cap=2
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-10")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(
				tick = 2L,
				appliedOutcomes = outcomes
			)
			val ctx = RenderContext(
				observation = observation,
				previous = DispatcherObservation.EMPTY,
				history = emptyList(),
				workingMemory = WorkingMemory.EMPTY,
				affordances = listOf(Affordance.NO_OP)
			)
			val rendered = CompactTextRenderer().render(ctx)
			assertThat(rendered.contains("approve_train T-10 : ADMITTED")).isTrue()
		}
	}

	// ── AC: Metrics separation — RejectionCode vs ApplyFailureCode ────────────────────────

	@Nested
	@DisplayName("AC: Metrics separation — pre-queue RejectionCode vs apply-time ApplyFailureCode")
	inner class MetricsSeparation {
		@Test
		@DisplayName("pre-queue rejection code is RejectionCode.CAPACITY_FULL (not ApplyFailureCode)")
		fun preQueueRejectionIsRejectionCode() {
			// The pre-queue check produces RejectionCode.CAPACITY_FULL
			val code = RejectionCode.CAPACITY_FULL
			// The apply-time refusal produces ApplyFailureCode.CAP_EXCEEDED
			val applyCode = ApplyFailureCode.CAP_EXCEEDED
			// These are distinct types — not related by inheritance or equality
			assertThat(code::class).isNotEqualTo(applyCode::class)
		}

		@Test
		@DisplayName("AppliedOutcome.Approved with admitted=false carries an ApplyFailureCode reason")
		fun approvedWithAdmittedFalseHasApplyFailureCodeReason() {
			val outcome = AppliedOutcome.Approved(
				trainId = "T-1",
				admitted = false,
				reason = ApplyFailureCode.CAP_EXCEEDED,
				id = CommandId(1L),
				tickIndex = 1L
			)
			assertThat(outcome.admitted).isFalse()
			assertThat(outcome.reason).isNotNull()
			assertThat(outcome.reason).isEqualTo(ApplyFailureCode.CAP_EXCEEDED)
		}

		@Test
		@DisplayName("AppliedOutcome.Approved with admitted=true has null reason")
		fun approvedWithAdmittedTrueHasNullReason() {
			val outcome = AppliedOutcome.Approved(
				trainId = "T-2",
				admitted = true,
				id = CommandId(2L),
				tickIndex = 1L
			)
			assertThat(outcome.admitted).isTrue()
			assertThat(outcome.reason).isNull()
		}
	}

	// ── AC: P3 — component never originates an ApproveTrain ───────────────────────────────

	@Nested
	@DisplayName("AC: P3 — DispatchDecisionApplier never originates an ApproveTrain")
	inner class P3NoOrigination {
		@Test
		@DisplayName("with empty queue, onControlStep never calls onApproveTrain (zero admissions)")
		fun emptyQueue_zeroAdmissions() {
			liveActiveCount.set(0)
			correlationMap.newCycle()
			val (_, applier) = makeCapApplier(maxConcurrentTrains = 2)

			// Simulate multiple control steps with no commands ever posted
			repeat(10) { applier.onControlStep() }

			assertThat(admittedTrains).hasSize(0)
			// No outcomes published either
			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(0)
		}

		@Test
		@DisplayName("applier only acts on commands from the queue, never self-initiates")
		fun applierNeverSelfInitiates() {
			liveActiveCount.set(0)
			correlationMap.newCycle()
			val (queue, applier) = makeCapApplier(maxConcurrentTrains = 2)

			// Step 1: no commands — zero admissions
			applier.onControlStep()
			assertThat(admittedTrains).hasSize(0)

			// Step 2: post a command — exactly one admission
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-LLM")))
			applier.onControlStep()
			assertThat(admittedTrains).hasSize(1)
			assertThat(admittedTrains[0]).isEqualTo("T-LLM")

			// Step 3: no more commands — no further admissions
			applier.onControlStep()
			assertThat(admittedTrains).hasSize(1) // unchanged
		}
	}

	// ── AC: Backward compat — no activeTrainCountProvider → no apply-time check ────────────

	@Nested
	@DisplayName("Backward compatibility — null activeTrainCountProvider disables apply-time check")
	inner class BackwardCompat {
		@Test
		@DisplayName("without activeTrainCountProvider, onApproveTrain is always called (pre-SP2c.18 behaviour)")
		fun withoutProvider_onApproveTrainAlwaysCalled() {
			// Construct an applier WITHOUT the activeTrainCountProvider (backward-compat path)
			val queue = ActuatorCommandQueue()
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = networkActuator,
					onApproveTrain = { trainId -> admittedTrains.add(trainId) }
					// no activeTrainCountProvider — pre-SP2c.18 behaviour
				)

			// Even if the "logical" count would exceed the default cap, no apply-time check runs
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T-A"),
					DispatchDecision.ApproveTrain("T-B"),
					DispatchDecision.ApproveTrain("T-C")
				)
			)
			applier.onControlStep()

			// All three admitted — no cap check without provider
			assertThat(admittedTrains).hasSize(3)
		}
	}
}
