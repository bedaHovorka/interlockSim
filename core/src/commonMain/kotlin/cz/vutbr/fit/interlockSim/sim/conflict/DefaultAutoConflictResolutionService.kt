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

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default [AutoConflictResolutionService] implementation.
 *
 * Delegates candidate generation to [resolver] and persistence to [preferenceStore].
 * Always picks the **first** element of the ranked list returned by
 * [ConflictResolver.generateResolutions] — that element has the lowest operational
 * impact score per [ConflictResolutionRanker].
 *
 * @param resolver         The [ConflictResolver] used to generate candidates.
 * @param preferenceStore  The [DispatcherPreferenceStore] that records each auto-applied
 *                         choice.
 *
 * @see AutoConflictResolutionService
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
class DefaultAutoConflictResolutionService(
	private val resolver: ConflictResolver,
	private val preferenceStore: DispatcherPreferenceStore
) : AutoConflictResolutionService {
	override fun applyTopRanked(event: ConflictDetectedEvent): ConflictResolution? {
		val ranked = resolver.generateResolutions(event)
		val top = ranked.firstOrNull()

		if (top == null) {
			logger.warn {
				"applyTopRanked: no candidates for conflict " +
					"(${event.trainId} vs ${event.conflictingTrainId}) — nothing applied"
			}
			return null
		}

		preferenceStore.record(event, top, DispatcherPreferenceStore.ApplicationSource.AUTO)

		logger.debug {
			"applyTopRanked: selected ${top.strategy} for conflict " +
				"(${event.trainId} vs ${event.conflictingTrainId}) " +
				"at T=${event.time}s — impact: ${top.estimatedImpact.description}"
		}

		return top
	}

	companion object {
		private val logger = KotlinLogging.logger {}
	}
}
