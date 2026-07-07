/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Immutable snapshot of a track block's occupancy state at a moment in simulation time.
 *
 * Produced by [NetworkPerceptionPort.blockOccupancy] and [NetworkPerceptionPort.allBlockOccupancies].
 * The snapshot captures the block state at the instant it is read; it is not updated
 * as the simulation advances.
 *
 * ## State Lifecycle
 *
 * ```
 * FREE        — no train reserved or occupies this block
 * RESERVED    — path set up for [trainId]; train not yet physically present
 * OCCUPIED    — [trainId] is physically on the block (front entered, tail not yet cleared)
 * ```
 *
 * @property blockId Block name or identifier as configured in the railway XML
 *   (e.g. `"k1"`, `"kA"`).  Unnamed blocks use a generated `"sep1-sep2"` label.
 * @property state Current occupancy state.
 * @property trainId Identifier of the train that owns (reserved or occupies) this block.
 *   `null` when [state] is [TrackFacility.State.FREE].
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
data class BlockOccupancyReading(
	val blockId: String,
	val state: TrackFacility.State,
	val trainId: String?
)
