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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.java.KoinJavaComponent.getKoin
import java.io.InputStream

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
class MockSimulationContext(private val delegate: DefaultSimulationContext) : SimulationContext by delegate {
	private var currentTime: Double = 0.0
	private val workers: MutableMap<InOut, InOutWorker> = mutableMapOf()
	private val enabledReports: MutableCollection<ReportType> = mutableListOf()
	private var stopped: Boolean = false

	init {
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

	override fun getWorkerFor(inOut: InOut): InOutWorker = workers[inOut]!!

	override fun getInOuts(): Collection<InOut> {
		val inOuts = mutableListOf<InOut>()
		val grid = delegate.getRailWayNetGrid()
		for (p in delegate.getGraph().nodeSet()) {
			val cell = grid[p]
			if (cell is InOut) {
				inOuts.add(cell)
			}
		}
		return inOuts
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

	// Delegate methods to wrapped DefaultSimulationContext

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
}

fun createMockSimulationContext(): MockSimulationContext {
	val defaultContext = contextFactory().createEmptyContext() as DefaultSimulationContext
	return MockSimulationContext(defaultContext)
}

fun createMockSimulationContext(xml: InputStream): MockSimulationContext {
	val defaultContext = contextFactory().createContext(xml) as DefaultSimulationContext
	return MockSimulationContext(defaultContext)
}

private fun contextFactory(): XMLContextFactory = getKoin().get<XMLContextFactory>()
