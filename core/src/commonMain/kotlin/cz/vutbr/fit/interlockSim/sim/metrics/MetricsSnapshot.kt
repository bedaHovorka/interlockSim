/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * Immutable point-in-time snapshot of simulation performance KPIs.
 *
 * Captured at a specific [time] in simulation seconds and reflects cumulative
 * counters (conflicts, completed trains, total wait) plus instantaneous
 * measurements (occupied blocks, utilization).
 *
 * ## KPI Definitions
 *
 * - **[conflictCount]** — Total reservation conflicts since simulation start.
 *   A conflict occurs when two trains attempt to reserve the same block.
 *
 * - **[completedTrains]** — Number of trains that have fully released all their
 *   reserved blocks **and** physically occupied at least one block during their
 *   journey (i.e., the train moved).  Trains whose routes are reclaimed by the
 *   `OrphanReservationSweeper` without ever moving are excluded.  Each train is
 *   counted at most once per journey.
 *
 * - **[throughput]** — Trains per simulation-second: `completedTrains / time`.
 *   Zero when [time] is zero.
 *
 * - **[totalWaitSeconds]** — Cumulative time (seconds) blocks spent in the
 *   "reserved but not yet physically occupied" state across all trains.
 *   Computed as `sum(OccupancySet.time − BlockReserved.time)` for each
 *   (block, train) pair.
 *
 * - **[averageWaitSeconds]** — Mean wait time per block-occupancy event:
 *   `totalWaitSeconds / occupancyEventCount`. Zero when no events have occurred.
 *
 * - **[occupiedBlocks]** — Number of blocks currently physically occupied by a
 *   train (state between [OccupancySet] and [OccupancyCleared]).
 *
 * - **[totalBlocks]** — Total track blocks in the network (graph edge count).
 *   Zero if the network was not yet initialised when the snapshot was taken.
 *
 * - **[utilization]** — Fraction of blocks currently occupied:
 *   `occupiedBlocks / totalBlocks`. Zero when [totalBlocks] is zero.
 *
 * @param time Simulation time (seconds) at which this snapshot was captured.
 * @param conflictCount Total reservation conflicts detected since start.
 * @param completedTrains Trains that fully released all reserved blocks after physically moving.
 * @param throughput Completed trains per simulation-second.
 * @param totalWaitSeconds Cumulative block-reservation wait time (seconds).
 * @param averageWaitSeconds Average wait time per occupancy event (seconds).
 * @param occupiedBlocks Number of currently physically occupied blocks.
 * @param totalBlocks Total blocks in the railway network.
 * @param utilization Fraction of blocks currently occupied (0.0–1.0).
 * @param trainsHoldingReservations Number of trains that currently hold at least one reserved
 *   block. Mid-run this is simply the trains under way; **at run end it is a leak gauge** — a
 *   train still counted here never had its reservations drained, so its journey was never credited
 *   and the blocks it holds were never returned to the network (Issue #936).
 * @param heldBlocks Number of block reservations outstanding across all trains, the block-level
 *   companion to [trainsHoldingReservations].
 *
 * @since Issue #672 (Goal 6 SP1)
 */
data class MetricsSnapshot(
	val time: Double,
	val conflictCount: Int,
	val completedTrains: Int,
	val throughput: Double,
	val totalWaitSeconds: Double,
	val averageWaitSeconds: Double,
	val occupiedBlocks: Int,
	val totalBlocks: Int,
	val utilization: Double,
	val trainsHoldingReservations: Int = 0,
	val heldBlocks: Int = 0
)
