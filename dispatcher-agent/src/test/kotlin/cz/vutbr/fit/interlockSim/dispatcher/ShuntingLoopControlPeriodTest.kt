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
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Pins the **control period** of `ShuntingLoop` at **2.0 simulated seconds** (Issue #834, SP2c.11).
 *
 * ## Why this test exists
 *
 * `LoopProcess.actions()` runs `iteration()` and *then* `interLoopSleep()`. `ShuntingLoop.iteration()`
 * ends in `hold(1.0)` and `ShuntingLoop.interLoopSleep()` holds another `1.0`, so one full control
 * cycle advances simulated time by 2.0 — not the 1.0 that several comments and one KDoc in this
 * repository used to assert. Everything paced off the control step inherits that factor of two:
 * `ControlStepListener` callbacks, `DispatchDecisionApplier.onControlStep`, and
 * `OrphanReservationSweeper.sweep` (whose 60.0-simulated-second staleness window is therefore a
 * window of 30 sweeps, not 60).
 *
 * The independent corroboration already recorded in the repository is
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.DecisionRateReport]'s "301 control ticks over
 * 600 s" — exactly `600 / 2 + 1`.
 *
 * ## Why it lives in `:dispatcher-agent` and not `:core`
 *
 * Issue #834's task 9 was scoped to comment-only changes under `core/`, so the pinning test was
 * placed alongside the dispatcher-agent components that actually depend on the period. The module
 * already compiles against `ShuntingLoop` (see [LiftedStackFixture]), so nothing extra is needed.
 *
 * ## Why no dispatcher is wired
 *
 * The period is a property of the loop's own pacing and is independent of what the control step
 * *does*. Leaving the listener as a pure recorder keeps the measurement free of any dispatcher
 * behaviour.
 *
 * @since Issue #834 (SP2c.11 — Goal 10)
 */
@DisplayName("ShuntingLoop control period is 2.0 simulated seconds (#834)")
@Tag("integration-test")
class ShuntingLoopControlPeriodTest : DispatcherKoinTestBase() {
	private val fixture = LiftedStackFixture()

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("control steps fire at t = 0, 2, 4, … — one every 2.0 simulated seconds")
	fun controlStepPeriodIsTwoSimulatedSeconds() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = END_TIME)
		val stepTimes = mutableListOf<Double>()
		// Runs on the kDisco simulation thread only — no synchronisation needed.
		loop.controlStepListener = ControlStepListener { stepTimes.add(loop.time()) }

		context.setMainProcess(loop)
		context.run()

		// endTime = 20 -> steps at 0, 2, 4, …, 20. A 1.0-second control period would have produced
		// 21 steps at every integer instead.
		assertThat(stepTimes).isEqualTo((0..END_TIME.toInt() step 2).map { it.toDouble() })
	}

	private companion object {
		/** Short horizon: long enough to show the cadence, short enough to stay a fast test. */
		const val END_TIME: Long = 20L
	}
}
