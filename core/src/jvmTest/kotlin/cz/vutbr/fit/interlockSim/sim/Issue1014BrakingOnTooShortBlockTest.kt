/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1014 — a block too short for the half-speed ramp must still be braked, not snapped.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.domain.MINIMAL_TRAIN_DECELERATION
import cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import cz.ksimulantenbande.kdisco.SimulationEvent as KDiscoSimulationEvent

private val logger = KotlinLogging.logger {}

/**
 * Issue #1014 — on a block too short for [Motor.onWarning]'s half-speed ramp, the train must
 * still be **braked** to its stand, within the deceleration bound, and aimed at the clearance
 * stop line.
 *
 * ## What went wrong
 *
 * `onWarning` runs in two phases: run up towards half the permitted speed, then brake to a
 * standstill. On a block too short for the ramp the train never reached half speed, so
 * `derivatives` eventually cleared `accelerate` when the distance ran out. That satisfied the
 * phase-1 wait **and** failed the `accelerate &&` guard that starts phase 2, so the braking
 * phase never ran at all: the train arrived at the signal at line speed and `Front.fireStop`
 * snapped it to zero.
 *
 * ## The ladder
 *
 * Each rung is red on the code before this fix, for a different reason, so together they pin
 * all three parts of it:
 *
 * - [shortBlockIsBrakedWithinTheDecelerationBound] — a 30 m block. Before the fix the train
 *   did reach half speed, just barely, and then braked at about 19.9 m/s² — over six times the
 *   bound — because phase 2 never published its condition, so `derivatives` clamped it as if it
 *   were still accelerating.
 * - [shortestLegalBlockIsBrakedWithinTheDecelerationBound] — a [MIN_TRACK_LENGTH] block, where
 *   the ramp cannot even start. Before the fix there was no braking at all; with the first cut
 *   of the fix the exit was aimed one metre past the point phase 2 brakes to, which forced
 *   about 4.7 m/s².
 * - [aspectClearingDuringTheApproachIsRunThroughNotBrakedTo] — the braking-room exit must be
 *   armed only while the train really does stop short of a restrictive signal. Without that
 *   gate the train creeps up to a signal that has already cleared.
 * - [terminatingTheMotorMidApproachLeavesNoProcessParkedInTheWait] — the teardown contract of
 *   the new wait. Not a defect rung: it pins what the other three cannot reach.
 *
 * ## Fixture
 *
 * `A —(approach)— Sem —100 m— B` from [TestTopologies.linearPathWithSemaphoreNetwork], the same
 * fixture the Issue #989 clearance-stop family uses, with the intermediate aspect forced to
 * [Signal.STOP] after the reservation has lit it. The approach length is the variable: the
 * commanded speed is 27.78 m/s, so the ramp to half speed needs about 25 m and any approach
 * shorter than about 59 m is a too-short block.
 *
 * @see Issue989StopShortOfRestrictiveSignalTest for where the stop line itself comes from.
 */
@Tag("integration-test")
@DisplayName("Issue #1014 — a block too short for the ramp is braked, not snapped")
class Issue1014BrakingOnTooShortBlockTest : KoinTestBase() {
	private companion object {
		/** A short block: long enough to reach half speed, too short to brake from it. */
		const val SHORT_APPROACH = 30.0

		/** Simulation end time for a rung that ends with the train held at the stop line. */
		const val HELD_END_TIME = 60L

		/** Simulation end time for a rung in which the train completes its journey. */
		const val RUNNING_END_TIME = 120L

		/** Sampling period for the rungs on [SHORT_APPROACH]. */
		const val SAMPLE_PERIOD = 0.01

		/**
		 * Sampling period for the [MIN_TRACK_LENGTH] rung, whose whole approach is five metres.
		 */
		const val FINE_SAMPLE_PERIOD = 0.005

		/** Train length used by the rungs on the shortest blocks, so the train fits the block. */
		const val SHORT_TRAIN_LENGTH = 3.0

		/** The deceleration bound the braking phase must respect, as a positive magnitude. */
		const val DECELERATION_BOUND = -MINIMAL_TRAIN_DECELERATION.toDouble()

		/**
		 * Headroom allowed on top of [DECELERATION_BOUND] when the bound is measured as a finite
		 * difference between samples.
		 *
		 * The braking law is clamped at the bound, so a difference quotient over any sample
		 * interval can only be *at or below* it — the measured value on a braked approach is
		 * 3.0000. The headroom covers numerical noise only, and stays well under the smallest
		 * real violation this ladder must catch (3.19 m/s² on [SHORT_APPROACH]).
		 */
		const val DECELERATION_HEADROOM = 0.05

		/**
		 * Speed above which the train is still meaningfully moving.
		 *
		 * The Issue #989 constant, for the same reason: the braking law drives `v → 0` as the
		 * target is reached, so a properly braked train is below this by the time it stands,
		 * while a train snapped to zero by `fireStop` is not.
		 */
		const val CRAWL_SPEED_MPS = 0.5

		/**
		 * Deceleration allowed while the train coasts after its motor was torn down. A coasting
		 * train holds its speed, so this is numerical noise only; a train that entered the
		 * braking phase would show the full bound instead.
		 */
		const val COASTING_DECELERATION_TOLERANCE = 0.05

		/**
		 * Speed the train must already be doing before the teardown rung fires, so the forced
		 * `terminate()` certainly lands inside the approach wait rather than before it starts.
		 */
		const val TEARDOWN_TRIGGER_SPEED_MPS = 1.0

		/**
		 * Lower bound on the speed at which a train must pass a signal that cleared during its
		 * approach. The braked-to-the-stop-line behaviour this rung forbids arrives at about
		 * 0.01 m/s; a run-through arrives at about 12.6 m/s.
		 */
		const val RUN_THROUGH_SPEED_MPS = 5.0

		/**
		 * Distance at which rung 3b flips the aspect: past the ~12.4 m braking-room crossing on
		 * [SHORT_APPROACH], so the flip lands inside phase 2 rather than phase 1.
		 */
		const val PHASE_TWO_FLIP_DISTANCE = 20.0
	}

	// ── Rung 1 ────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a block too short to brake from half speed is still braked within the bound")
	fun shortBlockIsBrakedWithinTheDecelerationBound() {
		val profile = runApproach("R1", SHORT_APPROACH, SHORT_TRAIN_LENGTH, SAMPLE_PERIOD)
		assertBrakedToTheStopLine(profile, SHORT_APPROACH)
	}

	// ── Rung 2 ────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the shortest legal block is braked within the bound, not snapped to zero")
	fun shortestLegalBlockIsBrakedWithinTheDecelerationBound() {
		val profile = runApproach("R2", MIN_TRACK_LENGTH, SHORT_TRAIN_LENGTH, FINE_SAMPLE_PERIOD)
		assertBrakedToTheStopLine(profile, MIN_TRACK_LENGTH)
	}

	// ── Rung 3 ────────────────────────────────────────────────────────────────────

	/**
	 * The braking-room exit exists to protect a stand in front of a **restrictive** signal. If
	 * the aspect clears while the train is still running up, there is no stand to protect: the
	 * train must carry on and be re-commanded at the separator, exactly as it was before this
	 * rule. An exit that ignores the aspect brakes the train to a crawl in front of a signal
	 * that is already showing proceed.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("an aspect clearing during the approach is run through, not braked up to")
	fun aspectClearingDuringTheApproachIsRunThroughNotBrakedTo() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = SHORT_APPROACH)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		// Well before the braking-room exit would fire (12.4 m on this approach), so the flip
		// really does land inside phase 1.
		val flip =
			AspectFlipOnce(
				network.semaphore,
				Signal.FREE,
				trigger = { it.totalDistance > SHORT_APPROACH / 5.0 }
			)
		val run =
			runClearanceStopScenario(
				ctx,
				semaphores = listOf(network.semaphore),
				endTime = RUNNING_END_TIME,
				initialAspect = Signal.STOP,
				trainLength = SHORT_TRAIN_LENGTH,
				samplePeriod = SAMPLE_PERIOD,
				onSample = { _, sample ->
					samples += sample
					flip.onSample(sample)
				}
			)
		val atSeparator =
			requireNotNull(samples.minByOrNull { abs(it.totalDistance - SHORT_APPROACH) }) {
				"no samples were taken"
			}
		logger.info { "R3: flipped=${flip.fired} at the separator $atSeparator" }

		assertThat(flip.fired, name = "the aspect was cleared during the approach").isTrue()
		assertThat(atSeparator.velocity, name = "speed passing the cleared signal")
			.isGreaterThan(RUN_THROUGH_SPEED_MPS)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isGreaterThan(0)
	}

	// ── Rung 3b ───────────────────────────────────────────────────────────────────

	/**
	 * [aspectClearingDuringTheApproachIsRunThroughNotBrakedTo]'s counterpart once the train has
	 * already crossed into phase 2: the aspect clears at 20 m, past the ~12.4 m braking-room
	 * crossing, while `targetSpeed` is already latched at zero.
	 *
	 * [Motor.approachMargin]'s own KDoc promises "the approach behaves exactly as it did before
	 * this rule: the train runs on" for an aspect that clears mid-phase — but that promise is
	 * built into the phase-1 wait condition only. Once phase 2 starts, nothing re-opens
	 * `targetSpeed`: [Motor.derivatives] re-reads [Motor.semaphoreToStopShortOf] every step through
	 * [Motor.brakingTargetDistance], so the aim point does snap back from the clearance line to the
	 * signal once the aspect clears, but the train still brakes all the way to that point because
	 * `targetSpeed` stays `0.0`. Red on this commit: the train crawls past the separator instead of
	 * running through it.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("an aspect clearing after the braking-room crossing is still run through")
	fun aspectClearingAfterTheBrakingRoomCrossingIsStillRunThrough() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = SHORT_APPROACH)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		// Past the ~12.4 m braking-room crossing on this approach, so the flip lands inside phase 2.
		val flip =
			AspectFlipOnce(
				network.semaphore,
				Signal.FREE,
				trigger = { it.totalDistance > PHASE_TWO_FLIP_DISTANCE }
			)
		val run =
			runClearanceStopScenario(
				ctx,
				semaphores = listOf(network.semaphore),
				endTime = RUNNING_END_TIME,
				initialAspect = Signal.STOP,
				trainLength = SHORT_TRAIN_LENGTH,
				samplePeriod = SAMPLE_PERIOD,
				onSample = { _, sample ->
					samples += sample
					flip.onSample(sample)
				}
			)
		val atSeparator =
			requireNotNull(samples.minByOrNull { abs(it.totalDistance - SHORT_APPROACH) }) {
				"no samples were taken"
			}
		logger.info { "R3b: flipped=${flip.fired} at the separator $atSeparator" }

		assertThat(flip.fired, name = "the aspect was cleared after the braking-room crossing").isTrue()
		assertThat(atSeparator.velocity, name = "speed passing the cleared signal")
			.isGreaterThan(RUN_THROUGH_SPEED_MPS)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isGreaterThan(0)
	}

	// ── Rung 4 ────────────────────────────────────────────────────────────────────

	/**
	 * The teardown contract of the converted wait.
	 *
	 * `Motor.terminate()` sets a flag and wakes the process, and the process is expected to leave
	 * its loop. For the approach that only works because the wake-up is a
	 * `Process.reactivate`: kDisco absorbs a plain `Process.activate` aimed at a process parked in
	 * a crossing wait whose guard is still positive, and at teardown the guard *is* positive —
	 * `Train.stop()` has already zeroed the velocity, so [Motor.approachMargin] falls back to its
	 * half-speed term. An absorbed wake-up would leave the motor parked for the rest of the run
	 * with a live crossing notice, re-evaluated after every event and every integration step, on a
	 * train whose reservations have already been released.
	 *
	 * **This rung is not a defect reproduction.** No route through `Train.actions()` reaches that
	 * state today: the last leg of a journey is always commanded with `accelerateTo`, because an
	 * exit `InOut`'s `outSemaphore` is a constant [Signal.FREE] and can never be restrictive, and
	 * every abnormal exit cancels the motor first. The other three rungs therefore cannot reach
	 * this path, which is exactly why it is pinned here instead: the guard is defensive, and a
	 * defensive guard that nothing exercises is a guard that can be quietly broken.
	 *
	 * The state is forced rather than produced, which is why the trigger is explicit about it.
	 *
	 * **Engine note.** This rung only discriminates on a kDisco that re-parks a crossing wait on a
	 * stray wake-up (kdisco#74). Measured by swapping `reactivate` back to `activate`: on that
	 * engine the motor is not torn down at the forced instant but keeps governing the train for a
	 * further 2.2 simulated seconds before it finally leaves the loop, and this rung goes red on
	 * the termination time. On the older engine an `activate` resumes a crossing-parked process
	 * directly, so the wait ends either way and the rung passes with both spellings — correctly,
	 * because there the swap is not needed. It is written to compile and pass on both: kDisco grew
	 * `isWaiting()` only in that same fix, so this rung deliberately asserts on `isTerminated()`,
	 * which both versions have.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("terminating the motor mid-approach leaves no process parked in the wait")
	fun terminatingTheMotorMidApproachLeavesNoProcessParkedInTheWait() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = SHORT_APPROACH)
		val ctx = network.context.tracked()
		val motorEvents = mutableListOf<KDiscoSimulationEvent>()
		var motor: Process? = null
		var terminatedAt = -1.0
		var speedAtTerminate = -1.0

		// Registered before the run: the context freezes its listeners once `run()` starts.
		ctx.onSimulationEvent { event ->
			if (event.process?.let { it::class.simpleName == "Motor" } == true) motorEvents += event
		}

		val run =
			runClearanceStopScenario(
				ctx,
				semaphores = listOf(network.semaphore),
				endTime = HELD_END_TIME,
				initialAspect = Signal.STOP,
				trainLength = SHORT_TRAIN_LENGTH,
				samplePeriod = SAMPLE_PERIOD,
				onSample = { train, sample ->
					if (motor == null && sample.velocity > TEARDOWN_TRIGGER_SPEED_MPS) {
						val parked = motorOf(train)
						// Phase 1 by construction: moving, and still well below the half-speed
						// hand-over, so the motor is parked in the approach wait and not in the
						// braking phase. State predicates are deliberately not used here — kDisco
						// only grew `isWaiting()` in the fix this branch pins, so asserting on it
						// would not compile against the engine CI resolves.
						assertThat(parked.isTerminated(), name = "motor already terminated")
							.isFalse()
						motor = parked
						terminatedAt = sample.time
						speedAtTerminate = sample.velocity
						parked.terminate()
					}
				}
			)
		val torn = requireNotNull(motor) { "the motor was never torn down — the trigger never fired" }
		logger.info {
			"R4: terminated at t=$terminatedAt v=$speedAtTerminate, " +
				"motor events=${motorEvents.map { "${it::class.simpleName}@${it.time}" }}"
		}

		// The wait really ended: an absorbed wake-up would leave the motor parked for the rest of
		// the run and it would never reach a terminated state.
		assertThat(torn.isTerminated(), name = "motor terminated").isTrue()
		val terminationEvent =
			motorEvents.firstOrNull { it is KDiscoSimulationEvent.ProcessTerminated }
		assertThat(terminationEvent, name = "motor termination event").isNotNull()
		assertThat(requireNotNull(terminationEvent).time, name = "time the motor terminated")
			.isLessThanOrEqualTo(terminatedAt + SAMPLE_PERIOD)

		// The braking phase was skipped, so the train coasts rather than braking: the `!terminate`
		// guard on the phase-2 entry. Any deceleration from here is the clearance gate's own stop.
		val coasting =
			run.samples.filter { it.time > terminatedAt && it.velocity > CRAWL_SPEED_MPS }
		assertThat(peakDeceleration(coasting), name = "deceleration while coasting after teardown")
			.isLessThanOrEqualTo(COASTING_DECELERATION_TOLERANCE)
	}

	// ── Shared ────────────────────────────────────────────────────────────────────

	/**
	 * The train's [Motor], reached by reflection.
	 *
	 * `Motor` is a private inner class with no public accessor, and it must stay that way — this
	 * rung must not push a lifecycle hook into `sim/` production code just to be observable. The
	 * same reflection idiom is used by the other `Train` tests in this package.
	 */
	private fun motorOf(train: Train): Process {
		val field = Train::class.java.getDeclaredField("motor")
		field.isAccessible = true
		return field.get(train) as Process
	}

	/**
	 * One approach to a signal at danger over an [approach] metre block, sampled throughout.
	 */
	private fun runApproach(
		label: String,
		approach: Double,
		trainLength: Double,
		samplePeriod: Double
	): List<TrainKinematicSample> {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = approach)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		runClearanceStopScenario(
			ctx,
			semaphores = listOf(network.semaphore),
			endTime = HELD_END_TIME,
			initialAspect = Signal.STOP,
			trainLength = trainLength,
			samplePeriod = samplePeriod,
			onSample = { _, sample -> samples += sample }
		)
		logger.info { "$label: ${samples.size} samples, final ${samples.last()}" }
		return samples
	}

	/**
	 * The three properties that separate a braked approach from a snapped one: the braking stays
	 * within the bound, the train is crawling by the time it stops, and it stops on the
	 * clearance stop line.
	 */
	private fun assertBrakedToTheStopLine(
		samples: List<TrainKinematicSample>,
		approach: Double
	) {
		assertThat(peakDeceleration(samples), name = "peak deceleration")
			.isLessThanOrEqualTo(DECELERATION_BOUND + DECELERATION_HEADROOM)
		val lastMoving =
			requireNotNull(samples.lastOrNull { it.velocity > 0.0 }) { "the train never moved" }
		assertThat(lastMoving.velocity, name = "speed on the last moving sample")
			.isLessThan(CRAWL_SPEED_MPS)
		assertStoodAtClearanceStopLine(samples.last().totalDistance, approach)
	}

	/**
	 * The steepest deceleration in the run, as a positive magnitude, measured as a difference
	 * quotient between neighbouring samples.
	 *
	 * Pairs are counted only while both ends are above [CRAWL_SPEED_MPS]. The braking law is
	 * asymptotic in its target, so the last fraction of a metre is always cut off by the
	 * clearance gate's own stop; that cut-off is a step from a crawl to zero and would dominate
	 * every measurement. It is not ignored — [assertBrakedToTheStopLine] asserts separately that
	 * the speed it cuts off from really is a crawl.
	 */
	private fun peakDeceleration(samples: List<TrainKinematicSample>): Double =
		samples
			.zipWithNext()
			.filter { (a, b) -> a.velocity > CRAWL_SPEED_MPS && b.velocity > CRAWL_SPEED_MPS }
			.maxOfOrNull { (a, b) -> (a.velocity - b.velocity) / (b.time - a.time) }
			?: 0.0
}
