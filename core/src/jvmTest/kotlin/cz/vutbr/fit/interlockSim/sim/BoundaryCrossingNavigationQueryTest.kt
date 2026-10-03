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
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.HOLD_AT_SEPARATOR_END_TIME
import cz.vutbr.fit.interlockSim.testutil.HOLD_AT_SEPARATOR_TRAIN_LENGTH
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runReservedSingleTrainScenario
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `Train.Site.actions()` asks navigation for the reserved path right after the front (or tail)
 * crosses a separator, to publish the section being entered, and the next loop iteration asked
 * the same question for the same separator again with nothing in between that could change the
 * answer (Issue #963). The first answer is now carried into the next iteration.
 *
 * The scenario runs one train `B` → `A` over `vyhybna.xml` with its whole route reserved up
 * front, so no query is ever answered with a wait unless a test injects one. It counts the
 * queries made from `vB`, the mid-leg switch five metres past `zB`: a switch is not a signal, so
 * `separatorAction` does not query navigation there, and every query from `vB` comes from the
 * loop itself or from its wait condition. The front and the tail each cross `vB` once.
 */
@DisplayName("One reserved-path query per block-boundary crossing (Issue #963)")
class BoundaryCrossingNavigationQueryTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `each crossing of a mid-leg switch queries navigation once`() {
		val run = runBToA { null }

		// The whole train left the network, so front and tail both crossed the switch.
		assertThat(run.trainsExited, name = "trains exited").isEqualTo(1)
		assertThat(
			run.queriesBySeparator[MID_LEG_SWITCH],
			name = "queries from $MID_LEG_SWITCH in ${run.queriesBySeparator}"
		).isEqualTo(CROSSINGS_OF_SWITCH)
	}

	/**
	 * The first query from `doA1` is the one right after the front crosses it, so its answer is
	 * the carried one. A carried `OwnershipConflict` must be used once: the train stops and waits,
	 * and every later query is asked fresh. If the carried answer outlived the wait, the loop would
	 * reuse the conflict forever at one frozen simulated instant — the separate-thread timeout
	 * turns that busy loop into a failure instead of a hang.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a carried ownership conflict is used once and the retry asks navigation fresh`() {
		val baseline = runBToA { null }.queriesBySeparator.getValue(MID_LEG_SIGNAL)
		val injected = AtomicInteger()
		val run =
			runBToA { separator ->
				if (separatorLabel(separator) == MID_LEG_SIGNAL && injected.getAndIncrement() == 0) {
					PathResult.OwnershipConflict
				} else {
					null
				}
			}

		assertThat(run.trainsExited, name = "trains exited after the wait").isEqualTo(1)
		// On top of the clean run: at least the wait's check and the loop's fresh retry.
		assertThat(run.queriesBySeparator.getValue(MID_LEG_SIGNAL), name = "queries from $MID_LEG_SIGNAL")
			.isGreaterThanOrEqualTo(baseline + 2)
	}

	private class Run(
		val trainsExited: Int,
		val queriesBySeparator: Map<String, Int>
	)

	/**
	 * Runs one train `B` → `A` with its whole route reserved and counts navigation queries per
	 * separator. [override] answers a query instead of the real service when it returns non-null.
	 */
	private fun runBToA(override: (PathSeparator) -> PathResult?): Run {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val queriesBySeparator = ConcurrentHashMap<String, Int>()
		val run =
			runReservedSingleTrainScenario(
				context,
				inName = ORIGIN,
				outName = DESTINATION,
				endTime = HOLD_AT_SEPARATOR_END_TIME,
				trainLength = HOLD_AT_SEPARATOR_TRAIN_LENGTH,
				findReservedPath = { realNav, trainId, separator ->
					queriesBySeparator.merge(separatorLabel(separator), 1, Int::plus)
					override(separator) ?: realNav.findReservedPathForTrain(trainId, separator)
				}
			)
		return Run(run.process.getTrainsExited(), queriesBySeparator)
	}

	private companion object {
		const val ORIGIN = "B"
		const val DESTINATION = "A"

		/** The mid-leg switch on the `zB`→`doA1` leg of the B→A route. */
		const val MID_LEG_SWITCH = "vB"

		/**
		 * The signal after [MID_LEG_SWITCH]. An injected wait goes here, not to the switch: a front
		 * stopped at `vB` in this scenario never started again (only `semaphoreAction` commands the
		 * engine), and a reserved route never ends at a switch in a real run.
		 */
		const val MID_LEG_SIGNAL = "doA1"

		/** Front and tail each cross [MID_LEG_SWITCH] once. */
		const val CROSSINGS_OF_SWITCH = 2
	}
}
