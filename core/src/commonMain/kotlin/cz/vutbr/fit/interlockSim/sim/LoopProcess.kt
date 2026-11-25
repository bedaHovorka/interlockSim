/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.DiscoException
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.isProcessControl
import cz.vutbr.fit.interlockSim.util.isPlatformInterrupt
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Cycle process...
 * Because describing behaviour with only while(true) in actions in Process is a bad idea,
 * this class allows inter-iteration termination of the process.
 */
abstract class LoopProcess : Process() {
	private companion object {
		private val logger = KotlinLogging.logger {}
	}

	private var terminate = false

	/**
	 * The plain failure that ended this process, or `null` while it runs or when it ended normally
	 * (Issue #1361). kDisco runs every process under a supervisor job and reports a process that threw
	 * like one that ended normally, so the simulation context reads this to end a run whose main
	 * process died, which would otherwise go on with the processes left. The net covers any
	 * non-control Throwable, an [Error] included, so a death by Error (an OOM was the origin scenario
	 * of Issue #1361, PR #1419) is recorded too. kDisco's process control ([isProcessControl]) and a
	 * thread interrupt ([Throwable.isPlatformInterrupt], a GUI stop) are not failures.
	 */
	internal var failure: Throwable? = null
		private set

	final override suspend fun actions() {
		try {
			startAction()
			while (true) {
				if (terminate) break
				iteration()
				if (terminate) break
				interLoopSleep()
			}
			byTerminateAction()
		} catch (e: Throwable) {
			// Recorded, then rethrown: kDisco still sees the failure as before.
			if (!isProcessControl(e) && !e.isPlatformInterrupt()) failure = e
			throw e
		}
	}

	// suspend needed: subclasses call suspend fns (e.g. interLoopSleep)
	@Suppress("RedundantSuspendModifier")
	protected open suspend fun startAction() {
		// EMPTY
	}

	// suspend needed: subclasses may call hold(), passivate() etc.
	@Suppress("RedundantSuspendModifier")
	protected open suspend fun byTerminateAction() {
		// EMPTY
	}

	protected abstract suspend fun iteration()

	protected open suspend fun interLoopSleep() {
		passivate()
	}

	/**
	 * Safe cancellation of process
	 */
	final override fun terminate() {
		terminate = true
		if (!terminated()) {
			try {
				Process.activate(this)
			} catch (_: DiscoException) {
				logger.debug { "terminate(): activate() failed outside simulation — will exit at next iteration check" }
			}
		}
	}
}
