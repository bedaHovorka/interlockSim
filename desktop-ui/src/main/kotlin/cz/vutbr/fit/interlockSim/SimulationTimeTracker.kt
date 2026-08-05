/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.sim.SimulationEvent

/**
 * Records the highest simulated time seen on the context's event stream (Issue #847 round 3).
 *
 * [cz.vutbr.fit.interlockSim.sim.TextReporter] already tracks this, but its `lastSimTime` is
 * private and it lives in `core/`, which PR #891 leaves untouched. This listener reads the same
 * [SimulationEvent] stream in `:desktop-ui` instead, so [Main.runExample] can compare reached
 * against requested simulated time and classify the run via [RunCompletionCheck].
 *
 * Takes the maximum rather than the last value seen: nothing in the listener contract guarantees
 * events arrive in simulated-time order, and a single out-of-order tail event would otherwise make
 * a completed run look truncated.
 *
 * @since Issue #847 (round 3)
 */
class SimulationTimeTracker : ContextPropertyChangeListener {
	/** Highest simulated time observed so far; `0.0` if no simulation event has arrived. */
	var lastSimTime: Double = 0.0
		private set

	override fun propertyChange(event: ContextChangeEvent) {
		val simEvent = SimulationEvent.fromContextChangeEvent(event) ?: return
		if (simEvent.simulationTime > lastSimTime) {
			lastSimTime = simEvent.simulationTime
		}
	}
}
