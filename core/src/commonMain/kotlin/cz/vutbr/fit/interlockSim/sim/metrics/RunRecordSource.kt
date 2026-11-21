/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * The one contract through which a caller gets the [RunRecord] of a completed run (Issue #676,
 * Goal 6 SP5), without depending on `:desktop-ui` or on the run's main process:
 *
 * ```kotlin
 * val record = context.getMetricsServices().getRunRecordSource().runRecord()
 * ```
 *
 * The metrics service of the run fills the record when the run ends
 * ([cz.vutbr.fit.interlockSim.sim.RunEnded]), for every harness. Read it on the simulation thread
 * or after `run()` returned, while the context is still open.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
fun interface RunRecordSource {
	/**
	 * The record of the run, or `null` while the run has not ended (or never started).
	 *
	 * @since Issue #676 (Goal 6 SP5)
	 */
	fun runRecord(): RunRecord?
}
