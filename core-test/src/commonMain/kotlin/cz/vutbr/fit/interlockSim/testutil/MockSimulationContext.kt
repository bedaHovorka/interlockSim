/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test Utility: Mock Simulation Context (commonMain — portable class)

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import org.koin.mp.KoinPlatformTools

/**
 * Mock implementation of SimulationContext for testing simulation components
 * without requiring kDisco framework initialization.
 *
 * The class itself is cross-platform (commonMain). JVM-only factory helpers
 * that accept InputStream live in core-test/jvmMain (MockSimulationContextJvm.kt).
 */
class MockSimulationContext(
	private val delegate: DefaultSimulationContext
) : SimulationContext by delegate {
	private var currentTime: Double = 0.0
	private val workers: MutableMap<DynamicInOut, InOutWorker> = mutableMapOf()
	private val enabledReports: MutableCollection<ReportType> = mutableListOf()
	private var stopped: Boolean = false

	init {
		// Enable all standard reports by default
		for (type in ReportType.ALL) {
			enabledReports.add(type)
		}
	}

	fun advanceTime(delta: Double) {
		this.currentTime += delta
	}

	fun setTime(time: Double) {
		this.currentTime = time
	}

	fun time(): Double = currentTime

	override fun getWorkerFor(inOut: DynamicInOut): InOutWorker {
		return delegate.getWorkerFor(inOut)
	}

	override fun getInOuts(): Collection<DynamicInOut> {
		return delegate.getInOuts()
	}

	override fun run() {
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
			println("[$type] $currentTime: $report")
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

	override fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: DynamicTrackBlock?
	): DynamicTrackBlock? {
		if (delegate.getRailWayNetGrid().getLocation(nodeCell) == null) {
			return null
		}
		return delegate.getNextTrackBlock(nodeCell, current)
	}

	override fun getSegment(
		separator: DynamicPathSeparator,
		track: Track
	): Segment {
		return Segment.A
	}

	override fun getSegment(
		separator: DynamicPathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Cell.Segment {
		return Segment.A
	}

	override fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean {
		return true
	}
}

/**
 * Creates an empty [MockSimulationContext] via Koin-provided factories.
 *
 * Uses [KoinPlatformTools] (koin-core, cross-platform) instead of the JVM-only
 * KoinJavaComponent.getKoin(). Requires Koin to be started with a module that
 * provides [SimulationProcessFactory].
 */
fun createMockSimulationContext(): MockSimulationContext {
	val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
	val defaultContext = CommonTestFixtures.createEmptySimulationContext(processFactory)
	return MockSimulationContext(defaultContext)
}

/**
 * Creates a [MockSimulationContext] from a raw XML string.
 *
 * Uses [CommonTestFixtures.parseSimulationContext] so this overload is cross-platform
 * (no InputStream). Requires Koin to be started with a module providing [SimulationProcessFactory].
 */
fun createMockSimulationContext(xml: String): MockSimulationContext {
	val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
	val defaultContext = CommonTestFixtures.parseSimulationContext(xml, processFactory)
	return MockSimulationContext(defaultContext)
}
