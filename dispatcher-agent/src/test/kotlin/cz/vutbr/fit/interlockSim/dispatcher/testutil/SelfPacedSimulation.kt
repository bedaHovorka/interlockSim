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

import cz.vutbr.fit.interlockSim.context.SimulationController
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.roundToLong
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
 * A [SimulationController] that throttles like `ThrottlingSimulationController` (sleeps
 * `simDelta / speedMultiplier` of wall clock, never pauses) and records every argument it is given,
 * so a test can see what the driver asked to sleep for. It has no pause or step support:
 * [awaitIfPaused] returns at once, [isPaused] is always `false`, and step requests are ignored.
 */
class SleepingPacingRecorder(
	private val speedMultiplier: Double
) : SimulationController {
	/** Every [throttle] argument, in call order. */
	val throttleDeltas: MutableList<Double> = CopyOnWriteArrayList()

	override suspend fun awaitIfPaused() = Unit

	override fun throttle(simDeltaSeconds: Double) {
		throttleDeltas += simDeltaSeconds
		val sleepMs = (simDeltaSeconds / speedMultiplier * MILLIS_PER_SECOND).roundToLong()
		if (sleepMs > 0) Thread.sleep(sleepMs)
	}

	override fun isPaused(): Boolean = false

	override fun pollStepEvent(): Boolean = false

	override fun pollStepTime(): Double? = null

	override fun requestPause() = Unit

	override fun requestResume() = Unit

	override fun currentSpeedMultiplier(): Double = speedMultiplier

	private companion object {
		const val MILLIS_PER_SECOND = 1_000.0
	}
}
