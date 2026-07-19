/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * 1000-iteration determinism test for ShuntingLoop at endTime=1024.
 * Heavy variant — run only after changes to simulation logic to verify stability.
 * See CLAUDE.md "heavyTest" section for when to run this.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Heavy-weight 1000-iteration determinism test for the ShuntingLoop scenario at endTime=1024.
 *
 * This test is tagged [@Tag("heavy-test")] and is **excluded from regular `test`
 * and `integrationTest` builds**. Run it deliberately with:
 * ```
 * ./gradlew :core:heavyTest
 * ```
 *
 * **When to run:** After changes to simulation logic (path reservation, train
 * physics, kDisco event scheduling) to detect deadlocks, race conditions, or
 * non-determinism that may appear only under many repeated runs.
 *
 * **Expected outcome (verified by manual run):** with the fixed-seed train
 * generator (`Generator.random = Random(0L)`) and this branch's `kdisco 0.6.1-SNAPSHOT`
 * `waitCrossing`-based block-boundary detection, a 1024s run against vyhybna.xml
 * always produces exactly 28 trains entered and 14 fully exited.
 *
 * **These counts are cross-platform identical** and must match the native
 * [cz.vutbr.fit.interlockSim.fastsim.ShuntingLoopDeterminismTest] (same seed, same
 * construction path via `wireSynchronousDispatcher`). kDisco's `Random.exp()`/`.normal()`
 * historically diverged between JVM and native builds because they routed the
 * (bit-identical) raw uniform draws through platform-delegating `kotlin.math.ln`/`exp`;
 * that was fixed upstream by a pure-Kotlin fdlibm `PortableMath` implementation:
 * https://github.com/bedaHovorka/kdisco/issues/69.
 *
 * For the shorter integration-test variants (120s/300s) see [ShuntingLoopSmokeTest].
 */
@Tag("heavy-test")
@DisplayName("ShuntingLoop — 1000-iteration heavy determinism test (endTime=1024)")
class ShuntingLoopHeavyTest : KoinTestBase() {
	private companion object {
		/** Simulation end time for each run. */
		const val END_TIME: Long = 1024L

		/** Expected trains generated into the queue by t=1024s (fixed-seed generator). */
		const val EXPECTED_TRAINS_ENTERED: Int = 28

		/** Expected trains that fully exited the system by t=1024s. */
		const val EXPECTED_TRAINS_EXITED: Int = 14
	}

	private val factory: SimulationContextFactory by inject()

	/**
	 * Single ShuntingLoop run repeated 1000 times.
	 *
	 * Acceptance criteria per run:
	 * - Exactly 28 trains entered (generated into the queue).
	 * - Exactly 14 trains fully exited the system.
	 * - Run completes within the per-run timeout → no deadlock.
	 */
	@RepeatedTest(1000)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("ShuntingLoop(1024s) run is deterministic: 28 entered / 14 exited")
	fun eachRunProducesDeterministicOutcome() {
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
			}
		context.use { ctx ->
			ctx.getInOuts()
			val loop = ShuntingLoop(ctx, endTime = END_TIME)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)
			ctx.run()

			assertThat(loop.getTrainsEntered(), name = "trains entered")
				.isEqualTo(EXPECTED_TRAINS_ENTERED)
			assertThat(loop.getTrainsExited(), name = "trains exited")
				.isEqualTo(EXPECTED_TRAINS_EXITED)
		}
	}
}
