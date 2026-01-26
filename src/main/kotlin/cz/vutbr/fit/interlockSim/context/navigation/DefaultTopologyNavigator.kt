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

import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.OrientedNodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of pure topology navigation.
 *
 * ## Architecture
 *
 * This implementation extracts the static graph traversal logic from
 * DefaultSimulationContext.getNextTrackSection() (lines 567-608), removing all
 * block state validation (lines 614-632).
 *
 * ## Design Principles
 *
 * - **Zero state dependencies**: No calls to DynamicTrackBlock.getState() or reservedFrom
 * - **Static topology only**: Works with immutable network structure from Context
 * - **Pure graph operations**: Uses Context.getGraph() and Context.getRailWayNetGrid()
 * - **No Dynamic wrappers**: Operates on static TrackBlock/NodeCell references only
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** Assumes single-threaded access to the Context.
 *
 * @property context The railway network context (EditingContext or SimulationContext)
 * @since Issue #293 (Phase 1 of Issue #292)
 */
class DefaultTopologyNavigator(
	private val context: Context<Cell, out TrackBlock>
) : TopologyNavigator {
	/**
	 * Get the next track section following a path separator, using pure topology traversal.
	 *
	 * This implementation corresponds to DefaultSimulationContext.getNextTrackSection()
	 * lines 567-608, but removes all dynamic state validation (lines 614-632).
	 *
	 * ## Navigation Logic (Extracted from DefaultSimulationContext)
	 *
	 * 1. If current != null, search within same block for next section
	 * 2. At block boundary, extract static NodeCell from separator
	 * 3. Query graph for next track block via getNextTrackBlock
	 * 4. Return first section in next block
	 *
	 * ## Critical Difference from Simulation Version
	 *
	 * **REMOVED (lines 614-632):**
	 * - Block state validation (FREE/RESERVED/OCCUPIED)
	 * - Reservation ownership checks (reservedFrom comparison)
	 * - Navigation blocking for unreserved blocks
	 *
	 * This method provides ONLY topological navigation. State-aware navigation
	 * will be handled by PathReservationService (Phase 2) and TrainNavigationService (Phase 3).
	 */
	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		// Step 1: Try to find next section within same block (if current != null)
		// Extracted from DefaultSimulationContext lines 571-582
		if (current != null) {
			val block = current.getTrackBlock()
			require(block != null) { "TrackBlock cannot be null for current track section" }

			val nextTrackSection = block.getNextTrackSection(separator, current)
			if (nextTrackSection != null) {
				logger.trace {
					"getNextTrackSection: found next section within same block from $separator"
				}
				return nextTrackSection
			}
		}

		// Step 2: At block boundary - navigate to next block via graph
		// Extracted from DefaultSimulationContext lines 586-595

		// Extract static NodeCell for graph operations
		// (Handles both static NodeCell and Dynamic* wrappers)
		val staticNodeCell = CellUtilities.assertNodeCell(separator)

		// Convert PathSeparator to NodeCell for getNextTrackBlock call
		// NodeCell is a subtype of PathSeparator, so static objects work directly
		val nodeCell =
			if (separator is NodeCell) {
				separator
			} else {
				staticNodeCell
			}

		// Query graph for next block
		val nextTrackBlock = getNextTrackBlock(nodeCell, current?.getTrackBlock())

		// Step 3: Return first section in next block (or null if no continuation)
		// Extracted from DefaultSimulationContext lines 634-639
		val result = nextTrackBlock?.getNextTrackSection(separator, null)
		logger.trace {
			"getNextTrackSection: navigating network from $separator, result: ${if (result != null) "found" else "not found"}"
		}
		return result
	}

	/**
	 * Get the next track block following a node cell, using pure topology traversal.
	 *
	 * This implementation corresponds to DefaultSimulationContext.getNextTrackBlock()
	 * (lines 520-545), but operates on static TrackBlock references instead of
	 * DynamicTrackBlock wrappers.
	 *
	 * ## Graph Query Logic (Extracted from DefaultSimulationContext)
	 *
	 * 1. Extract static NodeCell from input (handles Dynamic* wrappers)
	 * 2. Get location (Point) of the NodeCell in grid
	 * 3. Determine segment based on current block direction
	 * 4. Get following segment using NodeCell navigation methods
	 * 5. Query graph for edge assigned to following segment
	 *
	 * ## Critical Difference from Simulation Version
	 *
	 * - Returns static `TrackBlock` instead of `DynamicTrackBlock`
	 * - No dynamic wrapper lookups or state dependencies
	 */
	override fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: TrackBlock?
	): TrackBlock? {
		// Extract static NodeCell if it's a Dynamic wrapper
		// (Extracted from DefaultSimulationContext line 525)
		val staticNodeCell = CellUtilities.assertNodeCell(nodeCell)

		// Get location and segment (lines 526-527)
		val location = context.getRailWayNetGrid().getLocation(staticNodeCell)
			?: return null
		val segment = getSegment(location, current)

		// Determine following segment based on NodeCell type (lines 529-540)
		// OrientedNodeCell (InOut, Semaphore) has deterministic direction
		// Non-oriented (RailSwitch) uses possibleFollowers
		val followingSegment =
			when (staticNodeCell) {
				is OrientedNodeCell -> staticNodeCell.getFollowingSegment(segment)
				else -> {
					// Fall back to possibleFollowers for non-oriented NodeCells
					val followers = staticNodeCell.possibleFollowers(segment ?: return null)
					followers.firstOrNull()
				}
			}
		if (followingSegment == null) return null

		// Query graph for edge assigned to following segment (lines 543-544)
		val assignedEdges = context.getGraph().assignedEdges(location)
		return assignedEdges[followingSegment]
	}

	/**
	 * Find all topologically possible paths from start separator to target separator.
	 *
	 * Uses breadth-first search (BFS) to explore all possible routes through the network
	 * graph. Handles loops via cycle detection (visited set).
	 *
	 * ## Algorithm
	 *
	 * 1. Initialize BFS queue with start separator
	 * 2. For each separator, explore all possible next sections
	 * 3. Track visited separators to detect cycles
	 * 4. Build path by following parent pointers
	 * 5. Stop when target separator is reached
	 *
	 * ## Cycle Detection
	 *
	 * Railway networks can contain loops (e.g., around-the-block routing).
	 * The algorithm prevents infinite loops by tracking visited separators.
	 *
	 * ## Performance Characteristics
	 *
	 * - **Time complexity**: O(V + E) where V = number of path separators, E = track connections
	 * - **Space complexity**: O(V) for visited set and BFS queue
	 * - **Best case**: O(1) when start == target
	 * - **Worst case**: O(V + E) when exploring entire network before finding target
	 *
	 * @param start The starting path separator
	 * @param target The target path separator to reach
	 * @param maxDepth Maximum search depth to prevent runaway exploration
	 * @return List of paths (each path is a list of track sections)
	 */
	override fun findAllTopologicalPaths(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int
	): List<List<TrackSection>> {
		val paths = mutableListOf<List<TrackSection>>()
		val queue = ArrayDeque<PathNode>()
		val visited = mutableSetOf<PathSeparator>()

		// Initialize BFS with start separator
		queue.add(PathNode(start, null, null))

		while (queue.isNotEmpty()) {
			val node = queue.removeFirst()
			val separator = node.separator

			// Check depth limit
			if (node.depth >= maxDepth) {
				logger.debug { "findAllTopologicalPaths: reached max depth $maxDepth at $separator" }
				continue
			}

			// Skip if already visited (cycle detection)
			if (separator in visited) {
				logger.trace { "findAllTopologicalPaths: skipping visited separator $separator" }
				continue
			}
			visited.add(separator)

			// Check if we reached the target
			if (separator == target) {
				val path = buildPath(node)
				paths.add(path)
				logger.debug { "findAllTopologicalPaths: found path with ${path.size} sections" }
				continue
			}

			// Explore next section from this separator
			val nextSection = getNextTrackSection(separator, node.section)
			if (nextSection != null) {
				// Get the separator at the end of this section
				val nextSeparator = nextSection.getSecondEnd(separator)
				queue.add(PathNode(nextSeparator, nextSection, node))
			}
		}

		logger.info { "findAllTopologicalPaths: found ${paths.size} path(s) from $start to $target" }
		return paths
	}

	/**
	 * Helper method to get segment for a location based on current block direction.
	 *
	 * Extracted from DefaultSimulationContext to support getNextTrackBlock.
	 * This method determines which segment of the NodeCell to use based on the
	 * direction we're coming from (current block).
	 *
	 * @param location The location (Point) of the NodeCell in the grid
	 * @param current The current track block (for determining direction), or null
	 * @return The segment to use for navigation, or null if none found
	 */
	private fun getSegment(
		location: cz.vutbr.fit.interlockSim.util.Point,
		current: TrackBlock?
	): Cell.Segment? {
		if (current == null) return null

		// Query graph for all edges assigned to this location
		val assignedEdges: java.util.Map<Cell.Segment, out TrackBlock> = context.getGraph().assignedEdges(location)

		// Find the segment that connects to the current block
		return assignedEdges.entries.find { it.value == current }?.key
	}

	/**
	 * Build path by following parent pointers from target node back to start.
	 *
	 * @param targetNode The final node in the path (where we reached the target separator)
	 * @return List of track sections in forward order (start to target)
	 */
	private fun buildPath(targetNode: PathNode): List<TrackSection> {
		val sections = mutableListOf<TrackSection>()
		var node: PathNode? = targetNode

		// Traverse parent pointers to build path
		while (node != null) {
			val section = node.section
			if (section != null) {
				sections.add(0, section) // Prepend to maintain forward order
			}
			node = node.parent
		}

		return sections
	}

	/**
	 * Internal node for BFS path exploration.
	 *
	 * @property separator The path separator at this node
	 * @property section The track section that led to this node (null for start)
	 * @property parent The parent node (null for start)
	 */
	private data class PathNode(
		val separator: PathSeparator,
		val section: TrackSection?,
		val parent: PathNode?
	) {
		val depth: Int = if (parent == null) 0 else parent.depth + 1
	}
}
