/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: hold one train in an OwnershipConflict wait
 * 2026
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds exactly one train in an ownership-conflict wait: [navigation] answers
 * [PathResult.OwnershipConflict] for the first train that queries from a separator matching
 * [heldAt], and for every later query of that train from such a separator. Every other query —
 * other trains, and the held train's own queries from any other separator — goes to [realNav],
 * so the rest of the layout keeps working and simulated time keeps advancing around the held
 * train.
 *
 * The default [heldAt] (any separator but a [DynamicInOut]) holds the first train under way: it
 * has left its entry `InOut` and holds track, the never-extended-route shape of Issues #943 and
 * #1244. `heldAt = { it is DynamicInOut }` holds the first train at its entry `InOut` instead.
 *
 * Inject [navigation] through [NavigationDecoratingContext], so the held train's wait condition
 * consults it too and never becomes true:
 *
 * ```kotlin
 * val conflict = FirstTrainOwnershipConflict(context.getRoutingServices().getTrainNavigationService())
 * val conflictEnv = NavigationDecoratingContext(context, conflict.navigation) { errors.add(it) }
 * ```
 *
 * @param realNav the real navigation service every other query goes to
 * @param heldAt the separators a query must start from for the train to be held
 */
class FirstTrainOwnershipConflict(
	realNav: TrainNavigationService,
	heldAt: (PathSeparator) -> Boolean = { it !is DynamicInOut }
) {
	/** The held train: `null` until the first query from a [heldAt] separator, then fixed. */
	val heldTrainId = AtomicReference<String?>(null)

	/** The navigation service the simulation must see; it is called on the simulation thread. */
	val navigation: TrainNavigationService =
		decoratingTrainNavigationService(realNav) { trainId, separator ->
			if (heldAt(separator)) heldTrainId.compareAndSet(null, trainId)
			if (trainId == heldTrainId.get() && heldAt(separator)) {
				PathResult.OwnershipConflict
			} else {
				realNav.findReservedPathForTrain(trainId, separator)
			}
		}
}
