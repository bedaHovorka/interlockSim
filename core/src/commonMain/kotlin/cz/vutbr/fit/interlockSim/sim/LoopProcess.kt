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

import cz.hovorka.kdisco.engine.DiscoException
import cz.hovorka.kdisco.engine.Process

/**
 * Cycle process...
 * Because describing behaviour with only while(true) in actions in Process is a bad idea,
 * this class allows inter-iteration termination of the process.
 */
abstract class LoopProcess : Process() {
	private var terminate = false

	final override suspend fun actions() {
		startAction()
		while (true) {
			if (terminate) break
			iteration()
			if (terminate) break
			interLoopSleep()
		}
		byTerminateAction()
	}

	protected open suspend fun startAction() {
		// EMPTY
	}

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
				// Called from outside simulation context (e.g., EDT during stop());
				// simScope.cancel() handles cleanup when the simulation ends.
			}
		}
	}
}
