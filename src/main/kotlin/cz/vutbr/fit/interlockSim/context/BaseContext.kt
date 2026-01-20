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

import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.tracks.StaticTrack
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.HashMapGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.IdentityHashMap

/**
 * Abstract base class containing shared functionality between editing and simulation contexts.
 *
 * This class extracts common infrastructure from [DefaultEditingContext] and [DefaultSimulationContext],
 * providing:
 * - Grid management (RailwayNetGrid storage and access)
 * - Graph management (ExtendedUnorientedGraph storage and access)
 * - PropertyChangeSupport and listener management
 * - Configuration properties (currentMaxSpeed, currentTrackLength, currentNameString)
 * - Track block mappings and queries
 * - InOut (entry/exit points) management
 *
 * ## Design Rationale
 *
 * **Non-parameterized BaseContext**: This class is intentionally NOT parameterized with a type parameter.
 * The grid stores mixed Cell types: [cz.vutbr.fit.interlockSim.objects.cells.NodeCell] subclasses
 * (RailSwitch, RailSemaphore, InOut) and [cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart]
 * (intermediate track segments). Using a type parameter would require `@UnsafeVariance` annotations
 * throughout the codebase and create variance complexity without benefits.
 *
 * **Covariant Return Types**:
 * - [BaseContext.getRailWayNetGrid] returns `RailwayNetGrid<Cell>` (full mixed cell grid)
 * - [DefaultEditingContext] overrides it to return `RailwayNetGrid<NodeCell>` (editing operates on node cells)
 * - [DefaultSimulationContext] inherits the base `RailwayNetGrid<Cell>` implementation without override
 *
 * This approach provides compile-time type safety without variance complexity while keeping the base
 * context usable in both editing and simulation scenarios.
 *
 * ## Separation of Concerns
 *
 * **BaseContext** = Infrastructure (storage, access, configuration)
 * - Grid and graph storage
 * - Property change notification
 * - Configuration management
 * - Basic queries (grid access, graph access)
 *
 * **Subclasses** = Domain Logic (editing operations, simulation operations)
 * - [DefaultEditingContext]: putCell, removeCell, moveCell, joinCells, removeLine
 * - [DefaultSimulationContext]: run, stop, pathToNextSemaphore, toDynamic, reporting
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * BaseContext maintains mutable state including:
 * - Railway network grid (DefaultRailWayNetGrid)
 * - Graph structure (ExtendedUnorientedGraph)
 * - Property change listeners (PropertyChangeSupport)
 * - Track block mappings
 * - InOut list
 *
 * These data structures are not synchronized and will experience race conditions
 * if accessed concurrently from multiple threads.
 *
 * ### Design Decision
 *
 * Thread safety is intentionally NOT implemented because:
 * 1. Railway network operations are inherently sequential
 * 2. GUI editors operate in a single thread (Swing EDT)
 * 3. jDisco discrete event simulation framework operates in a single thread
 * 4. No current use cases require concurrent access
 * 5. Thread-safety would add complexity and performance overhead
 *
 * ### Usage Guidelines
 *
 * - **DO NOT** access BaseContext from multiple threads
 * - **DO NOT** share instances across thread boundaries
 * - **DO** use external synchronization if multi-threaded access is unavoidable
 * - **DO** keep all Context operations within the editing/simulation thread
 *
 * @see Context
 * @see DefaultEditingContext
 * @see DefaultSimulationContext
 * @see javax.annotation.concurrent.NotThreadSafe
 */
abstract class BaseContext(
	cols: Int,
	rows: Int
) {

	/**
	 * PropertyChangeSupport for notifying listeners of context changes.
	 * Replaces deprecated Observable pattern (Java 21 migration).
	 */
	private val changeSupport: PropertyChangeSupport = PropertyChangeSupport(this)

	/**
	 * Graph representing track blocks and their connections
	 */
	private val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> =
		HashMapGraph<Point, TrackBlock, Segment>()

	/**
	 * Maps track blocks to their line cell keys for removal tracking.
	 * Used to efficiently remove intermediate TrackBlockPart cells when a TrackBlock is removed.
	 */
	private val linesKeys: MutableMap<TrackBlock, Set<Point>> = IdentityHashMap<TrackBlock, Set<Point>>()

	/**
	 * List of entry/exit points in the railway network.
	 * Protected to allow subclass access (DefaultSimulationContext needs this for getInOuts()).
	 */
	protected var inouts: MutableList<InOut> = mutableListOf()

	/**
	 * Current maximum speed for path elements.
	 * Inherited from EditingContext interface.
	 * Open to allow subclass override if needed.
	 */
	open var currentMaxSpeed: Double = cz.vutbr.fit.interlockSim.objects.paths.PathElement.COMMON_MAX_SPEED

	/**
	 * Current track length for new track elements.
	 * Inherited from EditingContext interface.
	 * Open to allow subclass override if needed.
	 */
	open var currentTrackLength: Double = StaticTrack.COMMON_TRACK_LENGTH

	/**
	 * Railway network grid structure.
	 * Stores Cell type (both NodeCell and TrackBlockPart).
	 */
	private val railwayNetGrid: DefaultRailWayNetGrid = DefaultRailWayNetGrid(cols, rows)

	/**
	 * Name string for train generation (backing field for currentNameString).
	 * Private backing field - accessed via public property.
	 */
	private var nameString: String? = null

	/**
	 * Frozen state flag - when true, network structure is immutable.
	 * Used for simulation contexts to prevent runtime modifications after initialization.
	 * Default is false (mutable) for editing contexts.
	 */
	private var frozen: Boolean = false

	companion object {
		/**
		 * Logger for general class operations.
		 */
		private val logger = KotlinLogging.logger {}
	}

	init {
		logger.debug { "Initialized BaseContext with railway network grid: ${cols}x$rows cells" }
	}

	/**
	 * Get the railway network grid.
	 *
	 * **Base implementation returns `RailwayNetGrid<Cell>`** (most general type).
	 * Subclasses override with covariant return types:
	 * - [DefaultEditingContext]: returns `RailwayNetGrid<NodeCell>` (editing works with nodes only)
	 * - [DefaultSimulationContext]: inherits from DefaultEditingContext, uses its override
	 *
	 * @return railway network grid containing cells
	 */
	open fun getRailWayNetGrid(): RailwayNetGrid<Cell> = railwayNetGrid

	/**
	 * Protected accessor for the internal grid that stores Cell (not just NodeCell).
	 * This is needed by subclasses to access the raw grid without type casts.
	 *
	 * Used by:
	 * - DefaultSimulationContext to iterate over all cells including TrackBlockPart
	 * - DefaultEditingContext for internal operations
	 *
	 * @return the internal grid that can contain both NodeCell and TrackBlockPart
	 */
	protected fun getInternalGrid(): RailwayNetGrid<Cell> = railwayNetGrid

	/**
	 * Protected accessor for direct grid access (convenience method for subclasses).
	 * Provides untyped access to the grid for internal operations.
	 *
	 * @return the internal grid as DefaultRailWayNetGrid
	 */
	protected fun getGrid(): DefaultRailWayNetGrid = railwayNetGrid

	/**
	 * Get the extended unoriented graph of track blocks.
	 *
	 * @return graph representing track block connectivity
	 */
	open fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment> = extendedUnorientedGraph

	/**
	 * Add a listener for context changes.
	 * Replaces deprecated addObserver(Observer) method.
	 *
	 * **Thread Safety Note**: This method is synchronized to allow safe listener registration
	 * from multiple threads, even though the context itself is not thread-safe. This is a
	 * common pattern where listener management is synchronized but usage is not.
	 *
	 * @param listener the listener to add
	 */
	@Synchronized
	open fun addPropertyChangeListener(listener: PropertyChangeListener) {
		changeSupport.addPropertyChangeListener(listener)
	}

	/**
	 * Remove a listener for context changes.
	 * Replaces deprecated deleteObserver(Observer) method.
	 *
	 * **Thread Safety Note**: This method is synchronized to allow safe listener unregistration
	 * from multiple threads, even though the context itself is not thread-safe.
	 *
	 * @param listener the listener to remove
	 */
	@Synchronized
	open fun removePropertyChangeListener(listener: PropertyChangeListener) {
		changeSupport.removePropertyChangeListener(listener)
	}

	/**
	 * Get the PropertyChangeSupport instance for firing property changes.
	 * Protected to allow subclasses to fire property change events.
	 *
	 * @return the PropertyChangeSupport instance
	 */
	protected fun getChangeSupport(): PropertyChangeSupport = changeSupport

	/**
	 * Get the track block line keys mapping.
	 * Protected to allow subclasses to manage line cell removal.
	 *
	 * @return map of track blocks to their intermediate cell keys
	 */
	protected fun getLinesKeys(): MutableMap<TrackBlock, Set<Point>> = linesKeys

	/**
	 * Get the list of InOut elements (entry/exit points) in the railway network.
	 * Public method for accessing protected inouts field.
	 *
	 * @return list of InOut elements
	 */
	fun getInOutsList(): List<InOut> = inouts

	/**
	 * Current name string for train generation.
	 * Property with custom getter/setter for null handling.
	 * Open to allow subclass override (DefaultSimulationContext adds random generation).
	 */
	open var currentNameString: String
		get() = nameString ?: ""
		set(value) {
			nameString = value
		}

	/**
	 * Check if this context is frozen (immutable network structure).
	 *
	 * @return true if context is frozen and modifications are not allowed
	 */
	fun isFrozen(): Boolean = frozen

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
	 * Simulation contexts call freeze() after network initialization to enforce immutability.
	 * Editing contexts remain unfrozen to allow network modifications.
	 *
	 * @see isFrozen
	 * @see checkNotFrozen
	 */
	fun freeze() {
		if (!frozen) {
			frozen = true
			logger.info { "Context frozen - network structure is now immutable" }
		}
	}

	/**
	 * Protected helper for subclasses to check frozen state before modifications.
	 *
	 * Throws [UnsupportedOperationException] if context is frozen.
	 * Should be called at the start of any method that modifies network structure.
	 *
	 * @param operation Name of the operation being attempted (for error message)
	 * @throws UnsupportedOperationException if context is frozen
	 */
	protected fun checkNotFrozen(operation: String) {
		if (frozen) {
			throw UnsupportedOperationException(
				"Cannot $operation: context is frozen. " +
					"Network structure is immutable after simulation initialization. " +
					"Use EditingContext for network modifications."
			)
		}
	}
}
