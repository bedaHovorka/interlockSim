/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.Station

/**
 * Validates that a loaded [SimulationEnvironment] derives a [Station] the
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop] process can run on.
 *
 * Required by Issue #435 — previously, loading an incompatible network failed confusingly
 * inside ShuntingLoop's init block. This validator fails fast with an actionable error before
 * ShuntingLoop is constructed.
 *
 * Since Issue #1162 it reads the topology through [Station] and applies the same rule as the
 * ShuntingLoop constructor ([ShuntingLoop.compatibilityIssues]) instead of fixed vyhybna grid
 * coordinates, so the two can no longer disagree.
 *
 * @since Issue #435
 */
internal object ShuntingLoopNetworkValidator {
	/**
	 * Checks whether the environment's network derives a station ShuntingLoop can run on.
	 *
	 * @return list of human-readable issue strings; empty when the network is compatible.
	 */
	fun validateShuntingLoopCompatible(context: SimulationEnvironment): List<String> =
		ShuntingLoop.compatibilityIssues(Station.from(context))
}
