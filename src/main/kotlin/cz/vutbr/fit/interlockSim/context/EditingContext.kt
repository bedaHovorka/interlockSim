/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Interface to shared functions of inner data model, which is allowed by editing
 *
 * ## Type Parameters
 *
 * EditingContext specializes Context to use:
 * - [NodeCell] as the cell type
 * - [TrackBlock] as the track block type (static configuration)
 *
 * While editing operations via [putCell] only accept [NodeCell] and its
 * subtypes (InOut, RailSwitch, RailSemaphore), the grid also contains
 * [cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart] cells that are
 * automatically generated when joining nodes with track blocks.
 *
 * The grid returns [RailwayNetGrid]<[NodeCell]>, containing NodeCell subclasses.
 * TrackBlockPart instances are stored internally but not exposed through the
 * parameterized interface.
 *
 * The graph returns `ExtendedUnorientedGraph<Point, TrackBlock, Segment>` with
 * static track block configuration (no dynamic simulation state).
 *
 * ## Thread Safety
 *
 * **This interface is NOT thread-safe.** See [Context] for detailed thread safety
 * documentation and usage guidelines.
 *
 * All editing operations (putCell, removeCell, moveCell, joinCells, removeLine)
 * must be performed from a single thread.
 *
 * @see Context
 * @see javax.annotation.concurrent.NotThreadSafe
 */
interface EditingContext : Context<NodeCell, TrackBlock> {
	/**
	 * put the cell into context, the cell must be {@link NodeCell}
	 * @param key
	 * @param cell
	 */
	fun putCell(
		key: Point,
		cell: NodeCell
	)

	/**
	 * remove cell from context
	 * @param key
	 */
	fun removeCell(key: Point)

	/**
	 * @param from
	 * @param to
	 */
	fun moveCell(
		from: Point,
		to: Point
	)

	/**
	 * Remove {@link TrackBlock} from context
	 * @param block
	 */
	fun removeLine(block: TrackBlock)

	/**
	 * Current maximal speed, which is setting in elements
	 */
	var currentMaxSpeed: Double

	/**
	 * Current track length, which is setting in elements
	 */
	var currentTrackLength: Double

	/**
	 * Create relation between nodes. In specified places, must be {@link NodeCell}
	 * @param key1 location of first node
	 * @param key2 location of second node
	 * @param trackBlock block between nodes
	 */
	fun joinCells(
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	)

	/**
	 * Name which is setting in elements
	 */
	var currentNameString: String

	/**
	 * Get the list of InOut elements (entry/exit points) in this context.
	 *
	 * InOut elements represent entry and exit points in the railway network where
	 * trains enter and leave the system. This method provides access to these elements
	 * during network construction and transformation to simulation contexts.
	 *
	 * ## Implementation Notes
	 *
	 * Returns an immutable list to prevent external modification of the internal InOut
	 * collection. Changes to the InOut list should be made through proper editing
	 * operations (e.g., putCell).
	 *
	 * ## Usage
	 *
	 * - **Network Construction**: Access InOut elements during editing
	 * - **Transformation**: Copy InOut elements when converting to SimulationContext
	 * - **Validation**: Check network entry/exit points before simulation
	 *
	 * ## Design Note
	 *
	 * This method is distinct from `SimulationContext.getInOuts()` which returns
	 * `Collection<DynamicInOut>` (dynamic wrappers). EditingContext returns static
	 * InOut elements (`List<InOut>`), enabling type-safe transformation without
	 * runtime type checking.
	 *
	 * @return Immutable list of InOut elements (static entry/exit points)
	 */
	fun getInOuts(): List<cz.vutbr.fit.interlockSim.objects.cells.InOut>

	/**
	 * Freeze this context, making the network structure immutable.
	 *
	 * Once frozen, any attempt to modify the network structure (adding/removing cells,
	 * joining cells, modifying track blocks) will throw [UnsupportedOperationException].
	 *
	 * This operation is idempotent - calling freeze() multiple times has no effect.
	 * Once frozen, a context cannot be unfrozen.
	 *
	 * ## Usage
	 *
	 * Typical usage patterns:
	 * - **Explicit freeze**: Build network in editing context, then freeze before manual
	 *   conversion to simulation context
	 * - **Implicit freeze**: Conversion to [SimulationContext] automatically freezes
	 *   the context to enforce immutability
	 * - **Test scenarios**: Tests may freeze editing contexts to verify immutability
	 *   enforcement
	 *
	 * ## Design Rationale
	 *
	 * Exposing freeze() in EditingContext interface (not just BaseContext) provides:
	 * - **Clean API**: Users can freeze without casting to concrete types
	 * - **Type Safety**: Works with interface references, no runtime type checks
	 * - **Testability**: Tests can verify immutability without implementation knowledge
	 * - **Explicit Intent**: Makes immutability enforcement visible in the public API
	 *
	 * ## Example
	 *
	 * ```kotlin
	 * val context: EditingContext = factory.createEmptyContext()
	 * context.putCell(Point(1, 1), inA)
	 * context.putCell(Point(5, 5), outB)
	 *
	 * // Freeze before conversion (explicit)
	 * context.freeze()
	 * val simContext = factory.createContext(context)  // Already frozen
	 *
	 * // Or let factory freeze implicitly
	 * val simContext2 = factory.createContext(context)  // Freezes internally
	 * ```
	 *
	 * @see isFrozen
	 * @throws UnsupportedOperationException if subsequent modifications are attempted
	 */
	fun freeze()

	/**
	 * Check if this context is frozen (immutable network structure).
	 *
	 * Returns `true` if the context has been frozen via [freeze] or during conversion
	 * to [SimulationContext]. When frozen, all editing operations (putCell, removeCell,
	 * moveCell, joinCells, removeLine) will throw [UnsupportedOperationException].
	 *
	 * ## Frozen vs Unfrozen States
	 *
	 * **Unfrozen (false)**: Editing operations allowed
	 * - putCell(), removeCell(), moveCell() work normally
	 * - joinCells(), removeLine() work normally
	 * - Network structure can be modified
	 * - Typical state for EditingContext
	 *
	 * **Frozen (true)**: Editing operations prohibited
	 * - All editing operations throw UnsupportedOperationException
	 * - Network structure is immutable
	 * - Read operations (getRailWayNetGrid, getGraph) still work
	 * - Typical state for SimulationContext
	 *
	 * ## Usage
	 *
	 * Use this method to:
	 * - **Test preconditions**: Verify context state before operations
	 * - **Debug immutability**: Check why editing operations fail
	 * - **Conditional logic**: Branch based on mutability state (rare)
	 *
	 * ## Example
	 *
	 * ```kotlin
	 * val context: EditingContext = factory.createEmptyContext()
	 * println(context.isFrozen())  // false (editing allowed)
	 *
	 * context.freeze()
	 * println(context.isFrozen())  // true (editing prohibited)
	 *
	 * // Safe check before modification
	 * if (!context.isFrozen()) {
	 *     context.putCell(Point(1, 1), inA)
	 * }
	 * ```
	 *
	 * @return true if context is frozen and modifications are not allowed, false otherwise
	 * @see freeze
	 */
	fun isFrozen(): Boolean
}
