/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

/**
 * The run's main process died of [cause], so the simulation context stopped the run early (Issue #1361).
 *
 * Built while the run is still active, when the main process dies; a caller observes it as the exception
 * `SimulationContext.run()` throws after the run end has been delivered, so the run record exists. Its
 * own type lets a caller tell a run that ended early from a run that failed to start.
 *
 * @param cause The failure that ended the main process.
 * @param mainProcess The main process that died.
 * @since Issue #1361
 */
class MainProcessFailedException(
	cause: Throwable,
	mainProcess: Any?
) : SimulationException(
		Severity.FATAL,
		"The main process failed, so the run ended early (Issue #1361)",
		cause,
		mainProcess
	)
