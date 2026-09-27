/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * One reserved-path query per block-boundary crossing (Issue #963)
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.runSimpleLinearTrackScenario
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `Train.Site.actions()` asks navigation for the reserved path right after the front (or tail)
 * crosses a separator, to publish the section being entered, and the next loop iteration asked
 * the same question for the same separator again with nothing in between that could change the
 * answer (Issue #963). The first answer is now carried into the next iteration.
 *
 * The scenario runs one train `B` → `A` over `vyhybna.xml` with its whole route reserved up
 * front, so no query is ever answered with a wait and the loop never retries. It counts the
 * queries made from `vB`, the mid-leg switch five metres past `zB`: a switch is not a signal, so
 * `separatorAction` does not query navigation there, and every query from `vB` comes from the
 * loop itself. The front and the tail each cross `vB` once, so one query per crossing is two.
 */
@DisplayName("One reserved-path query per block-boundary crossing (Issue #963)")
class BoundaryCrossingNavigationQueryTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `each crossing of a mid-leg switch queries navigation once`() {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val inOuts = context.getInOuts().toList()
		val origin = inOuts.single { it.name == ORIGIN }
		val destination = inOuts.single { it.name == DESTINATION }
		val reservationService = context.getRoutingServices().getPathReservationService()
		val realNav = context.getRoutingServices().getTrainNavigationService()

		val queriesBySeparator = ConcurrentHashMap<String, Int>()
		val countingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->
				queriesBySeparator.merge(separatorLabel(separator), 1, Int::plus)
				realNav.findReservedPathForTrain(trainId, separator)
			}

		val run =
			runSimpleLinearTrackScenario(
				context,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						SimpleLinearTrackTestProcess.TrainSpec(
							inName = ORIGIN,
							outName = DESTINATION,
							inTime = 1.0,
							outTime = END_TIME.toDouble(),
							length = TRAIN_LENGTH
						)
					),
				env = NavigationDecoratingContext(context, countingNav)
			) { train ->
				assertReservationSuccess(reservationService.reservePath(train.name, origin, destination))
			}

		// The whole train left the network, so front and tail both crossed the switch.
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
		assertThat(queriesBySeparator[MID_LEG_SWITCH], name = "queries from $MID_LEG_SWITCH in $queriesBySeparator")
			.isEqualTo(CROSSINGS_OF_SWITCH)
	}

	private companion object {
		const val ORIGIN = "B"
		const val DESTINATION = "A"

		/** The mid-leg switch on the `zB`→`doA1` leg of the B→A route. */
		const val MID_LEG_SWITCH = "vB"

		/** Front and tail each cross [MID_LEG_SWITCH] once. */
		const val CROSSINGS_OF_SWITCH = 2

		const val END_TIME = 90L
		const val TRAIN_LENGTH = 20.0
	}
}
