/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * One observation of the train's kinematic state, taken on the simulation thread.
 *
 * Extracted verbatim from the Issue #989 test class, which needs it in four scenarios; the
 * follow-up tests (consecutive stops, aspect flicker, route release) sample the same way.
 * JVM-only: [toString] uses `String.format`.
 */
data class TrainKinematicSample(
	val time: Double,
	val distanceToSemaphore: Double,
	val velocity: Double,
	val totalDistance: Double
) {
	override fun toString(): String =
		"t=%.3f d=%.6f v=%.6f s=%.6f".format(time, distanceToSemaphore, velocity, totalDistance)
}

/**
 * Polls [train] every [period] simulated seconds until [endTime].
 *
 * A kDisco [Process] rather than a listener, because [Train]'s kinematic state must only be
 * read from the simulation thread. Activate it from the scenario's train-creation callback and
 * collect the samples in the [onSample] callback's closure.
 *
 * Extracted verbatim from the Issue #989 test class. Its contract is pinned by
 * `TrainKinematicSamplerContractTest` in `:core` jvmTest: one sample per period from
 * activation until [endTime], with a never-decreasing `totalDistance`.
 */
class TrainKinematicSampler(
	private val train: Train,
	private val endTime: Double,
	private val period: Double,
	private val onSample: (TrainKinematicSample) -> Unit
) : Process() {
	override suspend fun actions() {
		while (time() < endTime) {
			onSample(
				TrainKinematicSample(
					time = time(),
					distanceToSemaphore = train.distanceToSemaphore(),
					velocity = train.getVelocity(),
					totalDistance = train.totalDistance
				)
			)
			hold(period)
		}
	}
}

/**
 * Applies one aspect change to [semaphore] — the first time a sample satisfies [trigger].
 *
 * The Issue #989 clearance-stop scenarios all drive the intermediate signal from the sampling
 * callback: wait for a stand or a distance window, flip the aspect once, then assert the flip
 * happened. This class is that pattern once instead of a private near-copy per scenario: the
 * [fired] flag replaces each scenario's "already flipped" boolean, and [onFlip] hands back the
 * sample the flip was applied at (for example to record where the train stood).
 *
 * Feed it from [TrainKinematicSampler]'s [onSample][TrainKinematicSampler.onSample] callback:
 * that runs on the simulation thread, the only thread allowed to write
 * [DynamicRailSemaphore.signal]. The trigger may capture the semaphore to guard on the current
 * aspect, exactly like the inline lambdas it replaces did.
 */
class AspectFlipOnce(
	private val semaphore: DynamicRailSemaphore,
	private val toAspect: Signal,
	private val trigger: (TrainKinematicSample) -> Boolean,
	private val onFlip: (TrainKinematicSample) -> Unit = {}
) {
	/** True once the aspect has been applied; the trigger is not evaluated again after that. */
	var fired: Boolean = false
		private set

	/** Feeds one sample: applies the flip on the first sample that satisfies [trigger]. */
	fun onSample(sample: TrainKinematicSample) {
		if (fired) return
		if (trigger(sample)) {
			fired = true
			semaphore.signal = toAspect
			onFlip(sample)
		}
	}
}