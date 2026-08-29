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

import cz.vutbr.fit.interlockSim.domain.COMMON_MAX_SPEED
import cz.vutbr.fit.interlockSim.domain.COMMON_TRACK_LENGTH
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.HashMapGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.github.oshai.kotlinlogging.KotlinLogging

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
 * **Parameterized BaseContext**: This class is parameterized with type parameter `T` that extends [TrackBlock].
 * The type parameter allows different contexts to use different track block representations:
 * - [DefaultEditingContext]: uses static `TrackBlock` (editing-time configuration)
 * - [DefaultSimulationContext]: uses `DynamicTrackBlock` (simulation-time state + configuration)
 *
 * **Grid remains non-parameterized**: The grid stores mixed Cell types: [cz.vutbr.fit.interlockSim.objects.cells.NodeCell]
 * subclasses (RailSwitch, RailSemaphore, InOut) and [cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart]
 * (intermediate track segments). Grid parameterization is handled separately via covariant return types.
 *
 * **Graph is now parameterized**: Following the same pattern as grid parameterization (Issue #131),
 * the graph now uses the type parameter `T` for type-safe access to track blocks.
 *
 * **Covariant Return Types**:
 * - [BaseContext.getRailWayNetGrid] returns `RailwayNetGrid<Cell>` (full mixed cell grid)
 * - [DefaultEditingContext] overrides it to return `RailwayNetGrid<NodeCell>` (editing operates on node cells)
 * - [DefaultSimulationContext] inherits the base `RailwayNetGrid<Cell>` implementation without override
 * - [BaseContext.getGraph] returns `ExtendedUnorientedGraph<Point, T, Segment>` (parameterized graph)
 *
 * This approach provides compile-time type safety for graph access while keeping the base
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
 * - [DefaultSimulationContext]: run, stop, toDynamic, reporting, navigation service access
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
 * 3. kDisco discrete event simulation framework operates in a single thread
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
 * @param T The track block type: [TrackBlock] for editing, [DynamicTrackBlock] for simulation
 * @see Context
 * @see DefaultEditingContext
 * @see DefaultSimulationContext
 */
abstract class BaseContext<T : TrackBlock>(
	cols: Int,
	rows: Int
) {
	/**
	 * Listeners for context change notifications.
	 * Copy-on-write list — @Volatile guarantees cross-thread visibility
	 * (kDisco simulation thread → EDT via SwingUtilities.invokeLater).
	 */
	@kotlin.concurrent.Volatile
	private var listeners: List<ContextPropertyChangeListener> = emptyList()

	/**
	 * Graph representing track blocks and their connections.
	 * Parameterized with type T to support both static (editing) and dynamic (simulation) track blocks.
	 */
	private val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, T, Segment> =
		HashMapGraph<Point, T, Segment>()

	/**
	 * Maps track blocks to their line cell keys for removal tracking.
	 * Used to efficiently remove intermediate TrackBlockPart cells when a TrackBlock is removed.
	 * Protected to allow subclasses to manage line cell removal.
	 */
	protected val linesKeys: MutableMap<T, Set<Point>> = HashMap()

	/**
	 * List of entry/exit points in the railway network.
	 * Protected to allow subclass access (DefaultSimulationContext needs this for getInOuts()).
	 */
	protected var inouts: MutableList<InOut> = mutableListOf()

	/**
	 * Current maximum speed for path elements.
	 * Inherited from EditingContext interface.
	 * Open to allow subclass override if needed.
	 * Fires PropertyChangeEvent when value changes.
	 */
	open var currentMaxSpeed: Double = COMMON_MAX_SPEED
		set(value) {
			val old = field
			field = value
			firePropertyChangeAlways("currentMaxSpeed", old, value)
		}

	/**
	 * Current track length for new track elements.
	 * Inherited from EditingContext interface.
	 * Open to allow subclass override if needed.
	 * Fires PropertyChangeEvent when value changes.
	 */
	open var currentTrackLength: Double = COMMON_TRACK_LENGTH
		set(value) {
			val old = field
			field = value
			firePropertyChangeAlways("currentTrackLength", old, value)
		}

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
	 * Returns parameterized graph based on context type:
	 * - [DefaultEditingContext]: `ExtendedUnorientedGraph<Point, TrackBlock, Segment>`
	 * - [DefaultSimulationContext]: `ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>`
	 *
	 * @return graph representing track block connectivity with type-safe access
	 */
	open fun getGraph(): ExtendedUnorientedGraph<Point, T, Segment> = extendedUnorientedGraph

	/**
	 * Add a listener for context changes.
	 * Replaces deprecated addObserver(Observer) method.
	 *
	 * @param listener the listener to add
	 */
	open fun addPropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners + listener
	}

	/**
	 * Remove a listener for context changes.
	 * Replaces deprecated deleteObserver(Observer) method.
	 *
	 * @param listener the listener to remove
	 */
	open fun removePropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners - listener
	}

	/**
	 * Get the listener list for subclasses that need to fire property changes.
	 *
	 * @return the list of registered listeners
	 */
	protected fun getListeners(): List<ContextPropertyChangeListener> = listeners

	/**
	 * Fire a property change event to all registered listeners.
	 * Protected to allow subclasses to notify listeners of changes.
	 *
	 * @param propertyName the name of the property that changed
	 * @param oldValue the old value of the property
	 * @param newValue the new value of the property
	 */
	protected fun firePropertyChange(
		propertyName: String,
		oldValue: Any?,
		newValue: Any?
	) {
		val snapshot = listeners
		snapshot.forEach { it.propertyChange(ContextChangeEvent(propertyName, oldValue, newValue)) }
	}

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
	 * Fires PropertyChangeEvent when value changes.
	 */
	open var currentNameString: String
		get() = nameString ?: ""
		set(value) {
			val old = nameString ?: ""
			nameString = value
			firePropertyChangeAlways("currentNameString", old, value)
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
			firePropertyChangeAlways("frozen", false, true)
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

	private fun firePropertyChangeAlways(
		propertyName: String,
		oldValue: Any?,
		newValue: Any?
	) {
		if (oldValue == newValue) {
			return
		}

		val snapshot = listeners
		snapshot.forEach { it.propertyChange(ContextChangeEvent(propertyName, oldValue, newValue)) }
	}
}
