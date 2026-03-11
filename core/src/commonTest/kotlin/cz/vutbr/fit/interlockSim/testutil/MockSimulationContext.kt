/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test Utility: Mock Simulation Context (core module version)

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import org.koin.java.KoinJavaComponent.getKoin
import java.io.InputStream

/**
 * Mock implementation of SimulationContext for testing simulation components
 * without requiring jDisco framework initialization.
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

fun createMockSimulationContext(): MockSimulationContext {
	val editingFactory = getKoin().get<EditingContextFactory>()
	val simulationFactory = getKoin().get<SimulationContextFactory>()
	val editingContext = editingFactory.createEmptyContext()
	val defaultContext = simulationFactory.createContext(editingContext) as DefaultSimulationContext
	return MockSimulationContext(defaultContext)
}

fun createMockSimulationContext(xml: InputStream): MockSimulationContext {
	val simulationFactory = getKoin().get<SimulationContextFactory>()
	val defaultContext = simulationFactory.createContext(xml) as DefaultSimulationContext
	return MockSimulationContext(defaultContext)
}
