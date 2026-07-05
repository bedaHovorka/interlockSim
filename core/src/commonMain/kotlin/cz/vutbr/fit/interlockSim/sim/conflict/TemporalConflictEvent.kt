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

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Event emitted when two trains are predicted to occupy the same track block at the same
 * future simulation time, detected by a lookahead scan in [TemporalConflictDetector].
 *
 * Unlike [ConflictDetectedEvent] (Goal 9 SP1), which fires at the moment a reservation
 * actually conflicts, [TemporalConflictEvent] is **predictive**: it is emitted when the
 * lookahead projection shows that [trainId] and [otherTrainId] will both occupy
 * [conflictBlock] at approximately [predictedConflictTime], even though neither train
 * may have attempted a reservation yet.
 *
 * This early signal allows the Goal 9 conflict-resolution layer to re-route or delay
 * one of the trains **before** the physical conflict occurs.
 *
 * Subscribe via
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onTemporalConflictEvent].
 *
 * @property trainId              One of the two trains projected to conflict.
 * @property otherTrainId         The other train projected to conflict.
 * @property conflictBlock        The track block both trains are predicted to occupy.
 * @property predictedConflictTime Estimated simulation time (seconds) when the overlap begins.
 * @property detectionTime        Simulation time (seconds) at which the lookahead scan
 *   detected the conflict.
 *
 * @since Issue #583 (Goal 9 SP2)
 */
data class TemporalConflictEvent(
	val trainId: String,
	val otherTrainId: String,
	val conflictBlock: DynamicTrackBlock,
	val predictedConflictTime: Double,
	val detectionTime: Double
)
