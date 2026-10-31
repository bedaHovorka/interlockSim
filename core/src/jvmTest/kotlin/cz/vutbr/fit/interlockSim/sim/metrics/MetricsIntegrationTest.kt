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
