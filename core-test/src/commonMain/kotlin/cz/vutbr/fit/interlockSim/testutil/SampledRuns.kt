/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: run a simulation with a per-frame sampling callback
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener

/**
 * Runs [context] to completion with [onFrame] attached to every report event in
 * [reportTypes] and to every [BlockEvent].
 *
 * This is the listener wiring the sampling regression tests spelled out four times
 * (core `TrainFrontBoundaryStateTest`; desktop `StraightRun`/`RedSignalWait`/`Boundary`
 * heading tests): register a property-change listener filtered to the interesting report
 * types, register a block listener, `run()`, and always remove both listeners. The
 * removal matters — the base classes close `testContext`, and a listener left on a closed
 * context turns one test's noise into the next test's failure.
 *
 * [onFrame] receives the report message for a report event, or `null` for a block event.
 * A test that counts arrivals can pass the message straight to [ArrivalTally.record]:
 *
 * ```kotlin
 * val arrivals = ArrivalTally()
 * runSampled(context, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) { message ->
 *     message?.let(arrivals::record)
 *     sampleTrains()
 * }
 * ```
 *
 * The callbacks run on the kDisco simulation thread, inside `run()` — the same thread that
 * mutates the trains, so the sampled state needs no synchronisation.
 *
 * @param context the context to run; must already have its main process set
 * @param reportTypes the report types that trigger a frame; every other report is ignored
 * @param onFrame called once per accepted report event and once per block event
 */
fun runSampled(
	context: SimulationContext,
	reportTypes: Set<ReportType> = setOf(ReportType.TRAIN_EVENTS),
	onFrame: (message: String?) -> Unit
) {
	val acceptedNames = reportTypes.map { it.name }.toSet()
	val reportListener =
		ContextPropertyChangeListener { event ->
			if (event.propertyName !in acceptedNames) return@ContextPropertyChangeListener
			onFrame(event.newValue?.toString().orEmpty())
		}
	val blockListener =
		object : BlockEventListener {
			override fun onBlockEvent(event: BlockEvent) {
				onFrame(null)
			}
		}

	context.addPropertyChangeListener(reportListener)
	context.addBlockEventListener(blockListener)
	try {
		context.run()
	} finally {
		context.removePropertyChangeListener(reportListener)
		context.removeBlockEventListener(blockListener)
	}
}
