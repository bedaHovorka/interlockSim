/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for the [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.onCollisionWarning]
 * subscription contract on [DefaultSimulationContext] (#611).
 *
 * ## Acceptance Criteria
 * - Listener registered **before** [DefaultSimulationContext.run] receives warnings delivered
 *   via [DefaultCollisionDetectionService.emitWarning].
 * - Listener registered **after** [DefaultSimulationContext.run] is silently ignored and
 *   receives nothing — same contract as [onBlockEvent].
 *
 * @since Issue #611 (Goal 3 SP1)
 */
@Tag("integration-test")
@DisplayName("CollisionWarning subscription — #611 event delivery")
class CollisionWarningSubscriptionTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private fun singleTrainContext(): DefaultSimulationContext {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	private fun runWithSingleTrain(ctx: DefaultSimulationContext) {
		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Listener registered before run() receives a manually emitted warning")
	fun listenerBeforeRunReceivesManuallyEmittedWarning() {
		val ctx = singleTrainContext()
		val received = mutableListOf<CollisionWarning>()

		// Register BEFORE run — must be wired into the service at run() time
		ctx.getCollisionServices().onCollisionWarning { received.add(it) }

		runWithSingleTrain(ctx)

		// Manually emit a warning via the internal test hook — no live collision required
		val service = ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		val warning =
			CollisionWarning.ReservationConflict(
				trainId = "T1",
				conflictingTrainId = "T2",
				time = 42.0
			)
		service.emitWarning(warning)

		assertThat(received).containsExactly(warning)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Listener registered after run() is silently ignored")
	fun listenerAfterRunIsIgnored() {
		val ctx = singleTrainContext()

		runWithSingleTrain(ctx)

		// Register AFTER run — must be silently ignored
		val received = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { received.add(it) }

		// Emit a warning; since the listener was registered after run(), it should receive nothing
		val service = ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		service.emitWarning(
			CollisionWarning.ReservationConflict(
				trainId = "T1",
				conflictingTrainId = "T2",
				time = 42.0
			)
		)

		assertThat(received).isEmpty()
	}
}
