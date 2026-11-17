/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

/**
 * Issue #1387: per train, the signal candidates toward the train's own exit
 * ([BlockInputObservation.destinationReachableVia]) the last time the platform steer was applicable
 * and was lost — every one of them unavailable while some other candidate was available, so the
 * pick fell back to the fixed order. A class A train recorded here is a fallback (its platform
 * toward the exit was busy at the deciding pick), not blind routing.
 *
 * The record has a bounded lifetime, so it always carries the state of the tick that decided the
 * departure route (the review round of #1387, Copilot thread "Record fallback only after an
 * off-destination route is accepted"):
 * - A tick with **no available candidate at all** decides nothing: it neither writes nor clears.
 *   Recording from it would mislabel a train whose later tick takes a platform toward its exit.
 * - A later input whose steer wins ([ReservationTargetPolicy] step 3 — a reachable signal
 *   available) **clears** the record: the train was routed toward its exit, not by the fallback.
 * - The #1244 awaiting state ([BlockInputObservation.awaitingRouteExtension]) **clears** the
 *   record: the shell forces the reachable set empty there, so the record could only be stale.
 * - An input the steer does not apply to (empty reachable set — destination resolution and the
 *   #1278/#1299 rules decide — but not awaiting) **keeps** the record: that is the last
 *   applicable state.
 *
 * The decision which case a tick is comes from [ReservationTargetPolicy] through the one
 * target-selection seam ([BlockInputObservation.chosenTargetName]), never from the raw candidate
 * list — a second opinion would drift from the dispatchers and fail `TargetSelectionSingleEntryTest`.
 *
 * The entry is dropped when the train is pruned ([remove], called from `classifyWrongOut`).
 * Keyed by train only: the train's last observing input wins. Lives in the shell, not in a
 * dispatcher, so every dispatcher arm reads the same classification.
 */
internal class WrongOutFallbackRecorder {
	private val busyTowardOwnExit: MutableMap<String, Set<String>> = mutableMapOf()

	/** Updates the record keyed by [BlockInputObservation.ownerTrainId] from [input]'s facts, per the class KDoc. */
	fun observe(input: BlockInputObservation) {
		val train = input.ownerTrainId ?: return
		val reachable = input.destinationReachableVia
		if (input.awaitingRouteExtension) {
			busyTowardOwnExit.remove(train) // #1244: the set is forced empty there
			return
		}
		if (reachable.isEmpty()) return // the steer does not apply: the last applicable state stays
		when (val pick = input.chosenTargetName()) {
			null -> Unit // no pick this tick: nothing is decided
			in reachable -> busyTowardOwnExit.remove(train) // step 3 routed the train toward its exit
			else -> busyTowardOwnExit[train] = reachable // the fixed order picked: the steer was lost
		}
	}

	/** Removes and returns [train]'s record, or `null` when it has none. */
	fun remove(train: String): Set<String>? = busyTowardOwnExit.remove(train)
}
