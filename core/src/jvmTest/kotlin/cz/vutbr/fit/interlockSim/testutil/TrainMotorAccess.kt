/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Reflective access to a train's engine process for lifecycle assertions.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.sim.Engine
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * The train's [Engine], reached by reflection.
 *
 * [Engine] is package-internal and held as a private field on [Train] with no public accessor —
 * a test that needs its lifecycle state (`isPassivated()`, `isTerminated()`, `terminate()`) must
 * not push a hook into `sim/` production code just to be observable. One helper instead of a
 * private copy per test class.
 *
 * Issue #1059 renamed the former private inner `Motor` to top-level [Engine]; the field name is
 * `engine`.
 */
internal fun engineOf(train: Train): Engine {
	val field = Train::class.java.getDeclaredField("engine")
	field.isAccessible = true
	return field.get(train) as Engine
}

/**
 * Lifecycle view of the train's engine as a kDisco [Process].
 *
 * Prefer [engineOf] when the test needs the [Engine] type; this alias keeps existing call sites
 * that only need process lifecycle checks.
 */
fun motorOf(train: Train): Process = engineOf(train)
