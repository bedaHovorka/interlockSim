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
 * Default [DispatcherPreferenceStore] implementation backed by an in-memory list.
 *
 * Thread-safe: [record] and the query methods synchronize on [lock] so that the
 * store can be written from the simulation thread and read from a UI or test thread
 * concurrently.
 *
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
class DefaultDispatcherPreferenceStore : DispatcherPreferenceStore {
	private val lock = Any()
	private val choices = mutableListOf<DispatcherPreferenceStore.DispatcherChoice>()

	override fun record(
		event: ConflictDetectedEvent,
		applied: ConflictResolution,
		source: DispatcherPreferenceStore.ApplicationSource
	) {
		val choice = DispatcherPreferenceStore.DispatcherChoice(event, applied, source)
		synchronized(lock) {
			choices.add(choice)
		}
	}

	override fun getChoices(): List<DispatcherPreferenceStore.DispatcherChoice> =
		synchronized(lock) { choices.toList() }

	override fun getChoicesForTrain(trainId: String): List<DispatcherPreferenceStore.DispatcherChoice> =
		synchronized(lock) {
			choices.filter { choice ->
				choice.event.trainId == trainId || choice.event.conflictingTrainId == trainId
			}
		}
}
