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
 * Persistent record of every [ConflictResolution] applied during a simulation run.
 *
 * The operator-selection UI writes to this store via [record]; no automated writer exists
 * (the headless auto-resolution picker was removed by Goal 9B, Issue #1453). The accumulated
 * history is consumed by the Goal 9 preference-learning sub-task (Issue #592) to improve the
 * ranking of future candidates.
 *
 * ## Usage
 *
 * ```kotlin
 * val store: DispatcherPreferenceStore = DefaultDispatcherPreferenceStore()
 *
 * // Record an auto-applied resolution (no production writer since Issue #1453):
 * store.record(event, resolution, DispatcherPreferenceStore.ApplicationSource.AUTO)
 *
 * // Record an operator-selected resolution (written by the UI layer):
 * store.record(event, resolution, DispatcherPreferenceStore.ApplicationSource.OPERATOR)
 *
 * // Query history for a specific train:
 * val trainHistory = store.getChoicesForTrain("Train-1")
 * ```
 *
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
interface DispatcherPreferenceStore {
	/**
	 * Indicates whether a resolution was applied by the automated dispatcher or by
	 * a human operator via the UI.
	 */
	enum class ApplicationSource {
		/**
		 * Resolution was applied automatically, with no human in the loop. No production
		 * writer uses it since the headless auto-resolution picker was removed (Issue #1453).
		 */
		AUTO,

		/**
		 * Resolution was selected and applied by a human operator through the UI.
		 */
		OPERATOR
	}

	/**
	 * A single recorded resolution choice.
	 *
	 * @property event      The conflict that triggered this resolution.
	 * @property applied    The [ConflictResolution] candidate that was applied.
	 * @property source     Whether the choice was made automatically or by an operator.
	 */
	data class DispatcherChoice(
		val event: ConflictDetectedEvent,
		val applied: ConflictResolution,
		val source: ApplicationSource
	)

	/**
	 * Record that [applied] was selected for [event] by [source].
	 *
	 * May be called from any thread; implementations are responsible for any required
	 * thread safety.
	 *
	 * @param event   The conflict event for which the resolution was chosen.
	 * @param applied The resolution that was applied (must be one of the candidates
	 *                produced by [ConflictResolver.generateResolutions] for [event]).
	 * @param source  Who made the choice ([ApplicationSource.AUTO] or
	 *                [ApplicationSource.OPERATOR]).
	 */
	fun record(
		event: ConflictDetectedEvent,
		applied: ConflictResolution,
		source: ApplicationSource
	)

	/**
	 * Return all recorded choices in the order they were added.
	 *
	 * Returns an immutable snapshot; subsequent [record] calls do not affect the
	 * returned list.
	 */
	fun getChoices(): List<DispatcherChoice>

	/**
	 * Return all recorded choices for [trainId] (either as the blocked train or the
	 * conflicting train) in the order they were added.
	 *
	 * Returns an immutable snapshot.
	 */
	fun getChoicesForTrain(trainId: String): List<DispatcherChoice>
}
