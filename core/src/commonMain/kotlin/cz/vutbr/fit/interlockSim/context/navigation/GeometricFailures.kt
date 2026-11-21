/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

/**
 * Permanent geometric impossibilities seen while trying a request's candidate paths.
 *
 * The classes of impossibility are listed at
 * [PathReservationService.ReservationResult.GeometricallyImpossible]; the permanence gate that
 * consumes the two facts below is `DefaultPathReservationService.classifyExhaustedAttempt`.
 *
 * Two facts, deliberately kept apart (Issue #937):
 * - [reason] is the **first** one hit, because a geometric reason is the most informative
 *   thing to report and later candidates must not overwrite it (the Issue #903 ruling, which
 *   still stands for reporting).
 * - [count] is how many candidates hit one: permanence is an AND over candidates (see the gate above).
 *
 * Shared by [DefaultPathReservationService.reservePath], the candidate screening in
 * [RouteCandidateScreen], [CandidateCommitter]'s switch-configuration and G4 failure recorders and the
 * service's attempt classification (Issue #1210, SP12.9).
 *
 * @property reason First geometric failure reason, or `null` if no candidate hit one.
 * @property count Number of candidates that failed geometrically.
 */
internal data class GeometricFailures(
	val reason: String? = null,
	val count: Int = 0
) {
	/**
	 * Folds the failures of a later screen into these: this side's reason stays first (Issue #903),
	 * the counts add up (Issue #937). Used to fold the Step 1.4 START screen and the Step 1.5 END
	 * screen into one record (Issue #1378).
	 */
	operator fun plus(later: GeometricFailures): GeometricFailures =
		GeometricFailures(reason ?: later.reason, count + later.count)
}

/**
 * Records one geometric candidate failure: keeps the first [reason], increments the count
 * (Issue #903 for the reason, Issue #937 for the count). Extracted so each call site stays a
 * single line: the two screens in [RouteCandidateScreen] and [CandidateCommitter]'s switch-configuration
 * and G4 failure recorders.
 */
internal fun recordGeometricFailureOnce(
	current: GeometricFailures,
	reason: String
): GeometricFailures = GeometricFailures(current.reason ?: reason, current.count + 1)
