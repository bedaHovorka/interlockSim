/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationController

/**
 * Hand-written test double for [SimulationController].
 *
 * Tracks all method calls and supports configurable behaviour:
 * - [pauseAfterThrottleCalls]: pause after this many [throttle] calls (0 = never pause)
 * - [stepEventsQueued]: number of step-events to consume in [awaitIfPaused] before returning
 * - [stopAfterThrottleCalls]: throw [StopSimulation] from [throttle] after N calls (0 = never stop)
 * - [stepTimeValues]: queue of values returned by [pollStepTime]; null when empty
 *
 * ## Stopping the simulation
 *
 * Throwing [StopSimulation] (a RuntimeException) from inside [throttle] propagates out of
 * `runBlocking` inside `DefaultSimulationContext.run()` and terminates the simulation loop
 * without being caught by its `catch (e: DiscoException)` handler.  The test catches it.
 *
 * @since 2026-06 (Issue #499, Goal 8 Phase 2.1)
 */
class FakeSimulationController(
	/** Pause after this many [throttle] calls.  0 = never pause. */
	val pauseAfterThrottleCalls: Int = 0,
	/** Number of step-event requests pre-loaded; each is consumed once by [awaitIfPaused]. */
	stepEventsQueued: Int = 0,
	/** Throw [StopSimulation] from [throttle] after this many calls.  0 = never stop this way. */
	val stopAfterThrottleCalls: Int = 0,
	/** Pre-loaded queue of values for [pollStepTime]; returns null when empty. */
	stepTimeValues: List<Double> = emptyList()
) : SimulationController {
	/** Exception thrown to terminate the simulation loop from inside [throttle]. */
	class StopSimulation : RuntimeException("FakeSimulationController: stop requested after N throttle calls")

	/** Total number of [throttle] calls received. */
	var throttleCalls: Int = 0
		private set

	/** All sim-delta values passed to [throttle] in order. */
	val throttleDeltas: MutableList<Double> = mutableListOf()

	/** Total number of [awaitIfPaused] calls received. */
	var awaitCalls: Int = 0
		private set

	/** Total number of [pollStepEvent] calls received. */
	var pollStepEventCalls: Int = 0
		private set

	/** Total number of [pollStepTime] calls received. */
	var pollStepTimeCalls: Int = 0
		private set

	/** Remaining step-event credits (decremented inside [awaitIfPaused]). */
	private var stepEventCredits: Int = stepEventsQueued

	/** Queue of time-step values; drained by [pollStepTime]. */
	private val stepTimeQueue: ArrayDeque<Double> = ArrayDeque(stepTimeValues)

	// ── SimulationController implementation ────────────────────────────────────

	override suspend fun awaitIfPaused() {
		awaitCalls++
		if (isPaused()) {
			// Consume one step-event credit to let the simulation advance
			if (stepEventCredits > 0) {
				stepEventCredits--
				// Return immediately — behaves like "step consumed, resume for one event"
				return
			}
			// No credits left and we're paused: keep blocking (infinite wait for tests that
			// stop via stopAfterThrottleCalls before this point is reached)
			// For test correctness we simply return here; the simulation will call throttle
			// next, which will throw StopSimulation if the limit is reached.
		}
	}

	override fun throttle(simDeltaSeconds: Double) {
		throttleCalls++
		throttleDeltas += simDeltaSeconds
		if (stopAfterThrottleCalls > 0 && throttleCalls >= stopAfterThrottleCalls) {
			throw StopSimulation()
		}
	}

	override fun isPaused(): Boolean = pauseAfterThrottleCalls > 0 && throttleCalls >= pauseAfterThrottleCalls

	/**
	 * Always returns `false` — step-events are consumed inside [awaitIfPaused], not via this poll.
	 */
	override fun pollStepEvent(): Boolean {
		pollStepEventCalls++
		return false
	}

	override fun pollStepTime(): Double? {
		pollStepTimeCalls++
		return if (stepTimeQueue.isEmpty()) null else stepTimeQueue.removeFirst()
	}
}
