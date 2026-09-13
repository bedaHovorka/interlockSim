/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Reflective access to a train's motor process for lifecycle assertions.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * The train's `Motor`, reached by reflection.
 *
 * `Motor` is a private inner class with no public accessor, and it must stay that way — a test
 * that needs its lifecycle state (`isPassivated()`, `isTerminated()`, `terminate()`) must not
 * push a hook into `sim/` production code just to be observable. One helper instead of a private
 * copy per test class.
 */
fun motorOf(train: Train): Process {
	val field = Train::class.java.getDeclaredField("motor")
	field.isAccessible = true
	return field.get(train) as Process
}
