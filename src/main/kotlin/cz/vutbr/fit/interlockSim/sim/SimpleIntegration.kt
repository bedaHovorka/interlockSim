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

import jDisco.Continuous
import jDisco.Variable

/**
 * Substitute for "SIMLIB Integrator"
 * Integrates dx.state into x.state using jDisco continuous simulation.
 *
 * @param x integrator output (position)
 * @param dx integrator input (velocity)
 */
class SimpleIntegration(
	private val x: Variable,
	private val dx: Variable
) : Continuous() {
	override fun derivatives() {
		x.rate = dx.state
	}
}
