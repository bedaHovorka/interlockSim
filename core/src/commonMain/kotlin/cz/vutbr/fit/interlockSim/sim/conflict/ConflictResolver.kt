/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.conflict

/**
 * Service that generates candidate [ConflictResolution]s for a detected conflict.
 *
 * Given a [ConflictDetectedEvent] this service produces a list of candidate resolutions,
 * ranked from least to most operationally disruptive
 * ([cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker]), that an operator
 * or an automated layer (Issue #568) can apply to resolve the conflict.  The service
 * itself is **headless** — it never applies a resolution; it only generates and ranks
 * options.
 *
 * ## Candidate strategies
 *
 * Implementations must generate at least the following candidates:
 *
 * | Strategy                         | Always generated? | Notes                                |
 * |----------------------------------|-------------------|--------------------------------------|
 * | [ConflictResolution.HoldTrain]   | Yes               | Hold the blocked train               |
 * | [ConflictResolution.Reroute]     | When available    | Only when an alternative path exists |
 * | [ConflictResolution.SpeedAdjust] | Yes               | Heuristic speed reduction            |
 *
 * ## Usage
 *
 * ```kotlin
 * val resolver: ConflictResolver = DefaultConflictResolver(routeFinder, inOuts, state)
 * val resolutions = resolver.generateResolutions(conflict)
 * resolutions.forEach { candidate ->
 *     logger.info { "Candidate: ${candidate.strategy} — ${candidate.estimatedImpact.description}" }
 * }
 * ```
 *
 * @see DefaultConflictResolver
 * @since Issue #585 (Goal 9 SP3)
 */
interface ConflictResolver {
	/**
	 * Generate candidate resolutions for [conflict].
	 *
	 * The returned list contains at least one [ConflictResolution.HoldTrain] and one
	 * [ConflictResolution.SpeedAdjust] candidate.  [ConflictResolution.Reroute] candidates
	 * are included only when the Goal 2 pathfinding API discovers at least one alternative
	 * route that avoids the contested block.
	 *
	 * The list is sorted from least to most operationally disruptive, per
	 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker].
	 *
	 * The list may be empty only in degenerate topologies where no feasible action
	 * can be computed (in practice this should not occur on any valid network).
	 *
	 * @param conflict The conflict event to resolve.
	 * @return A non-null, ranked list of candidate resolutions (may be empty in edge cases).
	 */
	fun generateResolutions(conflict: ConflictDetectedEvent): List<ConflictResolution>
}
