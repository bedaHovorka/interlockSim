/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Goal 10 SP2c.5: 1000-repetition heavy stress test for DispatchTickLoop's
 * signal-await + pacing path (Issue #828). See CLAUDE.md "Heavy tests" for when to run this.
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Heavy-repetition stability stress for [DispatchTickLoop] (SP2c.5, Issue #828).
 *
 * Each repetition drives a fresh loop through a full producer/consumer round trip against a
 * **real** [DefaultSnapshotSignal]: a producer thread signals, the loop's `runTick()` wakes on
 * `await()`, runs the whole sense → decide → validate → act → record → pace pipeline, and
 * returns. The invariants asserted every repetition are:
 *
 * - the tick completes (no deadlock on the semaphore handoff);
 * - exactly one [TickRecord][cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord] lands in the
 *   ring buffer per signal — no lost and no duplicated ticks;
 * - `CommandId`s stay strictly increasing, so the correlation map can never see a repeat;
 * - the run stays [RunOutcome.Running] — a rule-based emission must never trip the fallback guard.
 *
 * This test is tagged [Tag] `"heavy-test"` and is **excluded from regular `test` and
 * `integrationTest` builds** — see CLAUDE.md "Heavy tests". Run it deliberately with:
 * ```
 * ./gradlew :dispatcher-agent:heavyTest
 * ```
 *
 * **When to run:** after any change to the loop's step-0 signal-await, step-7 pacing, or to
 * [SnapshotSignal] itself. A low-rate deadlock or lost-tick race in that handoff is invisible at
 * the sample size the plain unit test uses (Issue #746 showed the same shape of residue for
 * [AgentLoopDriver]).
 *
 * @see DispatchTickLoopTest
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
@DisplayName("DispatchTickLoop signal/pacing stability — 1000-repetition heavy stress (#828)")
@Tag("heavy-test")
@Timeout(10, unit = TimeUnit.MINUTES)
class DispatchTickLoopStabilityHeavyTest {
	private val validator =
		ActionValidator(validEndpointNames = setOf("A", "B", "doA1"), blockIds = setOf("kA", "kB"))
	private val annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator())

	private val lastCommandId = AtomicLong(0L)

	private class CountingController : SimulationController {
		var awaitIfPausedCount: Int = 0
			private set
		var throttleCount: Int = 0
			private set

		override suspend fun awaitIfPaused() {
			awaitIfPausedCount++
		}

		override fun throttle(simDeltaSeconds: Double) {
			throttleCount++
		}

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() = Unit

		override fun requestResume() = Unit

		override fun currentSpeedMultiplier(): Double = 1.0
	}

	private fun observation(tick: Long): DispatcherObservation =
		DispatcherObservation.EMPTY.copy(
			tick = tick,
			simTime = tick.toDouble(),
			trains =
				listOf(
					TrainView(
						trainId = "T-1",
						phase = TrainPhase.QUEUED,
						frontSectionName = null,
						velocityMps = 0.0,
						accelerationMps2 = 0.0,
						destinationInOutName = "B",
						signalAheadName = null,
						signalAheadAspect = null,
						distanceToSignalAheadMetres = 0.0,
						waitingSinceSimTime = 0.0,
						waitSeconds = 1.0
					)
				),
			queued = listOf(QueuedTrainView("T-1", "B", 0.0)),
			activeCount = 0,
			capacity = 3
		)

	/**
	 * Two signalled ticks per repetition — enough to exercise the `prevSimTime`/`prevObs`
	 * carry-over between ticks, which a single-tick repetition would never touch.
	 */
	@RepeatedTest(1000)
	@DisplayName("a signalled tick always completes, records exactly once and stays Running")
	fun signalledTickIsStable(repetitionInfo: RepetitionInfo) {
		val signal = DefaultSnapshotSignal()
		val ring = TickRingBuffer(capacity = 4)
		val queue = ActuatorCommandQueue()
		val controller = CountingController()
		var tickCounter = 0L

		val loop =
			DispatchTickLoop(
				observations = {
					tickCounter++
					observation(tickCounter)
				},
				annotator = annotator,
				renderer = ObservationRenderer { "" },
				emission =
					EmissionStrategy { _, obs ->
						listOf(
							AttributedAction(
								commandId = CommandId(0L),
								tick = obs.tick,
								action = DispatchAction.ApproveTrain("T-1"),
								author = ActionAuthor.RULE_BASED
							)
						)
					},
				validator = validator,
				queue = queue,
				ring = ring,
				budget = NoTimeoutBudget,
				controller = controller,
				snapshotSignal = signal
			)

		// Signal ahead of each await: DefaultSnapshotSignal coalesces to at most one permit, so
		// the loop consumes exactly one per tick regardless of ordering with the producer.
		val records =
			runBlocking {
				signal.signal()
				val first = loop.runTick()
				signal.signal()
				val second = loop.runTick()
				listOfNotNull(first, second)
			}

		assertThat(records.size).isEqualTo(2)
		assertThat(ring.snapshot().size).isEqualTo(2)
		assertThat(records.map { it.tick }).isEqualTo(listOf(1L, 2L))
		assertThat(controller.awaitIfPausedCount).isEqualTo(2)
		assertThat(controller.throttleCount).isEqualTo(2)
		assertThat(loop.runOutcome).isEqualTo(RunOutcome.Running)

		// CommandIds are unique within a loop and strictly increasing; across repetitions each
		// loop restarts its own counter, so only the within-repetition ordering is asserted.
		val ids = records.flatMap { record -> record.actions.map { it.commandId.value } }
		assertThat(ids).isEqualTo(ids.sorted())
		assertThat(ids.toSet().size).isEqualTo(ids.size)
		lastCommandId.set(ids.last())

		// Both admissions were posted: the queue is the only handoff to the sim thread, so a
		// silently dropped decision here would be a lost command in production.
		assertThat(queue.drain().size).isEqualTo(2)
		assertThat(repetitionInfo.currentRepetition > 0).isTrue()
	}

	/**
	 * A timed-out signal must be a clean no-op, not a lost tick or a leaked permit. Repeated
	 * heavily because the timeout path is the one that runs ~20× per tick in GUI 1× mode.
	 */
	@RepeatedTest(1000)
	@DisplayName("an un-signalled await times out cleanly without recording or posting")
	fun unsignalledAwaitIsCleanNoOp() {
		val ring = TickRingBuffer()
		val queue = ActuatorCommandQueue()
		val controller = CountingController()

		val loop =
			DispatchTickLoop(
				observations = { observation(1L) },
				annotator = annotator,
				renderer = ObservationRenderer { "" },
				emission = EmissionStrategy { _, _ -> emptyList() },
				validator = validator,
				queue = queue,
				ring = ring,
				budget = NoTimeoutBudget,
				controller = controller,
				snapshotSignal = DefaultSnapshotSignal(awaitTimeoutMillis = 1L)
			)

		val record = runBlocking { loop.runTick() }

		assertThat(record == null).isTrue()
		assertThat(ring.snapshot()).isEmpty()
		assertThat(queue.drain()).isEmpty()
		assertThat(controller.awaitIfPausedCount).isEqualTo(1)
		assertThat(controller.throttleCount).isEqualTo(0)
		assertThat(loop.runOutcome).isEqualTo(RunOutcome.Running)
	}
}
