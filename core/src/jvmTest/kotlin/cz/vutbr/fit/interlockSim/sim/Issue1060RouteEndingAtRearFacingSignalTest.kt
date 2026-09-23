/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1060: a route that ends at a signal facing away from the train must be extended.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * A train's stored route runs past its next signal but ends at a signal that faces away from it
 * (`B → doB1` for a B → A train: `doB1` faces east). Navigation cannot build a leg out of such a
 * route and holds the train at the signal with an ownership conflict, "wait for the route to be
 * extended" (Issue #940). Nobody used to extend it: the rule engine skipped the input because the
 * route already extends beyond the signal (`pathAlreadyExtendedBeyond`), and the LLM was told
 * "route already set" (Issue #1060).
 *
 * G8 (Issue #1064) no longer lets a dispatcher grant such a route, so the state is produced here
 * by cutting the stored PathInfo of a legitimate B → A train back to `doB1` before the train
 * reaches `zB`. The rule dispatcher, the only one running, must notice the stand and extend the
 * route from `zB`, and the train must go on and leave the station.
 */
@Tag("integration-test")
@DisplayName("A route ending at a rear-facing signal is extended for the train standing before it (#1060)")
class Issue1060RouteEndingAtRearFacingSignalTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		const val END_TIME = 300L
		const val REAR_FACING_END = "doB1"

		/** Distance to the signal ahead (m) at or under which a train counts as standing at it. */
		const val STANDING_AT_SIGNAL_TOLERANCE = 1e-6
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the rule dispatcher extends the route from zB and the train leaves")
	fun ruleDispatcherExtendsTheRouteAndTheTrainLeaves() {
		val context = loadVyhybnaContext().tracked()
		context.getInOuts()
		val registry = context.scope.get<PathReservationRegistry>()

		val loop = ShuntingLoop(context, endTime = END_TIME)
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		// The cut train, the cut route's original rear-facing target, and the ordered
		// milestones the fix must produce: the train stands at its signal first, only then
		// does the route extend, and the train itself finishes its journey.
		var cutTrain: Train? = null
		var cutOriginalTarget: DynamicPathSeparator? = null
		var cutTrainStoodAtSignal = false
		var routeExtendedWhileStanding = false
		loop.controlStepListener =
			ControlStepListener {
				if (cutTrain == null) {
					cutTrain = cutRouteBackToRearFacingEnd(context, loop, registry)
					cutOriginalTarget = cutTrain?.let { registry.getPathInfo(it.name)?.target }
				}
				val cut = cutTrain
				if (cut != null && !routeExtendedWhileStanding) {
					val pathInfo = registry.getPathInfo(cut.name)
					if (pathInfo != null && cut.distanceToSignalAhead() <= STANDING_AT_SIGNAL_TOLERANCE) {
						cutTrainStoodAtSignal = true
					}
				}
				wired?.onControlStep()
				if (cutTrainStoodAtSignal && cut != null) {
					// The extension is identified by the TARGET ELEMENT changing, not by a name:
					// the final target is the InOut `A`, whose name-of is `null` — a `null`
					// comparison would not distinguish "extended to A" from "not extended".
					val pathInfo = registry.getPathInfo(cut.name)
					if (pathInfo != null && pathInfo.target != cutOriginalTarget) {
						routeExtendedWhileStanding = true
					}
				}
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(cutTrain, name = "a B -> A train whose route was cut back to $REAR_FACING_END").isNotNull()
		assertThat(cutTrainStoodAtSignal, name = "the cut train stood at its signal").isTrue()
		assertThat(routeExtendedWhileStanding, name = "route extended past $REAR_FACING_END while standing").isTrue()
		assertThat(cutTrain!!.terminated(), name = "the cut train itself finished its journey").isTrue()
	}

	/**
	 * Cuts the PathInfo of the first westbound train (destination `A`) whose route already runs
	 * past [REAR_FACING_END] back to it. Returns the train, or `null` when there is none yet.
	 */
	private fun cutRouteBackToRearFacingEnd(
		context: DefaultSimulationContext,
		loop: ShuntingLoop,
		registry: PathReservationRegistry
	): Train? {
		for (train in loop.getApprovedTrains()) {
			if (train.timetableDestinationName != "A") continue
			val info = registry.getPathInfo(train.name) ?: continue
			val elements = info.reservedPath.toList()
			val endIndex = elements.indexOfFirst { nameOf(it) == REAR_FACING_END }
			if (endIndex < 0 || endIndex == elements.lastIndex) continue
			val kept = elements.subList(0, endIndex + 1)
			val keptBlocks = kept.filterIsInstance<DynamicTrackBlock>().toSet()
			val cut =
				info.copy(
					target = kept.last() as DynamicPathSeparator,
					reservedPath = ArrayPath(context).apply { kept.forEach { add(it) } },
					entryDirections = info.entryDirections.filterKeys { it in keptBlocks }
				)
			registry.restorePathInfo(train.name, cut)
			releaseBlocksBeyond(context, train.name, elements.subList(endIndex + 1, elements.size))
			return train
		}
		return null
	}

	/** Frees the blocks the cut-off tail held, as the partial release of an abandoned tail does. */
	private fun releaseBlocksBeyond(
		context: DefaultSimulationContext,
		trainId: String,
		tail: List<PathElement>
	) {
		val service = context.getRoutingServices().getPathReservationService()
		val blocks = tail.filterIsInstance<DynamicTrackBlock>()
		for (block in blocks) {
			block.reservedFrom?.let { block.cancelPathSetup(it) }
			service.unregisterBlock(trainId, block)
		}
	}

	private fun nameOf(element: PathElement): String? =
		when (element) {
			is DynamicRailSemaphore -> element.name
			else -> null
		}

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
}
