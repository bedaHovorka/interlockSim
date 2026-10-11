/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP4 (#1151), acceptance B5: a Praha pair stays within fixed route-search limits.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Tag
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Acceptance B5 of Goal 1B (Issue #1151) on `praha-hlavni-nadrazi.xml`: a train that waits for a
 * busy Praha route makes at most [MAX_RESERVE_PATH_CALLS] `reservePath` calls and the scenario
 * stays within [WALL_BUDGET_MILLIS] of wall time, and the waiting does not repeat the route
 * search: every `(in, out)` pair is enumerated once per navigator.
 *
 * The scenario already met both caps on the Goal 1B base 9953beb4, before the route cache (25
 * calls, 0.61 s): a Praha pair was never expensive on the switch-constrained search, see
 * `docs/goal9b-demands/SP4-bounded-route-search.md`. This class is a regression guard. The
 * entry-attempt cap is [ENTRY_ATTEMPT_CAP], far above [MAX_RESERVE_PATH_CALLS], so the call-count
 * assertion fails on its own when waiting gets slower, instead of being satisfied by the cap.
 */
@Tag("integration-test")
class PrahaBoundedRouteSearchTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		/** Goal 1B B5 cap on `reservePath` calls for one Praha pair. */
		const val MAX_RESERVE_PATH_CALLS: Int = 50

		/** Goal 1B B5 wall-time cap for one Praha pair. */
		const val WALL_BUDGET_MILLIS: Long = 10_000L

		/** Entry attempts per train; far above [MAX_RESERVE_PATH_CALLS] so the cap never hides a breach. */
		const val ENTRY_ATTEMPT_CAP: Int = 1000

		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 40.0
		const val SECOND_TRAIN_IN_TIME: Double = 2.0
		const val ENTRY = "N-Lib-1"
		const val EXIT = "S-Vin-1"

		/** A Praha pair with topological paths but no switch-legal route (SP1 doc, #1179). */
		const val IMPOSSIBLE_ENTRY = "N-Bypass"
		const val IMPOSSIBLE_EXIT = "S-Vrs-3"
	}

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

	@Test
	fun blockedTrainOnABusyPrahaPairStaysWithinTheCaps() {
		val ctx = praha()
		ctx.getInOuts() // initialize dynamic wrappers
		val counting = CountingReservationService(ctx.getRoutingServices().getPathReservationService())
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(ENTRY, EXIT, 0.0, TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec(ENTRY, EXIT, SECOND_TRAIN_IN_TIME, TRAIN_LENGTH)
					),
				pathReservationService = counting,
				maxEntryAttempts = ENTRY_ATTEMPT_CAP
			)
		ctx.setMainProcess(loop)

		val startNanos = System.nanoTime()
		ctx.run()
		val wallMillis = (System.nanoTime() - startNanos) / 1_000_000
		val navigator = ctx.getRoutingServices().getTopologyNavigator() as DefaultTopologyNavigator

		logger.info {
			"Praha $ENTRY -> $EXIT, two trains: reservePath calls=${counting.reservePathCalls}, " +
				"wall=$wallMillis ms, enumerations=${navigator.routeEnumerationCount}, " +
				"cached pairs=${navigator.routeCacheSize}"
		}
		assertThat(loop.getEntryFailures(), name = "entry failures").isEmpty()
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(2)
		assertThat(counting.reservePathCalls, name = "reservePath calls").isLessThanOrEqualTo(MAX_RESERVE_PATH_CALLS)
		assertThat(wallMillis, name = "wall millis").isLessThanOrEqualTo(WALL_BUDGET_MILLIS)
		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(navigator.routeCacheSize)
	}

	@Test
	fun impossiblePrahaPairAnswersNoPathWithinTheCaps() {
		val ctx = praha()
		val service = ctx.getRoutingServices().getPathReservationService()
		val inOuts =
			ctx
				.getInOuts()
				.map { ctx.toDynamic(it) }
				.filterIsInstance<DynamicInOut>()
				.associateBy { it.name }
		val entry = inOuts.getValue(IMPOSSIBLE_ENTRY)
		val exit = inOuts.getValue(IMPOSSIBLE_EXIT)

		val startNanos = System.nanoTime()
		repeat(MAX_RESERVE_PATH_CALLS) {
			assertThat(service.reservePath("t1", entry, exit))
				.isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
		}
		val wallMillis = (System.nanoTime() - startNanos) / 1_000_000
		val navigator = ctx.getRoutingServices().getTopologyNavigator() as DefaultTopologyNavigator

		logger.info { "Praha $IMPOSSIBLE_ENTRY -> $IMPOSSIBLE_EXIT: $MAX_RESERVE_PATH_CALLS calls, wall=$wallMillis ms" }
		assertThat(wallMillis, name = "wall millis").isLessThanOrEqualTo(WALL_BUDGET_MILLIS)
		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(1)
	}
}
