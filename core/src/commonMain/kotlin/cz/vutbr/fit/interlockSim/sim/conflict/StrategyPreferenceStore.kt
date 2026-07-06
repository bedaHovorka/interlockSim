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
 * In-memory store that accumulates dispatcher conflict-resolution choices across a
 * simulation session and uses them to re-weight future rankings via
 * [ConflictResolutionRanker].
 *
 * ## Learning Behaviour
 *
 * Each call to [recordChoice] increments a selection counter for the given
 * `(conflictTypeKey, strategy)` pair.  [preferenceAdjustment] converts those counters
 * into a score boost that is **subtracted** from the base [ConflictResolutionRanker.score]
 * by the preference-aware overload of [ConflictResolutionRanker.rank].  The more times a
 * strategy has been chosen for a conflict type, the higher its effective rank becomes
 * (lower adjusted score = less disruptive in context = shown first).
 *
 * The boost is capped at [MAX_PREFERENCE_ADJUSTMENT], so learning can shift the ranking
 * between candidates that affect the same number of trains, but can never make a
 * preferred strategy affecting MORE trains outrank a less-preferred one affecting FEWER
 * trains — preserving the affected-train dominance invariant of
 * [ConflictResolutionRanker.AFFECTED_TRAIN_WEIGHT].
 *
 * ## Conflict-type key
 *
 * The caller defines the conflict-type key as a plain string.  A natural choice is the
 * contested block's identifier from [ConflictDetectedEvent] (e.g.
 * `conflict.block.getName()`), but any stable string that groups similar conflicts
 * together is valid — a block-group name, a junction label, or a coarser category string.
 *
 * ## Reset
 *
 * Learned preferences can be discarded at any time via [reset] (all types) or
 * [clearFor] (single type).  This is useful for starting a new simulation run with
 * a clean slate, or for an operator-initiated preference override.
 *
 * ## Example
 *
 * ```kotlin
 * val store = StrategyPreferenceStore()
 *
 * // Dispatcher chose REROUTE three times for the contested block "S1".
 * repeat(3) { store.recordChoice("S1", ConflictResolution.Strategy.REROUTE) }
 *
 * // The reroute option now ranks above speed-adjust for conflicts on "S1".
 * val ranked = ConflictResolutionRanker.rank(candidates, store, "S1")
 * ```
 *
 * @since Issue #592 (Goal 9 SP6)
 */
class StrategyPreferenceStore {
	companion object {
		/**
		 * Score boost (same units as [ConflictResolutionRanker.score]) subtracted per
		 * recorded selection of the same strategy for the same conflict type.
		 *
		 * A larger value makes learning more aggressive — after fewer selections the
		 * preferred strategy dominates the ranking.  The value is intentionally larger
		 * than [ConflictResolutionRanker.DELAY_WEIGHT_SECONDS] so that even a modest
		 * selection history can overcome typical delay differences between candidates.
		 */
		const val PREFERENCE_BOOST_PER_SELECTION: Double = 100.0

		/**
		 * Upper bound on the preference adjustment, in the same units as
		 * [ConflictResolutionRanker.score].  Deliberately half of
		 * [ConflictResolutionRanker.AFFECTED_TRAIN_WEIGHT] so that no amount of selection
		 * history can make a preferred strategy affecting MORE trains outrank a
		 * less-preferred one affecting FEWER trains — preserving the affected-train
		 * dominance invariant documented on [ConflictResolutionRanker.AFFECTED_TRAIN_WEIGHT].
		 */
		const val MAX_PREFERENCE_ADJUSTMENT: Double = ConflictResolutionRanker.AFFECTED_TRAIN_WEIGHT / 2.0
	}

	// conflictTypeKey → (strategy → selection count)
	private val selectionCounts: MutableMap<String, MutableMap<ConflictResolution.Strategy, Int>> =
		mutableMapOf()

	/**
	 * Record that [strategy] was chosen to resolve a conflict identified by [conflictTypeKey].
	 *
	 * Repeated calls for the same `(conflictTypeKey, strategy)` pair accumulate additively.
	 */
	fun recordChoice(
		conflictTypeKey: String,
		strategy: ConflictResolution.Strategy
	) {
		val countsForType = selectionCounts.getOrPut(conflictTypeKey) { mutableMapOf() }
		countsForType[strategy] = (countsForType[strategy] ?: 0) + 1
	}

	/**
	 * Return the number of times [strategy] has been chosen for [conflictTypeKey].
	 *
	 * Returns `0` when no choices have been recorded for this combination.
	 */
	fun selectionCount(
		conflictTypeKey: String,
		strategy: ConflictResolution.Strategy
	): Int = selectionCounts[conflictTypeKey]?.get(strategy) ?: 0

	/**
	 * Return the score adjustment for [strategy] given [conflictTypeKey].
	 *
	 * This is the amount to **subtract** from the base [ConflictResolutionRanker.score]:
	 * a positive value means the strategy ranks higher (less disruptive in context)
	 * relative to its unweighted position.
	 *
	 * Formula: `min(selectionCount(conflictTypeKey, strategy) × PREFERENCE_BOOST_PER_SELECTION, MAX_PREFERENCE_ADJUSTMENT)`
	 */
	fun preferenceAdjustment(
		conflictTypeKey: String,
		strategy: ConflictResolution.Strategy
	): Double =
		(selectionCount(conflictTypeKey, strategy) * PREFERENCE_BOOST_PER_SELECTION)
			.coerceAtMost(MAX_PREFERENCE_ADJUSTMENT)

	/**
	 * Clear all learned preferences, resetting every conflict-type counter to zero.
	 */
	fun reset() {
		selectionCounts.clear()
	}

	/**
	 * Clear learned preferences for [conflictTypeKey] only.
	 *
	 * Preferences for all other conflict types are not affected.
	 */
	fun clearFor(conflictTypeKey: String) {
		selectionCounts.remove(conflictTypeKey)
	}
}
