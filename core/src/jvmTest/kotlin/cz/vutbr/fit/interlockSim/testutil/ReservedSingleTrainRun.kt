/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * One train with its whole route reserved, seeing a decorated navigation service.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.SimpleLinearTrackTestProcess
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * Runs one train [inName] → [outName] over [context] with its whole route reserved at creation,
 * while the simulation asks [findReservedPath] instead of the real navigation service. The real
 * service is passed in as its first argument, so a decorator can fall back to it.
 *
 * This is the chain the navigation-injection tests repeat: they differ only in what the decorator
 * answers (a held separator, a counted or injected query) and in what else they start with the
 * train, which [onStart] adds after the reservation.
 *
 * Context lifetime stays with the caller: register it with `KoinTestBase.tracked()` — that
 * extension is protected, so this runner cannot own it.
 */
fun runReservedSingleTrainScenario(
	context: DefaultSimulationContext,
	inName: String,
	outName: String,
	endTime: Long,
	trainLength: Double,
	findReservedPath: (realNav: TrainNavigationService, trainId: String, separator: PathSeparator) -> PathResult,
	onStart: (Train) -> Unit = {}
): SimpleLinearTrackRun {
	val inOuts = context.getInOuts().toList()
	val origin = inOuts.single { it.name == inName }
	val destination = inOuts.single { it.name == outName }
	val reservationService = context.getRoutingServices().getPathReservationService()
	val realNav = context.getRoutingServices().getTrainNavigationService()
	val nav =
		decoratingTrainNavigationService(realNav) { trainId, separator ->
			findReservedPath(realNav, trainId, separator)
		}

	return runSimpleLinearTrackScenario(
		context,
		endTime = endTime,
		trainSpecs =
			listOf(
				SimpleLinearTrackTestProcess.TrainSpec(
					inName = inName,
					outName = outName,
					inTime = 1.0,
					outTime = endTime.toDouble(),
					length = trainLength
				)
			),
		env = NavigationDecoratingContext(context, nav)
	) { train ->
		assertReservationSuccess(reservationService.reservePath(train.name, origin, destination))
		onStart(train)
	}
}
