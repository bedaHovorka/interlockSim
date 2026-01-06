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

import cz.vutbr.fit.interlockSim.context.SimulationContext;

/**
 * control facility
 */
public abstract class Interlocking extends LoopProcess {
	private final SimulationContext context;

	protected Interlocking(SimulationContext context) {
		this.context = context;
	}

	//EXTENSION hledani cest, Dikstruv algoritmus, move to control
	//public abstract void setupWay(OrientedPathSeparator from, OrientedPathSeparator to);

	protected SimulationContext getContext() {
		return context;
	}
}
