/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared test utilities
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Checks if a path exists (or can be created) between two InOuts in the railway network.
 * Uses BFS to traverse the track graph and verify connectivity.
 *
 * PR #1043 review round: the three private copies in the XMLContextFactory test
 * suite (parse / rudyUjezd, complex-station, Prague bypass) are collapsed into
 * this one function. It owns no lifecycle — the caller keeps ownership of [context].
 */
fun existPath(
	from: InOut,
	to: InOut,
	context: EditingContext
): Boolean {
	// Get grid locations for both InOuts
	val fromLoc = context.getRailWayNetGrid().getLocation(from) ?: return false
	val toLoc = context.getRailWayNetGrid().getLocation(to) ?: return false

	// If they're the same location, path exists trivially
	if (fromLoc == toLoc) return true

	// BFS on the track graph
	val graph = context.getGraph()
	val visited = mutableSetOf<Point>()
	val queue = mutableListOf(fromLoc)

	while (queue.isNotEmpty()) {
		val current = queue.removeFirst()

		// Skip if already visited
		if (current in visited) continue
		visited.add(current)

		// Check if we reached the destination
		if (current == toLoc) return true

		// Get all track blocks connected to this location
		val edges = graph.assignedEdges(current)

		// For each track block, find the other end and add it to the queue
		for (entry in edges.entries) {
			val trackBlock = entry.value
			// TrackBlocks should be TrackSections which have ends()
			if (trackBlock !is TrackSection) continue

			val ends = trackBlock.ends()
			// Get grid locations of both ends
			for (pathSeparator in ends) {
				// Phase 6: Grid is now typed as NodeCell, but ends() returns PathSeparator
				// PathSeparator instances should be NodeCell in editing context
				if (pathSeparator !is NodeCell) continue

				val endLocation = context.getRailWayNetGrid().getLocation(pathSeparator) ?: continue
				// Add the other end (not current) to the queue
				if (endLocation != current && endLocation !in visited) {
					queue.add(endLocation)
				}
			}
		}
	}

	// No path found
	return false
}
