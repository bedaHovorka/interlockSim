/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests the minimum inter-cycle wall-clock spacing that makes `tickPeriodMs` a live grid axis
 * (SP2c.24, Issue #847).
 *
 * Before this, `tickPeriodMs` was written into every run JSON as the `-1` "not applicable"
 * sentinel: its only consumer was [DispatchTickLoop], which production never constructs.
 *
 * @since Issue #847 (SP2c.24 — parameter grid)
 */
@DisplayName("SP2c.24 — AgentLoopDriver minimum cycle period (#847)")
@Timeout(30, unit = TimeUnit.SECONDS)
class AgentLoopDriverTickPeriodTest {
	/**
	 * Simulated time must advance between cycles, or the driver's polling-mode stale-tick guard
	 * short-circuits every cycle after the first and never reaches the PACE step at all.
	 */
	private var simTime = 0.0

	private val perceptionPort: NetworkPerceptionPort =
		mockk(relaxed = true) {
			every { snapshot() } answers {
				simTime += 1.0
				SimulationSnapshot(
					simTime = simTime,
					semaphores = emptyList(),
					blocks = emptyList(),
					trainPositions = emptyList(),
					timetables = emptyList()
				)
			}
		}
	private val planner: DispatcherPlanner =
		mockk(relaxed = true) { coEvery { plan(any()) } returns listOf(DispatchDecision.NoAction) }

	// #926: pin currentSpeedMultiplier() to 1.0 — a relaxed mock defaults Double to 0.0, which
	// would zero out the wall-time subtraction in the throttle delta and mask the fix.
	private val controller: SimulationController =
		mockk(relaxed = true) { every { currentSpeedMultiplier() } returns 1.0 }

	private fun driver(tickPeriodMs: Long) =
		AgentLoopDriver(
			perceptionPort = perceptionPort,
			planner = planner,
			commandQueue = ActuatorCommandQueue(),
			controller = controller,
			tickPeriodMs = tickPeriodMs
		)

	@Test
	@DisplayName("consecutive cycles are held at least tickPeriodMs apart")
	fun spacingIsHonoured() {
		val period = 120L
		val driver = driver(period)

		val elapsedMs =
			runBlocking {
				driver.runCycle() // first cycle establishes the baseline; nothing to space against
				val start = System.nanoTime()
				driver.runCycle()
				driver.runCycle()
				(System.nanoTime() - start) / 1_000_000L
			}

		// Two spaced cycles after the baseline.
		assertThat(elapsedMs).isGreaterThanOrEqualTo(2 * period)
	}

	@Test
	@DisplayName("the default of 0 imposes nothing, so an unset grid paces exactly as before #847")
	fun zeroPeriodImposesNothing() {
		val driver = driver(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS)

		val elapsedMs =
			runBlocking {
				val start = System.nanoTime()
				repeat(5) { driver.runCycle() }
				(System.nanoTime() - start) / 1_000_000L
			}

		// Five no-op cycles against mocked ports; anything approaching a real period would show up
		// immediately. Generous bound so a loaded CI machine cannot make this flaky.
		assertThat(elapsedMs).isLessThan(1_000L)
	}
}
