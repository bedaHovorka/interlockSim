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

import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.hovorka.kdisco.Continuous

/**
 * Base class for validating continuous variable invariants during simulation.
 *
 * Extends jDisco's Continuous to check state invariants at each integration step.
 * Subclasses implement [check] to define invariant conditions and [report] for diagnostics.
 *
 * **Warning:** The [derivatives] method still uses `assert()` internally. Invariant violations
 * will only be detected if assertions are enabled (-ea JVM flag). This is a legacy limitation
 * from jDisco integration. Consider migrating to explicit exception throwing for production safety.
 *
 * @see check The method defining the invariant condition
 * @see report The method providing diagnostic information on invariant violations
 */
abstract class ContinuousInvariantChecker : Continuous() {
	protected final override fun derivatives() {
		requireSimulation(check()) {
			val sb = StringBuilder(time().toString()).append(" : ")
			val msg = report(sb as StringBuilder)
			msg.toString()
		}
	}

	/**
	 * @param reportObj
	 * @return Must return same reportObj back
	 */
	abstract fun report(reportObj: StringBuilder): StringBuilder

	/**
	 * This method describe conditions, which mean valid state
	 * @return condition result
	 */
	abstract fun check(): Boolean

	override fun start(): ContinuousInvariantChecker = super.start() as ContinuousInvariantChecker
}
