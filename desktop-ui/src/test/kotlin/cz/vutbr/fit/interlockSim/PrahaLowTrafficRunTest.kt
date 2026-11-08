/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * One headless 600 s run of the Praha rule-based control arm (Goal 1B SP10.2, Issue #1366).
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Runs the console `prahaHlavniNadrazi` example (the rule-based control arm, dispatcher-agent
 * stack, no Ollama) for 600 simulated seconds with a fixed generator seed and checks the low
 * traffic of the traffic-simulation-expert ruling: all 8 trains are generated, at most 4 are
 * admitted at once, the run ends (no deadlock holds it past the JUnit timeout), and at least
 * [MIN_EXITED] trains exit within the 600 s.
 *
 * Tagged `heavy-test` per the traffic-simulation-expert ruling: it runs only via
 * `:desktop-ui:heavyTest`, manually, as the SP10 measurement — never as a `test` or
 * `integrationTest` gate in CI.
 */
@Tag("heavy-test")
@DisplayName("Praha low-traffic control arm: one 600 s run (#1366, #1376)")
class PrahaLowTrafficRunTest : KoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		const val SEED: Long = 1366L
		const val END_TIME: String = "600"
		const val TRAINS: Int = 8
		const val LOW_TRAFFIC_CAP: Int = 4

		/**
		 * The ruling keeps 8 trains only while about 6 or more of them finish within 600 s (else it
		 * falls back to 6 trains). Measured on 2026-11-05 with this seed: all 8 exited, peak 4
		 * admitted, 0 failed reservations, about 18 s wall time. Re-measured on 2026-11-08 after the
		 * arms were restricted to switch-legal endpoint pairs (Issue #1376), which changes the pairs
		 * this seed draws: all 8 exited (the last at 375 s), peak 4 admitted, 0 failed reservations,
		 * about 13 s wall time, so the bound stays 6.
		 */
		const val MIN_EXITED: Int = 6

		/**
		 * `Train`'s tail reports this TRAIN_EVENTS text when the train leaves by another exit than its
		 * timetable's. Counted and logged, not asserted: the 2026-11-08 run (Issue #1376) had 3 such
		 * reports (trains 4, 6 and 7; 4 on the base before #1376). Every generated pair has a
		 * switch-legal route now, yet a train can still leave by another exit of the right side, both
		 * through `ReservationTargetPolicy.pick`'s fallback: its own exit is not among the exits one
		 * section ahead of the platform track it was routed to (trains 4 and 7), or it is busy past
		 * the own-exit wait bound (train 6). The count therefore changes no verdict yet.
		 */
		const val WRONG_OUT_SUFFIX: String = " ends in wrong out"
	}

	override fun getTestModule(): Module = testModuleFull

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun `eight trains enter, at most four at a time, and the run ends`() {
		val name = ExampleNames.PRAHA_HLAVNI_NADRAZI
		val factory = get<ExampleRegistry>().examples.getValue(name)
		val ctx = (factory(get(), arrayOf("example", name, END_TIME, SEED.toString())) as DefaultSimulationContext).tracked()
		val loop = ctx.mainProcess as ShuntingLoop

		var wrongOutReports = 0
		val startMs = System.currentTimeMillis()
		runSampled(ctx) { message ->
			if (message?.endsWith(WRONG_OUT_SUFFIX) == true) wrongOutReports++
		}
		val wallMs = System.currentTimeMillis() - startMs
		logger.info {
			"Praha low-traffic run: seed=$SEED entered=${loop.getTrainsEntered()} exited=${loop.getTrainsExited()} " +
				"maxConcurrent=${loop.getMaxConcurrentTrains()} failedReservations=${loop.getFailedReservations()} " +
				"wrongOutReports=$wrongOutReports wall=${wallMs}ms"
		}

		assertThat(loop.getTrainsEntered(), "trains generated").isEqualTo(TRAINS)
		assertThat(loop.getMaxConcurrentTrains(), "peak admitted trains").isLessThanOrEqualTo(LOW_TRAFFIC_CAP)
		assertThat(loop.getTrainsExited(), "trains exited within 600 s").isGreaterThanOrEqualTo(MIN_EXITED)
	}
}
