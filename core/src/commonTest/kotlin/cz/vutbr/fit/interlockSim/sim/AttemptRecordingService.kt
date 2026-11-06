/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): a reservation service that records every entry attempt.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.util.currentSimulationTime

/**
 * Records every `reservePath` call with its instant and outcome, for the retry and watchdog tests.
 *
 * By default it delegates to the real service, so the outcome comes from the real reservation
 * state. With [refuse] every request is answered with a retryable block result instead, so trains
 * wait for releases; the refuse-mode call numbered [throwOnCallNumber] throws instead of
 * answering, to exercise the retry drain's failure containment.
 */
internal class AttemptRecordingService(
	delegate: PathReservationService,
	private val refuse: Boolean = false,
	private val throwOnCallNumber: Int = Int.MAX_VALUE
) : PathReservationService by delegate {
	data class Attempt(
		val trainId: String,
		val time: Double,
		val granted: Boolean
	)

	private val real = delegate
	val attempts: MutableList<Attempt> = mutableListOf()

	override fun reservePath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int
	): PathReservationService.ReservationResult {
		val time = currentSimulationTime()
		return if (refuse) {
			attempts.add(Attempt(trainId, time, granted = false))
			if (attempts.size == throwOnCallNumber) {
				error("AttemptRecordingService: planned throw of call number $throwOnCallNumber")
			}
			PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths = 1)
		} else {
			val result = real.reservePath(trainId, start, target, maxDepth)
			attempts.add(Attempt(trainId, time, granted = result is PathReservationService.ReservationResult.Success))
			result
		}
	}
}
