/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Metrics integration tests - end-to-end KPI collection during a real kDisco run
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.sim.Interlocking
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration test for [MetricsCollectionService] during a real kDisco simulation.
 *
 * Unlike [DefaultMetricsCollectionServiceTest] (which drives `handleBlockEvent` directly with
 * synthetic events), this test runs a full [ShuntingLoop] simulation on `vyhybna.xml` and
 * verifies that the metrics service — wired through the `init{}` → `onBlockEvent` →
 * `pendingBlockEventListeners` → `wireCustomEventListeners` subscription chain — collects
 * non-trivial KPIs from the real event stream.
 *
 * This is the highest-value coverage addition for SP1: it guards the subscription wiring
 * that all synthetic-event unit tests bypass, and that SP2–SP4 will depend on.
 *
 * Run with: `./gradlew :core:integrationTest --tests "*MetricsIntegrationTest*"`
 *
 * @since Issue #672 (Goal 6 SP1)
 */
@DisplayName("Metrics collection (Integration)")
@Tag("integration-test")
class MetricsIntegrationTest : KoinTestBase() {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Standard simulation end time. Per `ShuntingLoopSmokeTest`:
		 * Train #1 completes ~49.5s, Train #2 ~101.5s. 120s yields ≥1 completion.
		 */
		private const val STANDARD_END_TIME = 120L

		/** Trains of the journey-record run (Issue #673); few enough that every one finishes. */
		private const val JOURNEY_RUN_TRAINS = 4

		/** End time of the journey-record run: ample for [JOURNEY_RUN_TRAINS] trains to exit. */
		private const val JOURNEY_RUN_END_TIME = 600L

		/**
		 * Wrong exits of the journey-record run: none. The synchronous dispatcher never reverses a
		 * train on `vyhybna.xml`, so each leaves through the InOut its timetable names (measured
		 * 2026-10-31: four trains B→A, A→B, B→A, B→A).
		 */
		private const val EXPECTED_WRONG_EXITS = 0
	}

	private fun createConfiguredSimulation(endTime: Long): SimulationContext {
		val factory = getKoin().get<SimulationContextFactory>()
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(
					factory.createContext(stream)
				)
			}
		// Initialize dynamic wrapper map (mirrors ShuntingLoopSmokeTest).
		val loop = prepareShuntingLoop(context, endTime)
		return context
	}

	/**
	 * Issue #673 (Goal 6 SP2): a full [ShuntingLoop] run leaves one journey record per train, with
	 * its entry, admission and exit, and the wrong-exit flag. The run generates a bounded number of
	 * trains and ends long after the last one has left, so every journey is complete; the loop's own
	 * counters are read only to cross-check the records.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `a full ShuntingLoop run leaves one journey record per train`() {
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(
					getKoin().get<SimulationContextFactory>().createContext(stream)
				)
			}
		val loop = prepareShuntingLoop(context, JOURNEY_RUN_END_TIME, maxTrains = JOURNEY_RUN_TRAINS)
		val metrics = context.getMetricsServices().getMetricsCollectionService()

		context.run()

		val journeys = metrics.journeys()
		journeys.forEach { logger.info { "Journey record: $it" } }
		assertThat(journeys.size, "one record per train").isEqualTo(JOURNEY_RUN_TRAINS)
		assertThat(journeys.map { it.trainId }.toSet().size, "distinct trains").isEqualTo(JOURNEY_RUN_TRAINS)
		assertThat(journeys.size, "records versus trains entered").isEqualTo(loop.getTrainsEntered())
		assertThat(journeys.count { it.exitTime != null }, "exited records").isEqualTo(loop.getTrainsExited())
		for (journey in journeys) {
			val entryTime = assertThat(journey.entryTime, "${journey.trainId} entry time").isNotNull()
			assertThat(journey.entryInOut, "${journey.trainId} entry InOut").isNotNull()
			assertThat(journey.plannedExit, "${journey.trainId} planned exit").isNotNull()
			assertThat(journey.actualExit, "${journey.trainId} actual exit").isNotNull()
			val admission = requireNotNull(journey.admissionTime) { "${journey.trainId} was never admitted" }
			val exit = requireNotNull(journey.exitTime) { "${journey.trainId} never exited" }
			entryTime.isLessThanOrEqualTo(admission)
			assertThat(admission, "${journey.trainId} admission before exit").isLessThanOrEqualTo(exit)
			assertThat(journey.wrongExit, "${journey.trainId} wrong exit")
				.isEqualTo(journey.actualExit != journey.plannedExit)
		}
		assertThat(journeys.count { it.wrongExit }, "wrong exits").isEqualTo(EXPECTED_WRONG_EXITS)
	}

	/**
	 * Verify that the metrics service is wired into the simulation event stream and
	 * collects non-trivial KPIs over a full ShuntingLoop run.
	 *
	 * - `totalBlocks == 4` proves the `env.getGraph()` path works end-to-end through
	 *   the real subscription (vyhybna.xml has 4 track blocks: k1, k2, kA, kB).
	 * - `completedTrains >= 1` proves BlockReserved → BlockReleased → completion
	 *   counting works on real events (Train #1 completes ~49.5s).
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `metrics service collects non-trivial KPIs over a real ShuntingLoop run`() {
		val context = createConfiguredSimulation(endTime = STANDARD_END_TIME)

		// Resolve the service before run() so the init{} subscription is buffered
		// into pendingBlockEventListeners (also exercises the force-init path in run()).
		val metrics = context.getMetricsServices().getMetricsCollectionService()

		context.run()

		val snap = metrics.getSnapshot()
		logger.info { "Post-run metrics snapshot: $snap" }

		// totalBlocks must match the real env graph size — proves the
		// env.getGraph() path works end-to-end through the subscription wiring.
		assertThat(snap.totalBlocks).isEqualTo(context.getGraph().size())
		assertThat(snap.totalBlocks).isGreaterThan(0)
		// Train #1 completes at ~49.5s, so at least one train has completed.
		assertThat(snap.completedTrains).isGreaterThan(0)
	}

	/**
	 * Issue #1280, end to end: a contention still open when the run ends reaches the metrics
	 * through [DefaultSimulationContext]'s end-of-run flush, and is counted as
	 * [MetricsSnapshot.unresolvedAtRunEndCount] — not as a [MetricsSnapshot.conflictCount], which
	 * stays for mid-run double-bookings. Same scenario as `ReservationConflictWarningTest`'s
	 * end-of-run flush test: one train holds A→B, a second is refused and the run stops.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `a contention open at run end is counted as unresolved and not as a conflict`() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		ctx.use {
			val inOuts = ctx.getInOuts()
			val reservationService = ctx.getRoutingServices().getPathReservationService()
			val metrics = ctx.getMetricsServices().getMetricsCollectionService()
			val process =
				object : Interlocking(ctx) {
					override suspend fun iteration() {
						val inA = inOuts.first { it.name == "A" }
						val inB = inOuts.first { it.name == "B" }
						assertThat(reservationService.reservePath("TrainBusy", inA, inB))
							.isInstanceOf(PathReservationService.ReservationResult.Success::class)
						hold(2.0)
						assertThat(reservationService.reservePath("TrainWaiting", inA, inB))
							.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)
						env.stop()
					}

					override suspend fun interLoopSleep() {
						terminate()
					}
				}
			ctx.setMainProcess(process)

			ctx.run()

			val snap = metrics.getSnapshot()
			assertThat(snap.conflictCount).isZero()
			assertThat(snap.unresolvedAtRunEndCount).isEqualTo(1)
		}
	}
}
