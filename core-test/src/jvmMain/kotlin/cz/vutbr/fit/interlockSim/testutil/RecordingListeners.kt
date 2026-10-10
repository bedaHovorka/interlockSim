/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener

/**
 * A [BlockOccupancyListener] that records every event it receives, in arrival order.
 *
 * Lives in `:core-test` so the navigation tests share one copy, the same move
 * [UncaughtSimulationExceptions] made.
 */
class RecordingBlockOccupancyListener : BlockOccupancyListener {
	val events = mutableListOf<BlockOccupancyEvent>()

	override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
		events.add(event)
	}
}

/**
 * A [ContextPropertyChangeListener] that throws on an unlock notification only (`newValue ==
 * false`): the shape that reaches the release-time reclaim, for listener-containment regression
 * tests (Issue #1103).
 */
fun throwingSwitchUnlockListener(): ContextPropertyChangeListener =
	ContextPropertyChangeListener { event ->
		if (event.newValue == false) error("switch listener failure")
	}