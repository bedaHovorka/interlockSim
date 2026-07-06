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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
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
		context.getInOuts()
		context.setMainProcess(ShuntingLoop(context, endTime))
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
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
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
}
