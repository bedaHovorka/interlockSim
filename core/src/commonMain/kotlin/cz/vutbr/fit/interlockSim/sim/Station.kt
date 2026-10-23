/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.Point
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A station as the dispatcher sees it, derived from the topology of any loaded network
 * (Goal 1B SP2, SIM-004).
 *
 * Nothing here is written by hand for one network: the block set, the entry/exit blocks with
 * their home signals and the switches all come from the track graph and the cells at its nodes.
 * This is a static inventory view; `:dispatcher-agent`'s `StationTopology` is the compact
 * prompt view of the same inventory, and `GridScanUtils.cellsOfType` scans whole-grid cells
 * rather than graph nodes.
 *
 * **Derivation rules**
 * - An **entry/exit block** is the block at an InOut. Its signal is the home signal: the
 *   [DynamicRailSemaphore] at the other end of that block, when there is one. Entry/exit
 *   blocks keep the order of [SimulationEnvironment.getInOuts] (the document order of the
 *   network XML). Corners: an InOut node may host more than one block (the blocks then share
 *   the derived name, and the last name wins in [applyBlockNames]), two InOuts joined by a
 *   single block produce two entry/exit blocks over the same block, and an InOut with no grid
 *   location is skipped with a warning.
 * - A **platform track** is a block bounded by a [DynamicRailSemaphore] at both ends where
 *   neither end is a home signal. A signal-bounded block next to a home signal belongs to the
 *   station throat, not to the station tracks. Platform tracks are ordered row by row (grid
 *   `y`, then `x` of their first end), and their signals are both end semaphores in the same
 *   order.
 * - The **switches** are every [DynamicRailSwitch] at a graph node, in the same row order.
 *
 * **Names.** An entry/exit block is named `k` + the InOut name (`kA`, `kB` on `vyhybna.xml`),
 * or `kInOut<n>` when the InOut has no name; platform tracks are named `k1`, `k2`, … in their
 * order. [from] only reads the network; [applyBlockNames] writes the names onto the blocks.
 *
 * On `vyhybna.xml` this yields exactly the blocks and signals `ShuntingLoop` used to look up
 * by hand-written coordinates (pinned by `StationTest`).
 *
 * @since Goal 1B SP2.1 (Issue #1161)
 */
class Station private constructor(
	/** Signal-bounded station tracks, row order. */
	val platformTracks: List<StationBlock>,
	/** Blocks at an InOut, in InOut order. */
	val entryExitBlocks: List<StationBlock>,
	/** Every switch of the network, row order. */
	val switches: List<DynamicRailSwitch>
) {
	/** All station blocks: platform tracks first, then entry/exit blocks. */
	val allBlocks: List<StationBlock>
		get() = platformTracks + entryExitBlocks

	/**
	 * One station block with its derived name and signals.
	 *
	 * @property signals for a platform track both end signals; for an entry/exit block the home
	 *   signal, or nothing when the block does not end at a semaphore
	 * @property inOut the InOut of an entry/exit block; `null` for a platform track, which makes
	 *   `inOut == null` the marker of the platform-track role
	 */
	class StationBlock(
		val name: String,
		val block: DynamicTrackBlock,
		val signals: List<DynamicRailSemaphore>,
		val inOut: DynamicInOut?
	)

	/** Writes the derived names onto the blocks (block names appear in observations and logs). */
	fun applyBlockNames() {
		for (stationBlock in allBlocks) {
			stationBlock.block.name = stationBlock.name
		}
	}

	companion object {
		private val logger = KotlinLogging.logger {}
		private const val BLOCK_PREFIX = "k"

		/** Derives the station of the network loaded into [context]. */
		fun from(context: SimulationEnvironment): Station {
			val grid = context.getRailWayNetGrid()
			val graph = context.getGraph()
			val nodes = graph.nodeSet().sortedWith(Array2DMap.POINT_COMPARATOR)

			val endpoints: Map<DynamicTrackBlock, List<Point>> =
				nodes
					.flatMap { node -> graph[node].map { it to node } }
					.groupBy(keySelector = { it.first }, valueTransform = { it.second })

			val entryExitBlocks = mutableListOf<StationBlock>()
			context.getInOuts().forEachIndexed { index, inOut ->
				val at = grid.getLocation(inOut)
				if (at == null) {
					logger.warn { "InOut ${inOut.name} has no grid location; skipped from the station derivation" }
					return@forEachIndexed
				}
				val name = BLOCK_PREFIX + inOut.name.ifBlank { "InOut${index + 1}" }
				for (block in graph[at]) {
					val farEnd = endpoints[block].orEmpty().firstOrNull { it != at }
					val homeSignal = farEnd?.let { grid[it] } as? DynamicRailSemaphore
					entryExitBlocks += StationBlock(name, block, listOfNotNull(homeSignal), inOut)
				}
			}

			val homeSignals = entryExitBlocks.flatMap { it.signals }.toSet()
			val platformTracks =
				endpoints
					.mapNotNull { (block, ends) ->
						val signals = ends.map { grid[it] }.filterIsInstance<DynamicRailSemaphore>()
						val isStationTrack = ends.size == 2 && signals.size == 2 && signals.none { it in homeSignals }
						if (isStationTrack) block to signals else null
					}.mapIndexed { index, (block, signals) ->
						StationBlock(BLOCK_PREFIX + (index + 1), block, signals, inOut = null)
					}

			val switches = nodes.mapNotNull { grid[it] as? DynamicRailSwitch }
			return Station(platformTracks, entryExitBlocks, switches)
		}
	}
}
