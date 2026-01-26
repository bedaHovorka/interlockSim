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
		// Kotlin idiom: Use let for cleaner null-safe chaining
		current?.let {
			val block = requireNotNull(it.getTrackBlock()) { "TrackBlock cannot be null for current track section" }
			block.getNextTrackSection(separator, it)?.also { nextSection ->
				logger.trace { "getNextTrackSection: found next section within same block from $separator" }
				return nextSection
			}
		}

		// Step 2: At block boundary - navigate to next block via graph
		// Extract static NodeCell for graph operations (handles both static NodeCell and Dynamic* wrappers)
		val staticNodeCell = CellUtilities.assertNodeCell(separator)

		// Convert PathSeparator to NodeCell for getNextTrackBlock call
		// Kotlin idiom: Use as? for safe cast with elvis operator
		val nodeCell = (separator as? NodeCell) ?: staticNodeCell

		// Step 3: Query graph for next block and return first section
		val result = getNextTrackBlock(nodeCell, current?.getTrackBlock())?.getNextTrackSection(separator, null)
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
		val location = context.getRailWayNetGrid().getLocation(staticNodeCell) ?: return null
		val segment = getSegment(location, current)

		// Determine following segment based on NodeCell type (lines 529-540)
		// Kotlin idiom: Use when expression with smart casts
		val followingSegment =
			when (staticNodeCell) {
				is OrientedNodeCell -> staticNodeCell.getFollowingSegment(segment)
				else -> staticNodeCell.possibleFollowers(segment ?: return null).firstOrNull()
			} ?: return null

		// Query graph for edge assigned to following segment (lines 543-544)
		return context.getGraph().assignedEdges(location)[followingSegment]
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
	 * ## Dynamic Wrapper Handling
	 *
	 * This method supports both static PathSeparators and Dynamic wrappers.
	 * Equality comparison uses the static reference for consistent behavior across
	 * EditingContext (static) and SimulationContext (dynamic) environments.
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
			// Note: visited set uses PathSeparator's equals(), which handles Dynamic wrappers correctly
			if (separator in visited) {
				logger.trace { "findAllTopologicalPaths: skipping visited separator $separator" }
				continue
			}
			visited.add(separator)

			// Check if we reached the target
			// Note: PathSeparator.equals() handles comparison between static and dynamic instances
			if (isSameSeparator(separator, target)) {
				val path = buildPath(node)
				paths.add(path)
				logger.debug { "findAllTopologicalPaths: found path with ${path.size} sections" }
				continue
			}

			// Explore all possible next sections from this separator
			// For switches, this may return multiple branches
			val nextSections = getAllNextTrackSections(separator, node.section)
			for (nextSection in nextSections) {
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
		current ?: return null

		// Query graph for all edges assigned to this location
		// Note: assignedEdges signature is problematic - returns raw Java Map
		// Use explicit cast and Java Map methods
		@Suppress("UNCHECKED_CAST")
		val assignedEdges = context.getGraph().assignedEdges(location) as java.util.Map<Cell.Segment, TrackBlock>

		// Find the segment that connects to the current block using Java Map iteration
		for (segment in assignedEdges.keySet()) {
			if (assignedEdges.get(segment) == current) {
				return segment
			}
		}
		return null
	}

	/**
	 * Get all possible next track sections following a path separator.
	 *
	 * This method is similar to getNextTrackSection, but explores ALL possible branches
	 * at switches instead of just the first one. This is essential for findAllTopologicalPaths
	 * to discover all possible routes through the network.
	 *
	 * @param separator The separator to navigate from
	 * @param current The current track section (for within-block navigation), or null
	 * @return List of all possible next track sections (may be empty, or contain multiple for switches)
	 */
	private fun getAllNextTrackSections(
		separator: PathSeparator,
		current: TrackSection?
	): List<TrackSection> {
		// Step 1: Try within-block navigation first
		current?.let {
			val block = requireNotNull(it.getTrackBlock()) { "TrackBlock cannot be null for current track section" }
			block.getNextTrackSection(separator, it)?.let { nextSection ->
				// Found next section within same block
				return listOf(nextSection)
			}
		}

		// Step 2: At block boundary - explore ALL possible next blocks
		val staticNodeCell = CellUtilities.assertNodeCell(separator)
		val nodeCell = (separator as? NodeCell) ?: staticNodeCell

		// Get all possible next blocks
		val nextBlocks = getAllNextTrackBlocks(nodeCell, current?.getTrackBlock())

		// Map each block to its first section from this separator
		return nextBlocks.mapNotNull { block ->
			block.getNextTrackSection(separator, null)
		}
	}

	/**
	 * Get all possible next track blocks following a node cell.
	 *
	 * For oriented cells (InOut, Semaphore), returns single deterministic block.
	 * For switches (RailSwitch), returns ALL possible blocks for all branches.
	 *
	 * @param nodeCell The node cell to navigate from
	 * @param current The current track block (for determining direction), or null
	 * @return List of all possible next track blocks (may be empty, or contain multiple for switches)
	 */
	private fun getAllNextTrackBlocks(
		nodeCell: NodeCell,
		current: TrackBlock?
	): List<TrackBlock> {
		val staticNodeCell = CellUtilities.assertNodeCell(nodeCell)
		val location = context.getRailWayNetGrid().getLocation(staticNodeCell) ?: return emptyList()
		val segment = getSegment(location, current)

		// Determine ALL following segments based on NodeCell type
		val followingSegments: Set<Cell.Segment> =
			when (staticNodeCell) {
				is OrientedNodeCell -> {
					// Oriented cells have single deterministic direction
					val following = staticNodeCell.getFollowingSegment(segment)
					if (following != null) setOf(following) else emptySet()
				}
				else -> {
					// Non-oriented (switches) may have multiple branches
					staticNodeCell.possibleFollowers(segment ?: return emptyList())
				}
			}

		// Query graph for ALL edges assigned to following segments
		val assignedEdges = context.getGraph().assignedEdges(location)
		return followingSegments.mapNotNull { followingSegment ->
			assignedEdges[followingSegment]
		}
	}

	/**
	 * Check if two PathSeparators refer to the same network element.
	 *
	 * This method handles both static PathSeparators and Dynamic wrappers correctly.
	 * Since static objects don't know about Dynamic wrappers, we normalize both
	 * separators to their static references before comparison using identity (===).
	 *
	 * ## Why Identity Comparison?
	 *
	 * Dynamic wrappers use identity comparison (===) of static references in their equals()
	 * implementation. To ensure consistent behavior regardless of comparison order:
	 * - Extract static reference from both separators
	 * - Compare using identity (===) like Dynamic wrappers do
	 *
	 * This works for all combinations:
	 * - static === static (direct identity)
	 * - dynamic === dynamic (compares static references)
	 * - static === dynamic (compares static references)
	 * - dynamic === static (compares static references)
	 *
	 * @param sep1 First separator (may be static or dynamic)
	 * @param sep2 Second separator (may be static or dynamic)
	 * @return true if they represent the same network element
	 */
	private fun isSameSeparator(
		sep1: PathSeparator,
		sep2: PathSeparator
	): Boolean {
		// Extract static references for identity comparison
		val static1 = CellUtilities.assertNodeCell(sep1)
		val static2 = CellUtilities.assertNodeCell(sep2)
		return static1 === static2
	}

	/**
	 * Build path by following parent pointers from target node back to start.
	 *
	 * Kotlin idiom: Use generateSequence to traverse parent chain, then reverse for forward order.
	 *
	 * @param targetNode The final node in the path (where we reached the target separator)
	 * @return List of track sections in forward order (start to target)
	 */
	private fun buildPath(targetNode: PathNode): List<TrackSection> =
		generateSequence(targetNode) { it.parent }
			.mapNotNull { it.section }
			.toList()
			.reversed()

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
