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

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Pure topology navigation over railway network graph.
 *
 * ## Purpose
 *
 * Provides static graph traversal without any dependency on dynamic state (block reservations,
 * occupancy, etc.). This is Phase 1 of the Path Discovery Restructuring (Issue #292).
 *
 * ## Design Principles
 *
 * - **Zero state dependencies**: No calls to `getState()`, `reservedFrom`, or any dynamic state
 * - **Static topology only**: Works with immutable network structure
 * - **Editing context compatible**: Can run in EditingContext (no simulation required)
 * - **Pure graph traversal**: Uses only network topology and switch configuration
 *
 * ## Use Cases
 *
 * 1. **Network validation** - Check connectivity, find dead-ends, detect loops
 * 2. **Editor features** - Highlight possible paths, validate network design
 * 3. **Path planning** - Find all topologically possible routes between points
 * 4. **Foundation for reservation** - Phase 2 will build on this for reservation logic
 *
 * ## Separation of Concerns
 *
 * This interface is intentionally separated from:
 * - **Dynamic state** - Block reservations/occupancy (handled by PathReservationService in Phase 2)
 * - **Train navigation** - Following reserved paths (handled by TrainNavigationService in Phase 3)
 * - **Simulation execution** - Runtime state management (handled by SimulationContext)
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** All operations assume single-threaded access to the network graph.
 *
 * @see cz.vutbr.fit.interlockSim.context.Context
 * @see cz.vutbr.fit.interlockSim.context.EditingContext
 * @since Issue #293 (Phase 1 of Issue #292)
 */
interface TopologyNavigator {
	/**
	 * Get the next track section following a path separator, using pure topology traversal.
	 *
	 * This method navigates the railway network graph based only on static topology:
	 * - Current switch positions (static configuration)
	 * - Track block connectivity (graph structure)
	 * - Directional flow through separators
	 *
	 * **Critical: Zero state validation.** This method does NOT check:
	 * - Block reservation status (FREE/RESERVED/OCCUPIED)
	 * - Block ownership (reservedFrom)
	 * - Semaphore states (RED/GREEN)
	 *
	 * ## Navigation Logic
	 *
	 * 1. If `current != null`, try to find next section within the same block
	 * 2. If at block boundary, use graph to find next block via separator
	 * 3. Return first section in next block, or null if no continuation exists
	 *
	 * ## Parameters
	 *
	 * @param separator The path separator (switch, semaphore, InOut) to navigate from
	 * @param current The current track section (for determining direction), or null for initial entry
	 *
	 * ## Return Value
	 *
	 * - **Non-null**: Next track section found in topology
	 * - **Null**: Dead-end, no topological continuation exists
	 *
	 * ## Examples
	 *
	 * ```kotlin
	 * // Navigate from InOut through first block
	 * val firstSection = navigator.getNextTrackSection(inOut, null)
	 *
	 * // Continue through switch
	 * val nextSection = navigator.getNextTrackSection(switch, currentSection)
	 *
	 * // Dead-end at buffer stop
	 * val end = navigator.getNextTrackSection(bufferStop, lastSection) // returns null
	 * ```
	 *
	 * ## Comparison with SimulationContext.getNextTrackSection
	 *
	 * This method extracts lines 567-608 from DefaultSimulationContext.getNextTrackSection(),
	 * removing all block state validation (lines 614-632). The simulation method will eventually
	 * delegate to this interface and add state validation on top.
	 *
	 * @see findAllTopologicalPaths
	 */
	fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection?

	/**
	 * Get the next track block following a node cell, using pure topology traversal.
	 *
	 * This is the low-level graph traversal operation used by [getNextTrackSection].
	 * It queries the railway network graph to find the next block connected to a node cell.
	 *
	 * **Pure graph operation**: Uses only network topology, no state dependencies.
	 *
	 * ## Parameters
	 *
	 * @param nodeCell The node cell (switch, semaphore, InOut) to query
	 * @param current The current track block (for determining direction), or null
	 *
	 * ## Return Value
	 *
	 * - **Non-null**: Next track block found in graph
	 * - **Null**: No following block exists in topology
	 *
	 * ## Note
	 *
	 * This method corresponds to SimulationContext.getNextTrackBlock() but works with
	 * static TrackBlock references instead of DynamicTrackBlock wrappers.
	 */
	fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: TrackBlock?
	): TrackBlock?

	/**
	 * Find all topologically possible paths from start separator to target separator.
	 *
	 * Uses breadth-first search (BFS) to explore all possible routes through the network
	 * graph. Returns multiple paths if switches allow branching.
	 *
	 * **Pure topology**: Ignores all dynamic state (reservations, occupancy, semaphore states).
	 *
	 * ## Algorithm
	 *
	 * 1. BFS exploration from start separator
	 * 2. Follow all possible switch positions
	 * 3. Detect and handle loops (cycle detection)
	 * 4. Stop when target separator is reached
	 *
	 * ## Parameters
	 *
	 * @param start The starting path separator (typically InOut or switch)
	 * @param target The target path separator to reach
	 * @param maxDepth Maximum search depth to prevent infinite loops (default: 100)
	 *
	 * ## Return Value
	 *
	 * List of paths, where each path is a list of track sections in sequence.
	 * - **Empty list**: No topological path exists
	 * - **Single path**: Unique route exists
	 * - **Multiple paths**: Switches allow multiple routes
	 *
	 * ## Examples
	 *
	 * ```kotlin
	 * // Find all routes from entry to exit
	 * val paths = navigator.findAllTopologicalPaths(inOut1, inOut2)
	 * if (paths.isEmpty()) {
	 *     println("No route exists")
	 * } else {
	 *     println("Found ${paths.size} possible route(s)")
	 * }
	 * ```
	 *
	 * ## Use Cases
	 *
	 * - Network validation: Check if route exists between two points
	 * - Editor features: Show all possible routes for user selection
	 * - Foundation for Phase 2: PathReservationService will filter by availability
	 *
	 * @see getNextTrackSection
	 */
	fun findAllTopologicalPaths(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int = 100
	): List<List<TrackSection>>
}
