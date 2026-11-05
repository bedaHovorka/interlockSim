/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B exit calibration (#591 part 1, Issue #1346): the low-traffic seeded exit test.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The low-traffic exit test of Goal 1B (#591 part 1, Issue #1346): one repetition per seed of
 * [LowTrafficExitScenario.LOW_TRAFFIC_SEEDS] (repetition `i` runs seed `i`), each asserting only
 * the functional exit contract of [LowTrafficExitScenario.assertFunctional].
 *
 * Tagged `integration-test` since #591 part 2 (the flip): it is the Goal 1B exit gate (B8) and runs
 * in every `integrationTest` gate and in the CI integration-test step. Run it alone with:
 * ```
 * ./gradlew :core:integrationTest --tests '*LowTrafficExitCalibrationTest*'
 * ```
 *
 * No wall time is asserted: the 15 s per run / 3 min total budget is measured on a quiet machine
 * from the logged `wall=` values. The `@Timeout` is a safety ceiling only; `SEPARATE_THREAD` makes
 * it fire even while the CPU-bound simulation never checks the interrupt flag.
 */
@Tag("integration-test")
@DisplayName("MultiTrainLoop — low-traffic exit gate (Goal 1B B8, integration test)")
class LowTrafficExitCalibrationTest : KoinTestBase() {
	private companion object {
		/** Safety ceiling per seed, not a budget (the budget is 15 s per run, measured). */
		private const val SAFETY_CEILING_SECONDS: Long = 120L
	}

	@RepeatedTest(10, name = "seed {currentRepetition} of {totalRepetitions}")
	@Timeout(value = SAFETY_CEILING_SECONDS, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun lowTrafficSeedMeetsTheFunctionalExitContract(repetition: RepetitionInfo) {
		val seeds = LowTrafficExitScenario.LOW_TRAFFIC_SEEDS
		val seed = seeds[repetition.currentRepetition - 1]
		LowTrafficExitScenario.logger.info { "Exit calibration seed list $seeds; running seed $seed" }

		val run = LowTrafficExitScenario.run(seed, getKoin().get<SimulationProcessFactory>())

		LowTrafficExitScenario.assertFunctional(run)
	}
}
