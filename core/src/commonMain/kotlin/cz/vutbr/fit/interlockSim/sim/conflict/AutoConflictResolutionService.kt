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
 * Headless auto-resolution API — applies the top-ranked [ConflictResolution] for a
 * detected conflict without any UI involvement.
 *
 * This service is the non-interactive counterpart to the operator-selection UI (Issue
 * #531): instead of presenting a ranked list to a human, it picks the first (least
 * disruptive) candidate produced by [ConflictResolver.generateResolutions] and records
 * the choice in [DispatcherPreferenceStore].  The returned resolution is purely advisory
 * — the caller (typically SP2b, the deliberative dispatcher in Goal 10) is responsible
 * for enacting the simulation effect described by the resolution.
 *
 * ## Usage (SP2b pattern)
 *
 * ```kotlin
 * val autoResolver: AutoConflictResolutionService =
 *     DefaultAutoConflictResolutionService(resolver, preferenceStore)
 *
 * env.onConflictDetectedEvent { event ->
 *     val resolution = autoResolver.applyTopRanked(event) ?: return@onConflictDetectedEvent
 *     when (resolution) {
 *         is ConflictResolution.HoldTrain   -> holdTrain(resolution.trainId, resolution.holdDurationSeconds)
 *         is ConflictResolution.Reroute     -> rerouteTrain(resolution.trainId, resolution.alternativeRoute)
 *         is ConflictResolution.SpeedAdjust -> adjustSpeed(resolution.trainId, resolution.speedReductionFactor)
 *     }
 * }
 * ```
 *
 * @see DefaultAutoConflictResolutionService
 * @see ConflictResolver
 * @see DispatcherPreferenceStore
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
interface AutoConflictResolutionService {
	/**
	 * Select and record the top-ranked resolution for [event].
	 *
	 * Calls [ConflictResolver.generateResolutions], picks the first element (the least
	 * disruptive candidate per [ConflictResolutionRanker]), records it in
	 * [DispatcherPreferenceStore] with source [DispatcherPreferenceStore.ApplicationSource.AUTO],
	 * and returns it to the caller.
	 *
	 * Returns `null` only if [ConflictResolver.generateResolutions] returns an empty list
	 * (a degenerate case that should not occur on any valid network — see
	 * [ConflictResolver.generateResolutions] KDoc).
	 *
	 * @param event The spatial or temporal conflict that needs to be resolved.
	 * @return The resolution that was selected and recorded, or `null` if no candidates
	 *         were available.
	 */
	fun applyTopRanked(event: ConflictDetectedEvent): ConflictResolution?
}
