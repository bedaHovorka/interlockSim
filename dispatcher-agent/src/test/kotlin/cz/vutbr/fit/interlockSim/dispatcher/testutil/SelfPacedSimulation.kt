/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import assertk.assertThat
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.min
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The simulation time of a sim thread that paces itself on the wall clock, as the production one
 * does through the shared [SimulationController] (`DefaultSimulationContext.advanceControlledStep`
 * throttles every control step): it advances [speedMultiplier] sim seconds per wall second, in whole
 * control steps of [controlStepSeconds] (`ShuntingLoop` ticks every 2 sim s), independently of how
 * long the dispatcher driver takes. The clock starts at construction, at sim time 0.
 *
 * Used by the Issue #1340 pacing tests, which need the sim to keep running while the driver's
 * `plan()` is stuck — the shape of a runaway LLM cycle.
 */
class SelfPacedSimClock(
	private val speedMultiplier: Double,
	private val controlStepSeconds: Double
) {
	private val start = TimeSource.Monotonic.markNow()

	/** The sim time of the last control step completed by now. */
	fun simTime(): Double {
		val raw = start.elapsedNow().toDouble(DurationUnit.SECONDS) * speedMultiplier
		return floor(raw / controlStepSeconds) * controlStepSeconds
	}
}

/**
 * A self-paced sim clock like [SelfPacedSimClock], but without the control-step floor: sim time
 * accumulates at [initialSpeedMultiplier] until [drop] ends the fast stretch, then at
 * [droppedSpeedMultiplier]. The sim thread paces itself on the same controller the operator's
 * speed change reaches, so a dropped dispatcher speed is a dropped sim speed. The floor is not
 * kept because the instant test plans give the dropped speed no wall time to fill a whole
 * control step with, while a production inference costs tens of seconds per cycle — and the
 * throttle pricing under test does not depend on the floor.
 */
class SpeedDroppingSimClock(
	private val initialSpeedMultiplier: Double,
	private val droppedSpeedMultiplier: Double
) {
	private val start = TimeSource.Monotonic.markNow()

	/** Wall seconds elapsed when [drop] ended the fast stretch; `null` before it. */
	private var elapsedAtDropSeconds: Double? = null

	/** Ends the fast stretch; sim time from here on accumulates at [droppedSpeedMultiplier]. */
	fun drop() {
		elapsedAtDropSeconds = start.elapsedNow().toDouble(DurationUnit.SECONDS)
	}

	/** The sim time of a wall clock advancing at the multiplier the controller currently reports. */
	fun simTime(): Double {
		val elapsed = start.elapsedNow().toDouble(DurationUnit.SECONDS)
		val fastSeconds = min(elapsed, elapsedAtDropSeconds ?: elapsed)
		return fastSeconds * initialSpeedMultiplier + (elapsed - fastSeconds) * droppedSpeedMultiplier
	}
}

/**
 * A [SimulationController] that paces the wall clock exactly as `ThrottlingSimulationController`
 * does (it delegates every [throttle] call to one, so the sleep math has one source of truth) and
 * records every argument it is given, so a test can see what the driver asked to sleep for. It has
 * no pause or step support: [awaitIfPaused] returns at once, [isPaused] is always `false`, and step
 * requests are ignored.
 */
class SleepingPacingRecorder(
	speedMultiplier: Double
) : SimulationController {
	/** Every [throttle] argument, in call order. */
	val throttleDeltas: MutableList<Double> = CopyOnWriteArrayList()

	private val pacing = ThrottlingSimulationController(speedMultiplier)

	override suspend fun awaitIfPaused() = Unit

	override fun throttle(simDeltaSeconds: Double) {
		throttleDeltas += simDeltaSeconds
		pacing.throttle(simDeltaSeconds)
	}

	override fun isPaused(): Boolean = false

	override fun pollStepEvent(): Boolean = false

	override fun pollStepTime(): Double? = null

	override fun requestPause() = Unit

	override fun requestResume() = Unit

	override fun currentSpeedMultiplier(): Double = pacing.currentSpeedMultiplier()
}

/**
 * A [SimulationController] that starts at [initialSpeedMultiplier] and drops to
 * [droppedSpeedMultiplier] as soon as the first PACE step has throttled — the operator's
 * down-shift landing between the first pace and the later ones — calling [onSpeedDropped] at
 * that instant so the test's sim clock drops with it (the sim thread paces itself on the same
 * shared controller). Records every [throttle] argument without sleeping, so the pinned
 * per-cycle bound is what fails first on the pre-fix code instead of the run stalling inside
 * the minutes-long sleep the defect is about.
 *
 * The speed profile is carried by a real [ThrottlingSimulationController], of which the recorder
 * holds one at [initialSpeedMultiplier] and swaps in a fresh one at [droppedSpeedMultiplier] when
 * the drop lands (the production controller takes its speed at construction), so
 * [currentSpeedMultiplier] is forwarded from the production class instead of a test-local
 * conditional. [throttle] does not delegate: a sleeping delegate would stall this run inside
 * exactly the minutes-long sleep the defect is about, which is why the delegate is used for the
 * speed state only.
 */
class SpeedDroppingPacingRecorder(
	initialSpeedMultiplier: Double,
	private val droppedSpeedMultiplier: Double,
	private val onSpeedDropped: () -> Unit
) : SimulationController {
	private var pacing = ThrottlingSimulationController(initialSpeedMultiplier)

	/** Every [throttle] argument, in call order. */
	val throttleDeltas: MutableList<Double> = mutableListOf()

	/** Whether the drop has landed (the first [throttle] call has happened). */
	var speedDropped = false
		private set

	override suspend fun awaitIfPaused() = Unit

	override fun throttle(simDeltaSeconds: Double) {
		if (!speedDropped) {
			speedDropped = true
			onSpeedDropped()
			pacing = ThrottlingSimulationController(droppedSpeedMultiplier)
		}
		throttleDeltas += simDeltaSeconds
	}

	override fun isPaused(): Boolean = false

	override fun pollStepEvent(): Boolean = false

	override fun pollStepTime(): Double? = null

	override fun requestPause() = Unit

	override fun requestResume() = Unit

	override fun currentSpeedMultiplier(): Double = pacing.currentSpeedMultiplier()
}

/** `ShuntingLoop`'s control step: one tick per 2 sim s. */
const val CONTROL_STEP = 2.0

/** Upper bound on [AgentLoopDriver.runCycle] calls, short-circuited ones included. */
const val MAX_RUN_CYCLE_CALLS = 100_000

/**
 * Calls [AgentLoopDriver.runCycle] until [cycles] of them have processed a snapshot, counting every
 * short-circuited call against [MAX_RUN_CYCLE_CALLS] so a stuck driver cannot hang the test.
 */
fun runProcessedCycles(
	driver: AgentLoopDriver,
	cycles: Int
) {
	var processed = 0
	var calls = 0
	runBlocking {
		while (processed < cycles && calls < MAX_RUN_CYCLE_CALLS) {
			calls++
			if (driver.runCycle()) processed++
		}
	}
}

/**
 * The Issue #1340 throttle-argument pin kit: every throttle argument after [firstCycleIndex] stays
 * within [bound], and their sum stays within [bound] per asserted cycle — sim-time bounds no
 * machine load can move, so a broken pacing cannot hide behind wall-clock jitter (the ruling in
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.OllamaTimeoutAbortTest]: counts, never wall clock).
 */
fun assertThrottleArgsWithinBound(
	throttleDeltas: List<Double>,
	bound: Double,
	firstCycleIndex: Int
) {
	val afterFirst = throttleDeltas.drop(firstCycleIndex)
	afterFirst.forEachIndexed { i, delta ->
		assertThat(delta, "throttle argument of cycle ${i + firstCycleIndex + 1}").isLessThanOrEqualTo(bound)
	}
	assertThat(
		afterFirst.sum(),
		"throttle arguments of cycles ${firstCycleIndex + 1}..${throttleDeltas.size}, summed"
	).isLessThanOrEqualTo((throttleDeltas.size - firstCycleIndex) * bound)
}
