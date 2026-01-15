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
 * Default implementation of {@link SimulationContext} that extends {@link DefaultEditingContext}.
 *
 * Combines editing capabilities with simulation-specific operations:
 * - Running discrete event simulations using jDisco framework
 * - Managing simulation processes (main process, InOut workers)
 * - Path finding for train navigation (pathToNextSemaphore)
 * - Simulation reporting and event logging
 * - Train name generation
 *
 * This class extends {@link DefaultEditingContext} and adds simulation-specific fields
 * and methods. It uses {@link SimulationProcessFactory} to create simulation processes,
 * decoupling from concrete simulation class implementations.
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * In addition to inherited thread-safety concerns from DefaultEditingContext,
 * DefaultSimulationContext maintains additional mutable state:
 * - Simulation process references (mainProcess, workers)
 * - Report type configuration (allowedReportTypes)
 * - Random number generator (for train naming)
 *
 * The jDisco discrete event simulation framework operates in a single thread,
 * ensuring sequential execution of all simulation events.
 *
 * ### Usage
 *
 * - Access DefaultSimulationContext only from the simulation thread
 * - All simulation events execute sequentially via jDisco
 * - Do not share instances across thread boundaries
 *
 * @see SimulationContext
 * @see DefaultEditingContext
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
) : DefaultEditingContext(cols, rows), SimulationContext {

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
	 * Mapping from static TrackFacility to Dynamic wrapper (for simulation context)
	 * Maps TrackBlock to DynamicTrack wrappers for state management
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
	 */
	private fun initializeDynamicMapping() {
		// Track what we're mapping to avoid duplicates
		var mappedCount = 0
		val grid = getRailWayNetGrid()

		// Iterate through all cells in the railway network grid
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y) ?: continue

				// Skip if already mapped (handles case where getInOuts was called early)
				if (cell in staticToDynamicMap) {
					logger.trace { "Skipping ${cell.javaClass.simpleName} at ($x,$y) - already mapped" }
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
		}
		logger.debug { "Initialized $trackMappedCount dynamic track wrappers (total in map: ${staticTrackToDynamicMap.size})" }
	}

	/**
	 * Validate that all PathSeparators in the network have Dynamic wrappers.
	 *
	 * Based on architectural assumption: simulation context has immutable network structure.
	 * All separators must be wrapped at initialization - discovering an unwrapped separator
	 * during simulation indicates a bug.
	 */
	private fun validateDynamicMapping() {
		val grid = getRailWayNetGrid()
		val unmapped = mutableListOf<String>()

		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y) ?: continue

				if (cell is PathSeparator && cell !in staticToDynamicMap) {
					unmapped.add("${cell.javaClass.simpleName} at ($x,$y)")
				}
			}
		}

		if (unmapped.isNotEmpty()) {
			throw IllegalStateException(
				"Dynamic mapping incomplete! Unmapped separators: ${unmapped.joinToString(", ")}. " +
				"Map contains ${staticToDynamicMap.size} entries. " +
				"This indicates initialization logic is incomplete."
			)
		}

		logger.info { "Dynamic mapping validation passed: all ${staticToDynamicMap.size} separators mapped" }
	}

	/**
	 * Convert a static PathSeparator to its Dynamic wrapper.
	 * Returns the Dynamic wrapper if found, otherwise returns the input separator unchanged.
	 * This is used by Train to ensure it always works with Dynamic wrappers.
	 */
	override fun toDynamic(separator: PathSeparator): PathSeparator {
		return if (separator is DynamicPathSeparator) {
			separator  // Already dynamic
		} else {
			staticToDynamicMap[separator] ?: separator  // Convert or fallback to static
		}
	}

	/**
	 * Convert a static TrackFacility to its Dynamic wrapper.
	 * Returns the Dynamic wrapper if found, otherwise returns null.
	 * This is used by simulation components to access dynamic track state.
	 */
	override fun toDynamic(track: TrackFacility): DynamicTrack? {
		return staticTrackToDynamicMap[track]
	}

	@Throws(EmptyContextException::class, SimulationException::class)
	override fun run() {
		if (getGraph().isEmpty() || getRailWayNetGrid().isEmpty() || inouts.isEmpty()) {
			logger.warn {
				"Cannot start simulation: graph=${if (getGraph().isEmpty()) "empty" else "ok"}, " +
					"grid=${if (getRailWayNetGrid().isEmpty()) "empty" else "ok"}, " +
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
		val segment = if (separator is DynamicPathSeparator) {
			getSegment(separator, next, previous)
		} else {
			// Static separator - need to get segment differently
			val staticNodeCell = CellUtilities.assertNodeCell(separator)
			if (next != null) {
				getSegment(staticNodeCell, next as? TrackBlock)
			} else {
				getSegment(staticNodeCell, previous as? TrackBlock)
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
	 */
	override fun pathToNextSemaphore(
		sep: PathSeparator,
		nxt: TrackSection
	): Path? {
		logger.debug { "pathToNextSemaphore: searching path from $sep via track section" }
		var separator = sep
		var previous: TrackSection? = null
		var next: TrackSection? = nxt
		val path = ArrayPath(this)
		do {
			path.add(separator)
			if (next != null) {
				path.add(next)
				// Extract static separator for track operations (getSecondEnd uses === comparison)
				val staticSeparator = CellUtilities.assertNodeCell(separator)
				val staticResult = next.getSecondEnd(staticSeparator)
				// Wrap static result back to Dynamic wrapper (must exist in map)
				separator = staticToDynamicMap[staticResult]
					?: throw IllegalStateException(
						"No dynamic wrapper found for static separator: $staticResult. " +
							"Map contains ${staticToDynamicMap.size} entries. " +
							"This indicates initialization failed."
					)
				previous = next
				next = getNextTrackSection(separator, next)
			} else {
				break
			}
			if (separator is OrientedPathSeparator) {
				if (isSeparatorInDirection(separator, next, previous)) {
					path.add(separator)
					logger.trace { "pathToNextSemaphore: found complete path with length ${path.length()}" }
					return path
				}
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
