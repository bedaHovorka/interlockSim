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

/**
 * One open blocked-path contention as a value: a train that waits for a track section, and the
 * train that keeps the section from it now, if any (Issue #1470, Goal 9B SP4.4).
 *
 * The value twin of `context.navigation.OpenContention`, captured into [SimulationSnapshot.contentions]
 * on the simulation thread from `RoutingServices.getContentionQuery()`. Unlike `OpenContention`, it
 * holds no live domain object: the track section is named by its [BlockOccupancyReading.sectionId],
 * never by the live `DynamicTrackBlock`, so the reading is safe to read on any thread (#1467 spec §2).
 *
 * @property waitingTrainId The waiting train (`OpenContention.trainId`).
 * @property sectionId The [BlockOccupancyReading.sectionId] of the first busy section of the refused
 *   request, the one the contention was recorded on (`OpenContention.block`). It joins this reading
 *   to [SimulationSnapshot.blocks].
 * @property ownerTrainId The train that holds the section when the snapshot is taken
 *   (`OpenContention.owner`); `null` when no train holds it any more (the waiting train has not
 *   asked again yet).
 * @property sinceSimTime Simulation time (seconds) the contention was first observed
 *   (`OpenContention.since`).
 * @since Issue #1470 (Goal 9B SP4.4)
 */
data class ContentionReading(
	val waitingTrainId: String,
	val sectionId: String,
	val ownerTrainId: String?,
	val sinceSimTime: Double
)
