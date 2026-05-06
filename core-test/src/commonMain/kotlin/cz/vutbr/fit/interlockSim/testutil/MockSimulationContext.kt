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
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
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
	private var runListeners: List<ContextPropertyChangeListener> = emptyList()

	/** Number of times [close] has been called. Used by tests to verify scope cleanup. */
	var closeCount: Int = 0
		private set

	/**
	 * On-demand cache for [DynamicTrack] wrappers.
	 *
	 * [DefaultSimulationContext.toDynamic] requires [initializeDynamicMapping] to have
	 * been called first (normally inside [DefaultSimulationContext.run]). Tests that call
	 * [Frame.setContext] without actually running the simulation trigger the animation
	 * system which calls [toDynamic] before [run]. This cache creates wrappers on demand
	 * so tests do not need to call [run] first.
	 */
	private val dynamicTrackCache: MutableMap<TrackFacility, DynamicTrack> = mutableMapOf()

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

	override fun addPropertyChangeListener(listener: ContextPropertyChangeListener) {
		runListeners = runListeners + listener
		delegate.addPropertyChangeListener(listener)
	}

	override fun removePropertyChangeListener(listener: ContextPropertyChangeListener) {
		runListeners = runListeners - listener
		delegate.removePropertyChangeListener(listener)
	}

	override fun run() {
		stopped = false
		// Fire directly from runListeners so callers waiting on addPropertyChangeListener
		// (e.g. FrameSimulationLifecycleTest) are notified when simulation starts.
		// Cannot rely on delegate.freeze() because ContextTransformer already freezes the
		// delegate at creation time, making freeze() a no-op here.
		val event = ContextChangeEvent("frozen", false, true)
		runListeners.forEach { it.propertyChange(event) }
	}

	override fun close() {
		closeCount++
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

	/**
	 * Returns a [DynamicTrack] wrapper for [track], creating one on demand if needed.
	 *
	 * Delegate's map is only populated after [DefaultSimulationContext.run] calls
	 * [initializeDynamicMapping]. Tests that call [Frame.setContext] without starting
	 * the simulation trigger the animation system before [run], so the map is empty.
	 * This override falls back to an on-demand cache so tests remain independent of
	 * simulation startup order.
	 */
	override fun toDynamic(track: TrackFacility): DynamicTrack {
		return try {
			delegate.toDynamic(track)
		} catch (_: IllegalStateException) {
			// Expected: delegate map is empty before initializeDynamicMapping() runs (i.e.,
			// before DefaultSimulationContext.run()). Create a wrapper on demand for test use.
			val staticKey = (track as? DynamicTrackBlock)?.staticRef as? TrackFacility ?: track
			dynamicTrackCache.getOrPut(staticKey) { DynamicTrack(staticKey) }
		}
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
