/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView

/**
 * Derives the finite set of [DispatchAction] candidates from a [DispatcherObservation]
 * (SP2c.4, Issue #827).
 *
 * ## Design contract
 *
 * This class contains **no predicate logic** — it only shapes the set of possible actions.
 * [AffordanceAnnotator] applies [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] to
 * evaluate each candidate. Keeping shape and evaluation separate closes root cause RC4
 * (annotation drifting from the validator) by construction rather than by a test that could rot.
 *
 * ## Candidate set
 *
 * For each [DispatcherObservation]:
 * - One [DispatchAction.ApproveTrain] per queued train.
 * - One [DispatchAction.RequestRoute] + one [DispatchAction.CancelRoute] per active
 *   (non-QUEUED, non-EXITED) train. The candidate set is bounded to **1 `request_route`
 *   per train** (its own declared destination), keeping block sizes manageable on larger
 *   topologies.
 *
 * ## `from` endpoint selection for [DispatchAction.RequestRoute]
 *
 * The `from` endpoint follows the same logic
 * [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] uses for origin validation:
 * - HELD train with non-null [TrainView.signalAheadName]: `from = signalAheadName` (the
 *   validator enforces this for HELD trains).
 * - All other active phases: `from = destinationInOutName` (the validator performs no origin
 *   check for non-HELD trains, so any valid endpoint name passes this check).
 *
 * @param destinationOf Maps each [TrainView] to its declared destination InOut name. Defaults
 *   to [TrainView.destinationInOutName]. Override in tests to inject fixed values.
 *
 * @since Issue #827 (SP2c.4 — Goal 10)
 */
class ActionCandidateEnumerator(
	private val destinationOf: (TrainView) -> String = { it.destinationInOutName }
) {
	/**
	 * Returns the candidate [DispatchAction] list for the given [observation].
	 *
	 * Result order is deterministic and stable for equivalent inputs:
	 * `approve_train` entries appear first (in [DispatcherObservation.queued] order), then
	 * `request_route`/`cancel_route` pairs for each active train in
	 * [DispatcherObservation.trains] order.
	 *
	 * [DispatchAction.NoOp] is **not** included — it is always appended last by
	 * [AffordanceAnnotator.annotate] as a fixed sentinel.
	 */
	fun enumerate(observation: DispatcherObservation): List<DispatchAction> {
		val candidates = mutableListOf<DispatchAction>()

		// approve_train: one per queued train
		observation.queued.forEach { queuedTrain ->
			candidates += DispatchAction.ApproveTrain(queuedTrain.trainId)
		}

		// request_route + cancel_route: one pair per active (non-QUEUED, non-EXITED) train
		observation.trains
			.filter { it.phase != TrainPhase.QUEUED && it.phase != TrainPhase.EXITED }
			.forEach { train ->
				val from =
					if (train.phase == TrainPhase.HELD && train.signalAheadName != null) {
						train.signalAheadName
					} else {
						train.destinationInOutName
					}
				candidates += DispatchAction.RequestRoute(train.trainId, from, destinationOf(train))
				candidates += DispatchAction.CancelRoute(train.trainId)
			}

		return candidates
	}
}
