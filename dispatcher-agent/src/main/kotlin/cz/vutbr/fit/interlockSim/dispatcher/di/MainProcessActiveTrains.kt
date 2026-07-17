/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ApprovesTrains
import cz.vutbr.fit.interlockSim.sim.Train
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Returns the trains currently approved on [context]'s main process, or `emptyList()` if
 * there is no main process yet or it does not implement [ApprovesTrains].
 *
 * Shared by both the production and test Koin modules so the `activeTrains` supplier body
 * is defined exactly once (PR #769 review).
 */
fun mainProcessActiveTrains(context: DefaultSimulationContext): List<Train> {
	val mainProcess = context.getMainProcess() ?: return emptyList()
	val approver = mainProcess as? ApprovesTrains
	if (approver == null) {
		logger.debug {
			"Main process ${mainProcess::class.simpleName} does not implement ApprovesTrains; " +
				"activeTrains() will report emptyList()."
		}
		return emptyList()
	}
	return approver.getApprovedTrains()
}
