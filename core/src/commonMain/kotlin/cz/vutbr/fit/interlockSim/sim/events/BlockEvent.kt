package cz.vutbr.fit.interlockSim.sim.events

import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

sealed class BlockEvent {
	data class OccupancySet(
		val block: DynamicTrackBlock,
		val occupant: TrackOccupant,
		val entrySeparator: PathSeparator,
		val time: Double
	) : BlockEvent()

	data class OccupancyCleared(
		val block: DynamicTrackBlock,
		val occupant: TrackOccupant,
		val time: Double
	) : BlockEvent()
}

fun interface BlockEventListener {
	fun onBlockEvent(event: BlockEvent)
}
