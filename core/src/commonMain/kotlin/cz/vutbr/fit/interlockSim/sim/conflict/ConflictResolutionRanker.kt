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
 * Computes an operational-impact ranking score for [ConflictResolution] candidates and
 * sorts them from least to most disruptive.
 *
 * ## Scoring
 *
 * The score combines three independent measures of operational impact:
 *
 * 1. **Total delay added** — [ConflictResolution.EstimatedImpact.delaySeconds], weighted
 *    by [DELAY_WEIGHT_SECONDS].
 * 2. **Number of affected trains** — `resolution.affectedTrains.size`, weighted by
 *    [AFFECTED_TRAIN_WEIGHT]. This weight is deliberately large relative to
 *    [DELAY_WEIGHT_SECONDS] and [PATH_LENGTH_WEIGHT_PER_METER] so that a resolution
 *    affecting more trains always ranks as more disruptive than one affecting fewer,
 *    independent of delay or path-length magnitude.
 * 3. **Path length change** — only [ConflictResolution.Reroute] carries a physical
 *    route; [ConflictResolution.Reroute.alternativeRoute]'s total length in meters is the
 *    extra distance the train must travel to bypass the contested block, weighted by
 *    [PATH_LENGTH_WEIGHT_PER_METER]. [ConflictResolution.HoldTrain] and
 *    [ConflictResolution.SpeedAdjust] keep the train on its original path, so their
 *    contribution is always `0.0`.
 *
 * Lower score means less disruptive. [rank] sorts candidates ascending by score.
 *
 * ## Tie-breaking
 *
 * Equal scores are broken deterministically, in order:
 * 1. [ConflictResolution.Strategy] ordinal (`HOLD_TRAIN` < `REROUTE` < `SPEED_ADJUST`) —
 *    prefers keeping the train on its planned route over diverting it, all else equal.
 * 2. A strategy-specific secondary key (hold duration, route cost, or the inverse of the
 *    speed-reduction factor) so that, e.g., the cheaper of two otherwise-equal reroutes
 *    sorts first.
 * 3. The joined [ConflictResolution.affectedTrains] IDs, as a last-resort stable key.
 *
 * This class is independent of any UI so it can be shared by the resolution panel and by
 * the automated-application layer (Issue #568).
 *
 * ## Preference-weighted ranking
 *
 * An overload of [rank] accepts a [StrategyPreferenceStore] and a conflict-type key.
 * For each candidate the adjusted score is:
 *
 * ```
 * adjustedScore = score(resolution) - preferenceStore.preferenceAdjustment(conflictTypeKey, resolution.strategy)
 * ```
 *
 * Strategies chosen more frequently for the given conflict type accumulate a larger
 * preference boost and therefore rank higher (lower adjusted score) over time.
 * The tie-breaking rules are unchanged.
 *
 * @see StrategyPreferenceStore
 * @since Issue #588 (Goal 9 SP4); preference-weighted overload since Issue #592 (Goal 9 SP6)
 */
object ConflictResolutionRanker {
	/** Weight applied to [ConflictResolution.EstimatedImpact.delaySeconds] (seconds → score). */
	const val DELAY_WEIGHT_SECONDS: Double = 1.0

	/**
	 * Weight applied per affected train.
	 *
	 * Deliberately large relative to [DELAY_WEIGHT_SECONDS] and
	 * [PATH_LENGTH_WEIGHT_PER_METER] so that affected-train count dominates the score:
	 * a resolution that affects more trains is always more disruptive than one that
	 * affects fewer, regardless of delay or path-length change.
	 */
	const val AFFECTED_TRAIN_WEIGHT: Double = 10_000.0

	/** Weight applied per meter of [ConflictResolution.Reroute] path-length change. */
	const val PATH_LENGTH_WEIGHT_PER_METER: Double = 0.1

	/**
	 * Compute the operational-impact score for [resolution]. Lower is less disruptive.
	 */
	fun score(resolution: ConflictResolution): Double =
		resolution.estimatedImpact.delaySeconds * DELAY_WEIGHT_SECONDS +
			resolution.affectedTrains.size * AFFECTED_TRAIN_WEIGHT +
			pathLengthChange(resolution) * PATH_LENGTH_WEIGHT_PER_METER

	/**
	 * Physical path length change (in meters) introduced by [resolution].
	 *
	 * `0.0` for [ConflictResolution.HoldTrain] and [ConflictResolution.SpeedAdjust], which
	 * keep the train on its original path. For [ConflictResolution.Reroute], the total
	 * length of the alternative route — the extra distance the train must travel to
	 * bypass the contested block.
	 */
	fun pathLengthChange(resolution: ConflictResolution): Double =
		when (resolution) {
			is ConflictResolution.Reroute -> resolution.alternativeRoute.totalLength
			is ConflictResolution.HoldTrain, is ConflictResolution.SpeedAdjust -> 0.0
		}

	/**
	 * Sort [resolutions] from least to most disruptive, per [score], with deterministic
	 * tie-breaking (see class documentation).
	 */
	fun rank(resolutions: List<ConflictResolution>): List<ConflictResolution> =
		resolutions.sortedWith(
			compareBy<ConflictResolution> { score(it) }
				.thenBy { it.strategy.ordinal }
				.thenBy { secondaryKey(it) }
				.thenBy { it.affectedTrains.joinToString(",") }
		)

	/**
	 * Sort [resolutions] from least to most disruptive using preference weights from
	 * [preferenceStore] for [conflictTypeKey].
	 *
	 * The adjusted score for each candidate is:
	 * ```
	 * adjustedScore = score(resolution) - preferenceStore.preferenceAdjustment(conflictTypeKey, strategy)
	 * ```
	 *
	 * Strategies that have been chosen more frequently for [conflictTypeKey] accumulate a
	 * larger preference boost and therefore rank higher (lower adjusted score) over time.
	 * Tie-breaking uses the same rules as [rank].
	 *
	 * @param preferenceStore  The store holding learned preferences.
	 * @param conflictTypeKey  Key identifying the conflict type — typically the contested
	 *   block identifier or a coarser category string.
	 *
	 * @see StrategyPreferenceStore
	 * @since Issue #592 (Goal 9 SP6)
	 */
	fun rank(
		resolutions: List<ConflictResolution>,
		preferenceStore: StrategyPreferenceStore,
		conflictTypeKey: String
	): List<ConflictResolution> =
		resolutions.sortedWith(
			compareBy<ConflictResolution> {
				score(it) - preferenceStore.preferenceAdjustment(conflictTypeKey, it.strategy)
			}.thenBy { it.strategy.ordinal }
				.thenBy { secondaryKey(it) }
				.thenBy { it.affectedTrains.joinToString(",") }
		)

	/**
	 * Strategy-specific tie-break key used when two candidates with the same [ConflictResolution.Strategy]
	 * have equal [score]: hold duration (shorter = less disruptive), route cost (cheaper =
	 * less disruptive), or the *inverse* of the speed-reduction factor (a factor closer to
	 * `1.0`, i.e. a smaller reduction, is less disruptive).
	 */
	private fun secondaryKey(resolution: ConflictResolution): Double =
		when (resolution) {
			is ConflictResolution.HoldTrain -> resolution.holdDurationSeconds
			is ConflictResolution.Reroute -> resolution.alternativeRoute.cost
			is ConflictResolution.SpeedAdjust -> 1.0 - resolution.speedReductionFactor
		}
}
