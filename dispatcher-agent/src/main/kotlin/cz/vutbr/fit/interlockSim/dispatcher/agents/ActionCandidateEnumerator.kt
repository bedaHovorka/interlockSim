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
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
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
 * - Active train with an existing reservation: `from = reservation.targetName` so the
 *   request_route candidate correctly models the forward extension (new start == old target).
 * - Otherwise: `from = destinationInOutName` (last-resort fallback; the validator performs no
 *   origin check for non-HELD trains without a reservation).
 *
 * @param destinationOf Maps each [TrainView] to its declared destination InOut name. Defaults
 *   to [TrainView.destinationInOutName]. Override in tests to inject fixed values.
 *
 * @since Issue #827 (SP2c.4 — Goal 10); forward-extension `from` fix in Issue #829 (SP2c.6)
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
				val existingReservation: ReservationView? =
					observation.reservations.find { it.trainId == train.trainId }
				val from =
					when {
						train.phase == TrainPhase.HELD && train.signalAheadName != null ->
							train.signalAheadName
						existingReservation != null ->
							// Forward extension: new.from == old.target mirrors
							// PathReservationRegistry's merge precondition and ActionValidator's
							// forward-extension allowance (Issue #829 SP2c.6).
							existingReservation.targetName
						else -> train.destinationInOutName
					}
				candidates += DispatchAction.RequestRoute(train.trainId, from, destinationOf(train))
				candidates += DispatchAction.CancelRoute(train.trainId)
			}

		return candidates
	}
}
