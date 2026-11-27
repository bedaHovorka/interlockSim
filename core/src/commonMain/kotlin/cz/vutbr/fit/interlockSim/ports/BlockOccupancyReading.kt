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
 * @property sectionId The block's unique grid id: its two end points in row order, for example
 *   `(16,8)-(25,8)` — the form of `Station.StationBlock.id`, for every block (Issue #1473). Unlike
 *   [blockId], it is unique per network by construction; two blocks with the same end points (not
 *   possible in today's graph) would get `/2`, `/3`, ... in their order of appearance. It is the
 *   join key of the planning-snapshot facets (#1467 spec §7). Empty in a reading built by hand.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
data class BlockOccupancyReading(
	val blockId: String,
	val state: TrackFacility.State,
	val trainId: String?,
	val sectionId: String = ""
)
