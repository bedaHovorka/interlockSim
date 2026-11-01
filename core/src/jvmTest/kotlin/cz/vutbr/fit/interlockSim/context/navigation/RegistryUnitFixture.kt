/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.routeBlocksOf
import cz.vutbr.fit.interlockSim.testutil.separatorAt

/**
 * The east approach of `vyhybna.xml` as the registry units' tests (Issue #1172) see it:
 * `B -> zB -> vB -> doB1`, one block between each pair of separators, in the direction a train
 * coming in from `B` travels.
 *
 * `vB` is a switch, so [blockBtoZB] / [blockZBtoVB] and [blockZBtoVB] / [blockVBtoDoB1] are the two
 * block pairs that meet at a separator, and only the second pair meets at a switch.
 */
internal class RegistryUnitFixture(
	val context: DefaultSimulationContext
) {
	val inOutB: DynamicPathSeparator = context.getInOuts().single { it.name == "B" }
	val zB: DynamicPathSeparator = context.separatorAt(27, 8)
	val vB: DynamicRailSwitch = context.separatorAt(26, 8) as DynamicRailSwitch
	val doB1: DynamicPathSeparator = context.separatorAt(25, 8)

	val blockBtoZB: DynamicTrackBlock = context.routeBlocksOf(inOutB, zB).first()
	val blockZBtoVB: DynamicTrackBlock = context.routeBlocksOf(zB, vB).first()
	val blockVBtoDoB1: DynamicTrackBlock = context.routeBlocksOf(vB, doB1).first()

	/** The whole approach as one stored route: `B, block, zB, block, vB, block, doB1`. */
	fun approachPathInfo(): PathInfo = pathInfo(inOutB, blockBtoZB, zB, blockZBtoVB, vB, blockVBtoDoB1, doB1)

	/** A [PathInfo] from the first to the last of [elements], without entry directions. */
	fun pathInfo(vararg elements: PathElement): PathInfo {
		val path = ArrayPath(context)
		elements.forEach { path.add(it) }
		return PathInfo(
			start = elements.first() as DynamicPathSeparator,
			target = elements.last() as DynamicPathSeparator,
			reservedPath = path,
			entryDirections = emptyMap()
		)
	}
}
