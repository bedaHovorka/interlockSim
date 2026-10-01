/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: travel direction on a straight west-to-east test line (Issue #1030).
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import java.util.IdentityHashMap

/**
 * Grid column of every [PathSeparator] in [context]'s network grid, keyed by the static separator
 * (identity). Built once, so a reader thread can look columns up without scanning the grid.
 */
fun separatorGridColumns(context: SimulationContext): Map<PathSeparator, Int> {
	val grid = context.getRailWayNetGrid()
	val columns = IdentityHashMap<PathSeparator, Int>()
	for (separator in context.cellsOfType<PathSeparator>()) {
		columns[keyOf(separator)] = grid.getLocation(separator)?.x ?: continue
	}
	return columns
}

/** The static separator a [PathSeparator] (dynamic wrapper or static) is keyed by. */
private fun keyOf(separator: PathSeparator): PathSeparator = DynamicWrapperUtils.unwrapToStatic(separator) ?: separator

/**
 * Whether [entry] is the end a train running west to east (towards growing grid columns) enters
 * [section] through: one of the section's two ends, and the one in the smaller column.
 *
 * On a straight line built with growing columns from origin to destination every train runs that
 * way, so this pins not just "an end of the section" but the *right* end — the check a torn
 * `(section, exit end of that section)` pair fails.
 */
fun isWestToEastEntryEnd(
	columns: Map<PathSeparator, Int>,
	section: TrackSection,
	entry: PathSeparator
): Boolean {
	fun column(separator: PathSeparator): Int? = columns[keyOf(separator)]
	val ends = section.ends()
	if (ends.none { sameStatic(it, entry) }) return false
	val entryColumn = column(entry) ?: return false
	return ends.all { end -> column(end)?.let { entryColumn <= it } ?: false }
}
