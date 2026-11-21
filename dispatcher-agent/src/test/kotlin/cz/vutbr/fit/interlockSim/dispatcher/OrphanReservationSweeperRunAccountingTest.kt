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
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The orphan sweeper's stale-route releases reach the metrics' run-end accounting as events
 * (Issue #1304, Goal 6 SP6). `OrphanReservationSweeperTest` runs the sweeper outside a simulation,
 * where the announcement is a no-op, so only a running context can show it.
 *
 * A whole un-travelled route is planted before the run for an owner the sweeper's queue port reports
 * as queued, so the sweeper treats it as a known train's stale route. Its threshold is set below the
 * kernel's reservation TTL, so the sweeper, not the kernel expiry, releases it.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
@DisplayName("OrphanReservationSweeper stale releases are counted by the metrics from events")
@Tag("integration-test")
class OrphanReservationSweeperRunAccountingTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a stale whole-route release inside a run is counted by the metrics`() {
		val context = LiftedStackFixture().loadShuntingLoopContext().tracked()
		val inOuts = context.getInOuts()
		val planted =
			context.getRoutingServices().getPathReservationService().reservePath(
				OWNER,
				context.toDynamic(inOuts.single { it.name == "A" }),
				context.toDynamic(inOuts.single { it.name == "B" })
			)
		assertThat(planted, name = "planted route").isInstanceOf<PathReservationService.ReservationResult.Success>()

		val loop = ShuntingLoop(context, END_TIME)
		val perception = DefaultNetworkPerceptionPort(context, loop::getApprovedTrains)
		val queue = mockk<DispatchLoopSensorPort>()
		every { queue.getQueuedTrains() } returns listOf(QueuedTrainObservation(OWNER, "B"))
		val sweeper =
			OrphanReservationSweeper(
				perceptionPort = perception,
				dispatchLoopSensorPort = queue,
				actuatorPort = DefaultNetworkActuatorPort(context),
				staleAfterSimSeconds = STALE_AFTER_SIM_SECONDS
			)
		loop.snapshotCaptureHook = perception::captureSnapshot
		loop.controlStepListener = ControlStepListener { sweeper.sweep() }
		context.setMainProcess(loop)

		context.run()

		val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
		assertThat(sweeper.staleReleaseCount, name = "stale releases by the sweeper").isGreaterThan(0)
		assertThat(summary.staleReleaseCount, name = "stale releases the metrics counted")
			.isEqualTo(sweeper.staleReleaseCount)
		assertThat(summary.expiredHoldingCount, name = "the kernel expiry did not take it first").isEqualTo(0)
	}

	private companion object {
		const val OWNER = "Queued train"

		/** Well below the kernel's 60 s reservation TTL, so the sweeper acts first. */
		const val STALE_AFTER_SIM_SECONDS = 10.0
		const val END_TIME = 40L
	}
}
