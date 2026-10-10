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

import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator

/**
 * One next separator the interlocking could set a route to, with the result of its availability
 * check (Issue #970).
 *
 * @property separator The reachable separator: a forward-facing semaphore, or an InOut (always a
 *   valid terminal target — see `findNextSemaphoresVia`).
 * @property available `true` when a physically legal route to [separator] exists whose blocks are
 *   all FREE, or owned by the train the query was made for (Issue #1060). Always the result of a
 *   real check: no candidate is reported unavailable without the check having run.
 * @since Issue #970
 */
data class ReservationTargetCandidate(
	val separator: DynamicPathSeparator,
	val available: Boolean
)

/**
 * The read-only query the dispatcher's target choice is made from (Issue #970): every settable
 * next separator one section ahead, each with its availability. The interlocking reports the
 * routes it could set; choosing among them is the dispatcher's act
 * ([cz.vutbr.fit.interlockSim.sim.ReservationTargetPolicy]).
 *
 * Deliberately **not** a superinterface of [PathReservationService]: making this method
 * inherited would force every implementer of the service interface to grow the candidate-query
 * machinery. [DefaultPathReservationService] implements both interfaces from one instance, and
 * [RoutingServices.getReservationTargetQuery] exposes the capability separately.
 *
 * @since Issue #970
 */
fun interface ReservationTargetQuery {
	/**
	 * Lists the next reservation targets one section ahead of [start] — the read-only twin of
	 * [PathReservationService.reservePathToAnyNextSemaphore] that reports every candidate the
	 * reserving overload would try, **without reserving anything**.
	 *
	 * Selection mirrors [PathReservationService.reservePathToAnyNextSemaphore]: determine the forward
	 * track section from [start]'s orientation, enumerate reachable separators via that section
	 * (InOuts prioritized over semaphores), and evaluate each one's availability
	 * ([PathReservationService.isPathAvailable], owner-aware when [ownerTrainId] is given). The list
	 * keeps the enumeration order, so the first available element is exactly what
	 * [PathReservationService.findNextReservationTarget] returns.
	 *
	 * Empty when [start] has no grid location or no forward track section, or when no separator is
	 * reachable — the caller then has nothing to choose from (the train waits), matching the prior
	 * `AllPathsBlocked`/`NoPathExists` outcome.
	 *
	 * Note: like [PathReservationService.findNextReservationTarget], this does not validate that the
	 * path goes through the required `next` block (that check needs a live reservation); see that
	 * method's KDoc for the scope of this simplification.
	 *
	 * @param start Starting oriented path separator (typically a semaphore).
	 * @param ownerTrainId The train that would reserve the path — its own blocks count as available
	 *   (Issue #1060) — or `null` for a FREE-only evaluation.
	 * @return The reachable next separators in enumeration order, each with its evaluated
	 *   availability; empty when none is reachable.
	 * @see PathReservationService.findNextReservationTarget
	 * @since Issue #970
	 */
	fun findReservationTargetCandidates(
		start: OrientedPathSeparator,
		ownerTrainId: String?
	): List<ReservationTargetCandidate>
}
