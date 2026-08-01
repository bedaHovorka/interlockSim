/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.timing

import assertk.assertThat
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Tag("integration-test")
@DisplayName("F1 paused-clock spike — pause/resume overhead per tick (#849)")
@Timeout(120, unit = TimeUnit.SECONDS)
class PauseResumeOverheadTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@Test
	@DisplayName("AC3: the pause/resume control-primitive round trip costs under 1 ms at p99")
	fun pauseResumePrimitiveOverhead() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		try {
			harness.start()
			harness.awaitTick(minTick = 3L)

			val primitiveMicros = mutableListOf<Long>()
			repeat(CYCLES) {
				val startedAt = System.nanoTime()
				harness.runner.isPaused = true
				harness.runner.isPaused = false
				primitiveMicros += (System.nanoTime() - startedAt) / NANOS_PER_MICRO
			}

			val p99 = percentile(primitiveMicros, PERCENTILE_99)
			logger.info {
				"Pause/resume primitive over $CYCLES cycles — " +
					"mean: ${primitiveMicros.average()} us, p99: $p99 us, max: ${primitiveMicros.max()} us"
			}

			assertThat(p99).isLessThan(MICROS_PER_MILLI)
		} finally {
			harness.stop()
		}
	}

	private fun percentile(
		samples: List<Long>,
		fraction: Double
	): Long {
		val sorted = samples.sorted()
		val index = ((sorted.size - 1) * fraction).roundToInt()
		return sorted[index]
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Enough samples for a meaningful p99 without materially lengthening the run. */
		private const val CYCLES: Int = 200

		private const val PERCENTILE_99: Double = 0.99
		private const val NANOS_PER_MICRO: Long = 1_000L
		private const val MICROS_PER_MILLI: Long = 1_000L
	}
}
