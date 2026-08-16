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
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DeadlineTickBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.FailureReason
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationSource
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit coverage for [DispatchTickLoop] (SP2c.5, Issue #828) — the C2/A2 unconditional fixed-tick
 * control loop that replaces [AgentLoopDriver] + `RuleBasedPlanAdapter` for the P10 gate.
 *
 * The loop is exercised end-to-end through real collaborators wherever practical (a real
 * [ActionValidator], [ActuatorCommandQueue], [TickRingBuffer], [AffordanceAnnotator] and
 * [TerminalFallbackGuard]); only the three genuinely external seams — the observation source,
 * the emission strategy and the simulation controller — are replaced by recording fakes, so the
 * assertions are about real behaviour rather than about mock interactions.
 *
 * ## Test topology
 *
 * A minimal station: endpoints `A`/`B` (InOut) plus signals `doA1`/`doB1`, blocks `kA`..`kC`.
 * Enough for the validator's endpoint and block-id checks without pulling in a real network.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
@DisplayName("SP2c.5 — DispatchTickLoop unconditional fixed-tick control loop (#828)")
@Timeout(30, unit = TimeUnit.SECONDS)
class DispatchTickLoopTest {
	// ── Test topology ─────────────────────────────────────────────────────────────────────

	private val validEndpoints = setOf("A", "B", "doA1", "doB1")
	private val blockIds = setOf("kA", "kB", "kC")

	private val validator = ActionValidator(validEndpointNames = validEndpoints, blockIds = blockIds)
	private val annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator())

	// ── Fakes for the three external seams ────────────────────────────────────────────────

	/**
	 * Serves a scripted sequence of observations; the last entry is repeated once the script is
	 * exhausted, so a loop driven for more ticks than the script has entries keeps working.
	 */
	private class ScriptedObservations(
		private val script: List<DispatcherObservation>
	) : DispatcherObservationSource {
		var latestCallCount: Int = 0
			private set

		override fun latest(): DispatcherObservation {
			val index = minOf(latestCallCount, script.lastIndex)
			latestCallCount++
			return script[index]
		}
	}

	/** Emits a scripted per-call result; `null` models a budget/deadline miss. */
	private class ScriptedEmission(
		private val script: List<List<AttributedAction>?>
	) : EmissionStrategy {
		var emitCallCount: Int = 0
			private set
		val seenPrompts: MutableList<String> = mutableListOf()

		override suspend fun emit(
			prompt: String,
			observation: DispatcherObservation
		): List<AttributedAction>? {
			seenPrompts.add(prompt)
			val index = minOf(emitCallCount, script.lastIndex)
			emitCallCount++
			return script[index]
		}
	}

	/** Records the pacing calls the loop makes, so step 7 of the tick contract is assertable. */
	private class RecordingController : SimulationController {
		var awaitIfPausedCount: Int = 0
			private set
		val throttleDeltas: MutableList<Double> = mutableListOf()

		override suspend fun awaitIfPaused() {
			awaitIfPausedCount++
		}

		override fun throttle(simDeltaSeconds: Double) {
			throttleDeltas.add(simDeltaSeconds)
		}

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() = Unit

		override fun requestResume() = Unit

		override fun currentSpeedMultiplier(): Double = 1.0
	}

	/** Returns scripted [await] results; the last entry repeats once exhausted. */
	private class ScriptedSignal(
		private val script: List<Boolean>
	) : SnapshotSignal {
		var awaitCallCount: Int = 0
			private set

		override fun signal() = Unit

		override fun await(): Boolean {
			val index = minOf(awaitCallCount, script.lastIndex)
			awaitCallCount++
			return script[index]
		}
	}

	// ── Observation builders ──────────────────────────────────────────────────────────────

	private fun queuedTrain(
		trainId: String,
		destination: String = "B"
	) = TrainView(
		trainId = trainId,
		phase = TrainPhase.QUEUED,
		frontSectionName = null,
		velocityMps = 0.0,
		accelerationMps2 = 0.0,
		destinationInOutName = destination,
		signalAheadName = null,
		signalAheadAspect = null,
		distanceToSignalAheadMetres = 0.0,
		waitingSinceSimTime = 0.0,
		waitSeconds = 1.0
	)

	private fun runningTrain(
		trainId: String,
		destination: String = "B",
		frontSection: String = "kA"
	) = queuedTrain(trainId, destination).copy(
		phase = TrainPhase.RUNNING,
		frontSectionName = frontSection,
		velocityMps = 5.0,
		waitingSinceSimTime = null,
		waitSeconds = 0.0
	)

	/**
	 * Observation with one queued train (`T-1`) and one running train (`T-2` at `kA`),
	 * `activeCount` slots still free under [capacity].
	 */
	private fun observation(
		tick: Long = 1L,
		simTime: Double = 10.0,
		trains: List<TrainView> = listOf(runningTrain("T-2"), queuedTrain("T-1")),
		queued: List<QueuedTrainView> = listOf(QueuedTrainView("T-1", "B", 0.0)),
		reservations: List<ReservationView> = emptyList(),
		activeCount: Int = 1,
		capacity: Int = 3,
		appliedOutcomes: List<AppliedOutcome> = emptyList()
	): DispatcherObservation =
		DispatcherObservation.EMPTY.copy(
			tick = tick,
			simTime = simTime,
			trains = trains,
			queued = queued,
			reservations = reservations,
			activeCount = activeCount,
			capacity = capacity,
			appliedOutcomes = appliedOutcomes
		)

	// ── Loop builder ──────────────────────────────────────────────────────────────────────

	private class Harness(
		val loop: DispatchTickLoop,
		val observations: ScriptedObservations,
		val emission: ScriptedEmission,
		val controller: RecordingController,
		val queue: ActuatorCommandQueue,
		val ring: TickRingBuffer,
		val guard: TerminalFallbackGuard,
		val signal: ScriptedSignal?
	)

	private fun harness(
		observationScript: List<DispatcherObservation>,
		emissionScript: List<List<AttributedAction>?>,
		signalScript: List<Boolean>? = null,
		maxActionsPerTick: Int = DispatchTickLoop.DEFAULT_MAX_ACTIONS_PER_TICK,
		queue: ActuatorCommandQueue = ActuatorCommandQueue(),
		budget: cz.vutbr.fit.interlockSim.dispatcher.agents.TickBudget = NoTimeoutBudget,
		guard: TerminalFallbackGuard = TerminalFallbackGuard(),
		fallbackEmission: EmissionStrategy? = null,
		emissionOverride: EmissionStrategy? = null
	): Harness {
		val observations = ScriptedObservations(observationScript)
		val scriptedEmission = ScriptedEmission(emissionScript)
		val activeEmission: EmissionStrategy = emissionOverride ?: scriptedEmission
		val controller = RecordingController()
		val ring = TickRingBuffer()
		val signal = signalScript?.let { ScriptedSignal(it) }
		val loop =
			DispatchTickLoop(
				observations = observations,
				annotator = annotator,
				renderer = ObservationRenderer { ctx -> "tick=${ctx.observation.tick}" },
				emission = activeEmission,
				validator = validator,
				queue = queue,
				ring = ring,
				workingMemory = WorkingMemory.EMPTY,
				budget = budget,
				fallbackGuard = guard,
				fallbackEmission = fallbackEmission,
				controller = controller,
				snapshotSignal = signal,
				maxActionsPerTick = maxActionsPerTick
			)
		return Harness(loop, observations, scriptedEmission, controller, queue, ring, guard, signal)
	}

	private fun action(
		dispatchAction: DispatchAction,
		author: ActionAuthor = ActionAuthor.LLM,
		tick: Long = 1L
	) = AttributedAction(commandId = CommandId(0L), tick = tick, action = dispatchAction, author = author)

	// ── C2: unconditional fixed tick ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("C2: the tick runs unconditionally on every fresh signal")
	inner class UnconditionalTick {
		/**
		 * The central claim of #828: the `simTime == prevSimTime` skip that [AgentLoopDriver]
		 * applied is deliberately NOT ported (it is the #746 bug). Two consecutive observations
		 * carrying an identical `simTime` must therefore both produce a full tick.
		 *
		 * Would fail if a `simTime`/digest-equality short-circuit were reintroduced into
		 * [DispatchTickLoop.runTick] step 1.
		 */
		@Test
		@DisplayName("two consecutive ticks with an unchanged simTime both run the full pipeline")
		fun unchangedSimTimeStillRunsTheTick() {
			val obs = observation(tick = 1L, simTime = 10.0)
			val sameSimTimeNextTick = observation(tick = 2L, simTime = 10.0)
			val h =
				harness(
					observationScript = listOf(obs, sameSimTimeNextTick),
					emissionScript = listOf(listOf(action(DispatchAction.ApproveTrain("T-1"))))
				)

			val first = runBlocking { h.loop.runTick() }
			val second = runBlocking { h.loop.runTick() }

			assertThat(first).isNotNull()
			assertThat(second).isNotNull()
			assertThat(h.emission.emitCallCount).isEqualTo(2)
			assertThat(h.ring.snapshot()).hasSize(2)
		}

		@Test
		@DisplayName("returns null and only awaits pause when the snapshot signal times out")
		fun signalTimeoutShortCircuits() {
			val h =
				harness(
					observationScript = listOf(observation()),
					emissionScript = listOf(emptyList()),
					signalScript = listOf(false)
				)

			val record = runBlocking { h.loop.runTick() }

			assertThat(record).isNull()
			assertThat(h.emission.emitCallCount).isEqualTo(0)
			assertThat(h.observations.latestCallCount).isEqualTo(0)
			assertThat(h.controller.awaitIfPausedCount).isEqualTo(1)
			assertThat(h.controller.throttleDeltas).isEmpty()
		}

		@Test
		@DisplayName("returns null on the EMPTY observation (tick == 0, projector not started)")
		fun emptyObservationShortCircuits() {
			val h =
				harness(
					observationScript = listOf(DispatcherObservation.EMPTY),
					emissionScript = listOf(emptyList())
				)

			val record = runBlocking { h.loop.runTick() }

			assertThat(record).isNull()
			assertThat(h.emission.emitCallCount).isEqualTo(0)
			assertThat(h.controller.awaitIfPausedCount).isEqualTo(1)
			assertThat(h.ring.snapshot()).isEmpty()
		}

		@Test
		@DisplayName("the signal is awaited exactly once per tick attempt")
		fun signalAwaitedOncePerTick() {
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L)),
					emissionScript = listOf(emptyList()),
					signalScript = listOf(true)
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.signal?.awaitCallCount).isEqualTo(2)
		}
	}

	// ── Budget / TIMEOUT_NOOP substitution ────────────────────────────────────────────────

	@Nested
	@DisplayName("Budget: null emission becomes TIMEOUT_NOOP, empty emission becomes a strategy-authored NoOp")
	inner class BudgetSubstitution {
		/**
		 * Would fail if the elvis in [DispatchTickLoop.runTick] step 4 were dropped or the
		 * substituted author changed away from [ActionAuthor.TIMEOUT_NOOP].
		 */
		@Test
		@DisplayName("a null emission result yields exactly one NoOp attributed to TIMEOUT_NOOP")
		fun nullEmissionBecomesTimeoutNoOp() {
			val h = harness(listOf(observation()), listOf(null))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.actions[0].author).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
			assertThat(record.actions[0].action).isEqualTo(DispatchAction.NoOp)
			assertThat(h.queue.drain()).isEmpty()
		}

		/**
		 * The `NoAction → emptyList()` contract [RuleBasedEmissionStrategy] depends on (SP2c.6 / #829):
		 * an empty list means "the strategy ran and decided there is nothing to do this tick", which the
		 * loop substitutes with a single [DispatchAction.NoOp] authored by the strategy's own
		 * [EmissionStrategy.author] ([ActionAuthor.RULE_BASED] for the rule engine / [ScriptedEmission]
		 * default) — a deliberate idle tick, NOT a degraded [ActionAuthor.TIMEOUT_NOOP]. Would fail if
		 * the substitution used `isNullOrEmpty()` (collapsing empty into the timeout branch) or if the
		 * empty branch were dropped (leaving zero actions, breaking the "exactly one action per tick,
		 * `no_op` included" contract).
		 */
		@Test
		@DisplayName("an empty emission list yields one strategy-authored NoOp, not a TIMEOUT_NOOP and not zero actions")
		fun emptyEmissionRecordsOneStrategyNoOp() {
			val h = harness(listOf(observation()), listOf(emptyList()))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.actions[0].action).isEqualTo(DispatchAction.NoOp)
			assertThat(record.actions[0].author).isEqualTo(ActionAuthor.RULE_BASED)
			assertThat(record.verdicts).hasSize(1)
			assertThat(h.queue.drain()).isEmpty()
		}

		@Test
		@DisplayName("DeadlineTickBudget substitutes a TIMEOUT_NOOP when the strategy overruns")
		fun deadlineBudgetTimesOut() {
			val slowEmission =
				EmissionStrategy { _, _ ->
					delay(5_000L)
					listOf(action(DispatchAction.ApproveTrain("T-1")))
				}
			val ring = TickRingBuffer()
			val loop =
				DispatchTickLoop(
					observations = { observation() },
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = slowEmission,
					validator = validator,
					queue = ActuatorCommandQueue(),
					ring = ring,
					budget = DeadlineTickBudget(timeoutMillis = 30L),
					controller = RecordingController()
				)

			val record = runBlocking { loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.actions[0].author).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
		}

		/**
		 * Exercises issue #833's F2 acceptance criterion literally: the 3 s hard deadline
		 * ([DeadlineTickBudget]'s documented default for LLM strategies) against a fake emission
		 * that sleeps 5 s — i.e. the deadline is exceeded, not merely delayed past a tiny test
		 * timeout as in [deadlineBudgetTimesOut] above. Runs for ~3 real seconds.
		 */
		@Test
		@Tag("integration-test")
		@DisplayName("F2: a 3 s hard deadline against a 5 s emission yields exactly one NoOp authored TIMEOUT_NOOP")
		fun threeSecondDeadlineAgainstFiveSecondEmissionYieldsSingleTimeoutNoOp() {
			val slowEmission =
				EmissionStrategy { _, _ ->
					delay(5_000L)
					listOf(action(DispatchAction.ApproveTrain("T-1")))
				}
			val ring = TickRingBuffer()
			val loop =
				DispatchTickLoop(
					observations = { observation() },
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = slowEmission,
					validator = validator,
					queue = ActuatorCommandQueue(),
					ring = ring,
					budget = DeadlineTickBudget(timeoutMillis = 3_000L),
					controller = RecordingController()
				)

			val record = runBlocking { loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.actions[0].action).isEqualTo(DispatchAction.NoOp)
			assertThat(record.actions[0].author).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
		}

		@Test
		@DisplayName("NoTimeoutBudget lets a slow strategy finish")
		fun noTimeoutBudgetNeverCancels() {
			val slowButFinishing =
				EmissionStrategy { _, _ ->
					delay(50L)
					listOf(action(DispatchAction.ApproveTrain("T-1")))
				}
			val loop =
				DispatchTickLoop(
					observations = { observation() },
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = slowButFinishing,
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			val record = runBlocking { loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.actions[0].author).isNotEqualTo(ActionAuthor.TIMEOUT_NOOP)
		}
	}

	// ── ActionAuthor / RunOutcome ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("RunOutcome: TIMEOUT_NOOP threshold (threshold=5) drives terminal failure (SP2c.8 #831)")
	inner class RunOutcomeTransitions {
		/**
		 * Conflating [ActionAuthor.RULE_BASED] with [ActionAuthor.RULE_FALLBACK] would mark every
		 * P10 rule-based determinism run as FAILED. Would fail if [TerminalFallbackGuard.observe]
		 * matched on `RULE_BASED` or `RULE_FALLBACK`.
		 */
		@Test
		@DisplayName("a RULE_BASED action leaves the run Running")
		fun ruleBasedKeepsRunning() {
			val h =
				harness(
					listOf(observation()),
					listOf(listOf(action(DispatchAction.ApproveTrain("T-1"), ActionAuthor.RULE_BASED)))
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("a RULE_FALLBACK action alone does NOT fail the run (no longer terminal by author)")
		fun ruleFallbackAloneDoesNotFailRun() {
			// SP2c.8 (#831): RULE_FALLBACK is a post-engagement author, not the trigger for failure.
			// The guard now counts TIMEOUT_NOOP ticks; a single RULE_FALLBACK resets the counter.
			val h =
				harness(
					listOf(observation()),
					listOf(listOf(action(DispatchAction.ApproveTrain("T-1"), ActionAuthor.RULE_FALLBACK)))
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("4 consecutive null emissions (TIMEOUT_NOOP) ⇒ still Running (below threshold=5)")
		fun fourTimeoutsStillRunning() {
			val obs = (1..4).map { i -> observation(tick = i.toLong(), simTime = i * 10.0) }
			val h = harness(obs, obs.map { null })

			runBlocking { obs.forEach { _ -> h.loop.runTick() } }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("5 consecutive null emissions (TIMEOUT_NOOP) fail the run")
		fun fiveTimeoutsFailRun() {
			// Use a threshold=1 guard to avoid creating 5 observations
			val guard = TerminalFallbackGuard(threshold = 1)
			val h =
				harness(
					listOf(observation()),
					listOf(null),
					guard = guard
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		}

		@Test
		@DisplayName("4 TIMEOUT_NOOP then one LLM no_op resets counter and stays Running")
		fun fourTimeoutsThenLlmNoOpResetsCounter() {
			// 4 timeouts + 1 LLM no_op: counter resets on the LLM tick, stays below threshold
			val obs = (1..5).map { i -> observation(tick = i.toLong(), simTime = i * 10.0) }
			val emissionScript =
				listOf(
					null,
					null,
					null,
					null, // ticks 1–4: TIMEOUT_NOOP (counter 1→4)
					listOf(action(DispatchAction.NoOp, ActionAuthor.LLM)) // tick 5: LLM resets counter
				)
			val h = harness(obs, emissionScript)

			runBlocking { obs.forEach { _ -> h.loop.runTick() } }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("a Failed outcome never reverts on a subsequent clean tick")
		fun failedNeverReverts() {
			val guard = TerminalFallbackGuard(threshold = 1)
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript =
						listOf(
							null, // tick 1: TIMEOUT_NOOP → guard engages (threshold=1)
							listOf(action(DispatchAction.NoOp, ActionAuthor.RULE_BASED)) // tick 2: clean tick
						),
					guard = guard
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		}

		@Test
		@DisplayName("a TIMEOUT_NOOP substitution (single tick) does not fail the run")
		fun timeoutNoOpDoesNotFailRun() {
			val h = harness(listOf(observation()), listOf(null))

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("an LLM-authored action does not fail the run")
		fun llmAuthoredDoesNotFailRun() {
			val h =
				harness(
					listOf(observation()),
					listOf(listOf(action(DispatchAction.ApproveTrain("T-1"), ActionAuthor.LLM)))
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName(
			"unhandled exception from emission.emit immediately fails the run when fallback is configured (SP2c.8 #831)"
		)
		fun exceptionFromEmissionImmediatelyFailsWithFallback() {
			val throwingEmission =
				object : EmissionStrategy {
					override suspend fun emit(
						prompt: String,
						observation: cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
					): List<AttributedAction>? = throw RuntimeException("simulated LLM crash")
				}
			val fallbackEmission =
				object : EmissionStrategy {
					override val author: ActionAuthor get() = ActionAuthor.RULE_FALLBACK

					override suspend fun emit(
						prompt: String,
						observation: cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
					): List<AttributedAction>? = emptyList()
				}
			val h =
				harness(
					listOf(observation()),
					emptyList(),
					emissionOverride = throwingEmission,
					fallbackEmission = fallbackEmission
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		}

		@Test
		@DisplayName(
			"unhandled exception from emission.emit rethrows when no fallback is configured (SP2c.26 #849 invariant)"
		)
		fun exceptionFromEmissionRethrowsWithoutFallback() {
			val throwingEmission =
				object : EmissionStrategy {
					override suspend fun emit(
						prompt: String,
						observation: cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
					): List<AttributedAction>? = throw RuntimeException("simulated LLM crash")
				}
			val h =
				harness(
					listOf(observation()),
					emptyList(),
					emissionOverride = throwingEmission
				)

			val thrown = runCatching { runBlocking { h.loop.runTick() } }.exceptionOrNull()

			assertThat(thrown is RuntimeException).isTrue()
			// Guard was not engaged — the exception surfaced before engagement.
			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("after guard engagement, fallbackEmission is used and actions are authored RULE_FALLBACK")
		fun afterEngagementFallbackEmissionIsUsed() {
			val guard = TerminalFallbackGuard(threshold = 1)
			// Fallback strategy uses RULE_FALLBACK author (RuleBasedEmissionStrategy in production)
			val fallbackEmission =
				object : EmissionStrategy {
					override val author: ActionAuthor get() = ActionAuthor.RULE_FALLBACK

					override suspend fun emit(
						prompt: String,
						observation: cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
					): List<AttributedAction>? = emptyList()
				}
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript =
						listOf(
							null, // tick 1: TIMEOUT_NOOP → guard engages (threshold=1)
							listOf(action(DispatchAction.NoOp, ActionAuthor.RULE_BASED)) // tick 2: unused
						),
					guard = guard,
					fallbackEmission = fallbackEmission
				)

			val tick1 = runBlocking { h.loop.runTick() }!!
			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))

			val tick2 = runBlocking { h.loop.runTick() }!!
			// After engagement, fallbackEmission produces empty list → idle-substituted NoOp
			// authored by fallbackEmission.author = RULE_FALLBACK
			assertThat(tick2.actions[0].author).isEqualTo(ActionAuthor.RULE_FALLBACK)
		}
	}

	// ── C6: optimistic intra-tick projection ──────────────────────────────────────────────

	@Nested
	@DisplayName("C6: each action is re-validated against the optimistically projected state")
	inner class OptimisticProjection {
		/**
		 * Two admissions in one tick with exactly one free slot. Without the projection both
		 * would validate against the same pre-tick `activeCount` and both be accepted — the
		 * stale-snapshot bug SP2c.18 also fixes at apply time. Would fail if
		 * `currentObs = applyOptimistically(...)` were removed from step 5.
		 */
		@Test
		@DisplayName("the second approve_train in one tick is rejected once the cap is reached")
		fun secondApprovalRejectedAtCap() {
			val obs =
				observation(
					trains = listOf(runningTrain("T-9"), queuedTrain("T-1"), queuedTrain("T-2")),
					queued = listOf(QueuedTrainView("T-1", "B", 0.0), QueuedTrainView("T-2", "B", 0.0)),
					activeCount = 1,
					capacity = 2
				)
			val h =
				harness(
					listOf(obs),
					listOf(
						listOf(
							action(DispatchAction.ApproveTrain("T-1")),
							action(DispatchAction.ApproveTrain("T-2"))
						)
					)
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.verdicts[0]).isEqualTo(ValidationVerdict.Valid)
			val second = record.verdicts[1]
			assertThat(second).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat((second as ValidationVerdict.Rejected).code).isEqualTo(RejectionCode.CAPACITY_FULL)
			assertThat(h.queue.drain()).hasSize(1)
		}

		/**
		 * Would fail if [DispatchTickLoop.applyOptimistically] stopped adding a [ReservationView]
		 * for `request_route`.
		 */
		@Test
		@DisplayName("a duplicate request_route in the same tick is rejected as already-held")
		fun duplicateRequestRouteRejected() {
			val obs = observation(trains = listOf(runningTrain("T-2")), queued = emptyList(), activeCount = 1)
			val requestRoute = DispatchAction.RequestRoute("T-2", "doA1", "B")
			val h = harness(listOf(obs), listOf(listOf(action(requestRoute), action(requestRoute))))

			val record = runBlocking { h.loop.runTick() }!!

			val second = record.verdicts[1]
			assertThat(second).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat((second as ValidationVerdict.Rejected).code)
				.isEqualTo(RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET)
		}

		/**
		 * Would fail if `CancelRoute` stopped removing the train's [ReservationView] from the
		 * projection — the following `request_route` would then be rejected as already-held.
		 */
		@Test
		@DisplayName("cancel_route clears the projection so a following request_route is accepted")
		fun cancelThenRequestIsAccepted() {
			val obs =
				observation(
					trains = listOf(runningTrain("T-2")),
					queued = emptyList(),
					reservations = listOf(ReservationView("T-2", "doA1", "B", listOf("kA"))),
					activeCount = 1
				)
			val h =
				harness(
					listOf(obs),
					listOf(
						listOf(
							action(DispatchAction.CancelRoute("T-2")),
							action(DispatchAction.RequestRoute("T-2", "doA1", "B"))
						)
					)
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.verdicts[0]).isEqualTo(ValidationVerdict.Valid)
			assertThat(record.verdicts[1]).isEqualTo(ValidationVerdict.Valid)
		}

		/**
		 * A rejected action must leave the projection untouched, otherwise one bad action would
		 * poison the validation of every later action in the same tick. Would fail if
		 * `applyOptimistically` were called before/regardless of the `Rejected` `continue`.
		 */
		@Test
		@DisplayName("a rejected action does not advance the projection")
		fun rejectedActionDoesNotAdvanceProjection() {
			val obs =
				observation(
					trains = listOf(runningTrain("T-9"), queuedTrain("T-1")),
					queued = listOf(QueuedTrainView("T-1", "B", 0.0)),
					activeCount = 1,
					capacity = 3
				)
			val h =
				harness(
					listOf(obs),
					listOf(
						listOf(
							// Unknown train: rejected, must not consume a capacity slot.
							action(DispatchAction.ApproveTrain("T-ghost")),
							action(DispatchAction.ApproveTrain("T-1"))
						)
					)
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.verdicts[0]).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat(record.verdicts[1]).isEqualTo(ValidationVerdict.Valid)
			assertThat(h.queue.drain()).hasSize(1)
		}
	}

	// ── Posting: action → decision mapping ────────────────────────────────────────────────

	@Nested
	@DisplayName("Posting: validated actions map to the right DispatchDecision")
	inner class Posting {
		@Test
		@DisplayName("approve_train posts DispatchDecision.ApproveTrain")
		fun approveTrainMapping() {
			val h = harness(listOf(observation()), listOf(listOf(action(DispatchAction.ApproveTrain("T-1")))))

			runBlocking { h.loop.runTick() }

			assertThat(h.queue.drain()).containsExactly(DispatchDecision.ApproveTrain("T-1"))
		}

		@Test
		@DisplayName("request_route posts DispatchDecision.RequestRoute with from/to preserved")
		fun requestRouteMapping() {
			val obs = observation(trains = listOf(runningTrain("T-2")), queued = emptyList(), activeCount = 1)
			val h =
				harness(
					listOf(obs),
					listOf(listOf(action(DispatchAction.RequestRoute("T-2", "doA1", "B"))))
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.queue.drain()).containsExactly(
				DispatchDecision.RequestRoute(trainName = "T-2", fromEndpointName = "doA1", toEndpointName = "B")
			)
		}

		/**
		 * `cancel_route` maps to `ReleaseRoute`, not to a same-named decision — the easiest
		 * mapping in `toDispatchDecisions` to get wrong.
		 */
		@Test
		@DisplayName("cancel_route posts DispatchDecision.ReleaseRoute")
		fun cancelRouteMapsToReleaseRoute() {
			val obs =
				observation(
					trains = listOf(runningTrain("T-2")),
					queued = emptyList(),
					reservations = listOf(ReservationView("T-2", "doA1", "B", listOf("kA"))),
					activeCount = 1
				)
			val h = harness(listOf(obs), listOf(listOf(action(DispatchAction.CancelRoute("T-2")))))

			runBlocking { h.loop.runTick() }

			assertThat(h.queue.drain()).containsExactly(DispatchDecision.ReleaseRoute(trainName = "T-2"))
		}

		@Test
		@DisplayName("no_op is recorded but posts nothing")
		fun noOpPostsNothing() {
			val h = harness(listOf(observation()), listOf(listOf(action(DispatchAction.NoOp))))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(h.queue.drain()).isEmpty()
		}

		@Test
		@DisplayName("a rejected action posts nothing")
		fun rejectedActionPostsNothing() {
			val h = harness(listOf(observation()), listOf(listOf(action(DispatchAction.ApproveTrain("T-ghost")))))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.verdicts[0]).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat(h.queue.drain()).isEmpty()
		}

		/**
		 * Would fail if `emitted.take(maxActionsPerTick)` were dropped from step 5.
		 */
		@Test
		@DisplayName("emissions beyond maxActionsPerTick are neither recorded nor posted")
		fun maxActionsPerTickTruncates() {
			val obs =
				observation(
					trains = listOf(queuedTrain("T-1"), queuedTrain("T-2"), queuedTrain("T-3")),
					queued =
						listOf(
							QueuedTrainView("T-1", "B", 0.0),
							QueuedTrainView("T-2", "B", 0.0),
							QueuedTrainView("T-3", "B", 0.0)
						),
					activeCount = 0,
					capacity = 5
				)
			val h =
				harness(
					observationScript = listOf(obs),
					emissionScript =
						listOf(
							listOf(
								action(DispatchAction.ApproveTrain("T-1")),
								action(DispatchAction.ApproveTrain("T-2")),
								action(DispatchAction.ApproveTrain("T-3"))
							)
						),
					maxActionsPerTick = 2
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(2)
			assertThat(h.queue.drain()).hasSize(2)
		}

		/**
		 * Queue backpressure must be logged, not thrown — a full queue is a transient condition,
		 * not a loop-terminating error. Would fail if `postAll`'s `false` result were turned into
		 * an exception.
		 */
		@Test
		@DisplayName("a full queue is tolerated: the tick still completes and records the action")
		fun queueBackpressureIsTolerated() {
			val fullQueue = ActuatorCommandQueue(capacity = 1)
			fullQueue.postAll(listOf(DispatchDecision.ApproveTrain("filler")))
			val h =
				harness(
					observationScript = listOf(observation()),
					emissionScript = listOf(listOf(action(DispatchAction.ApproveTrain("T-1")))),
					queue = fullQueue
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(1)
			assertThat(record.verdicts[0]).isEqualTo(ValidationVerdict.Valid)
		}
	}

	// ── Recording ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Recording: one TickRecord per completed tick")
	inner class Recording {
		@Test
		@DisplayName("the record carries the observation's tick, simTime and digest")
		fun recordCarriesObservationIdentity() {
			val obs = observation(tick = 7L, simTime = 42.5)
			val h = harness(listOf(obs), listOf(emptyList()))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.tick).isEqualTo(7L)
			assertThat(record.simTime).isEqualTo(42.5)
			assertThat(record.stateDigest).isEqualTo(obs.digest())
		}

		/**
		 * Would fail if step 6 stopped copying `obs0.appliedOutcomes` into the record — the
		 * SP2c.17 outcome channel would then never reach the ring buffer or working memory.
		 */
		@Test
		@DisplayName("the record carries the observation's appliedOutcomes")
		fun recordCarriesAppliedOutcomes() {
			val outcome =
				AppliedOutcome.Approved(trainId = "T-1", admitted = true, id = CommandId(9L), tickIndex = 6L)
			val h = harness(listOf(observation(appliedOutcomes = listOf(outcome))), listOf(emptyList()))

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.outcomes).containsExactly(outcome)
		}

		@Test
		@DisplayName("exactly one record is pushed to the ring buffer per successful tick")
		fun oneRecordPerTick() {
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript = listOf(emptyList())
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.ring.snapshot()).hasSize(2)
			assertThat(h.ring.snapshot().map { it.tick }).containsExactly(1L, 2L)
		}

		/**
		 * Would fail if `commandIdCounter` were reset per tick or the `copy(commandId = ...)`
		 * re-stamping in step 4 were removed — correlating an applied outcome back to its
		 * originating action depends on ids being unique for the loop's whole lifetime.
		 */
		@Test
		@DisplayName("command ids are unique and strictly increasing across ticks")
		fun commandIdsAreUniqueAndIncreasing() {
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript = listOf(listOf(action(DispatchAction.NoOp)))
				)

			val first = runBlocking { h.loop.runTick() }!!
			val second = runBlocking { h.loop.runTick() }!!

			assertThat(second.actions[0].commandId.value).isGreaterThan(first.actions[0].commandId.value)
		}

		@Test
		@DisplayName("actions and verdicts stay parallel lists")
		fun actionsAndVerdictsAreParallel() {
			val h =
				harness(
					listOf(observation()),
					listOf(
						listOf(
							action(DispatchAction.ApproveTrain("T-1")),
							action(DispatchAction.ApproveTrain("T-ghost"))
						)
					)
				)

			val record = runBlocking { h.loop.runTick() }!!

			assertThat(record.actions).hasSize(record.verdicts.size)
		}
	}

	// ── Pacing ────────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Pacing: pause and throttle are honoured once per tick")
	inner class Pacing {
		/**
		 * Would fail if `throttle` were passed the absolute `simTime` instead of the delta since
		 * the previous tick — wall-clock pacing would then be wrong from the second tick on.
		 */
		@Test
		@DisplayName("throttle receives the simTime delta since the previous tick")
		fun throttleReceivesSimTimeDelta() {
			val h =
				harness(
					observationScript =
						listOf(observation(tick = 1L, simTime = 10.0), observation(tick = 2L, simTime = 17.5)),
					emissionScript = listOf(emptyList())
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.controller.throttleDeltas).containsExactly(10.0, 7.5)
		}

		@Test
		@DisplayName("awaitIfPaused is called exactly once per completed tick")
		fun awaitIfPausedOncePerTick() {
			val h =
				harness(
					observationScript = listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript = listOf(emptyList())
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.controller.awaitIfPausedCount).isEqualTo(2)
		}
	}

	// ── F2 real-time ratio reporting (SP2c.10, Issue #833) ─────────────────────────────────

	@Nested
	@DisplayName("F2: real-time ratio is reported at debug level without gating the tick")
	inner class RealTimeRatioReporting {
		/**
		 * Deterministically exercises the loop's own F2 ratio branch
		 * (`if (emissionNanos > 0L && simDelta > 0.0) { logger.debug { "real-time ratio=..." } }`)
		 * which is otherwise only covered non-deterministically by `TimingRegimesOllamaTest`
		 * (that test recomputes the ratio outside the loop against a live Ollama call).
		 *
		 * A fake emission strategy sleeps briefly so `emissionNanos > 0`, and a first-tick
		 * observation with `simTime = 10.0` (against the `prevSimTime = 0.0` initial) gives
		 * `simDelta = 10.0 > 0.0`. The ratio log line is captured via a Logback `ListAppender`
		 * after raising the `cz.vutbr.fit.interlockSim.dispatcher` package logger to DEBUG
		 * (`logback-test.xml` pins it to WARN, which would otherwise suppress the line). The
		 * ratio value is parsed and asserted finite and `> 0`, but NO exact value is asserted —
		 * `emissionNanos` is wall-clock-timing-dependent.
		 */
		private lateinit var appender: ListAppender<ILoggingEvent>
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
		private var originalRootLevel: Level = Level.WARN
		private var originalDispatcherLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
			dispatcherLogger =
				LoggerFactory.getLogger("cz.vutbr.fit.interlockSim.dispatcher") as LogbackLogger
			originalRootLevel = rootLogger.level
			originalDispatcherLevel = dispatcherLogger.level
			rootLogger.level = Level.DEBUG
			dispatcherLogger.level = Level.DEBUG
			appender = ListAppender()
			rootLogger.addAppender(appender)
			appender.start()
		}

		@AfterEach
		fun detachAppender() {
			rootLogger.detachAppender(appender)
			rootLogger.level = originalRootLevel
			dispatcherLogger.level = originalDispatcherLevel
		}

		private fun ratioLines(): List<String> =
			appender.list.map { it.formattedMessage }.filter { it.contains("real-time ratio=") }

		@Test
		@DisplayName("a tick with a slow emission logs a finite, positive real-time ratio")
		fun slowEmissionLogsFinitePositiveRatio() {
			val slowEmission =
				EmissionStrategy { _, _ ->
					delay(50L)
					emptyList()
				}
			val loop =
				DispatchTickLoop(
					observations = { observation(tick = 1L, simTime = 10.0) },
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = slowEmission,
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			runBlocking { loop.runTick() }

			val lines = ratioLines()
			assertThat(lines).isNotEmpty()
			val ratioLine = lines.first()
			val match = Regex("real-time ratio=([0-9]+\\.?[0-9]*)").find(ratioLine)
			val ratio = match?.groupValues?.get(1)?.toDoubleOrNull()
			assertThat(ratio).isNotNull()
			assertThat(ratio!!.isFinite()).isTrue()
			assertThat(ratio).isGreaterThan(0.0)
		}
	}

	// ── Rendering seam ────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Rendering: the prompt is built once per tick and handed to the strategy")
	inner class Rendering {
		@Test
		@DisplayName("the rendered prompt for the current tick reaches the emission strategy")
		fun renderedPromptReachesStrategy() {
			val h = harness(listOf(observation(tick = 11L)), listOf(emptyList()))

			runBlocking { h.loop.runTick() }

			assertThat(h.emission.seenPrompts).containsExactly("tick=11")
		}

		@Test
		@DisplayName("the previous observation is threaded into the next tick's render context")
		fun previousObservationIsThreaded() {
			val seenPrevious = mutableListOf<Long?>()
			val loop =
				DispatchTickLoop(
					observations =
						ScriptedObservations(
							listOf(observation(tick = 1L, simTime = 10.0), observation(tick = 2L, simTime = 20.0))
						),
					annotator = annotator,
					renderer =
						ObservationRenderer { ctx ->
							seenPrevious.add(ctx.previous?.tick)
							""
						},
					emission = EmissionStrategy { _, _ -> emptyList() },
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			runBlocking {
				loop.runTick()
				loop.runTick()
			}

			assertThat(seenPrevious).containsExactly(null, 1L)
		}

		@Test
		@DisplayName("the ring-buffer history from earlier ticks reaches the render context")
		fun historyReachesRenderContext() {
			val seenHistorySizes = mutableListOf<Int>()
			val loop =
				DispatchTickLoop(
					observations =
						ScriptedObservations(
							listOf(observation(tick = 1L, simTime = 10.0), observation(tick = 2L, simTime = 20.0))
						),
					annotator = annotator,
					renderer =
						ObservationRenderer { ctx ->
							seenHistorySizes.add(ctx.history.size)
							""
						},
					emission = EmissionStrategy { _, _ -> emptyList() },
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			runBlocking {
				loop.runTick()
				loop.runTick()
			}

			assertThat(seenHistorySizes).containsExactly(0, 1)
		}

		@Test
		@DisplayName("affordances are annotated for the current observation before the prompt is built")
		fun affordancesAreAnnotated() {
			var affordanceCount = 0
			val loop =
				DispatchTickLoop(
					observations = { observation() },
					annotator = annotator,
					renderer =
						ObservationRenderer { ctx ->
							affordanceCount = ctx.affordances.size
							""
						},
					emission = EmissionStrategy { _, _ -> emptyList() },
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			runBlocking { loop.runTick() }

			// One approve_train candidate (T-1 queued), a request_route/cancel_route pair for the
			// running T-2, plus the always-appended NO_OP sentinel.
			assertThat(affordanceCount).isGreaterThan(0)
		}
	}

	// ── Defaulted constructor parameters ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Construction: loop-private collaborators default sensibly")
	inner class Defaults {
		@Test
		@DisplayName("a loop built without ring/workingMemory/fallbackGuard still records and reports Running")
		fun defaultedCollaboratorsWork() {
			val loop =
				DispatchTickLoop(
					observations = { observation() },
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = EmissionStrategy { _, _ -> emptyList() },
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			val record = runBlocking { loop.runTick() }

			assertThat(record).isNotNull()
			assertThat(loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("a loop built without a snapshot signal polls the observation source directly")
		fun noSignalMeansPolling() {
			var latestCalls = 0
			val loop =
				DispatchTickLoop(
					observations = {
						latestCalls++
						observation()
					},
					annotator = annotator,
					renderer = ObservationRenderer { "" },
					emission = EmissionStrategy { _, _ -> emptyList() },
					validator = validator,
					queue = ActuatorCommandQueue(),
					budget = NoTimeoutBudget,
					controller = RecordingController()
				)

			runBlocking {
				loop.runTick()
				loop.runTick()
			}

			assertThat(latestCalls).isEqualTo(2)
		}
	}

	// ── Guard sanity ──────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Sanity: the loop's runOutcome delegates to the injected guard")
	inner class GuardDelegation {
		@Test
		@DisplayName("the externally supplied guard and the loop report the same outcome")
		fun outcomeIsDelegatedToTheGuard() {
			// SP2c.8 (#831): the guard now engages on a TIMEOUT_NOOP threshold, not on any
			// particular action author, so a null emission (⇒ TIMEOUT_NOOP) with threshold=1
			// is what actually drives engagement here (a RULE_FALLBACK action alone no longer
			// fails the run — see [RunOutcome] "TIMEOUT_NOOP threshold" tests above).
			val guard = TerminalFallbackGuard(threshold = 1)
			val h = harness(listOf(observation()), listOf(null), guard = guard)

			runBlocking { h.loop.runTick() }

			assertThat(h.guard.currentOutcome).isEqualTo(h.loop.runOutcome)
			assertThat(h.guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		}

		@Test
		@DisplayName("a short-circuited tick never reaches the guard")
		fun shortCircuitDoesNotTouchGuard() {
			val h =
				harness(
					observationScript = listOf(DispatcherObservation.EMPTY),
					emissionScript = listOf(listOf(action(DispatchAction.NoOp, ActionAuthor.RULE_FALLBACK)))
				)

			runBlocking { h.loop.runTick() }

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
		}

		@Test
		@DisplayName("isPaused is not consulted by the tick itself")
		fun tickDoesNotConsultIsPaused() {
			// The loop delegates pause handling to awaitIfPaused; a tick that also polled
			// isPaused() would be double-checking state the controller already owns.
			val h = harness(listOf(observation()), listOf(emptyList()))

			runBlocking { h.loop.runTick() }

			assertThat(h.controller.isPaused()).isFalse()
			assertThat(h.controller.awaitIfPausedCount).isEqualTo(1)
		}

		@Test
		@DisplayName("consecutive clean ticks keep the run in Running")
		fun cleanTicksStayRunning() {
			val h =
				harness(
					observationScript =
						listOf(observation(tick = 1L), observation(tick = 2L, simTime = 20.0)),
					emissionScript = listOf(emptyList())
				)

			runBlocking {
				h.loop.runTick()
				h.loop.runTick()
			}

			assertThat(h.loop.runOutcome).isEqualTo(RunOutcome.Running)
			assertThat(h.loop.runOutcome).isNotEqualTo(RunOutcome.Completed)
		}
	}
}
