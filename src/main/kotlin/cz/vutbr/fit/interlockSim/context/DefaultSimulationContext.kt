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
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
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
	private var workers: MutableMap<InOut, InOutWorker> = IdentityHashMap<InOut, InOutWorker>()

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
		separator: PathSeparator,
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
		separator: PathSeparator,
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
		separator: PathSeparator,
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
		val location = getLocation(nodeCell)
		val segment = getSegment(location, current)
		val followingSegment = nodeCell.getFollowingSegment(segment)
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
		val nodeCell = CellUtilities.assertNodeCell(separator)
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
		// Use factory to create main process if not already set
		if (mainProcess == null) {
			mainProcess = processFactory.createMainProcess(this)
		}

		logger.info {
			"Starting simulation: ${inouts.size} InOut points, ${getGraph().size()} track blocks, " +
				"main process=${mainProcess!!.javaClass.simpleName}"
		}

		// Use factory to create worker for each InOut
		for (i in inouts) {
			workers[i] = processFactory.createInOutWorker(this, i)
		}

		try {
			Process.activate(mainProcess)
		} catch (e: DiscoException) {
			logger.error(e) { "Failed to activate main simulation process" }
			throw SimulationException(e)
		}
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
		System.exit(1) // TODO: Remove System.exit - see GitHub issue #56
	}

	/**
	 * Stop simulation with error reporting
	 */
	override fun errorStop(error: Throwable) {
		stop()
		error.printStackTrace()
	}

	/**
	 * Get list of entry/exit points
	 */
	override fun getInOuts(): Collection<InOut> = inouts as Collection<InOut>

	/**
	 * Check if a separator is in the specified direction
	 */
	override fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean {
		val segment = getSegment(separator, next, previous)
		if (segment == null && separator is InOut) return true
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
				separator = next.getSecondEnd(separator)
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
	override fun getWorkerFor(inOut: InOut): InOutWorker =
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
