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

import cz.hovorka.kdisco.DiscoException
import cz.hovorka.kdisco.Process
import cz.hovorka.kdisco.Random
import cz.hovorka.kdisco.Simulation
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.OrientedNodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.CollisionServices
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.collision.PauseController
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.util.platformIdentityCode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

/**
 * Default implementation of {@link SimulationContext} that extends {@link BaseContext} with [DynamicTrackBlock].
 *
 * Provides simulation-specific operations without editing capabilities:
 * - Running discrete event simulations using kDisco framework
 * - Managing simulation processes (main process, InOut workers)
 * - Path finding for train navigation (pathToNextSemaphore)
 * - Simulation reporting and event logging
 * - Train name generation
 * - Dynamic wrapper management (PathSeparator and TrackBlock wrappers)
 *
 * This class extends `BaseContext<DynamicTrackBlock>`, using dynamic track block wrappers
 * that separate static configuration from runtime simulation state. The graph stores
 * [DynamicTrackBlock] instances for type-safe, single-step access to dynamic state.
 * Simulation contexts are immutable - network structure cannot be modified during simulation.
 * It uses {@link SimulationProcessFactory} to create simulation processes,
 * decoupling from concrete simulation class implementations.
 *
 * ## Architecture
 *
 * **BaseContext<DynamicTrackBlock>** provides:
 * - Grid and graph storage (immutable during simulation, graph stores DynamicTrackBlock)
 * - Property change notification
 * - Configuration management (maxSpeed, trackLength, nameString)
 * - InOut list management
 *
 * **DefaultSimulationContext** adds:
 * - Simulation execution (run, stop, errorStop)
 * - Dynamic wrapper mappings (PathSeparator wrappers, backward-compatible TrackFacility access)
 * - Path operations (pathToNextSemaphore, navigation methods)
 * - Simulation reporting and logging
 * - Process and worker management
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * In addition to inherited thread-safety concerns from BaseContext,
 * DefaultSimulationContext maintains additional mutable state:
 * - Simulation process references (mainProcess, workers)
 * - Report type configuration (allowedReportTypes)
 * - Random number generator (for train naming)
 * - Dynamic wrapper mappings (staticToDynamicMap, staticTrackToDynamicMap)
 *
 * The kDisco discrete event simulation framework operates in a single thread,
 * ensuring sequential execution of all simulation events.
 *
 * ### Usage
 *
 * - Access DefaultSimulationContext only from the simulation thread
 * - All simulation events execute sequentially via kDisco
 * - Do not share instances across thread boundaries
 * - Do NOT call editing methods (putCell, removeCell, etc.) - use EditingContext for that
 *
 * @see SimulationContext
 * @see BaseContext
 * @see EditingContext
 * @see SimulationProcessFactory
 *
 * **Note:** Transitional implementation. Will be superseded in Phase 2 (Kalasim migration).
 */
open class DefaultSimulationContext(
	cols: Int,
	rows: Int,
	/**
	 * Factory for creating simulation processes.
	 * Decouples context from concrete simulation class implementations.
	 */
	private val processFactory: SimulationProcessFactory
) : BaseContext<DynamicTrackBlock>(cols, rows),
	SimulationContext,
	PauseController {
	/**
	 * Koin scope for this simulation context.
	 * Manages lifecycle of navigation services and ensures one shared PathReservationRegistry
	 * per context. The context itself is passed as the scope source, allowing services to access it via getSource().
	 * Scope is closed when context is destroyed via close().
	 *
	 * @see navigationModule
	 * @see close
	 */
	override val scope =
		org.koin.mp.KoinPlatformTools
			.defaultContext()
			.get()
			.createScope(
				scopeId = platformIdentityCode(this),
				qualifier =
					org.koin.core.qualifier
						.named<DefaultSimulationContext>(),
				source = this
			)

	/**
	 * Set of allowed report types for simulation output
	 */
	private val allowedReportTypes: MutableSet<ReportType> = mutableSetOf()

	/**
	 * Workers for each entry/exit point
	 */
	private var workers: MutableMap<DynamicInOut, InOutWorker> = HashMap()

	/**
	 * Cache of dynamic InOut wrappers (lazily created)
	 */
	private var dynamicInOuts: MutableList<DynamicInOut>? = null

	/**
	 * Mapping from static PathSeparator to Dynamic wrapper (for simulation context)
	 * Maps InOut, RailSemaphore, RailSwitch to their Dynamic counterparts
	 */
	private val staticToDynamicMap: MutableMap<PathSeparator, DynamicPathSeparator> = HashMap()

	/**
	 * Mapping from static TrackFacility to DynamicTrack wrapper (for simulation context)
	 * Maps track facilities to their Dynamic wrappers for state management
	 */
	private val staticTrackToDynamicMap: MutableMap<TrackFacility, DynamicTrack> = HashMap()

	/**
	 * Cache of PathSeparator grid positions for O(1) animation rendering.
	 * Populated once during fromEditingContext() transformation.
	 * Valid for context lifetime (grid is immutable after freeze).
	 *
	 * Used by TrainPositionCalculator to avoid O(n²) grid scans at 30 FPS.
	 */
	private lateinit var separatorPositionCache: Map<PathSeparator, Point>

	/**
	 * Main simulation process
	 */
	private var mainProcess: LoopProcess? = null

	/**
	 * kdisco-engine Simulation instance; set in [run], used in [stop] to signal exit.
	 */
	private var simulation: Simulation? = null

	/** Block-event listeners registered before run(); wired into kdisco at run() time. */
	private val pendingBlockEventListeners: MutableList<(BlockEvent) -> Unit> = mutableListOf()

	/** Raw kdisco event listeners registered before run(); wired into kdisco at run() time. */
	private val pendingSimEventListeners: MutableList<(cz.hovorka.kdisco.SimulationEvent) -> Unit> = mutableListOf()

	/** Spatial-conflict event listeners registered before run(); wired into kdisco at run() time. */
	private val pendingConflictEventListeners: MutableList<(ConflictDetectedEvent) -> Unit> = mutableListOf()

	/**
	 * True once run() has been invoked and the simulation has started.
	 * Guards onBlockEvent/onSimulationEvent: listeners registered after this point are silently ignored.
	 * Distinct from isFrozen() because fromEditingContext() freezes the context before run() is called.
	 */
	private var simulationHasStarted: Boolean = false

	/**
	 * Active [SimulationController] for the current run; used by [requestPause] to delegate
	 * pause requests from the collision detection service.
	 * Set at the start of [run] and cleared when run completes.
	 *
	 * Marked `@Volatile` because it is written on the control/caller thread at the start of
	 * [run] and cleared on completion, but read from the simulation thread inside
	 * [requestPause] (the [PauseController] contract permits call from the simulation thread).
	 */
	@kotlin.concurrent.Volatile
	private var currentController: SimulationController? = null

	/** Collision warning listeners registered before run(); wired into the service at run() time. */
	private val pendingCollisionWarningListeners: MutableList<(CollisionWarning) -> Unit> = mutableListOf()

	/**
	 * Collision detection service scoped to this context.
	 * Retrieved from this context's Koin scope. SP1 ships a thin backbone; SP2/SP3/SP4
	 * will add the block-event subscriptions and detection rules.
	 */
	private val collisionDetectionServiceInstance: CollisionDetectionService by lazy {
		scope.get<CollisionDetectionService>()
	}

	/**
	 * Grouped collision-detection services facade for this simulation context.
	 * Delegates to the buffered warning-subscription path and the scoped
	 * [CollisionDetectionService], keeping them behind a single [CollisionServices]
	 * accessor instead of flattening them onto [SimulationEnvironment].
	 */
	private val collisionServicesInstance: CollisionServices by lazy {
		object : CollisionServices {
			override fun getCollisionDetectionService(): CollisionDetectionService = collisionDetectionServiceInstance

			override fun onCollisionWarning(listener: (CollisionWarning) -> Unit) = registerCollisionWarningListener(listener)

			override fun registerHaltCallback(
				trainId: String,
				callback: () -> Unit
			) {
				collisionDetectionServiceInstance.registerHaltCallback(trainId, callback)
			}
		}
	}

	/**
	 * Random number generator for name generation (kDisco)
	 */
	private val random: Random = Random(0L)

	/**
	 * Topology navigator for pure topology navigation (no state dependencies).
	 * Lazy-initialized on first access to ensure Context (this) is fully constructed.
	 * Retrieved from this context's scope.
	 */
	private val topologyNavigatorInstance: cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator by lazy {
		scope.get<cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator>()
	}

	/**
	 * Train navigation service for train-specific path following.
	 * Lazy-initialized on first access to ensure SimulationEnvironment (this) is fully constructed.
	 * Retrieved from this context's scope, ensuring shared PathReservationRegistry with other services.
	 */
	private val trainNavigationServiceInstance: TrainNavigationService by lazy {
		scope.get<TrainNavigationService>()
	}

	/**
	 * Path reservation service for atomic path reservation and ownership tracking.
	 * Lazy-initialized on first access to ensure SimulationEnvironment (this) is fully constructed.
	 * Retrieved from this context's scope, ensuring shared PathReservationRegistry and TopologyNavigator.
	 */
	private val pathReservationServiceInstance: PathReservationService by lazy {
		scope.get<PathReservationService>()
	}

	/**
	 * Automatic path finding service for static Dijkstra-based route search.
	 * Lazy-initialized; scoped to this simulation context.
	 */
	private val automaticPathFindingServiceInstance: AutomaticPathFindingService by lazy {
		scope.get<AutomaticPathFindingService>()
	}

	/**
	 * Route finder for automatic route planning between InOut elements.
	 * Lazy-initialized; scoped to this simulation context.
	 */
	private val routeFinderInstance: cz.vutbr.fit.interlockSim.context.RouteFinder by lazy {
		scope.get<cz.vutbr.fit.interlockSim.context.RouteFinder>()
	}

	/**
	 * Grouped routing/navigation services facade for this simulation context.
	 * Delegates to the scoped service instances, keeping them behind a single
	 * [RoutingServices] accessor instead of flattening them onto [SimulationEnvironment].
	 */
	private val routingServicesInstance: RoutingServices by lazy {
		object : RoutingServices {
			override fun getTopologyNavigator(): cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator =
				topologyNavigatorInstance

			override fun getPathReservationService(): PathReservationService = pathReservationServiceInstance

			override fun getTrainNavigationService(): TrainNavigationService = trainNavigationServiceInstance
		}
	}

	// ========================================
	// Context Interface Implementation
	// ========================================
	// DefaultSimulationContext implements SimulationContext which extends Context<Cell>.
	// The grid internally stores all Cell types (NodeCell subclasses + TrackBlockPart).
	// Simulation contexts are immutable - network structure cannot be modified during simulation.
	// Editing operations are NOT supported and should only be accessed through EditingContext.

	// ========================================
	// Simulation-Specific Implementation
	// ========================================

	companion object {
		/**
		 * Logger for general simulation context operations.
		 */
		private val logger = KotlinLogging.logger {}

		/**
		 * Separate logger for simulation events to allow independent level control.
		 * Configured in logback.xml as "cz.vutbr.fit.interlockSim.simulation".
		 */
		private val simulationLogger = KotlinLogging.logger("cz.vutbr.fit.interlockSim.simulation")

		/**
		 * Factory method to create SimulationContext from EditingContext.
		 *
		 * Uses GridTransformer to convert static grid to dynamic grid.
		 * This method creates a new simulation context with dynamic wrapper mappings
		 * for PathSeparators (InOut, RailSemaphore, RailSwitch).
		 *
		 * The transformation process is broken down into 6 distinct phases:
		 * 1. Transform static grid to dynamic grid using GridTransformer
		 * 2. Populate simulation grid with DYNAMIC cells (not static cells)
		 * 3. Store static→dynamic mapping cache for toDynamic() lookups
		 * 4. Copy graph structure (track block connections)
		 * 5. Copy InOut elements list using DYNAMIC wrappers
		 * 6. Copy configuration properties
		 *
		 * **Fix for Issue #280 (sub-issue #284):**
		 * Previously, copyGridCells() copied STATIC cells to simulation grid, causing
		 * identity mismatches between grid navigation (returns static cells) and
		 * pathToNextSemaphore() (returns dynamic wrappers in path). This caused trains
		 * to deadlock with zero acceleration.
		 *
		 * Now, we use GridTransformer.dynamicGrid directly to populate the simulation
		 * grid with DYNAMIC wrappers, ensuring consistent identity throughout navigation.
		 *
		 * @param editingContext The editing context with static network configuration
		 * @param processFactory Factory for creating simulation processes
		 * @return New simulation context with transformed grid
		 */
		fun fromEditingContext(
			editingContext: EditingContext,
			processFactory: SimulationProcessFactory
		): DefaultSimulationContext {
			// Create base simulation context
			val grid = editingContext.getRailWayNetGrid()
			val cols = grid.cols
			val rows = grid.rows

			val context = DefaultSimulationContext(cols, rows, processFactory)

			// PHASE 1: Transform grid (creates dynamic wrappers)
			@Suppress("UNCHECKED_CAST")
			val cellGrid = grid as RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.core.Cell>
			val transformationResult = GridTransformer.transformGrid(cellGrid)

			// PHASE 2: Populate simulation grid with DYNAMIC cells (FIX for #280/#284)
			// Previously: copyGridCells() copied STATIC cells from editing grid
			// Now: Use GridTransformer.dynamicGrid for NodeCell wrappers + copy TrackBlockPart from editing grid

			// 2a. Copy all cells from editing grid (both NodeCell and TrackBlockPart)
			// TrackBlockPart cells are not transformed, so we need to copy them from the original grid
			for ((point, cell) in cellGrid) {
				context.getGrid().put(point, cell)
			}
			logger.debug { "Copied ${cellGrid.count()} cells from editing grid (includes TrackBlockPart)" }

			// 2b. Overwrite NodeCell positions with DYNAMIC wrappers from GridTransformer
			// This replaces static NodeCells with dynamic wrappers while preserving TrackBlockPart
			for ((point, dynamicCell) in transformationResult.dynamicGrid) {
				context.getGrid().put(point, dynamicCell)
			}
			logger.debug { "Overwrote ${transformationResult.dynamicGrid.count()} positions with DYNAMIC NodeCell wrappers" }

			// PHASE 3: Store static→dynamic mapping cache for toDynamic() lookups
			context.staticToDynamicMap.putAll(transformationResult.staticToDynamicMap)
			logger.debug { "Stored ${transformationResult.staticToDynamicMap.size} static→dynamic mappings" }

			// DIAGNOSTIC: Log semaphore mappings from GridTransformer
			transformationResult.staticToDynamicMap.entries
				.filter { it.key is InOut }
				.forEach { (inout, dynamicInOut) ->
					if (inout is InOut && dynamicInOut is DynamicInOut) {
						logger.debug {
							"GridTransformer mapped InOut ${inout.getName()}: " +
								"inSem@${platformIdentityCode(inout.getInSemaphore())} -> " +
								"${platformIdentityCode(dynamicInOut.inSemaphore)}, " +
								"outSem@${platformIdentityCode(inout.getOutSemaphore())} -> " +
								"${platformIdentityCode(dynamicInOut.outSemaphore)}"
						}
					}
				}

			// PHASE 4: Copy graph structure (existing logic)
			copyGraphStructure(editingContext, context)

			// PHASE 5: Copy InOut list (static InOut instances)
			// Note: We keep static InOut instances in the inouts list for backward compatibility.
			// The simulation code uses context.getInOuts() to get the list, then looks up
			// dynamic wrappers via staticToDynamicMap when needed.
			// This preserves the existing architecture where inouts list contains static objects.
			context.inouts.addAll(editingContext.getInOuts())
			logger.debug { "Copied ${editingContext.getInOuts().size} static InOut elements to inouts list" }

			// PHASE 6: Copy configuration (existing logic)
			copyConfiguration(editingContext, context)

			// PHASE 7: Initialize separator position cache for animation rendering
			initializeSeparatorPositionCache(context)

			// Validate transformation
			validateTransformation(editingContext, context)

			// Freeze the context to prevent modifications after creation
			// Simulation context has immutable network structure
			context.freeze()

			return context
		}

		/**
		 * Copy graph structure (track block connections) from editing to simulation context,
		 * wrapping static TrackBlock instances in DynamicTrackBlock wrappers.
		 *
		 * This is the core of Issue #277: Instead of copying static TrackBlock objects directly,
		 * we create DynamicTrackBlock wrappers for type-safe access to dynamic simulation state.
		 *
		 * @param editingContext Source editing context (graph contains static TrackBlock)
		 * @param simulationContext Target simulation context (graph will contain DynamicTrackBlock)
		 */
		private fun copyGraphStructure(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			// Source graph: ExtendedUnorientedGraph<Point, TrackBlock, Segment>
			val sourceGraph = editingContext.getGraph()
			// Target graph: ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
			val targetGraph = simulationContext.getGraph()

			var wrappedCount = 0

			for (entry in sourceGraph.entrySet()) {
				// Each entry has a Doubleton<Point, Segment> key and TrackBlock value
				val doubleton = entry.key
				val staticTrackBlock = entry.value

				// Extract the two nodes from the doubleton
				val iterator = doubleton.iterator()
				val first = iterator.next()
				val second = iterator.next()

				// Get the segment extensions for each node
				val firstExt =
					requireSimulationNotNull(doubleton.getValue(first)) {
						"Inconsistent graph entry: missing segment for first point $first in Doubleton key $doubleton"
					}
				val secondExt =
					requireSimulationNotNull(doubleton.getValue(second)) {
						"Inconsistent graph entry: missing segment for second point $second in Doubleton key $doubleton"
					}

				// ===== KEY CHANGE FOR ISSUE #277 =====
				// Wrap static TrackBlock in DynamicTrackBlock wrapper
				val end1: DynamicPathSeparator = simulationContext.getRailWayNetGrid()[first] as DynamicPathSeparator
				val end2: DynamicPathSeparator = simulationContext.getRailWayNetGrid()[second] as DynamicPathSeparator
				val dynamicTrackBlock = DynamicTrackBlock(staticTrackBlock, end1, end2)

				// Put dynamic wrapper into the simulation graph (type-safe)
				targetGraph.put(first, firstExt, second, secondExt, dynamicTrackBlock)
				wrappedCount++

				logger.trace {
					"Wrapped TrackBlock ${platformIdentityCode(staticTrackBlock)} -> " +
						"DynamicTrackBlock ${platformIdentityCode(dynamicTrackBlock)}"
				}
			}

			logger.debug {
				"Copied ${sourceGraph.size()} graph entries, created $wrappedCount DynamicTrackBlock wrappers"
			}
		}

		/**
		 * Copy configuration properties from editing to simulation context.
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 */
		private fun copyConfiguration(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			simulationContext.currentMaxSpeed = editingContext.currentMaxSpeed
			simulationContext.currentTrackLength = editingContext.currentTrackLength
			simulationContext.currentNameString = editingContext.currentNameString

			logger.debug {
				"Copied configuration: speed=${editingContext.currentMaxSpeed}, " +
					"length=${editingContext.currentTrackLength}, " +
					"name=${editingContext.currentNameString}"
			}
		}

		/**
		 * Initialize PathSeparator position cache for animation rendering.
		 *
		 * Performs single O(n²) grid scan to build map from PathSeparator to grid Point.
		 * This cache enables O(1) position lookups in TrainPositionCalculator, avoiding
		 * repeated grid scans at 30 FPS rendering rate.
		 *
		 * After grid transformation, the grid contains Dynamic wrappers (DynamicInOut,
		 * DynamicRailSemaphore, DynamicRailSwitch). We map their static references to
		 * grid positions for consistent lookup by TrainPositionCalculator.
		 *
		 * Called once during fromEditingContext() after grid transformation.
		 *
		 * @param simulationContext Target simulation context
		 */
		private fun initializeSeparatorPositionCache(simulationContext: DefaultSimulationContext) {
			val grid = simulationContext.getRailWayNetGrid()
			val cache = mutableMapOf<PathSeparator, Point>()

			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell = grid.getCellAt(x, y)
					if (cell is PathSeparator) {
						// Cell is PathSeparator - add directly
						cache[cell] = Point(x, y)
					} else if (cell is DynamicPathSeparator) {
						// Cell is DynamicPathSeparator - add static reference
						val staticRef =
							when (cell) {
								is DynamicInOut -> cell.staticRef
								is DynamicRailSemaphore -> cell.staticRef
								is DynamicRailSwitch -> cell.staticRef
								else -> null
							}
						if (staticRef != null) {
							cache[staticRef] = Point(x, y)
						}
					}
				}
			}

			simulationContext.separatorPositionCache = cache.toMap() // Immutable
			logger.info { "Initialized PathSeparator position cache: ${cache.size} separators" }
		}

		/**
		 * Validate that all InOut elements have corresponding dynamic wrappers.
		 * This ensures GridTransformer correctly created wrappers for all InOuts.
		 *
		 * **Fix for Issue #280/#284:**
		 * The inouts list contains static InOut instances (for backward compatibility),
		 * but each must have a corresponding DynamicInOut wrapper in staticToDynamicMap.
		 * This validation ensures the transformation created all required mappings.
		 *
		 * @param simulationContext Target simulation context
		 * @throws IllegalStateException if any InOut is missing from staticToDynamicMap
		 */
		private fun validateInOutMappings(simulationContext: DefaultSimulationContext) {
			for (inout in simulationContext.inouts) {
				val dynamicWrapper = simulationContext.staticToDynamicMap[inout]
				require(dynamicWrapper != null) {
					"InOut $inout (${inout.getName()}) not found in staticToDynamicMap after GridTransformer. " +
						"This indicates InOut is in inouts list but GridTransformer did not create a wrapper for it."
				}
				require(dynamicWrapper is DynamicInOut) {
					"Wrapper for InOut $inout (${inout.getName()}) is not a DynamicInOut. " +
						"Type: ${dynamicWrapper::class.simpleName ?: "unknown"}. " +
						"This indicates GridTransformer created wrong wrapper type."
				}
			}
			logger.debug {
				"Validated ${simulationContext.inouts.size} InOut elements have DynamicInOut wrappers"
			}
		}

		/**
		 * Validate transformation completeness and correctness.
		 *
		 * Verifies:
		 * - All NodeCells have corresponding dynamic wrappers
		 * - Graph structure preserved (same size, connectivity)
		 * - InOut list copied correctly
		 * - Configuration properties copied
		 * - Grid dimensions match
		 * - No orphaned static references
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 * @throws ContextCreationException if validation fails
		 */
		private fun validateTransformation(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			val errors = mutableListOf<String>()

			// 1. Validate grid dimensions
			val sourceGrid = editingContext.getRailWayNetGrid()
			val targetGrid = simulationContext.getGrid()
			if (sourceGrid.cols != targetGrid.cols ||
				sourceGrid.rows != targetGrid.rows
			) {
				errors.add(
					"Grid dimensions mismatch: source ${sourceGrid.cols}x${sourceGrid.rows}, " +
						"target ${targetGrid.cols}x${targetGrid.rows}"
				)
			}

			// 2. Validate all NodeCells have wrappers
			val nodeCells = mutableListOf<NodeCell>()

			@Suppress("UNCHECKED_CAST")
			val cellGrid = sourceGrid as RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.core.Cell>
			for ((_, cell) in cellGrid) {
				if (cell is NodeCell) {
					nodeCells.add(cell)
				}
			}

			val unmappedCells = nodeCells.filter { it !in simulationContext.staticToDynamicMap }
			if (unmappedCells.isNotEmpty()) {
				errors.add(
					"Missing dynamic wrappers for ${unmappedCells.size} NodeCells: " +
						unmappedCells.take(5).joinToString { "${it::class.simpleName} at ${it.getSpatialType()}" }
				)
			}

			// 3. Validate graph size preserved
			val sourceGraphSize = editingContext.getGraph().size()
			val targetGraphSize = simulationContext.getGraph().size()
			if (sourceGraphSize != targetGraphSize) {
				errors.add(
					"Graph size mismatch: source $sourceGraphSize entries, target $targetGraphSize entries"
				)
			}

			// 4. Validate InOut list copied
			val sourceInOuts = editingContext.getInOuts()
			val targetInOuts = simulationContext.getInOuts()
			if (sourceInOuts.size != targetInOuts.size) {
				errors.add(
					"InOut list size mismatch: source ${sourceInOuts.size}, target ${targetInOuts.size}"
				)
			}

			// 5. Validate configuration properties
			if (editingContext.currentMaxSpeed != simulationContext.currentMaxSpeed) {
				errors.add(
					"Max speed mismatch: source ${editingContext.currentMaxSpeed}, " +
						"target ${simulationContext.currentMaxSpeed}"
				)
			}
			if (editingContext.currentTrackLength != simulationContext.currentTrackLength) {
				errors.add(
					"Track length mismatch: source ${editingContext.currentTrackLength}, " +
						"target ${simulationContext.currentTrackLength}"
				)
			}
			// Note: currentNameString validation is skipped when source is empty because
			// DefaultSimulationContext.currentNameString getter auto-generates a random name
			// when empty (see line 1400). Empty source names are expected and valid.
			if (editingContext.currentNameString.isNotEmpty() &&
				editingContext.currentNameString != simulationContext.currentNameString
			) {
				errors.add(
					"Name string mismatch: source '${editingContext.currentNameString}', " +
						"target '${simulationContext.currentNameString}'"
				)
			}

			// Validate InOut wrapper mappings (existing check - keep for backward compatibility)
			try {
				validateInOutMappings(simulationContext)
			} catch (e: IllegalArgumentException) {
				errors.add("InOut wrapper validation failed: ${e.message}")
			}

			// Throw exception if any errors found
			if (errors.isNotEmpty()) {
				throw ContextCreationException(
					"Context transformation validation failed with ${errors.size} error(s):\n" +
						errors.joinToString("\n") { "  - $it" }
				)
			}

			// Log success with statistics
			logger.info {
				"Context transformation validated successfully: " +
					"${nodeCells.size} cells (${simulationContext.staticToDynamicMap.size} wrappers), " +
					"$targetGraphSize graph entries, " +
					"${targetInOuts.size} InOuts, " +
					"grid ${targetGrid.cols}x${targetGrid.rows}"
			}
		}
	}

	/**
	 * Get segment for a path separator and tracks
	 */
	override fun getSegment(
		separator: DynamicPathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment? {
		// If track is not null, use it; otherwise use secondEndTrack
		if (track != null) return getSegment(separator, track)
		requireSimulation(secondEndTrack != null) { "secondEndTrack cannot be null for separator $separator" }
		requireSimulation(separator is OrientedPathSeparator) {
			"PathSeparator must be OrientedPathSeparator, got ${separator::class.simpleName ?: "unknown"}"
		}
		val segment = getSegment(separator, secondEndTrack)
		// Match Java 1:1: return null when segment doesn't exist
		return separator.getFollowingSegment(segment)
	}

	/**
	 * Get segment for a path separator and track
	 */
	override fun getSegment(
		separator: DynamicPathSeparator,
		track: Track
	): Segment? =
		if (track is TrackSection) {
			@Suppress("UNCHECKED_CAST")
			val section = track as TrackSection
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(separator, section) ?: throw IllegalStateException("getSegment returned null for TrackSection")
		} else {
			val nodeCell: NodeCell = CellUtilities.assertNodeCell(separator)
			val trackBlock: DynamicTrackBlock = Util.assertInstanceOf<DynamicTrackBlock>(track)
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(nodeCell, trackBlock)
		}

	/**
	 * Get pseudo join segment in block for a path separator and track section
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		section: TrackSection
	): Segment? {
		val block = section.getTrackBlock()
		if (block.isInnerElement(separator)) {
			return block.getJoin(separator, section)
		}
		val nodeCell: NodeCell = CellUtilities.assertNodeCell(separator)
		// Look up DynamicTrackBlock wrapper for the static block from TrackSection
		val dynamicTrackBlock = block as? DynamicTrackBlock ?: getDynamicWrapper(block)
		return getSegment(nodeCell, dynamicTrackBlock)
	}

	/**
	 * Get segment at a node cell for a track block
	 */
	private fun getSegment(
		node: NodeCell,
		current: DynamicTrackBlock?
	): Segment? {
		val location = getLocation(node)
		return getSegment(location, current)
	}

	/**
	 * Get segment at a location for a dynamic track block
	 */
	private fun getSegment(
		location: Point,
		current: DynamicTrackBlock?
	): Segment? {
		if (current != null) {
			requireSimulation(getGraph().get(location).contains(current)) {
				"Current track block $current not found in graph at location $location"
			}
		}
		return if (current == null) null else getGraph().extensionalObject(location, current)
	}

	/**
	 * Get location of a node cell in the railway network
	 */
	private fun getLocation(node: NodeCell): Point {
		val location = getRailWayNetGrid().getLocation(node)
		requireSimulation(location != null) { "Location not found for nodeCell $node in grid" }
		return location
	}

	/**
	 * Get the next track block after the current one from a node
	 */
	override fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: DynamicTrackBlock?
	): DynamicTrackBlock? {
		// Extract static NodeCell if it's a Dynamic wrapper (for location/graph operations)
		val staticNodeCell = CellUtilities.assertNodeCell(nodeCell)
		val location = getLocation(staticNodeCell)
		val segment = getSegment(location, current)

		// For getFollowingSegment, we need DynamicPathSeparator or OrientedNodeCell
		// Dynamic* wrappers always have getFollowingSegment, static may not (only OrientedNodeCell does)
		val followingSegment =
			when {
				nodeCell is DynamicPathSeparator -> nodeCell.getFollowingSegment(segment)
				staticNodeCell is OrientedNodeCell -> staticNodeCell.getFollowingSegment(segment)
				else -> {
					// Fall back to possibleFollowers for non-oriented NodeCells (like RailSwitch)
					val followers = staticNodeCell.possibleFollowers(segment ?: return null)
					followers.firstOrNull()
				}
			}
		if (followingSegment == null) return null

		val assignedEdges = getGraph().assignedEdges(location)
		return assignedEdges[followingSegment]
	}

	/**
	 * Helper method to look up DynamicTrackBlock wrapper for a static TrackBlock.
	 * TrackSection objects belong to the static structure, so calling getTrackBlock()
	 * on them returns static TrackBlock objects. This method looks up the corresponding
	 * DynamicTrackBlock wrapper from the simulation graph.
	 */
	private fun getDynamicWrapper(staticBlock: TrackBlock): DynamicTrackBlock? {
		// Search through all graph edges to find the dynamic wrapper for this static block
		for (entry in getGraph().entrySet()) {
			val dynamicBlock = entry.value
			if (dynamicBlock.staticRef === staticBlock) {
				return dynamicBlock
			}
		}
		return null // No wrapper found
	}

	/**
	 * Initialize static-to-dynamic mapping for all PathSeparators in the network
	 * Must be called before simulation starts to ensure all separators have Dynamic wrappers
	 *
	 * Tests that call pathToNextSemaphore() without running full simulation must call this first
	 */
	internal fun initializeDynamicMapping() {
		// Track what we're mapping to avoid duplicates
		var mappedCount = 0
		// Use internal grid to access all cells (including TrackBlockPart), not just NodeCells
		val grid = getInternalGrid()

		// Iterate through all cells in the railway network grid
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid.getCellAt(x, y) ?: continue

				// Skip TrackBlockPart - these are not NodeCells and don't need dynamic wrappers
				if (cell !is NodeCell) {
					logger.trace { "Skipping ${cell::class.simpleName ?: "unknown"} at ($x,$y) - not a NodeCell" }
					continue
				}

				// Skip if already mapped (handles case where getInOuts was called early)
				if (cell in staticToDynamicMap) {
					logger.trace { "Skipping ${cell::class.simpleName ?: "unknown"} at ($x,$y) - already mapped" }
					continue
				}

				// Create Dynamic wrapper based on cell type
				when (cell) {
					is InOut -> {
						// Create and map InOut dynamic wrapper
						val dynamic = createDynamic(cell)
						staticToDynamicMap[cell] = dynamic
						// CRITICAL: Map InOut's semaphores to their Dynamic wrappers
						// These semaphores might be used in paths before they're encountered as separate cells
						// We use putIfAbsent to avoid overwriting if the semaphore was already mapped
						if (!staticToDynamicMap.containsKey(cell.getInSemaphore())) {
							staticToDynamicMap[cell.getInSemaphore()] = dynamic.inSemaphore
						}
						if (!staticToDynamicMap.containsKey(cell.getOutSemaphore())) {
							staticToDynamicMap[cell.getOutSemaphore()] = dynamic.outSemaphore
						}
						// Also add to dynamicInOuts list if it doesn't exist yet
						if (dynamicInOuts == null) {
							dynamicInOuts = mutableListOf()
						}
						if (dynamic !in dynamicInOuts!!) {
							dynamicInOuts!!.add(dynamic)
						}
						mappedCount++
						logger.trace { "Mapped InOut at ($x,$y) to dynamic wrapper (with semaphores)" }
					}
					is RailSemaphore -> {
						val dynamic = createDynamicInstance(cell)
						staticToDynamicMap[cell] = dynamic
						mappedCount++
						logger.trace { "Mapped RailSemaphore at ($x,$y) to dynamic wrapper" }
					}
					is RailSwitch -> {
						val dynamic = DynamicRailSwitch(cell)
						staticToDynamicMap[cell] = dynamic
						mappedCount++
						logger.trace { "Mapped RailSwitch at ($x,$y) to dynamic wrapper" }
					}
				}
			}
		}
		logger.debug { "Initialized $mappedCount dynamic wrappers (total in map: ${staticToDynamicMap.size})" }

		// Now iterate through all edges (TrackBlocks) in the graph and create DynamicTrack wrappers
		var trackMappedCount = 0
		val graph = getGraph()
		for (trackBlock in graph.values()) {
			// TrackBlock extends TrackFacility, but graph stores DynamicTrackBlock wrappers
			val dynamicBlock = trackBlock as DynamicTrackBlock
			val staticTrack = dynamicBlock.staticRef as TrackFacility

			if (!staticTrackToDynamicMap.containsKey(staticTrack)) {
				val dynamicTrack = DynamicTrack(staticTrack)
				staticTrackToDynamicMap[staticTrack] = dynamicTrack
				trackMappedCount++
				logger.trace { "Mapped TrackBlock ${staticTrack.hashCode()} to dynamic wrapper" }
			}

			// Ensure lookups by DynamicTrackBlock (graph values) still work by aliasing to the same wrapper
			if (!staticTrackToDynamicMap.containsKey(dynamicBlock)) {
				staticTrackToDynamicMap[dynamicBlock] = staticTrackToDynamicMap[staticTrack]
					?: error("Expected DynamicTrack for $staticTrack to be registered in the map")
			}

			// Recursively map any internal TrackSection objects
			mapInternalSections(dynamicBlock)
		}
		logger.debug {
			"Initialized $trackMappedCount dynamic track wrappers (total in map: ${staticTrackToDynamicMap.size})"
		}
	}

	/**
	 * Recursively discover and map all TrackSection objects within a TrackBlock.
	 *
	 * For SimpleTrackBlock (which is its own TrackSection), this is a no-op.
	 * For future CompoundTrackBlock implementations with internal sections, this ensures
	 * all sections get DynamicTrack wrappers during initialization.
	 *
	 * This prevents "Wrong state: FREE, expected: RESERVED" errors when trains
	 * try to enter internal sections that weren't mapped at initialization.
	 *
	 * @param trackBlock The TrackBlock to scan for internal sections
	 */
	private fun mapInternalSections(trackBlock: TrackBlock) {
		// Scan from both ends of the TrackBlock to discover all internal sections
		val ends = trackBlock.ends()
		val visited = mutableSetOf<TrackSection>()

		for (end in ends) {
			// Start navigation from this end (null current means "start of block")
			var currentSection: TrackSection? = trackBlock.getNextTrackSection(end, null)

			while (currentSection != null && currentSection !in visited) {
				visited.add(currentSection)

				// Skip if section is the TrackBlock itself (SimpleTrackBlock case)
				// SimpleTrackBlock implements both TrackBlock and TrackSection interfaces
				if (currentSection === trackBlock) {
					logger.trace { "Section is TrackBlock itself, skipping (SimpleTrackBlock pattern)" }
					break
				}

				// Check if this is a TrackFacility that needs mapping
				if (currentSection is TrackFacility) {
					val section = currentSection // capture for smart-cast in lambdas
					if (!staticTrackToDynamicMap.containsKey(section)) {
						// Create and map DynamicTrack wrapper for internal section
						val dynamicSection = DynamicTrack(section)
						staticTrackToDynamicMap[section] = dynamicSection
						logger.debug {
							"Mapped internal TrackSection ${platformIdentityCode(section)} " +
								"within TrackBlock ${platformIdentityCode(trackBlock)}"
						}
					} else {
						logger.trace {
							"Internal section ${platformIdentityCode(section)} already mapped"
						}
					}
				}

				// Move to next section in the sequence
				// getSecondEnd returns the opposite end of the current section
				val nextSeparator = currentSection.getSecondEnd(end)
				currentSection = trackBlock.getNextTrackSection(nextSeparator, currentSection)
			}
		}

		if (visited.isNotEmpty() && visited.size > 1) {
			logger.info {
				"TrackBlock ${platformIdentityCode(trackBlock)} contains ${visited.size} sections"
			}
		}
	}

	/**
	 * Validate that all PathSeparators and TrackFacilities in the network have Dynamic wrappers.
	 *
	 * Based on architectural assumption: simulation context has immutable network structure.
	 * All separators and track facilities must be wrapped at initialization - discovering an
	 * unwrapped element during simulation indicates a bug.
	 */
	private fun collectUnmappedSeparators(unmappedSeparators: MutableList<String>) {
		val grid = getInternalGrid()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid.getCellAt(x, y) ?: continue
				when {
					cell !is PathSeparator -> continue
					cell is DynamicPathSeparator -> {
						val staticRef =
							when (cell) {
								is DynamicInOut -> cell.staticRef
								is DynamicRailSemaphore -> cell.staticRef
								is DynamicRailSwitch -> cell.staticRef
								else -> throw IllegalStateException("Unknown DynamicPathSeparator type: ${cell::class.simpleName ?: "unknown"}")
							}
						if (staticRef !in staticToDynamicMap) {
							unmappedSeparators.add("${cell::class.simpleName ?: "unknown"} at ($x,$y) - staticRef not mapped")
						}
					}
					else -> {
						if (cell !in staticToDynamicMap) {
							unmappedSeparators.add("${cell::class.simpleName ?: "unknown"} at ($x,$y)")
						}
					}
				}
			}
		}
	}

	private fun collectUnmappedTracks(unmappedTracks: MutableList<String>) {
		val graph = getGraph()
		for (trackBlock in graph.values()) {
			val trackFacility = trackBlock as TrackFacility
			if (trackFacility !in staticTrackToDynamicMap) {
				unmappedTracks.add("TrackBlock ${platformIdentityCode(trackBlock)}")
			}
			val ends = trackBlock.ends()
			for (end in ends) {
				var currentSection: TrackSection? = trackBlock.getNextTrackSection(end, null)
				val visited = mutableSetOf<TrackSection>()
				while (currentSection != null && currentSection !in visited) {
					visited.add(currentSection)
					if (currentSection === trackBlock) break
					if (currentSection is TrackFacility && currentSection !in staticTrackToDynamicMap) {
						unmappedTracks.add(
							"TrackSection ${platformIdentityCode(currentSection)} " +
								"in TrackBlock ${platformIdentityCode(trackBlock)}"
						)
					}
					val nextSeparator = currentSection.getSecondEnd(end)
					currentSection = trackBlock.getNextTrackSection(nextSeparator, currentSection)
				}
			}
		}
	}

	private fun validateDynamicMapping() {
		val unmappedSeparators = mutableListOf<String>()
		val unmappedTracks = mutableListOf<String>()

		collectUnmappedSeparators(unmappedSeparators)
		collectUnmappedTracks(unmappedTracks)

		if (unmappedSeparators.isNotEmpty() || unmappedTracks.isNotEmpty()) {
			val message =
				buildString {
					append("Dynamic mapping incomplete!\n")
					if (unmappedSeparators.isNotEmpty()) {
						append("Unmapped separators: ${unmappedSeparators.joinToString(", ")}\n")
					}
					if (unmappedTracks.isNotEmpty()) {
						append("Unmapped tracks: ${unmappedTracks.joinToString(", ")}\n")
					}
					append("Separator map: ${staticToDynamicMap.size} entries, ")
					append("Track map: ${staticTrackToDynamicMap.size} entries.")
				}
			throw IllegalStateException(message)
		}

		logger.info {
			"Dynamic mapping validation passed: ${staticToDynamicMap.size} separators, " +
				"${staticTrackToDynamicMap.size} tracks mapped"
		}
	}

	/**
	 * Convert a static PathSeparator to its Dynamic wrapper.
	 *
	 * Uses staticToDynamicMap for lookups. The grid contains static cells (NodeCell),
	 * and this method provides dynamic wrappers for simulation state management.
	 * Used in pathToNextSemaphore to ensure paths contain only dynamic references.
	 *
	 * @param separator The separator to convert (static or already Dynamic)
	 * @return The Dynamic wrapper (either found in map or the input if already dynamic)
	 * @throws IllegalStateException if the separator is static and not found
	 */
	override fun toDynamic(separator: PathSeparator): DynamicPathSeparator {
		// If already dynamic, return as-is (idempotent operation)
		if (separator is DynamicPathSeparator) {
			logger.trace { "toDynamic: separator already dynamic, returning as-is: ${separator::class.simpleName ?: "unknown"}" }
			return separator
		}

		// Use static-to-dynamic map for conversions (SINGLETON PATTERN - same wrapper instance every time)
		val dynamic =
			staticToDynamicMap[separator]
				?: throw IllegalStateException(
					"Dynamic wrapper not found for separator: $separator (${separator::class.simpleName ?: "unknown"}). " +
						"Map contains ${staticToDynamicMap.size} entries. " +
						"This indicates the separator was not registered during initialization. " +
						"Ensure initializeDynamicMapping() completed successfully before simulation starts."
				)

		// Verify singleton behavior: same static object always returns same wrapper instance
		logger.trace {
			"toDynamic: converted static ${separator::class.simpleName ?: "unknown"} " +
				"(identity: ${platformIdentityCode(separator)}) to " +
				"${dynamic::class.simpleName ?: "unknown"} (identity: ${platformIdentityCode(dynamic)})"
		}

		return dynamic
	}

	/**
	 * Convert a TrackFacility to its DynamicTrack wrapper.
	 * All tracks are wrapped eagerly during initialization (via initializeDynamicMapping).
	 * If a track has no wrapper, this indicates an initialization error.
	 * Uses identity-based mapping to ensure each static track maps to exactly one wrapper.
	 */
	override fun toDynamic(track: TrackFacility): DynamicTrack {
		staticTrackToDynamicMap[track]?.let { return it }

		val staticKey = (track as? DynamicTrackBlock)?.staticRef as? TrackFacility
		if (staticKey != null) {
			staticTrackToDynamicMap[staticKey]?.let { return it }
		}

		throw IllegalStateException(
			"Dynamic wrapper not found for track: ${platformIdentityCode(track)} " +
				"(${track::class.simpleName ?: "unknown"}). " +
				"Map contains ${staticTrackToDynamicMap.size} entries. " +
				"This indicates the track was not registered during initialization. " +
				"Ensure initializeDynamicMapping() completed successfully before simulation starts."
		)
	}

	override fun onBlockEvent(listener: (BlockEvent) -> Unit) {
		if (simulationHasStarted) return
		pendingBlockEventListeners += listener
	}

	override fun onSimulationEvent(listener: (cz.hovorka.kdisco.SimulationEvent) -> Unit) {
		if (simulationHasStarted) return
		pendingSimEventListeners += listener
	}

	override fun onConflictDetectedEvent(listener: (ConflictDetectedEvent) -> Unit) {
		if (simulationHasStarted) return
		pendingConflictEventListeners += listener
	}

	/**
	 * Buffer a collision-warning listener registered before [run]; listeners registered
	 * after [run] has started are silently ignored (same contract as [onBlockEvent]).
	 * Wired into the [CollisionDetectionService] at [run] time.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	private fun registerCollisionWarningListener(listener: (CollisionWarning) -> Unit) {
		if (simulationHasStarted) return
		pendingCollisionWarningListeners += listener
	}

	/**
	 * Collision-detection services facade for this context. Exposes the scoped
	 * [CollisionDetectionService] and the buffered [CollisionServices.onCollisionWarning]
	 * subscription path behind a single accessor.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	override fun getCollisionServices(): CollisionServices = collisionServicesInstance

	/**
	 * Request an immediate pause via the active [SimulationController].
	 * Delegates to the controller stored at the start of [run]; safe to call from the simulation thread.
	 * Does nothing if called before [run] or after simulation finishes.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	override fun requestPause() {
		currentController?.requestPause()
	}

	override fun run(controller: SimulationController) {
		// Store the active controller so requestPause() can delegate to it.
		currentController = controller

		// Force initialization of the collision detection service now, regardless of whether any
		// onCollisionWarning listeners were registered. DefaultCollisionDetectionService subscribes
		// to env.onBlockEvent/env.onSimulationEvent in its init{} block, and detection must be active
		// even in headless/CLI runs that never register a warning listener.
		val collisionService = collisionDetectionServiceInstance
		pendingCollisionWarningListeners.forEach { collisionService.onCollisionWarning(it) }

		// Mark simulation as started — listeners registered after this point are silently ignored.
		// Must be set before any simulation logic so that late-registering callers are correctly rejected.
		simulationHasStarted = true

		val gridEmpty = !getRailWayNetGrid().iterator().hasNext()
		if (getGraph().isEmpty() || gridEmpty || inouts.isEmpty()) {
			logger.warn {
				"Cannot start simulation: graph=${if (getGraph().isEmpty()) "empty" else "ok"}, " +
					"grid=${if (gridEmpty) "empty" else "ok"}, " +
					"inouts=${if (inouts.isEmpty()) "empty" else "ok"}"
			}
			throw EmptyContextException()
		}

		// Initialize ALL dynamic wrappers before starting simulation
		// Based on assumption: immutable network structure in simulation context
		// NOTE: getInOuts() might have been called already (e.g., by XMLContextFactory),
		// so initializeDynamicMapping handles both fresh init and completion of partial init
		initializeDynamicMapping() // Maps ALL separators (InOut, RailSemaphore, RailSwitch)

		// Validate completeness - catch initialization bugs early
		validateDynamicMapping()

		logger.info {
			"Simulation initialization complete: ${staticToDynamicMap.size} dynamic wrappers created"
		}

		// Freeze the context to prevent runtime modifications after initialization
		// Based on architectural decision: simulation context has immutable network structure
		freeze()

		// Use factory to create main process if not already set
		if (mainProcess == null) {
			mainProcess = processFactory.createMainProcess(this)
		}

		logger.info {
			"Starting simulation: ${inouts.size} InOut points, ${getGraph().size()} track blocks, " +
				"main process=${requireNotNull(mainProcess) { "mainProcess must be initialized" }::class.simpleName ?: "unknown"}"
		}

		// Use factory to create worker for each InOut
		// Reuse dynamic wrappers from getInOuts() (already initialized above)
		for (dynamicInOut in getInOuts()) {
			workers[dynamicInOut] = processFactory.createInOutWorker(this, dynamicInOut)
		}

		val sim =
			Simulation.create {
				Process.activate(requireNotNull(mainProcess) { "mainProcess must be initialized before activation" })
			}
		simulation = sim
		// Wire pre-registered listeners into kdisco simulation
		pendingSimEventListeners.forEach { sim.onEvent(it) }
		wireCustomEventListeners(sim, pendingBlockEventListeners)
		wireCustomEventListeners(sim, pendingConflictEventListeners)
		try {
			var stepState = StepControlState(prevTime = 0.0)
			runBlocking {
				sim.run(Double.MAX_VALUE) {
					stepState = advanceControlledStep(sim, controller, stepState)
				}
			}
		} catch (e: DiscoException) {
			logger.error(e) { "Simulation run failed" }
			throw SimulationException(e)
		} finally {
			flushUnresolvedReservationConflicts(sim.time())
			simulation = null // Release reference once sim.run() returns (natural end or stop() called)
			currentController = null
		}
	}

	/**
	 * Surface any reservation contention that was still unresolved when the run ended
	 * (Issue #612 SP2 follow-up). Delivered directly to the buffered block-event
	 * listeners rather than via `emitCustom()`: the top-level `emitCustom` is a no-op
	 * once `Process.activeContext` is null, which is exactly the state here -- the run
	 * has already fully stopped, so there is nothing left to pause.
	 *
	 * Extracted out of [run] purely to keep that method's cyclomatic complexity within
	 * budget; carries no logic of its own beyond the guard + delivery.
	 */
	private fun flushUnresolvedReservationConflicts(simulationEndTime: Double) {
		runCatching { pathReservationServiceInstance.flushUnresolvedConflicts(simulationEndTime) }
			.onSuccess { events -> events.forEach { event -> pendingBlockEventListeners.forEach { it(event) } } }
			.onFailure { e -> logger.warn(e) { "flushUnresolvedConflicts failed during run() cleanup" } }
	}

	/**
	 * Wire a buffered listener list for one `SimulationEvent.Custom` payload type into [sim],
	 * filtering by payload type at delivery time. No-op if [listeners] is empty.
	 *
	 * Extracted out of [run] to eliminate the block-event/conflict-event wiring duplication
	 * introduced in PR #580 (Goal 9 SP1) and to keep [run]'s cyclomatic complexity within
	 * budget -- same rationale as [flushUnresolvedReservationConflicts].
	 */
	private inline fun <reified T : Any> wireCustomEventListeners(
		sim: Simulation,
		listeners: List<(T) -> Unit>
	) {
		if (listeners.isEmpty()) return
		val snapshot = listeners.toList()
		sim.onEvent { event ->
			if (event is cz.hovorka.kdisco.SimulationEvent.Custom) {
				val payload = event.payload
				if (payload is T) {
					snapshot.forEach { it(payload) }
				}
			}
		}
	}

	/** Timing state threaded across ticks of [run]'s controlled event loop. */
	private data class StepControlState(
		val prevTime: Double,
		val stepTimeTarget: Double? = null
	)

	/**
	 * Advance the controlled simulation loop by one tick: resets the step-time guard once
	 * reached, throttles wall-clock time relative to simulation time advanced, then applies
	 * pause/step controls unless a step-time window is still active.
	 *
	 * Extracted out of [run] purely to keep that method's cyclomatic complexity within
	 * budget (same rationale as [flushUnresolvedReservationConflicts]); no logic change
	 * relative to the inline callback it replaces.
	 */
	private suspend fun advanceControlledStep(
		sim: Simulation,
		controller: SimulationController,
		state: StepControlState
	): StepControlState {
		val t = sim.time()
		// Reset step-time guard when target is reached (clock caught up)
		var stepTimeTarget = state.stepTimeTarget
		if (stepTimeTarget != null && t >= stepTimeTarget) stepTimeTarget = null
		// Throttle wall-clock relative to simulation time advanced
		controller.throttle(t - state.prevTime)
		// If we are NOT mid-step-time run: apply pause/step control
		if (stepTimeTarget == null) {
			if (controller.isPaused()) logger.debug { "Simulation paused at t=$t" }
			controller.awaitIfPaused()
			// Consume a step-event request. Return value intentionally not acted on:
			// per SimulationController contract, isPaused() remains true after a
			// step-event is consumed, so the next iteration re-enters awaitIfPaused().
			controller.pollStepEvent()
			// Consume a step-time request; if present, allow events to run until target
			controller.pollStepTime()?.let { dt -> stepTimeTarget = t + dt }
		}
		return StepControlState(prevTime = t, stepTimeTarget = stepTimeTarget)
	}

	private fun createDynamic(i: InOut): DynamicInOut {
		val inSemaphore = createDynamicInstance(i.getInSemaphore())
		val outSemaphore = createConstantInstance(i.getOutSemaphore(), Signal.FREE)
		return DynamicInOut(i, inSemaphore, outSemaphore)
	}

	/**
	 * Stop the simulation
	 *
	 * Terminates all simulation processes (workers and main process) and cleans up resources.
	 * Does not exit the JVM, allowing the simulation to be stopped and restarted.
	 *
	 * Exception safety: Ensures all workers and main process are terminated even if some
	 * terminations fail. Collects all exceptions and throws them after cleanup is complete.
	 */
	override fun stop() {
		simulation?.stop() // Signal kdisco-engine event loop to exit
		requireSimulationNotNull(mainProcess) { "Main process must be initialized before stopping simulation" }
		logger.info { "Stopping simulation: terminating ${workers.size} workers and main process" }

		val exceptions = mutableListOf<Throwable>()

		// Terminate all InOut workers (continue even if some fail)
		for (worker in workers.values) {
			try {
				worker.terminate()
			} catch (e: Throwable) {
				logger.error(e) { "Failed to terminate worker: ${e.message}" }
				exceptions.add(e)
			}
		}

		// Terminate main simulation process (always attempt, even if workers failed)
		try {
			mainProcess?.terminate()
		} catch (e: Throwable) {
			logger.error(e) { "Failed to terminate main process: ${e.message}" }
			exceptions.add(e)
		}

		// Report success or throw collected exceptions
		if (exceptions.isEmpty()) {
			logger.info { "Simulation stopped successfully" }
		} else {
			val message = "Simulation stopped with ${exceptions.size} error(s) during cleanup"
			logger.warn { message }
			// Throw the first exception with all others as suppressed exceptions
			val primaryException = exceptions.first()
			exceptions.drop(1).forEach { primaryException.addSuppressed(it) }
			throw primaryException
		}
	}

	/**
	 * Stop simulation with error reporting.
	 *
	 * This method is called by simulation processes (e.g., [InOutWorker]) when a fatal
	 * error occurs during simulation execution, such as:
	 * - Track operation failures (path setup, state transitions)
	 * - Unexpected exceptions in simulation logic
	 * - Resource access errors
	 *
	 * ## Behavior
	 *
	 * 1. **Graceful shutdown**: Calls [stop] to terminate all simulation processes
	 * 2. **Error reporting**: Prints stack trace to stderr for debugging
	 * 3. **No JVM exit**: Does not call `System.exit()` - allows JVM to continue running
	 *
	 * ## Lifecycle
	 *
	 * After `errorStop()`:
	 * - All simulation processes (workers, main process) are terminated
	 * - Simulation cannot be resumed (must create new context)
	 * - JVM continues running (can create new simulations, run tests, etc.)
	 *
	 * ## Historical Note
	 *
	 * Prior to Issue #190 (2026-01-21), `stop()` called `System.exit(0)`, which meant
	 * `errorStop()` also exited the JVM. This prevented:
	 * - Running multiple simulations in same JVM session
	 * - Proper unit testing of simulation lifecycle
	 * - Graceful error recovery in applications
	 *
	 * The current implementation enables these use cases while still providing
	 * clear error reporting for simulation failures.
	 *
	 * ## Usage Example
	 *
	 * ```kotlin
	 * // InOutWorker error handling (from InOutWorker.kt:71, 99)
	 * try {
	 *     path.setUpPath(separator)
	 * } catch (e: TrackOperationException) {
	 *     logger.error(e) { "Path setup failed" }
	 *     env.errorStop(e) // Stop simulation, report error, don't crash JVM
	 *     return
	 * }
	 * ```
	 *
	 * @param error The error that caused simulation termination
	 * @throws Throwable If [stop] fails during cleanup (re-throws with suppressed exceptions)
	 * @see stop
	 * @see InOutWorker Path setup error handling at InOutWorker.kt:71, 99
	 */
	override fun errorStop(error: Throwable) {
		stop()
		error.printStackTrace()
	}

	/**
	 * Get list of entry/exit points (dynamic wrappers for simulation)
	 *
	 * Returns the dynamic InOut wrappers that were created during context initialization
	 * by GridTransformer. These wrappers separate static properties (name, position) from
	 * dynamic state (signal states).
	 *
	 * **Prerequisites:** This method requires GridTransformer.transform() or initializeDynamicMapping()
	 * to have been called first. Both methods ensure all InOut wrappers and their embedded
	 * semaphores are mapped in staticToDynamicMap.
	 *
	 * **Wrapper Identity:** This method retrieves existing wrappers from staticToDynamicMap
	 * to preserve wrapper identity (singleton guarantee). It never creates new wrappers.
	 * Creating duplicate wrappers causes path progression failures because train navigation
	 * compares wrapper instances for equality.
	 *
	 * @return Collection of DynamicInOut wrappers (one per InOut in the network)
	 * @throws IllegalStateException if any InOut is missing from staticToDynamicMap
	 */
	override fun getInOuts(): Collection<DynamicInOut> {
		// Lazy initialization: retrieve existing dynamic wrappers from staticToDynamicMap
		if (dynamicInOuts == null) {
			dynamicInOuts =
				inouts
					.map {
						// Use existing wrapper from staticToDynamicMap (created by GridTransformer or initializeDynamicMapping)
						// GridTransformer.transform() already mapped InOut's embedded semaphores (inSemaphore, outSemaphore)
						// using putIfAbsent(), so all necessary mappings are guaranteed to exist
						staticToDynamicMap[it] as? DynamicInOut
							?: throw IllegalStateException(
								"InOut wrapper not found in staticToDynamicMap: $it. " +
									"GridTransformer.transform() or initializeDynamicMapping() must be called first."
							)
					}.toMutableList()
		}
		return dynamicInOuts!!
	}

	/**
	 * Check if a separator is in the specified direction
	 */
	override fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean {
		// Get segment from separator based on next/previous tracks
		// Uses NEXT track (where train is going TO) to check direction
		// This matches baseline behavior and ensures correct path termination
		val segment =
			if (separator is DynamicPathSeparator) {
				// Dynamic separator - use Dynamic API
				getSegment(separator, next, previous)
			} else {
				// Static separator - extract node cell and use static API
				val nodeCell = CellUtilities.assertNodeCell(separator)
				if (next != null) {
					getSegment(nodeCell, next as? DynamicTrackBlock)
				} else {
					null
				}
			}
		// Allow null segment for InOut (both static and Dynamic wrapper)
		if (segment == null && (separator is InOut || separator is DynamicInOut)) return true
		requireSimulation(segment != null) { "Segment cannot be null for separator $separator" }
		val direction = separator.direction()
		val inDirection = segment === direction
		logger.debug {
			"isSeparatorInDirection: separator $separator, segment=$segment, direction=$direction, result=$inDirection"
		}
		return inDirection
	}

	/**
	 * Report simulation events
	 */
	override fun report(
		report: CharSequence,
		obj: Any,
		type: ReportType
	) {
		if (!isReporting(type)) return

		val buf = if (report is StringBuilder) report else StringBuilder(report)
		buf.insert(0, ' ')
		// obj.toString() used directly (no reflection check); objects without custom toString()
		// emit ClassName@hashcode in the log — acceptable trade-off for KMP compatibility
		buf.insert(0, obj)
		buf.insert(0, ' ')
		buf.insert(0, Process.time())
		simulationLogger.info { buf }

		// Fire property change event for animation event timeline
		// Property name = report type name, new value = formatted message
		firePropertyChange(type.name, null, buf.toString())
	}

	/**
	 * Add report types to be reported
	 */
	override fun addReportTypes(vararg types: ReportType) {
		if (types.isEmpty()) {
			allowedReportTypes.clear()
		} else {
			allowedReportTypes.addAll(types.asList())
		}
	}

	/**
	 * Release all path reservations for a train that has completed its journey.
	 *
	 * Unregisters the train from the PathReservationRegistry, removing all block
	 * reservations and PathInfo metadata. This cleanup is essential to prevent
	 * conflicts when subsequent trains try to reserve the same blocks.
	 *
	 * @param trainId The train identifier to release reservations for
	 */
	override fun releaseTrainReservations(trainId: String) {
		val pathService = pathReservationServiceInstance
		val releasedBlocks = pathService.unregister(trainId)
		logger.debug {
			"releaseTrainReservations: Released ${releasedBlocks.size} blocks for train '$trainId'"
		}
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Delegates to PathReservationService to remove the block from the registry
	 * if it is FREE (no occupant).
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 */
	override fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	) {
		val pathService = pathReservationServiceInstance
		pathService.unregisterBlock(trainId, block)
	}

	/**
	 * Check if a report type is enabled
	 */
	override fun isReporting(type: ReportType): Boolean = allowedReportTypes.contains(type)

	/**
	 * Remove report types from reporting
	 */
	override fun removeReportTypes(vararg types: ReportType) {
		if (types.isEmpty()) return
		for (t in types) {
			allowedReportTypes.remove(t)
		}
	}

	/**
	 * Override currentNameString to include random generation for simulation
	 */
	override var currentNameString: String
		get() = super.currentNameString.ifEmpty { randomString() }
		set(value) {
			super.currentNameString = value
		}

	/**
	 * Generate random name string (single character A-T)
	 */
	private fun randomString(): String = (65 + random.randInt(0, 19)).toChar().toString()

	/**
	 * Get the worker for an entry/exit point
	 */
	override fun getWorkerFor(inOut: DynamicInOut): InOutWorker =
		workers[inOut] ?: throw IllegalStateException("No worker found for InOut: $inOut")

	/**
	 * Get the grouped routing/navigation services for this simulation context.
	 *
	 * Routing accessors (topology navigation, path reservation, train navigation) are
	 * grouped behind the [RoutingServices] sub-interface rather than being flattened
	 * directly onto [SimulationEnvironment]. This implementation delegates to the
	 * scoped service instances resolved via Koin.
	 *
	 * @return RoutingServices instance for this simulation context
	 * @see RoutingServices
	 */
	override fun getRoutingServices(): RoutingServices = routingServicesInstance

	override fun getAutomaticPathFindingService(): AutomaticPathFindingService = automaticPathFindingServiceInstance

	override fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder = routeFinderInstance

	/**
	 * Get PathSeparator grid position cache for animation rendering.
	 *
	 * Returns a map from PathSeparator to grid Point for O(1) position lookups.
	 * Used by TrainPositionCalculator to avoid O(n²) grid scans at 30 FPS.
	 *
	 * The cache is populated once during fromEditingContext() transformation and
	 * remains valid for the context lifetime (grid is immutable after freeze).
	 *
	 * @return Map from PathSeparator to grid Point
	 */
	fun getSeparatorPositionCache(): Map<PathSeparator, Point> = separatorPositionCache

	/**
	 * Configure semaphore signal appearance after path reservation.
	 *
	 * This method separates signal configuration from block reservation logic.
	 * PathReservationService handles block ownership tracking, while this method
	 * updates semaphore visual signals (GO/SLOW/STOP) to match the reserved path.
	 *
	 * ## Implementation
	 *
	 * Uses getSegment to determine signal segments (fromSegment, toSegment) and
	 * calls semaphore.setUpPath to update visual state.
	 *
	 * ## Error Handling
	 *
	 * Signal configuration failures are non-fatal - if semaphore update fails,
	 * blocks remain reserved and trains can proceed. Only logs warning.
	 *
	 * @param semaphore The semaphore to configure
	 * @param firstBlock First reserved block in the path
	 * @param allowedSpeed Speed limit for the path
	 */
	override fun configureSemaphoreSignal(
		semaphore: DynamicRailSemaphore,
		firstBlock: DynamicTrackBlock,
		allowedSpeed: Double?
	) {
		try {
			// Calculate allowed speed from path if not provided
			// Fixes circular logic bug where sem.allowedSpeed() returns 0.0 (STOP)
			val effectiveSpeed = allowedSpeed ?: firstBlock.maxSpeed(semaphore)

			val fromSegment = getSegment(semaphore, null, firstBlock)
			val toSegment = getSegment(semaphore, firstBlock, null)

			semaphore.setUpSpeed(fromSegment, toSegment, effectiveSpeed)

			logger.debug {
				"SEMAPHORE_CONFIGURED: ${semaphore.name} signal updated to " +
					"${semaphore.signal} (allowedSpeed=$effectiveSpeed)"
			}
		} catch (e: Exception) {
			logger.warn {
				"SEMAPHORE_CONFIG_WARNING: ${semaphore.name} signal configuration failed: ${e.message}"
			}
			// Non-fatal: blocks are reserved, train can proceed even if signal update fails
		}
	}

	/**
	 * Set the main process for the simulation
	 * (for examples where the main process is not a generator)
	 *
	 * @param process The custom main process (e.g., ShuntingLoop)
	 */
	fun setMainProcess(process: LoopProcess) {
		mainProcess = process
	}

	/**
	 * Returns the currently registered main process, or `null` if none has been set
	 * via [setMainProcess]. Callers may downcast to runtime-control interfaces such
	 * as [cz.vutbr.fit.interlockSim.sim.SpeedControllable] to retune the live
	 * simulation (e.g. wall-clock pacing) from the EDT.
	 */
	fun getMainProcess(): LoopProcess? = mainProcess
}
