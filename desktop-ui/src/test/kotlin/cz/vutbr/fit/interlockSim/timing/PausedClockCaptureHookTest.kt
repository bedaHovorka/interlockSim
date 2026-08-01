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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * SP2c.26 (Issue #849) evidence — **AC1: the F1 deadlock cause, documented**.
 *
 * Issue #849 asks whether the projector's sim-thread capture can still refresh while the
 * simulation is paused for inference. It cannot, and this test pins down why.
 *
 * ## The mechanism
 *
 * `DefaultSimulationContext.advanceControlledStep` calls
 * [SimulationController.awaitIfPaused][cz.vutbr.fit.interlockSim.context.SimulationController.awaitIfPaused]
 * from kDisco's **before-event** hook. A pause therefore parks the kernel *before* it runs the
 * next event, so [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.iteration] never executes — and with
 * it neither `snapshotCaptureHook` nor
 * [DispatcherObservationProjector.captureOnSimThread][cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector.captureOnSimThread].
 *
 * ## Consequence for the F1 contract
 *
 * Any F1 implementation that pauses the clock and then *waits for a fresh observation* deadlocks:
 * the only thing that could publish that observation is the thread the pause just parked. F1 is
 * therefore viable only while intra-tick re-validation stays on the optimistic in-process
 * projection SP2c.5 chose (`DispatchTickLoop.applyOptimistically`) — see
 * `PausedClockFreshCaptureDeadlockTest` for the executable form of that boundary.
 *
 * The second test is the control: it rules out the alternative explanation that the simulation had
 * simply ended, by showing the very same run resumes publishing once the pause is released.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@DisplayName("F1 paused-clock spike — snapshot-capture hook under pause (#849)")
@Timeout(60, unit = TimeUnit.SECONDS)
class PausedClockCaptureHookTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@Test
	@DisplayName("AC1: the capture hook is starved while the simulation is paused")
	fun captureHookIsStarvedWhilePaused() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		try {
			harness.start()
			val tickBeforePause = harness.awaitTick(minTick = 2L)
			assertThat(tickBeforePause).isGreaterThanOrEqualTo(2L)

			harness.runner.isPaused = true
			Thread.sleep(PAUSE_OBSERVATION_MILLIS)

			// No new tick across several ticks' worth of wall-clock: captureOnSimThread() cannot run,
			// because the thread that would call it is parked in awaitIfPaused().
			assertThat(harness.latest().tick).isEqualTo(tickBeforePause)
		} finally {
			harness.stop()
		}
	}

	@Test
	@DisplayName("AC1 control: publishing resumes once the pause is released")
	fun captureHookResumesAfterUnpause() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		try {
			harness.start()
			val tickBeforePause = harness.awaitTick(minTick = 2L)

			harness.runner.isPaused = true
			Thread.sleep(PAUSE_OBSERVATION_MILLIS)
			val tickWhilePaused = harness.latest().tick

			harness.runner.isPaused = false

			// The run was alive all along — starvation was caused by the pause, not by the sim ending.
			assertThat(harness.awaitTick(minTick = tickWhilePaused + 1L)).isGreaterThan(tickBeforePause)
		} finally {
			harness.stop()
		}
	}

	companion object {
		/**
		 * How long to hold the pause before sampling the projector. At the harness's 10x speed a
		 * running simulation publishes a tick roughly every 100 ms, so 750 ms is several ticks'
		 * worth of opportunity — long enough that "no new tick" cannot be mistaken for slowness.
		 */
		private const val PAUSE_OBSERVATION_MILLIS: Long = 750L
	}
}
