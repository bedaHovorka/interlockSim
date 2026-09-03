/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Build a [gridSize]x[gridSize] network with one entry InOut ([entryName] at [entryAt]) and one
 * exit InOut ([exitName] at [exitAt]), joined by a single [trackLength] m / [trackMaxSpeed] m/s
 * track. Both InOuts are horizontal; the entry faces one way and the exit the other.
 * (`StaticTrack.maxSpeed` documents the speed unit as m/s.)
 *
 * This is the smallest network that can be transformed and simulated, so several lifecycle and
 * workflow tests start from it (Issue #1035 review round). Add obstacles such as semaphores only
 * after this call — the track join must come first.
 *
 * The caller owns the returned context and must close it (`.use`) — Issue #1035.
 */
fun buildLinearNetwork(
	gridSize: Int,
	entryName: String,
	entryAt: Point,
	exitName: String,
	exitAt: Point,
	trackLength: Double,
	trackMaxSpeed: Double
): DefaultEditingContext {
	val editingContext = DefaultEditingContext(gridSize, gridSize)
	val entry = InOut(entryName, false, Cell.SpatialType.HORIZONTAL)
	val exit = InOut(exitName, true, Cell.SpatialType.HORIZONTAL)
	editingContext.putCell(entryAt, entry)
	editingContext.putCell(exitAt, exit)

	// Connect the track before adding obstacles in the path
	val track = SimpleTrackBlock(entry, exit, trackLength, trackMaxSpeed)
	editingContext.joinCells(entryAt, exitAt, track)

	return editingContext
}
