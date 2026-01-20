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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility
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
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.DiscoException
import jDisco.Process
import jDisco.Random
import java.util.EnumSet
import java.util.IdentityHashMap

/**
 * Default implementation of {@link SimulationContext} that extends {@link BaseContext}.
 *
 * Provides simulation-specific operations without editing capabilities:
 * - Running discrete event simulations using jDisco framework
 * - Managing simulation processes (main process, InOut workers)
 * - Path finding for train navigation (pathToNextSemaphore)
 * - Simulation reporting and event logging
 * - Train name generation
 * - Dynamic wrapper management (PathSeparator and TrackFacility wrappers)
 *
 * This class extends {@link BaseContext} directly, separating simulation from editing concerns.
 * Simulation contexts are immutable - network structure cannot be modified during simulation.
 * It uses {@link SimulationProcessFactory} to create simulation processes,
 * decoupling from concrete simulation class implementations.
 *
 * ## Architecture
 *
 * **BaseContext** provides:
 * - Grid and graph storage (immutable during simulation)
 * - Property change notification
 * - Configuration management (maxSpeed, trackLength, nameString)
 * - InOut list management
 *
 * **DefaultSimulationContext** adds:
 * - Simulation execution (run, stop, errorStop)
 * - Dynamic wrapper mappings (static to dynamic conversion)
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
) : BaseContext(cols, rows), SimulationContext {

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

			// Copy cells from editing context grid to simulation context grid
			// We need to copy all cells (both NodeCell and TrackBlockPart) to preserve the complete network
			// Cast to Cell grid because EditingContext grid actually contains both NodeCell and TrackBlockPart
			@Suppress("UNCHECKED_CAST")
			val cellGrid = grid as RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.cells.Cell>
			val simGrid = context.getGrid()
			for ((point, cell) in cellGrid) {
				simGrid.put(point, cell)
			}

			// Copy the graph from editing context
			// This ensures all track block connections are preserved
			val editGraph = editingContext.getGraph()
			val simGraph = context.getGraph()
			for (entry in editGraph.entrySet()) {
				// Each entry has a Doubleton<Point, Segment> key and TrackBlock value
				val doubleton = entry.key
				val trackBlock = entry.value

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

				// Put into the simulation graph
				simGraph.put(first, firstExt, second, secondExt, trackBlock)
			}

			// Copy InOut elements list
			// Cast editingContext to BaseContext to access protected inouts
			if (editingContext is DefaultEditingContext) {
				context.inouts.addAll(editingContext.getInOutsList())
			}

			// Copy configuration properties
			context.currentMaxSpeed = editingContext.currentMaxSpeed
			context.currentTrackLength = editingContext.currentTrackLength
			context.currentNameString = editingContext.currentNameString

			// Transform static grid to dynamic grid for wrapper mappings
			// Use the already-cast cellGrid from above
			val transformationResult = GridTransformer.transformGrid(cellGrid)

			// Store the transformation map for toDynamic() lookups
			context.staticToDynamicMap.putAll(transformationResult.staticToDynamicMap)

			logger.info {
				"Created simulation context from editing context: " +
				"${transformationResult.staticToDynamicMap.size} dynamic wrappers, " +
				"${context.inouts.size} InOuts, " +
				"grid: ${cols}x${rows}, graph: ${editingContext.getGraph().size()} track blocks"
			}

			return context
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
			val trackBlock: TrackBlock = Util.assertInstanceOf(TrackBlock::class.java, track)
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(nodeCell, trackBlock as TrackBlock?)
		}
	}

	/**
	 * Get pseudo join segment in block for a path separator and track section
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		section: TrackSection
	): Segment? {
		val trackBlock = section.getTrackBlock()
		if (trackBlock.isInnerElement(separator)) {
			return trackBlock.getJoin(separator, section)
		}
		val nodeCell: NodeCell = CellUtilities.assertNodeCell(separator)
		return getSegment(nodeCell, trackBlock as TrackBlock?)
	}

	/**
	 * Get segment at a node cell for a track block
	 */
	private fun getSegment(
		node: NodeCell,
		current: TrackBlock?
	): Segment? {
		val location = getLocation(node)
		return getSegment(location, current)
	}

	/**
	 * Get segment at a location for a track block
	 */
	private fun getSegment(
		location: Point,
		current: TrackBlock?
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
		current: TrackBlock?
	): TrackBlock? {
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
	 * Get the next track section from a path separator
	 */
	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		var trackBlock: TrackBlock? = null
		if (current != null) {
			trackBlock = current.getTrackBlock()
			requireSimulation(trackBlock != null) { "TrackBlock cannot be null for current track section" }
			val nextTrackSection = trackBlock?.getNextTrackSection(separator, current)
			if (nextTrackSection != null) {
				logger.trace {
					"getNextTrackSection: found next section within same block from $separator"
				}
				return nextTrackSection
			}
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
	 */
	override fun stop() {
		requireSimulationNotNull(mainProcess) { "Main process must be initialized before stopping simulation" }
		for (worker in workers.values) {
			worker.terminate()
		}
		mainProcess?.terminate()
		System.exit(0) // TODO: Remove System.exit - use proper termination handling
	}

	/**
	 * Stop simulation with error reporting
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
				getSegment(staticNodeCell, previous as? TrackBlock)
			} else {
				getSegment(staticNodeCell, next as? TrackBlock)
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
