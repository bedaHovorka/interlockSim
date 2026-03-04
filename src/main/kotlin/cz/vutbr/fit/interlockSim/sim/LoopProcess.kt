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

import cz.hovorka.kdisco.Continuous
import cz.hovorka.kdisco.Process

/**
 * Cycle process...
 * Because describing behaviour with only while(true) in actions in Process is a bad idea,
 * this class allows inter-iteration termination of the process.
 */
abstract class LoopProcess : Continuous() {
	override fun derivatives() {}
	private var terminate = false

	final override fun actions() {
		startAction()
		while (true) {
			if (terminate) break
			iteration()
			if (terminate) break
			interLoopSleep()
		}
		byTerminateAction()
	}

	protected open fun startAction() {
		// EMPTY
	}

	protected open fun byTerminateAction() {
		// EMPTY
	}

	protected abstract fun iteration()

	protected open fun interLoopSleep() {
		passivate()
	}

	/**
	 * Safe cancellation of process
	 */
	final override fun terminate() {
		terminate = true
		if (!terminated()) Process.activate(this)
	}
}
