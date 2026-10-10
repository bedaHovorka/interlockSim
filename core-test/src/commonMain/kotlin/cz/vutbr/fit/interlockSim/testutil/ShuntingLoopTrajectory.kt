/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: sample a ShuntingLoop train's kinematics at fixed simulation times
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.sim.ApprovesTrains
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * One train's velocity and travelled distance at one simulation time, or `null` values when the
 * followed train was not an approved train of the loop at that moment (none admitted yet, or
 * already gone). [trainName] is the followed train's name, or `null` before one was seen.
 */
data class TrajectorySample(
	val time: Double,
	val trainName: String?,
	val velocity: Double?,
	val totalDistance: Double?
)

/**
 * Samples the kinematics of the first train [loop] approves at each of [times] (simulation
 * seconds, ascending).
 *
 * Register it with `context.addPropertyChangeListener(sampler)` before `run()`. The first report
 * event — delivered on the simulation thread, with the kDisco context active — activates a
 * sampling [Process] that holds until each sample time and reads the train's kinematics from
 * [ApprovesTrains.getApprovedTrains] on the simulation thread; a listener is the only hook a
 * test has into a context it cannot activate a process in before `run()`. The train is followed
 * by identity, not by name: train numbers come from a process-wide counter, so the first train
 * of a run is `Train #1` only in the first run of a process. Platform-neutral on purpose: the
 * native and the JVM parity tests compare the same trajectory (Issue #1126), so an
 * integration-order or arithmetic difference between the two platforms cannot hide behind equal
 * train counts.
 */
class ShuntingLoopTrajectorySampler(
	private val loop: ApprovesTrains,
	private val times: List<Double>
) : ContextPropertyChangeListener {
	private val collected = mutableListOf<TrajectorySample>()
	private var started = false
	private var followed: Train? = null

	/** The samples taken so far, in time order; complete once the run has passed the last time. */
	val samples: List<TrajectorySample> get() = collected.toList()

	override fun propertyChange(event: ContextChangeEvent) {
		if (started) return
		started = true
		Process.activate(SamplingProcess())
	}

	private inner class SamplingProcess : Process() {
		override suspend fun actions() {
			for (t in times) {
				val wait = t - time()
				if (wait > 0.0) hold(wait)
				val approved = loop.getApprovedTrains()
				val train = followed ?: approved.firstOrNull()?.also { followed = it }
				val present = train?.takeIf { it in approved }
				collected += TrajectorySample(time(), train?.name, present?.getVelocity(), present?.totalDistance)
			}
		}
	}
}

/** One point of a reference trajectory: velocity (m/s) and travelled distance (m) at [time]. */
data class TrajectoryReferencePoint(
	val time: Double,
	val velocity: Double,
	val totalDistance: Double
)

/**
 * Tolerance on the reference trajectory's velocity and distance. The two platforms run the same
 * pure-Kotlin arithmetic (kdisco#69), so they are expected to agree far closer than this; the
 * tolerance only absorbs a last-bit difference, never an integration-order change.
 */
const val REFERENCE_TRAJECTORY_TOLERANCE = 1e-6

/**
 * Reference trajectory of the first train `ShuntingLoop(endTime = 60)` approves on
 * `vyhybna.xml` with the fixed-seed generator, at five simulation times that cover the first
 * run-up, the first braking leg, a resume and the second run-up. Measured on the JVM at the
 * generator's 1 ms `dtMax` with the engine ahead of the velocity integration (Issue #1126);
 * the native and the JVM parity tests both assert it within [REFERENCE_TRAJECTORY_TOLERANCE].
 * Before #1126 the same samples read 7.4026, 16.8019, 12.8075, 12.8590 and 22.4695 m/s — a
 * 5e-3 m/s shift the tolerance is well below.
 */
val SHUNTING_LOOP_REFERENCE_TRAJECTORY: List<TrajectoryReferencePoint> =
	listOf(
		TrajectoryReferencePoint(5.0, 7.40740740740752, 11.111111111110676),
		TrajectoryReferencePoint(10.0, 16.80266116739491, 75.7542281189626),
		TrajectoryReferencePoint(15.0, 12.799771462457981, 137.84279882953462),
		TrajectoryReferencePoint(20.0, 12.86224118047348, 213.16504577220496),
		TrajectoryReferencePoint(25.0, 22.469648109749144, 301.49476899765267)
	)

/** Simulation times of [SHUNTING_LOOP_REFERENCE_TRAJECTORY], for [ShuntingLoopTrajectorySampler]. */
val SHUNTING_LOOP_REFERENCE_TIMES: List<Double> = SHUNTING_LOOP_REFERENCE_TRAJECTORY.map { it.time }
