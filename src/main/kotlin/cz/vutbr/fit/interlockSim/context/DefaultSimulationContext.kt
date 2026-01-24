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

import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.OrientedNodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.DiscoException
import jDisco.Process
import jDisco.Random
import java.util.EnumSet
import java.util.IdentityHashMap

/**
 * Default implementation of {@link SimulationContext} that extends {@link BaseContext} with [DynamicTrackBlock].
 *
 * Provides simulation-specific operations without editing capabilities:
 * - Running discrete event simulations using jDisco framework
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
 * The jDisco discrete event simulation framework operates in a single thread,
 * ensuring sequential execution of all simulation events.
 *
 * ### Usage
 *
 * - Access DefaultSimulationContext only from the simulation thread
 * - All simulation events execute sequentially via jDisco
 * - Do not share instances across thread boundaries
 * - Do NOT call editing methods (putCell, removeCell, etc.) - use EditingContext for that
 *
 * @see SimulationContext
 * @see BaseContext
 * @see EditingContext
 * @see SimulationProcessFactory
 * @see javax.annotation.concurrent.NotThreadSafe
 */
open class DefaultSimulationContext(
	cols: Int,
	rows: Int,
	/**
	 * Factory for creating simulation processes.
	 * Decouples context from concrete simulation class implementations.
	 */
	private val processFactory: SimulationProcessFactory
) : BaseContext<DynamicTrackBlock>(cols, rows), SimulationContext {

	/**
	 * Set of allowed report types for simulation output
	 */
	private val allowedReportTypes: MutableSet<ReportType> = EnumSet.noneOf(ReportType::class.java)

	/**
	 * Workers for each entry/exit point
	 */
	private var workers: MutableMap<DynamicInOut, InOutWorker> = IdentityHashMap()

	/**
	 * Cache of dynamic InOut wrappers (lazily created)
	 */
	private var dynamicInOuts: MutableList<DynamicInOut>? = null

	/**
	 * Mapping from static PathSeparator to Dynamic wrapper (for simulation context)
	 * Maps InOut, RailSemaphore, RailSwitch to their Dynamic counterparts
	 */
	private val staticToDynamicMap: MutableMap<PathSeparator, DynamicPathSeparator> = IdentityHashMap()

	/**
	 * Mapping from static TrackFacility to DynamicTrack wrapper (for simulation context)
	 * Maps track facilities to their Dynamic wrappers for state management
	 */
	private val staticTrackToDynamicMap: MutableMap<TrackFacility, DynamicTrack> = IdentityHashMap()

	/**
	 * Main simulation process
	 */
	private var mainProcess: LoopProcess? = null

	/**
	 * Random number generator for name generation (jDisco)
	 */
	private val random: Random = Random(0)

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
		 * The transformation process is broken down into 5 distinct phases:
		 * 1. Copy grid cells (NodeCell and TrackBlockPart)
		 * 2. Copy graph structure (track block connections)
		 * 3. Copy InOut elements list
		 * 4. Copy configuration properties
		 * 5. Create dynamic wrapper mappings
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
			val cols = grid.getCols()
			val rows = grid.getRows()

			val context = DefaultSimulationContext(cols, rows, processFactory)

			// Orchestrate transformation phases
			copyGridCells(editingContext, context)
			copyGraphStructure(editingContext, context)
			copyInOutList(editingContext, context)
			copyConfiguration(editingContext, context)
			createDynamicMappings(editingContext, context)
			validateTransformation(editingContext, context)

			// Freeze the context to prevent modifications after creation
			// Simulation context has immutable network structure
			context.freeze()

			return context
		}

		/**
		 * Copy all cells from editing grid to simulation grid.
		 * Preserves NodeCell and TrackBlockPart cells.
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 */
		private fun copyGridCells(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			// We need to copy all cells (both NodeCell and TrackBlockPart) to preserve the complete network
			// Cast to Cell grid because EditingContext grid actually contains both NodeCell and TrackBlockPart
			val sourceGrid = editingContext.getRailWayNetGrid()
			@Suppress("UNCHECKED_CAST")
			val cellGrid = sourceGrid as RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.core.Cell>
			val targetGrid = simulationContext.getGrid()

			for ((point, cell) in cellGrid) {
				targetGrid.put(point, cell)
			}

			logger.debug { "Copied ${cellGrid.count()} cells to simulation grid" }
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
				val firstExt = requireSimulationNotNull(doubleton.getValue(first)) {
					"Inconsistent graph entry: missing segment for first point $first in Doubleton key $doubleton"
				}
				val secondExt = requireSimulationNotNull(doubleton.getValue(second)) {
					"Inconsistent graph entry: missing segment for second point $second in Doubleton key $doubleton"
				}

				// ===== KEY CHANGE FOR ISSUE #277 =====
				// Wrap static TrackBlock in DynamicTrackBlock wrapper
				val dynamicTrackBlock = DynamicTrackBlock(staticTrackBlock)

				// Put dynamic wrapper into the simulation graph (type-safe)
				targetGraph.put(first, firstExt, second, secondExt, dynamicTrackBlock)
				wrappedCount++

				logger.trace {
					"Wrapped TrackBlock ${System.identityHashCode(staticTrackBlock)} -> " +
						"DynamicTrackBlock ${System.identityHashCode(dynamicTrackBlock)}"
				}
			}

			logger.debug {
				"Copied ${sourceGraph.size()} graph entries, created $wrappedCount DynamicTrackBlock wrappers"
			}
		}

		/**
		 * Copy InOut elements list from editing to simulation context.
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 */
		private fun copyInOutList(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			// Use interface method getInOuts() instead of type-checking for LSP compliance
			simulationContext.inouts.addAll(editingContext.getInOuts())
			logger.debug { "Copied ${editingContext.getInOuts().size} InOut elements" }
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
		 * Create dynamic wrapper mappings using GridTransformer.
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 */
		private fun createDynamicMappings(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			// Transform static grid to dynamic grid for wrapper mappings
			val sourceGrid = editingContext.getRailWayNetGrid()
			@Suppress("UNCHECKED_CAST")
			val cellGrid = sourceGrid as RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.core.Cell>

			val transformationResult = GridTransformer.transformGrid(cellGrid)

			// Store the transformation map for toDynamic() lookups
			simulationContext.staticToDynamicMap.putAll(transformationResult.staticToDynamicMap)

			logger.debug { "Created ${transformationResult.staticToDynamicMap.size} dynamic wrappers" }
		}

		/**
		 * Validate transformation completeness and correctness.
		 * Logs summary statistics about the transformation.
		 *
		 * @param editingContext Source editing context
		 * @param simulationContext Target simulation context
		 */
		private fun validateTransformation(
			editingContext: EditingContext,
			simulationContext: DefaultSimulationContext
		) {
			val grid = editingContext.getRailWayNetGrid()
			val cols = grid.getCols()
			val rows = grid.getRows()

			logger.info {
				"Created simulation context from editing context: " +
				"${simulationContext.staticToDynamicMap.size} dynamic wrappers, " +
				"${simulationContext.inouts.size} InOuts, " +
				"grid: ${cols}x${rows}, graph: ${editingContext.getGraph().size()} track blocks"
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
			"PathSeparator must be OrientedPathSeparator, got ${separator.javaClass.simpleName}"
		}
		val segment = getSegment(separator, secondEndTrack!!)
		// Match Java 1:1: return null when segment doesn't exist
		return separator.getFollowingSegment(segment)
	}

	/**
	 * Get segment for a path separator and track
	 */
	override fun getSegment(
		separator: DynamicPathSeparator,
		track: Track
	): Segment? {
		return if (track is TrackSection) {
			@Suppress("UNCHECKED_CAST")
			val section = track as TrackSection
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(separator, section) ?: throw IllegalStateException("getSegment returned null for TrackSection")
		} else {
			val nodeCell: NodeCell = CellUtilities.assertNodeCell(separator)
			val trackBlock: DynamicTrackBlock = Util.assertInstanceOf(DynamicTrackBlock::class.java, track)
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(nodeCell, trackBlock)
		}
	}

	/**
	 * Get pseudo join segment in block for a path separator and track section
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		section: TrackSection
	): Segment? {
		val staticBlock = section.getTrackBlock()
		if (staticBlock.isInnerElement(separator)) {
			return staticBlock.getJoin(separator, section)
		}
		val nodeCell: NodeCell = CellUtilities.assertNodeCell(separator)
		// Look up DynamicTrackBlock wrapper for the static block from TrackSection
		val dynamicTrackBlock = getDynamicWrapper(staticBlock)
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
		return location!!
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
		val followingSegment = when {
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
		return null  // No wrapper found
	}

	/**
	 * Get the next track section from a path separator
	 */
	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		var trackBlock: DynamicTrackBlock? = null
		if (current != null) {
			// TrackSection belongs to static structure, so getTrackBlock() returns static TrackBlock
			// DynamicTrackBlock.getNextTrackSection() delegates to static block, so we can use it directly
			val staticBlock = current.getTrackBlock()
			requireSimulation(staticBlock != null) { "TrackBlock cannot be null for current track section" }
			val nextTrackSection = staticBlock.getNextTrackSection(separator, current)
			if (nextTrackSection != null) {
				logger.trace {
					"getNextTrackSection: found next section within same block from $separator"
				}
				return nextTrackSection
			}
			// Look up DynamicTrackBlock wrapper from graph for use in getNextTrackBlock call below
			trackBlock = getDynamicWrapper(staticBlock)
		}

		// z dalsi TrackBlock
		// Extract static NodeCell for getNextTrackBlock (which needs NodeCell interface)
		// but preserve the separator (which could be Dynamic*) for proper type checking
		val staticNodeCell = CellUtilities.assertNodeCell(separator)

		// Pass separator as NodeCell to getNextTrackBlock
		// (NodeCell is a subtype of PathSeparator, so static objects work directly;
		// Dynamic* also implement PathSeparator, so they work too)
		val nodeCell = if (separator is NodeCell) separator else staticNodeCell
		val nextTrackBlock = getNextTrackBlock(nodeCell, trackBlock)

		@Suppress("UNCHECKED_CAST")
		val result = nextTrackBlock?.getNextTrackSection(nodeCell, null as TrackSection?)
		logger.trace {
			"getNextTrackSection: navigating network from $separator, result: ${if (result != null) "found" else "not found"}"
		}
		return result
	}

	/**
	 * Run the simulation (jDisco framework integration)
	 */
	/**
	 * Initialize static-to-dynamic mapping for all PathSeparators in the network
	 * Must be called before simulation starts to ensure all separators have Dynamic wrappers
	 *
	 * Visibility: internal (visible for testing)
	 * Tests that call pathToNextSemaphore() without running full simulation must call this first
	 */
	internal fun initializeDynamicMapping() {
		// Track what we're mapping to avoid duplicates
		var mappedCount = 0
		// Use internal grid to access all cells (including TrackBlockPart), not just NodeCells
		val grid = getInternalGrid()

		// Iterate through all cells in the railway network grid
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y) ?: continue

				// Skip TrackBlockPart - these are not NodeCells and don't need dynamic wrappers
				if (cell !is NodeCell) {
					logger.trace { "Skipping ${cell.javaClass.simpleName} at ($x,$y) - not a NodeCell" }
					continue
				}

				// Skip if already mapped (handles case where getInOuts was called early)
				if (cell in staticToDynamicMap) {
					logger.trace { "Skipping ${cell::class.java.simpleName} at ($x,$y) - already mapped" }
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
						staticToDynamicMap.putIfAbsent(cell.getInSemaphore(), dynamic.inSemaphore)
						staticToDynamicMap.putIfAbsent(cell.getOutSemaphore(), dynamic.outSemaphore)
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
			// TrackBlock extends TrackFacility, so we can safely cast
			val trackFacility = trackBlock as TrackFacility
			
			// Skip if already mapped
			if (staticTrackToDynamicMap.containsKey(trackFacility)) {
				logger.trace { "Skipping TrackBlock ${trackFacility.hashCode()} - already mapped" }
				continue
			}

			// Create DynamicTrack wrapper for each TrackBlock
			val dynamicTrack = DynamicTrack(trackFacility)
			staticTrackToDynamicMap[trackFacility] = dynamicTrack
			trackMappedCount++
			logger.trace { "Mapped TrackBlock ${trackFacility.hashCode()} to dynamic wrapper" }

			// Recursively map any internal TrackSection objects
			// For SimpleTrackBlock (current impl), this is a no-op
			// For future CompoundTrackBlock, ensures all internal sections are mapped
			mapInternalSections(trackBlock)
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
					if (!staticTrackToDynamicMap.containsKey(currentSection)) {
						// Create and map DynamicTrack wrapper for internal section
						val dynamicSection = DynamicTrack(currentSection)
						staticTrackToDynamicMap[currentSection] = dynamicSection
						logger.debug {
							"Mapped internal TrackSection ${System.identityHashCode(currentSection)} " +
							"within TrackBlock ${System.identityHashCode(trackBlock)}"
						}
					} else {
						logger.trace {
							"Internal section ${System.identityHashCode(currentSection)} already mapped"
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
				"TrackBlock ${System.identityHashCode(trackBlock)} contains ${visited.size} sections"
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
	private fun validateDynamicMapping() {
		// Use internal grid to access all cells (including TrackBlockPart), not just NodeCells
		val grid = getInternalGrid()
		val unmappedSeparators = mutableListOf<String>()
		val unmappedTracks = mutableListOf<String>()

		// Validate PathSeparators from grid
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y) ?: continue

				if (cell is PathSeparator && cell !in staticToDynamicMap) {
					unmappedSeparators.add("${cell.javaClass.simpleName} at ($x,$y)")
				}
			}
		}

		// Validate all TrackFacility objects (including internal sections)
		val graph = getGraph()
		for (trackBlock in graph.values()) {
			// Check top-level TrackBlock
			val trackFacility = trackBlock as TrackFacility
			if (trackFacility !in staticTrackToDynamicMap) {
				unmappedTracks.add("TrackBlock ${System.identityHashCode(trackBlock)}")
			}

			// Check internal TrackSections
			val ends = trackBlock.ends()
			for (end in ends) {
				var currentSection: TrackSection? = trackBlock.getNextTrackSection(end, null)
				val visited = mutableSetOf<TrackSection>()

				while (currentSection != null && currentSection !in visited) {
					visited.add(currentSection)

					// Skip if section is the TrackBlock itself
					if (currentSection === trackBlock) break

					// Check if internal section is mapped
					if (currentSection is TrackFacility && currentSection !in staticTrackToDynamicMap) {
						unmappedTracks.add(
							"TrackSection ${System.identityHashCode(currentSection)} " +
							"in TrackBlock ${System.identityHashCode(trackBlock)}"
						)
					}

					val nextSeparator = currentSection.getSecondEnd(end)
					currentSection = trackBlock.getNextTrackSection(nextSeparator, currentSection)
				}
			}
		}

		if (unmappedSeparators.isNotEmpty() || unmappedTracks.isNotEmpty()) {
			val message = buildString {
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
			logger.trace { "toDynamic: separator already dynamic, returning as-is: ${separator.javaClass.simpleName}" }
			return separator
		}

		// Use static-to-dynamic map for conversions
		val dynamic = staticToDynamicMap[separator]
			?: throw IllegalStateException(
				"Dynamic wrapper not found for separator: $separator (${separator.javaClass.simpleName}). " +
					"Map contains ${staticToDynamicMap.size} entries. " +
					"This indicates the separator was not registered during initialization. " +
					"Ensure initializeDynamicMapping() completed successfully before simulation starts."
			)
		logger.trace { "toDynamic: converted static ${separator.javaClass.simpleName} to ${dynamic.javaClass.simpleName}" }
		return dynamic
	}

	/**
	 * Convert a TrackFacility to its DynamicTrack wrapper.
	 * Creates wrapper lazily if not yet created (for tracks discovered during simulation).
	 * Uses identity-based mapping to ensure each static track maps to exactly one wrapper.
	 */
	override fun toDynamic(track: TrackFacility): DynamicTrack {
		// Return existing wrapper if already mapped
		staticTrackToDynamicMap[track]?.let { return it }

		// Create new wrapper for unmapped track (lazy initialization)
		val dynamicTrack = DynamicTrack(track)
		staticTrackToDynamicMap[track] = dynamicTrack
		logger.debug { "Lazy-created DynamicTrack wrapper for track ${System.identityHashCode(track)}" }
		return dynamicTrack
	}

	@Throws(EmptyContextException::class, SimulationException::class)
	override fun run() {
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
		initializeDynamicMapping()  // Maps ALL separators (InOut, RailSemaphore, RailSwitch)

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
				"main process=${mainProcess!!.javaClass.simpleName}"
		}

		// Use factory to create worker for each InOut
		// Reuse dynamic wrappers from getInOuts() (already initialized above)
		for (dynamicInOut in getInOuts()) {
			workers[dynamicInOut] = processFactory.createInOutWorker(this, dynamicInOut)
		}

		try {
			Process.activate(mainProcess)
		} catch (e: DiscoException) {
			logger.error(e) { "Failed to activate main simulation process" }
			throw SimulationException(e)
		}
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
	 * Returns the dynamic InOut wrappers. Creates them lazily if not yet initialized.
	 * These wrappers separate static properties (name, position) from dynamic state (signal states).
	 */
	override fun getInOuts(): Collection<DynamicInOut> {
		// Lazy initialization: create dynamic wrappers if not yet created
		if (dynamicInOuts == null) {
			dynamicInOuts = inouts.map {
				val dynamic = createDynamic(it)
				staticToDynamicMap[it] = dynamic
				// Map InOut's semaphores (use putIfAbsent to avoid conflicts)
				staticToDynamicMap.putIfAbsent(it.getInSemaphore(), dynamic.inSemaphore)
				staticToDynamicMap.putIfAbsent(it.getOutSemaphore(), dynamic.outSemaphore)
				dynamic
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
		// Try to use Dynamic wrapper if available, otherwise use static OrientedNodeCell
		// CRITICAL FIX: Use PREVIOUS track (where train is coming FROM) to check if semaphore
		// is "in direction". This checks if the train is approaching the semaphore from its
		// facing direction, not if the train is going toward a track in that direction.
		val segment = if (separator is DynamicPathSeparator) {
			getSegment(separator, previous, next)  // Swapped: previous first!
		} else {
			// Static separator - need to get segment differently
			val staticNodeCell = CellUtilities.assertNodeCell(separator)
			if (previous != null) {  // Changed: check previous first!
				getSegment(staticNodeCell, previous as? DynamicTrackBlock)
			} else {
				getSegment(staticNodeCell, next as? DynamicTrackBlock)
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
	 * Find path to the next semaphore from a path separator
	 *
	 * Returns paths containing only dynamic references.
	 * All PathSeparators added to the path are converted to their dynamic wrappers
	 * to ensure consistent use of dynamic types throughout simulation.
	 */
	override fun pathToNextSemaphore(
		sep: PathSeparator,
		nxt: TrackSection
	): Path? {
		logger.debug { "pathToNextSemaphore: searching path from $sep via track section" }
		// Convert initial separator to dynamic reference
		var separator = toDynamic(sep)
		logger.trace { "Converted input separator to dynamic: ${separator.javaClass.simpleName}" }
		var previous: TrackSection? = null
		var next: TrackSection? = nxt
		val path = ArrayPath(this)
		do {
			// Add dynamic separator to path
			path.add(separator)
			if (next != null) {
				path.add(next)
				// Extract static separator for track operations (getSecondEnd uses === comparison)
				val staticSeparator = CellUtilities.assertNodeCell(separator)
				val staticResult = next.getSecondEnd(staticSeparator)
				// Convert static result to dynamic wrapper before adding to path
				separator = toDynamic(staticResult)
				logger.trace {
					"Converted separator from track to dynamic: ${separator.javaClass.simpleName}"
				}
				previous = next
				next = getNextTrackSection(separator, next)

				// Check if we've reached the final semaphore AFTER getting next section
				// This ensures we have proper next/previous values for direction check
				if (separator is OrientedPathSeparator) {
					// Direction check for oriented semaphores
					if (isSeparatorInDirection(separator, next, previous)) {
						// Add dynamic separator to path
						path.add(separator)
						logger.debug { "pathToNextSemaphore: found complete path to $separator with length ${path.length()}" }
						return path
					}
				}
			} else {
				break
			}
		} while (next != null)
		logger.debug { "pathToNextSemaphore: no path found from $sep" }
		return null
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
		try {
			if (obj.javaClass.getMethod("toString") != Any::class.java.getMethod("toString")) {
				buf.insert(0, ' ')
				buf.insert(0, obj)
			}
		} catch (e: Exception) {
			logger.error(e) { "Error generating simulation report for type $type" }
		}
		buf.insert(0, ' ')
		buf.insert(0, jDisco.Process.time())
		simulationLogger.info { buf }
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
	private fun randomString(): String = String(Character.toChars(65 + random.nextInt(20)))

	/**
	 * Get the worker for an entry/exit point
	 */
	override fun getWorkerFor(inOut: DynamicInOut): InOutWorker =
		workers[inOut] ?: throw IllegalStateException("No worker found for InOut: $inOut")

	/**
	 * Set the main process for the simulation
	 * (for examples where the main process is not a generator)
	 *
	 * @param process The custom main process (e.g., ShuntingLoop)
	 */
	fun setMainProcess(process: LoopProcess) {
		mainProcess = process
	}
}
