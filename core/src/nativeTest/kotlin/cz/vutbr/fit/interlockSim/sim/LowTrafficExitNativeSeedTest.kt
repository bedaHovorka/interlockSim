/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B exit calibration (#591 part 1, Issue #1346): the native seed.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import org.koin.core.component.get
import kotlin.test.Test

/**
 * The native run of the Goal 1B exit calibration (#591 part 1, Issue #1346): the first seed of
 * [LowTrafficExitScenario.LOW_TRAFFIC_SEEDS] on `linuxX64`, with the functional exit assertions.
 *
 * It lives in `nativeTest` (created by `applyDefaultHierarchyTemplate()`), not in `commonTest`,
 * so it runs in `:core:linuxX64Test` only: the JVM runs every seed in the
 * integration test `LowTrafficExitCalibrationTest`. Untagged, so it runs in every gate and in the CI
 * `linuxX64Test` step; there is no `@Timeout` on Kotlin/Native, the CI step's own limit is the
 * ceiling. No wall time is asserted.
 *
 * **Cross-platform check:** the counts are pinned to the ones the JVM measured for the same seed
 * (`LowTrafficExitCalibrationTest`, seed 1, 2026-11-03). A native run that reaches different
 * counts from the same specs diverges from the JVM, which this test reports even when both runs
 * pass the functional assertions.
 */
class LowTrafficExitNativeSeedTest : CommonKoinTestBase() {
	private companion object {
		/** Trains seed 1 injects; the JVM run entered and exited all of them. */
		private const val JVM_SEED_ONE_TRAINS: Int = 18

		/** Peak concurrency of the JVM seed-1 run. */
		private const val JVM_SEED_ONE_MAX_CONCURRENT: Int = 3
	}

	@Test
	fun firstSeedMeetsTheFunctionalExitContractAndMatchesTheJvmCounts() {
		val seed = LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.first()

		val run = LowTrafficExitScenario.run(seed, get())

		LowTrafficExitScenario.assertFunctional(run)
		assertAll {
			assertThat(run.entered, name = "entered, native vs JVM seed $seed").isEqualTo(JVM_SEED_ONE_TRAINS)
			assertThat(run.exited, name = "exited, native vs JVM seed $seed").isEqualTo(JVM_SEED_ONE_TRAINS)
			assertThat(run.maxConcurrent, name = "max concurrent, native vs JVM seed $seed")
				.isEqualTo(JVM_SEED_ONE_MAX_CONCURRENT)
		}
	}
}
