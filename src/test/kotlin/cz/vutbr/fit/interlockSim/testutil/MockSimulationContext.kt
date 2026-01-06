/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test Utility: Mock Simulation Context
	Test-friendly simulation context implementation

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.awt.Point
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap

/**
 * Mock implementation of {@link SimulationContext} for testing simulation components
 * without requiring jDisco framework initialization.
 *
 * <p>This mock provides controllable simulation time and test-friendly implementations
 * of simulation methods. It extends the test context builder functionality to support
 * simulation-specific operations.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * MockSimulationContext mockContext = MockSimulationContext()
 * mockContext.advanceTime(1.0); // Advance simulation time by 1 second
 * Double time = mockContext.time(); // Returns 1.0
 *
 * mockContext.addReportTypes(ReportType.TRAIN_EVENTS);
 * mockContext.report("Train event", train, ReportType.TRAIN_EVENTS);
 * }</pre>
 *
 * @see SimulationContext
 * @see TestContextBuilder
 */
class MockSimulationContext : SimulationContext {
	private val delegate: DefaultContext
	private var currentTime: Double = 0.0
	private val workers: MutableMap<InOut, InOutWorker> = HashMap()
	private val enabledReports: MutableCollection<ReportType> = ArrayList()
	private var stopped: Boolean = false

	/**
	 * Creates a mock simulation context with empty 100x100 grid.
	 */
	constructor() {
		this.delegate = XMLContextFactory.getInstance().createEmptyContext()
		// Enable all standard reports by default
		for (type in ReportType.ALL) {
			enabledReports.add(type)
		}
	}

	/**
	 * Creates a mock simulation context wrapping an existing DefaultContext.
	 *
	 * @param context existing context to wrap
	 */
	constructor(context: DefaultContext) {
		this.delegate = context
		// Enable all standard reports by default
		for (type in ReportType.ALL) {
			enabledReports.add(type)
		}
	}

	/**
	 * Advances the simulation time by the specified delta.
	 *
	 * @param delta time increment in seconds
	 */
	fun advanceTime(delta: Double) {
		this.currentTime += delta
	}

	/**
	 * Sets the simulation time to a specific value.
	 *
	 * @param time simulation time in seconds
	 */
	fun setTime(time: Double) {
		this.currentTime = time
	}

	/**
	 * Returns the current simulation time.
	 *
	 * @return current time in seconds
	 */
	fun time(): Double = currentTime

	/**
	 * Registers an InOutWorker for the specified InOut point.
	 *
	 * @param inOut the InOut point
	 * @param worker the worker to register
	 */
	fun registerWorker(
		inOut: InOut,
		worker: InOutWorker
	) {
		workers[inOut] = worker
	}

	override fun getWorkerFor(inOut: InOut): InOutWorker = workers[inOut]!!

	override fun getInOuts(): Collection<InOut> {
		val inOuts: ArrayList<InOut> = ArrayList()
		val grid = delegate.getRailWayNetGrid()
		for (p in delegate.getGraph().nodeSet()) {
			val cell = grid[p]
			if (cell is InOut) {
				inOuts.add(cell)
			}
		}
		return inOuts as Collection<InOut>
	}

	override fun run() {
		// Mock implementation - does not actually run simulation
		stopped = false
	}

	override fun stop() {
		stopped = true
	}

	override fun errorStop(error: Throwable) {
		stopped = true
		throw RuntimeException("Simulation error", error)
	}

	/**
	 * Returns whether the simulation has been stopped.
	 *
	 * @return true if stopped
	 */
	fun isStopped(): Boolean = stopped

	override fun report(
		report: CharSequence,
		obj: Any,
		type: ReportType
	) {
		if (isReporting(type)) {
			println(String.format("[%s] %.2f: %s", type, currentTime, report))
		}
	}

	override fun addReportTypes(vararg types: ReportType) {
		for (type in types) {
			if (!enabledReports.contains(type)) {
				enabledReports.add(type)
			}
		}
	}

	override fun removeReportTypes(vararg types: ReportType) {
		for (type in types) {
			enabledReports.remove(type)
		}
	}

	override fun isReporting(type: ReportType): Boolean = enabledReports.contains(type)

	// Delegate methods to wrapped DefaultContext

	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		// Check if node is in grid before delegating
		if (separator is NodeCell && delegate.getRailWayNetGrid().getLocation(separator) == null) {
			// Node not in grid - return null (graceful handling for tests)
			return null
		}
		return delegate.getNextTrackSection(separator, current)
	}

	override fun pathToNextSemaphore(
		separator: PathSeparator,
		next: TrackSection
	): Path? {
		// Simplified implementation for testing - delegate to DefaultContext
		return delegate.pathToNextSemaphore(separator, next)
	}

	override fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: TrackBlock?
	): TrackBlock? {
		// Check if node is in grid before delegating
		if (delegate.getRailWayNetGrid().getLocation(nodeCell) == null) {
			// Node not in grid - return null (graceful handling for tests)
			return null
		}
		return delegate.getNextTrackBlock(nodeCell, current)
	}

	override fun getSegment(
		separator: PathSeparator,
		track: Track
	): Segment {
		// Simplified implementation for testing
		return Segment.A
	}

	override fun getSegment(
		separator: PathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment {
		// Simplified implementation for testing
		return Segment.A
	}

	override fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean {
		// Simplified implementation for testing
		return true
	}

	override fun getRailWayNetGrid(): RailwayNetGrid = delegate.getRailWayNetGrid()

	override fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment> = delegate.getGraph()

	override fun addPropertyChangeListener(listener: java.beans.PropertyChangeListener) {
		delegate.addPropertyChangeListener(listener)
	}

	override fun removePropertyChangeListener(listener: java.beans.PropertyChangeListener) {
		delegate.removePropertyChangeListener(listener)
	}

	/**
	 * Returns the underlying DefaultContext for advanced operations.
	 *
	 * @return delegate context
	 */
	fun getDelegate(): DefaultContext = delegate
}
