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
 * Projected occupancy of a single track block by a train during a lookahead scan.
 *
 * Instances are produced by a provider registered via
 * [TemporalConflictDetector.registerProjectionProvider] and describe the time window
 * during which a train is expected to occupy [block], expressed as offsets in seconds
 * from the current simulation time.
 *
 * **Time window semantics:**
 * - [enterOffsetSeconds] = 0.0 means the train's **front** is already in (or at the
 *   entrance of) this block right now.
 * - [exitOffsetSeconds] is the time at which the train's **tail** fully clears the block.
 *   It must satisfy `exitOffsetSeconds > enterOffsetSeconds`.
 *
 * Two trains are considered to have a temporal conflict on [block] when their time
 * windows overlap: `A.enterOffset < B.exitOffset && B.enterOffset < A.exitOffset`.
 *
 * @property block    The track block that the train is projected to occupy.
 * @property enterOffsetSeconds  Seconds from now until the train's front enters [block] (≥ 0.0).
 * @property exitOffsetSeconds   Seconds from now until the train's tail exits [block]
 *   (> [enterOffsetSeconds]).
 *
 * @since Issue #583 (Goal 9 SP2)
 */
data class ProjectedOccupancy(
	val block: DynamicTrackBlock,
	val enterOffsetSeconds: Double,
	val exitOffsetSeconds: Double
)
