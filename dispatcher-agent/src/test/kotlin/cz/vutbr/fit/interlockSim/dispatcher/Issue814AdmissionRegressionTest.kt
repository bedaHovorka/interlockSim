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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Deterministic regression coverage for Issue #814 — the SP2b.9 follow-up whose verification list
 * has never been executed end to end.
 *
 * ## What #814 actually asks
 *
 * #814 is written as a *live* checklist ("run `shuntingLoopAI` against real Ollama until 4–5
 * trains queue and depart, confirm `approve_train` keeps firing, confirm no `OwnershipConflict`,
 * confirm no unexpected fallback"). A live LLM run cannot be a CI gate: it is slow, needs a GPU,
 * and its outcome depends on model behaviour that changes between releases.
 *
 * This class answers the same questions **deterministically**, by driving a real `vyhybna.xml`
 * simulation through the production lifted stack with a *scripted planner* that reproduces the
 * exact LLM misbehaviours #814 observed:
 *
 * | #814 symptom | Scripted planner used here |
 * |---|---|
 * | LLM never calls `approve_train` | [NeverAdmitsPlanner] (+ safety net) |
 * | LLM admits the first train, then stops | [AdmitsOnceThenStopsPlanner] (+ safety net) |
 * | LLM redundantly re-issues `request_route` for a train that already holds one | [RedundantRouteRequestPlanner] |
 *
 * ## The live half of #814 stays manual, deliberately
 *
 * #814's own wording ("run `shuntingLoopAI` ... for long enough that at least 4-5 trains queue and
 * depart") describes a session, not a test. Under the lock-step handshake this fixture uses, one
 * driver cycle runs per simulation tick, so a 300 s run would need ~300 sequential LLM calls —
 * well over half an hour of wall clock, with an outcome that varies by model. That is a manual
 * verification run:
 *
 * ```
 * ./gradlew :desktop-ui:runExampleAIGui -PendTime=600
 * ```
 *
 * The live *capabilities* those runs depend on are covered by the `@Tag("ollama-test")` suite
 * (`OllamaRuntimeContractOllamaTest`, `KoogRealOllamaToolCallingTest`,
 * `OllamaFormatToolsCoexistenceOllamaTest`); what this class covers is the dispatcher behaviour
 * #814 was actually worried about, at a speed that can gate CI.
 *
 * ## Why the safety net is modelled rather than reused
 *
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] needs a real
 * `KoogAgentFactory` and a live LLM, so it cannot run here. Its admission safety net is a small,
 * fully-specified policy — "post `ApproveTrain` for the oldest queued train(s), FIFO, up to the
 * free capacity, after every completed non-fallback cycle" — and [SafetyNet] reimplements exactly
 * that policy against the same [ActuatorCommandQueue]. The unit-level fidelity of the real
 * implementation to this policy is already locked down by `KoogAgentPlanAdapterTest`; what this
 * class adds is what that unit test cannot show — that the policy actually keeps trains flowing
 * through a real simulation.
 *
 * @see cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapterTest for the unit-level
 *   safety-net policy tests
 * @see ActionValidator814RegressionTest for the pre-queue rejection of a redundant `request_route`
 * @since Issue #814 (SP2b.9 follow-up — Goal 10)
 */
@DisplayName("Issue #814 — admission keeps firing and stale route replays never stall a train")
@Tag("integration-test")
@Timeout(3, unit = TimeUnit.MINUTES)
class Issue814AdmissionRegressionTest {
	private val fixture = LiftedStackFixture()

	/** Long enough for several trains to be generated, admitted and to exit. */
	private val endTimeSeconds = 300L

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	// ── Scripted planners reproducing #814's observed LLM behaviours ───────────────────────

	/**
	 * Reimplements [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]'s admission
	 * safety net: after a completed cycle, force-approve the oldest queued train(s), FIFO, up to
	 * the free capacity. Counts its own admissions so a test can attribute admissions to the net
	 * rather than to the planner — #814's final checklist item.
	 */
	private class SafetyNet(
		private val queue: ActuatorCommandQueue,
		private val maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
	) {
		val forcedAdmissions = AtomicInteger(0)

		fun run(observation: DispatchObservation) {
			val freeSlots = maxConcurrentTrains - observation.approvedTrainCount
			if (freeSlots <= 0) return
			val toAdmit = observation.unapprovedTrains.take(freeSlots)
			if (toAdmit.isEmpty()) return
			forcedAdmissions.addAndGet(toAdmit.size)
			queue.postAll(toAdmit.map { DispatchDecision.ApproveTrain(it.trainId) })
		}
	}

	/** Shared plumbing for the scripted planners: routing decisions plus optional safety net. */
	private abstract class ScriptedPlanner(
		protected val queue: ActuatorCommandQueue
	) : DispatcherPlanner {
		val cycles = AtomicLong(0)
		val plannerAdmissions = AtomicInteger(0)

		override val capabilities: PlannerCapabilities =
			PlannerCapabilities(
				name = "Scripted814",
				isAsynchronous = false,
				maxSpeedMultiplier = PlannerCapabilities.UNRESTRICTED
			)

		/** Routing-only decisions from the rule-based dispatcher, with admissions stripped out. */
		protected fun routingOnly(observation: DispatchObservation): List<DispatchDecision> =
			RuleBasedDispatcher()
				.decide(observation)
				.filterNot { it is DispatchDecision.ApproveTrain }
	}

	/**
	 * #814 symptom 1: the LLM never calls `approve_train` at all. Routing still happens, so
	 * without the safety net no train is ever admitted and the station stays empty forever.
	 */
	private class NeverAdmitsPlanner(
		queue: ActuatorCommandQueue,
		private val safetyNet: SafetyNet?
	) : ScriptedPlanner(queue) {
		override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
			cycles.incrementAndGet()
			val decisions = routingOnly(observation)
			safetyNet?.run(observation)
			return decisions
		}
	}

	/**
	 * #814 symptom 2: the LLM admits its first train, then stops calling `approve_train` for every
	 * subsequent cycle despite free capacity. Without the safety net, exactly one train ever runs.
	 */
	private class AdmitsOnceThenStopsPlanner(
		queue: ActuatorCommandQueue,
		private val safetyNet: SafetyNet?
	) : ScriptedPlanner(queue) {
		private var admittedOnce = false

		override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
			cycles.incrementAndGet()
			val decisions = mutableListOf<DispatchDecision>()
			if (!admittedOnce) {
				observation.unapprovedTrains.firstOrNull()?.let {
					admittedOnce = true
					plannerAdmissions.incrementAndGet()
					decisions += DispatchDecision.ApproveTrain(it.trainId)
				}
			}
			decisions += routingOnly(observation)
			safetyNet?.run(observation)
			return decisions
		}
	}

	/**
	 * #814 symptom 2's corruption half: a stateless LLM re-issues `request_route` for a train that
	 * already holds a route to the same target. Before the `reservePath` idempotency guard, the
	 * re-merge corrupted `PathReservationRegistry`'s `PathInfo` and froze the train at its first
	 * semaphore with an `OwnershipConflict`.
	 *
	 * Reproducing it needs more than emitting each decision twice: the rule-based dispatcher only
	 * proposes the *next* hop, so a within-cycle duplicate targets a hop the train does not hold
	 * yet and never reaches the guard. What #814 actually saw was a **stateless** dispatcher
	 * re-issuing a route it had already been granted on an *earlier* cycle, after the train had
	 * moved on. This planner therefore remembers the first `ReservePath` it ever emitted per train
	 * and re-emits that stale decision on every later cycle, alongside the current one.
	 */
	private class RedundantRouteRequestPlanner(
		queue: ActuatorCommandQueue,
		private val safetyNet: SafetyNet?
	) : ScriptedPlanner(queue) {
		/** First route ever proposed for each train — replayed forever, as a stateless LLM would. */
		private val firstRouteByTrain = mutableMapOf<String, DispatchDecision.ReservePath>()

		val staleReRequests = AtomicInteger(0)

		override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
			cycles.incrementAndGet()
			val routing = routingOnly(observation)

			routing.filterIsInstance<DispatchDecision.ReservePath>().forEach { reservation ->
				firstRouteByTrain.putIfAbsent(reservation.trainId, reservation)
			}

			// Replay every previously-granted route for trains that are still active. Each replay
			// is a request for a route the train already holds (or has already passed) — precisely
			// the redundant `request_route` that used to corrupt PathInfo.
			val activeTrainIds =
				observation.snapshot.trainPositions
					.map { it.trainId }
					.toSet()
			val replays =
				firstRouteByTrain
					.filterKeys { it in activeTrainIds }
					.values
					.toList()
			staleReRequests.addAndGet(replays.size)

			safetyNet?.run(observation)
			return routing + replays
		}
	}

	// ── Harness ───────────────────────────────────────────────────────────────────────────

	private class RunOutcome(
		val trainsExited: Int,
		val maxConcurrent: Int,
		val conflictEvents: List<ConflictDetectedEvent>,
		val cycles: Long,
		val plannerAdmissions: Int,
		val safetyNetAdmissions: Int,
		val staleReRequests: Int
	)

	private fun runWith(
		plannerBuilder: (ActuatorCommandQueue, SafetyNet?) -> ScriptedPlanner,
		withSafetyNet: Boolean
	): RunOutcome {
		val context = fixture.loadShuntingLoopContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = endTimeSeconds)

		val conflictEvents = mutableListOf<ConflictDetectedEvent>()
		context.onConflictDetectedEvent { conflictEvents.add(it) }

		var planner: ScriptedPlanner? = null
		var safetyNet: SafetyNet? = null
		val run =
			fixture.run(loop, context) { queue ->
				safetyNet = if (withSafetyNet) SafetyNet(queue) else null
				plannerBuilder(queue, safetyNet).also { planner = it }
			}

		return RunOutcome(
			trainsExited = run.trainsExited(),
			maxConcurrent = run.maxConcurrentTrains(),
			conflictEvents = conflictEvents,
			cycles = planner!!.cycles.get(),
			plannerAdmissions = planner!!.plannerAdmissions.get(),
			safetyNetAdmissions = safetyNet?.forcedAdmissions?.get() ?: 0,
			staleReRequests = (planner as? RedundantRouteRequestPlanner)?.staleReRequests?.get() ?: 0
		)
	}

	// ── Symptom 1: the LLM never admits ───────────────────────────────────────────────────

	@Nested
	@DisplayName("Symptom 1 — the planner never calls approve_train")
	inner class PlannerNeverAdmits {
		/**
		 * Establishes the failure this is a regression test *for*: with no safety net and a
		 * planner that never admits, nothing ever enters the station. If this ever stopped being
		 * true, some other component would be originating admissions — which P3 forbids.
		 */
		@Test
		@DisplayName("without the safety net, no train is ever admitted (the #814 failure)")
		fun withoutSafetyNetNothingRuns() {
			val outcome = runWith({ queue, net -> NeverAdmitsPlanner(queue, net) }, withSafetyNet = false)

			logger.info { "#814 symptom 1, no safety net: exited=${outcome.trainsExited} cycles=${outcome.cycles}" }
			assertThat(outcome.cycles).isGreaterThanOrEqualTo(1L)
			assertThat(outcome.trainsExited).isEqualTo(0)
			assertThat(outcome.maxConcurrent).isEqualTo(0)
		}

		/**
		 * The fix: the safety net admits on the planner's behalf and trains flow again. Would fail
		 * if the net's FIFO/free-capacity policy were dropped or if `ApproveTrain` stopped being
		 * accepted from the queue.
		 */
		@Test
		@DisplayName("with the safety net, trains are admitted and exit despite the planner never admitting")
		fun safetyNetKeepsTrainsFlowing() {
			val outcome = runWith({ queue, net -> NeverAdmitsPlanner(queue, net) }, withSafetyNet = true)

			logger.info {
				"#814 symptom 1, with safety net: exited=${outcome.trainsExited} " +
					"maxConcurrent=${outcome.maxConcurrent} netAdmissions=${outcome.safetyNetAdmissions}"
			}
			assertThat(outcome.trainsExited).isGreaterThanOrEqualTo(1)
			assertThat(outcome.safetyNetAdmissions).isGreaterThanOrEqualTo(1)
			assertThat(outcome.plannerAdmissions).isEqualTo(0)
		}

		/**
		 * The net must never over-admit. `ShuntingLoop` refuses beyond its own cap as a backstop,
		 * so this asserts the observable peak rather than the net's intent — which is what a
		 * railway operator would actually care about.
		 */
		@Test
		@DisplayName("the safety net never pushes the station above its concurrent-train cap")
		fun safetyNetRespectsTheCap() {
			val outcome = runWith({ queue, net -> NeverAdmitsPlanner(queue, net) }, withSafetyNet = true)

			assertThat(outcome.maxConcurrent)
				.isLessThanOrEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		}
	}

	// ── Symptom 2: admission stops after the first train ──────────────────────────────────

	@Nested
	@DisplayName("Symptom 2 — the planner admits once, then stops")
	inner class AdmissionStopsAfterFirstTrain {
		/**
		 * The exact shape #814 reported: the first train is admitted, then admission silently
		 * stops even though capacity is free. Without the net, the run stalls at one train.
		 */
		@Test
		@DisplayName("without the safety net, only the first train ever runs")
		fun withoutSafetyNetOnlyOneTrainRuns() {
			val outcome =
				runWith({ queue, net -> AdmitsOnceThenStopsPlanner(queue, net) }, withSafetyNet = false)

			logger.info {
				"#814 symptom 2, no safety net: exited=${outcome.trainsExited} " +
					"plannerAdmissions=${outcome.plannerAdmissions}"
			}
			assertThat(outcome.plannerAdmissions).isEqualTo(1)
			assertThat(outcome.trainsExited).isLessThanOrEqualTo(1)
		}

		/**
		 * **The core #814 assertion.** Over a run long enough for several trains to queue, at
		 * least four distinct trains must complete their journey — i.e. admission keeps firing for
		 * the 2nd, 3rd and 4th train, not just the first.
		 */
		@Test
		@DisplayName("with the safety net, at least four trains exit — admission keeps firing")
		fun admissionKeepsFiringForLaterTrains() {
			val outcome =
				runWith({ queue, net -> AdmitsOnceThenStopsPlanner(queue, net) }, withSafetyNet = true)

			logger.info {
				"#814 symptom 2, with safety net: exited=${outcome.trainsExited} " +
					"plannerAdmissions=${outcome.plannerAdmissions} " +
					"netAdmissions=${outcome.safetyNetAdmissions}"
			}
			assertThat(outcome.trainsExited).isGreaterThanOrEqualTo(4)
		}

		/**
		 * #814's last checklist item, made measurable: "if the safety net ends up doing most of
		 * the admission work, note that as a finding". Here the planner admits exactly once by
		 * construction, so every later admission is attributable to the net — and the two counters
		 * are separately observable, which is what makes the finding reportable at all.
		 */
		@Test
		@DisplayName("planner-initiated and safety-net admissions are separately attributable")
		fun admissionAttributionIsMeasurable() {
			val outcome =
				runWith({ queue, net -> AdmitsOnceThenStopsPlanner(queue, net) }, withSafetyNet = true)

			val total = outcome.plannerAdmissions + outcome.safetyNetAdmissions
			logger.info {
				"#814 admission attribution: planner=${outcome.plannerAdmissions} " +
					"safetyNet=${outcome.safetyNetAdmissions} total=$total"
			}
			assertThat(outcome.plannerAdmissions).isEqualTo(1)
			assertThat(outcome.safetyNetAdmissions).isGreaterThanOrEqualTo(1)
			assertThat(total).isGreaterThanOrEqualTo(outcome.trainsExited)
		}
	}

	// ── Symptom 2b: redundant request_route must not corrupt the registry ─────────────────

	/**
	 * #814's third checklist item: "confirm no `OwnershipConflict`/permanent-stall lines appear,
	 * **even if** the LLM redundantly re-calls `request_route` for an already-granted train."
	 *
	 * ## Scope — what these tests do and do not prove
	 *
	 * These are **system-level robustness** checks, not a regression test for the `reservePath`
	 * idempotency guard specifically. That distinction was established empirically, not assumed:
	 * disabling the guard in `DefaultPathReservationService` leaves every test below green, while
	 * it immediately fails
	 * `PathReservationServiceTest.RedundantReservationIdempotency` — which is where the guard's
	 * own contract (PathInfo must not be re-merged) is directly locked down.
	 *
	 * So what these tests add is the outcome #814 actually asks about, which no unit test can
	 * show: that a dispatcher replaying stale routes forever still lets trains complete their
	 * journeys, with the station draining and no conflict events — whichever internal mechanism
	 * happens to deliver that. Each test asserts [RunOutcome.staleReRequests] first, so a future
	 * change that stopped the replay from happening would fail loudly rather than turn these into
	 * vacuous passes.
	 *
	 * @see cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest for the guard's
	 *   own unit-level regression test
	 */
	@Nested
	@DisplayName("Symptom 2b — replaying an already-granted route never freezes a train")
	inner class RedundantRouteRequests {
		/**
		 * A stateless dispatcher replays the first route it ever granted each train, on every
		 * later cycle, for the whole run. Trains must still complete their journeys.
		 */
		@Test
		@DisplayName("duplicating every routing decision still lets trains complete their journey")
		fun redundantRoutingDoesNotFreezeTrains() {
			val outcome =
				runWith({ queue, net -> RedundantRouteRequestPlanner(queue, net) }, withSafetyNet = true)

			logger.info {
				"#814 symptom 2b: exited=${outcome.trainsExited} conflicts=${outcome.conflictEvents.size} " +
					"staleReRequests=${outcome.staleReRequests}"
			}
			// The scenario must actually have occurred, or the assertion below proves nothing.
			assertThat(outcome.staleReRequests).isGreaterThanOrEqualTo(1)
			assertThat(outcome.trainsExited).isGreaterThanOrEqualTo(1)
		}

		/**
		 * The observable signature of the #814 corruption was a conflict event. Zero conflicts
		 * across a whole run of continuous stale re-requests is the end-to-end statement #814's
		 * checklist asks for.
		 */
		@Test
		@DisplayName("no conflict events fire despite stale routes being replayed every cycle")
		fun redundantRoutingProducesNoConflictEvents() {
			val outcome =
				runWith({ queue, net -> RedundantRouteRequestPlanner(queue, net) }, withSafetyNet = true)

			assertThat(outcome.staleReRequests).isGreaterThanOrEqualTo(1)
			assertThat(outcome.conflictEvents).isEmpty()
		}

		/**
		 * A frozen train would show as a station that filled up and never drained. Asserting the
		 * peak stayed within the cap *and* trains still exited distinguishes "stalled at capacity"
		 * from "flowing normally".
		 */
		@Test
		@DisplayName("the station drains: trains exit rather than accumulating at the cap")
		fun stationDrainsUnderRedundantRouting() {
			val outcome =
				runWith({ queue, net -> RedundantRouteRequestPlanner(queue, net) }, withSafetyNet = true)

			assertThat(outcome.staleReRequests).isGreaterThanOrEqualTo(1)
			assertThat(outcome.maxConcurrent)
				.isLessThanOrEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
			assertThat(outcome.trainsExited).isGreaterThanOrEqualTo(1)
		}
	}

	// ── Baseline: the rule-based planner still behaves ────────────────────────────────────

	@Nested
	@DisplayName("Baseline — an unmodified rule-based run is unaffected")
	inner class Baseline {
		/**
		 * Anchors the scripted-planner numbers above against the unmodified stack. If this drifts,
		 * the #814 assertions are measuring a changed simulation rather than a changed dispatcher.
		 */
		@Test
		@DisplayName("the default rule-based planner exits at least four trains with no conflicts")
		fun ruleBasedBaselineIsHealthy() {
			val context = fixture.loadShuntingLoopContext()
			context.getInOuts()
			val loop = ShuntingLoop(context, endTime = endTimeSeconds)
			val conflictEvents = mutableListOf<ConflictDetectedEvent>()
			context.onConflictDetectedEvent { conflictEvents.add(it) }

			val run = fixture.run(loop, context)

			logger.info {
				"#814 baseline: exited=${run.trainsExited()} conflicts=${conflictEvents.size}"
			}
			assertThat(run.trainsExited()).isGreaterThanOrEqualTo(4)
			assertThat(conflictEvents).isEmpty()
			assertThat(run.driverCycleCount.get() > 0).isTrue()
		}
	}
}
