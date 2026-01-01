/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;

import jDisco.Process;

/**
 * Cycle proces...
 * because desription behaviour with only while(true) in actions in Proces is bad idea
 * this class can inter iteration terminate process
 */
public abstract class LoopProcess extends Process {
	private boolean terminate = false;

	@Override
	protected final void actions() {
		startAction();
		while (true) {
			if (terminate) break;
			iteration();
			if (terminate) break;
			interLoopSleep();
		}
		byTerminateAction();
	}

	protected void startAction() {
		// EMPTY
	}

	protected void byTerminateAction() {
		//EMPTY
	}

	protected abstract void iteration();

	protected void interLoopSleep() {
		passivate();
	}

	/**
	 * safe canceling of process
	 */
	public final void terminate() {
		terminate = true;
		if (!terminated()) Process.activate(this);
	}
}
