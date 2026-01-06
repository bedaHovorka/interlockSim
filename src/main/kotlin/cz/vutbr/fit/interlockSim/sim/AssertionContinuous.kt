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

/**
 * Base of simple validation feature for continuous variables and processes
 *
 */
abstract class AssertionContinuous : Continuous() {
	companion object {
		private var assertionEnabled = false
		private var assertionForced = true

		init {
			assert(
				run {
					assertionEnabled = true
					true
				}
			)
			if (assertionForced && !assertionEnabled) {
				throw RuntimeException("Please enable assertion facility.")
			}
		}
	}

	protected final override fun derivatives() {
		if (!check()) {
			val sb = StringBuilder(time().toString()).append(" : ")
			val msg = report(sb as StringBuilder)
			assert(false) { msg.toString() }
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

	override fun start(): AssertionContinuous = if (assertionEnabled) super.start() as AssertionContinuous else this
}
