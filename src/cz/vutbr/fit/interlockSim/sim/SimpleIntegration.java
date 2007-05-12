/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;

import jDisco.Continuous;
import jDisco.Variable;

/**
 * Substitute for "SIMLIB Integrator"
 *
 */
public final class SimpleIntegration extends Continuous {
	private final Variable x;
	private final Variable dx;
	
	/**
	 * @param x integrator output
	 * @param dx integrator input
	 */
	public SimpleIntegration(final Variable x, final Variable dx) {
		this.x = x;
		this.dx = dx;
	}

	@Override
	protected void derivatives() {
		x.rate = dx.state;
	}
}