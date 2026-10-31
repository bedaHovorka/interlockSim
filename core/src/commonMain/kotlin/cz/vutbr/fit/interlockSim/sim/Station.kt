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
import cz.vutbr.fit.interlockSim.objects.core.Cell
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
 * - **Signal direction.** A [DynamicRailSemaphore] has a travel direction ([DynamicRailSemaphore.direction]):
 *   the cell segment it lets a train leave through. Its `orientation` picks which of the two line
 *   segments of its spatial type that is: `false` is the first (`HORIZONTAL`: `F`, towards +x;
 *   `VERTICAL`: `H`, towards +y), `true` the second (`HORIZONTAL`: `A`, towards -x; `VERTICAL`: `C`,
 *   towards -y). A semaphore at the end of a block **faces away from the block** when that segment
 *   is not the one the block is attached to, that is when a train passing the signal in its travel
 *   direction leaves the block. Said along a horizontal block: the signal at the west end has
 *   `orientation = true` and the signal at the east end `orientation = false`. A signal that faces
 *   away from a block is a departure signal for it, and an incoming train at the other end
 *   meets it as an entry signal.
 * - An **entry/exit block** is the block at an InOut. Its signal is the home signal: the
 *   [DynamicRailSemaphore] at the other end of that block when there is one and it faces away from
 *   the block, that is towards the station, so it faces the train entering from the InOut; a semaphore
 *   there that faces into the block is no home signal and is kept as [StationBlock.misdirectedSignals]
 *   (Issue #1272). Entry/exit blocks keep the order of [SimulationEnvironment.getInOuts] (the
 *   document order of the network XML). Corners: an InOut node may host more than one block (each block is still one
 *   entry/exit block), two InOuts joined by a single block produce one entry/exit block naming
 *   both, and an InOut with no grid location is skipped with a warning.
 * - A **platform track** is a block bounded by a [DynamicRailSemaphore] at both ends where both
 *   semaphores face away from the block (a departure signal at each end, so the two signals face
 *   away from each other). Signal-bounded blocks whose signals face the same way, such as an
 *   automatic block section on open line or a throat block, are not station tracks, however deep
 *   they lie. Platform tracks are ordered row by row (grid
 *   `y`, then `x` of their first end), and their signals are both end semaphores in the same
 *   order.
 * - The **switches** are every [DynamicRailSwitch] at a graph node, in the same row order.
 *
 * **Names.** An entry/exit block is named `k` + the InOut name (`kA`, `kB` on `vyhybna.xml`),
 * or `kInOut<n>` when the InOut has no name (a block joining two InOuts is one entry/exit block
 * named after both, for example `kA/B`); platform tracks are named `k1`, `k2`, … in their
 * order. [from] only reads the network; [applyBlockNames] writes the names onto the blocks.
 * A name is for people and need not be unique; every block also has a unique [StationBlock.id],
 * its two end points in row order, for example `(16,8)-(25,8)`. [from] logs the derived station
 * once at INFO with every block name and id.
 *
 * **Capacity.** [topologyCapacity] is the number of platform tracks: every signal-bounded
 * station track a train can stand on, including through or bypass tracks without a platform.
 * It is therefore not the platform count of the station description: Praha hlavní nádraží has
 * 8 platforms and a bypass station track, capacity 9. A scenario may override it ([capacity]).
 *
 * On `vyhybna.xml` this yields exactly the blocks and signals `ShuntingLoop` used to look up
 * by hand-written coordinates (pinned by `StationTest`).
 *
 * @since Goal 1B SP2.1 (Issue #1161); capacity, ids and logging since SP2.2 (Issue #1162)
 */
class Station private constructor(
	/** Signal-bounded station tracks, row order. */
	val platformTracks: List<StationBlock>,
	/** Blocks at an InOut, in InOut order. */
	val entryExitBlocks: List<StationBlock>,
	/** Every switch of the network, row order. */
	val switches: List<DynamicRailSwitch>,
	/** Every InOut of the network, in document order. */
	val inOuts: List<DynamicInOut>,
	/** Every RailSemaphore at a graph node, row order (InOut-embedded semaphores are not cells). */
	val signals: List<DynamicRailSemaphore>
) {
	/**
	 * One station block with its derived name and signals.
	 *
	 * @property id unique within the station: the block's two end points in row order
	 * @property signals for a platform track both end signals; for an entry/exit block the home
	 *   signal, or nothing when there is no end semaphore or its end semaphore faces into the
	 *   block (see [misdirectedSignals])
	 * @property inOuts the InOut(s) of an entry/exit block — two when one block joins two InOuts;
	 *   empty for a platform track
	 * @property misdirectedSignals the semaphores at the ends of an entry/exit block that face into
	 *   it (see "Signal direction"), so they are no home signal; empty for a platform track, whose
	 *   end signals all face away by definition (Issue #1272)
	 */
	class StationBlock(
		val id: String,
		val name: String,
		val block: DynamicTrackBlock,
		val signals: List<DynamicRailSemaphore>,
		val inOuts: List<DynamicInOut>,
		val misdirectedSignals: List<DynamicRailSemaphore> = emptyList()
	) {
		/** The first InOut of an entry/exit block; `null` for a platform track. */
		val inOut: DynamicInOut? get() = inOuts.firstOrNull()
	}

	/** All station blocks: platform tracks first, then entry/exit blocks. */
	val allBlocks: List<StationBlock> = platformTracks + entryExitBlocks

	/** Station capacity from the topology: the number of [platformTracks]. */
	val topologyCapacity: Int = platformTracks.size

	/**
	 * The capacity a scenario runs with: [scenarioOverride] when given, else [topologyCapacity].
	 *
	 * @throws IllegalArgumentException when [scenarioOverride] is not positive
	 */
	fun capacity(scenarioOverride: Int? = null): Int {
		require(scenarioOverride == null || scenarioOverride > 0) {
			"Station capacity override must be positive, got: $scenarioOverride"
		}
		return scenarioOverride ?: topologyCapacity
	}

	/** Writes the derived names onto the blocks (block names appear in observations and logs). */
	fun applyBlockNames() {
		for (stationBlock in allBlocks) {
			stationBlock.block.name = stationBlock.name
		}
	}

	companion object {
		private val logger = KotlinLogging.logger {}
		private const val BLOCK_PREFIX = "k"
		internal const val UNNAMED = "(unnamed)"

		/** Derives the station of the network loaded into [context]. */
		fun from(context: SimulationEnvironment): Station {
			val grid = context.getRailWayNetGrid()
			val graph = context.getGraph()
			val nodes = graph.nodeSet().sortedWith(Array2DMap.POINT_COMPARATOR)

			val endpoints: Map<DynamicTrackBlock, List<Point>> =
				nodes
					.flatMap { node -> graph[node].map { it to node } }
					.groupBy(keySelector = { it.first }, valueTransform = { it.second })

			// One entry/exit block per block at an InOut: a block joining two InOuts (a plain
			// linear track) is one block naming both, so ids stay unique.
			// The semaphores at the ends of [block]: first those that face away from it, then those
			// that face into it (see "Signal direction").
			fun endSemaphores(
				ends: List<Point>,
				block: DynamicTrackBlock
			): Pair<List<DynamicRailSemaphore>, List<DynamicRailSemaphore>> {
				val (facingAway, facingInto) =
					ends
						.mapNotNull { end -> (grid[end] as? DynamicRailSemaphore)?.let { end to it } }
						.partition { (end, signal) -> facesAwayFrom(graph.assignedEdges(end), signal, block) }
				return facingAway.map { it.second } to facingInto.map { it.second }
			}

			val inOuts = context.getInOuts().toList()
			val inOutLabels = inOuts.mapIndexed { index, inOut -> inOut.name.ifBlank { "InOut${index + 1}" } }
			val inOutsByBlock: Map<DynamicTrackBlock, List<Int>> =
				inOuts
					.withIndex()
					.flatMap { (index, inOut) ->
						val at = grid.getLocation(inOut)
						if (at == null) {
							logger.warn { "InOut ${inOut.name} has no grid location; skipped from the station derivation" }
							emptyList<Pair<DynamicTrackBlock, Int>>()
						} else {
							graph[at].map { it to index }
						}
					}.groupBy(keySelector = { it.first }, valueTransform = { it.second })
			val entryExitBlocks =
				inOutsByBlock.map { (block, indices) ->
					val ends = endpoints[block].orEmpty()
					val (facingAway, misdirected) = endSemaphores(ends, block)
					val homeSignal = facingAway.singleOrNull()
					val name = BLOCK_PREFIX + indices.joinToString("/") { inOutLabels[it] }
					val blockInOuts = indices.map { inOuts[it] }
					StationBlock(idOf(ends), name, block, listOfNotNull(homeSignal), blockInOuts, misdirected)
				}

			val platformTracks =
				endpoints
					.mapNotNull { (block, ends) ->
						val signals = endSemaphores(ends, block).first
						val isStationTrack = ends.size == 2 && signals.size == 2
						if (isStationTrack) block to signals else null
					}.mapIndexed { index, (block, signals) ->
						val name = BLOCK_PREFIX + (index + 1)
						StationBlock(idOf(endpoints.getValue(block)), name, block, signals, emptyList())
					}

			val switches = nodes.mapNotNull { grid[it] as? DynamicRailSwitch }
			val signals = nodes.mapNotNull { grid[it] as? DynamicRailSemaphore }
			return Station(platformTracks, entryExitBlocks, switches, inOuts, signals).also { station ->
				logger.info { station.describe() }
			}
		}

		/**
		 * The signal-direction rule (see "Signal direction" in the class KDoc), the one place it is
		 * decided: [signal], whose node has [edgesAtSignal], faces away from [block] when the
		 * segment a train leaves [signal] through is not the one [block] is attached to.
		 */
		private fun facesAwayFrom(
			edgesAtSignal: Map<Cell.Segment, DynamicTrackBlock>,
			signal: DynamicRailSemaphore,
			block: DynamicTrackBlock
		): Boolean = edgesAtSignal[signal.direction()] !== block

		private fun idOf(ends: List<Point>): String = ends.joinToString("-") { "(${it.x},${it.y})" }
	}

	/** One English line naming every station block with its unique id and signals. */
	private fun describe(): String {
		val platforms =
			platformTracks.joinToString("; ") { track ->
				"${track.name} ${track.id} signals ${track.signals.joinToString(", ") { it.name.ifBlank { UNNAMED } }}"
			}
		val entries =
			entryExitBlocks.joinToString("; ") { entry ->
				val home = entry.signals.firstOrNull()?.let { "home signal ${it.name.ifBlank { UNNAMED }}" } ?: "no home signal"
				"${entry.name} ${entry.id} InOut ${entry.inOuts.joinToString("/") { it.name }} $home"
			}
		return "Station derived: capacity $topologyCapacity; ${platformTracks.size} platform track(s) [$platforms]; " +
			"${entryExitBlocks.size} entry/exit block(s) [$entries]; ${switches.size} switch(es)"
	}
}
