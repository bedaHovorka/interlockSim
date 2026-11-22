/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.guardedRecipient
import cz.vutbr.fit.interlockSim.context.rethrowProcessControl
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch

/**
 * A train's teardown ([RouteReleaser.unregister]) unlocked switch claims the train still held
 * (Issue #1271). Emitted once per teardown that released at least one claim, after the
 * `BlockReleased` events of the same teardown.
 *
 * It exists for the claims no block event carries: a plain claim leaves with the last block next to
 * its switch (the registry reclaims it inside `unregisterBlock`, before that block's
 * `BlockReleased`), but a FLANK claim (`DefaultInterlockingFacade.lockRouteAtomic`) never goes
 * stale, so it can outlive the train's last block and go only at the teardown -- when the train
 * holds no block whose release could announce it. A waiter refused on such a switch would
 * otherwise never be woken. Internal to `:core`: the one consumer is the event-driven retry of
 * [cz.vutbr.fit.interlockSim.sim.MultiTrainLoop].
 *
 * Delivered as a kDisco `SimulationEvent.Custom` payload on the simulation thread, synchronously
 * inside the teardown; subscribe through [onSwitchClaimsReleased] before `run()`. A listener must
 * only record.
 *
 * @property trainId The train whose claims were released
 * @property switches The switches whose claims were released, unlocked by now
 * @property time Simulation time of the teardown
 * @since Issue #1271
 */
internal data class SwitchClaimsReleased(
	val trainId: String,
	val switches: List<DynamicRailSwitch>,
	val time: Double
)

/**
 * Subscribe to [SwitchClaimsReleased] (Issue #1271). Sugar over
 * [SimulationEnvironment.onSimulationEvent], with its contract: register before `run()`, delivered
 * synchronously on the simulation thread. A [listener] that throws is logged at WARN and the event
 * still reaches the subscribers after it, as for [onReservationExpired] -- except kDisco's process
 * control (a `CancellationException`, a listener terminating the running process), which
 * [rethrowProcessControl] rethrows: the one contract of every typed lane, [guardedRecipient] (Issue #1324).
 */
internal fun SimulationEnvironment.onSwitchClaimsReleased(listener: (SwitchClaimsReleased) -> Unit) =
	onSimulationEvent(switchClaimsReleasedRecipient(listener))

/**
 * The raw kDisco listener behind [onSwitchClaimsReleased]: the typed lane's [guardedRecipient] (Issue
 * #1324). Kept as a named function so tests can wire it onto a bare kDisco `Simulation`.
 *
 * @since Issue #1324
 */
internal fun switchClaimsReleasedRecipient(listener: (SwitchClaimsReleased) -> Unit): (SimulationEvent) -> Unit =
	guardedRecipient(listener)
