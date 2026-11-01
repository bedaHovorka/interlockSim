/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP6.2 (#1166): curated Praha pairs stay within the SP4 caps.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.component.get
import java.util.stream.Stream

/**
 * Each curated Praha pair of [FixtureRoutingInvariantsTest.PRAHA_CURATED_PAIRS] (Issue #1166, SP6.2),
 * driven by two trains through [MultiTrainLoop] as in [PrahaBoundedRouteSearchTest], makes at most
 * [MAX_RESERVE_PATH_CALLS] `reservePath` calls inside [WALL_BUDGET_MILLIS] and both trains exit.
 */
@Tag("integration-test")
class PrahaCuratedPairsBoundedSearchTest : CommonKoinTestBase() {
	/** Counts the `reservePath` calls that reach [delegate]. */
	private class CountingReservationService(
		private val delegate: PathReservationService
	) : PathReservationService by delegate {
		var reservePathCalls: Int = 0
			private set

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			reservePathCalls++
			return delegate.reservePath(trainId, start, target, maxDepth)
		}
	}

	private fun praha(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, get()).tracked()

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("pairs")
	fun `two trains on a curated Praha pair stay within the SP4 caps`(
		entry: String,
		exit: String
	) {
		val ctx = praha()
		ctx.getInOuts() // initialize dynamic wrappers
		val counting = CountingReservationService(ctx.getRoutingServices().getPathReservationService())
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(entry, exit, 0.0, TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec(entry, exit, SECOND_TRAIN_IN_TIME, TRAIN_LENGTH)
					),
				pathReservationService = counting,
				maxEntryAttempts = ENTRY_ATTEMPT_CAP
			)
		ctx.setMainProcess(loop)

		val startNanos = System.nanoTime()
		ctx.run()
		val wallMillis = (System.nanoTime() - startNanos) / 1_000_000

		assertThat(loop.getEntryFailures(), name = "entry failures").isEmpty()
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(2)
		assertThat(counting.reservePathCalls, name = "reservePath calls").isLessThanOrEqualTo(MAX_RESERVE_PATH_CALLS)
		assertThat(wallMillis, name = "wall millis").isLessThanOrEqualTo(WALL_BUDGET_MILLIS)
	}

	companion object {
		/** Goal 1B B5 cap on `reservePath` calls for one Praha pair. */
		const val MAX_RESERVE_PATH_CALLS: Int = 50

		/** Goal 1B B5 wall-time cap for one Praha pair. */
		const val WALL_BUDGET_MILLIS: Long = 10_000L

		/** Entry attempts per train; far above [MAX_RESERVE_PATH_CALLS] so the cap never hides a breach. */
		const val ENTRY_ATTEMPT_CAP: Int = 1000

		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 40.0
		const val SECOND_TRAIN_IN_TIME: Double = 2.0

		@JvmStatic
		fun pairs(): Stream<Arguments> =
			FixtureRoutingInvariantsTest.PRAHA_CURATED_PAIRS
				.map { (entry, exit) -> Arguments.of(entry, exit) }
				.stream()
	}
}
