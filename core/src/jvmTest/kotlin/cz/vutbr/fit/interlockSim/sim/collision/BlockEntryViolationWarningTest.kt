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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration tests for [CollisionWarning.BlockEntryViolation] delivery and
 * [DefaultCollisionDetectionService.autoHaltTrainOnViolation] behaviour (#613).
 *
 * ## Acceptance Criteria
 * - [CollisionWarning.BlockEntryViolation] emitted via `emitCustom` (the emit-before-throw
 *   path in [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter]) is received
 *   by the [DefaultCollisionDetectionService] and forwarded to registered listeners.
 * - When [DefaultCollisionDetectionService.autoHaltTrainOnViolation] is `true`, the halt
 *   callback registered for the entering train is called.
 * - When [DefaultCollisionDetectionService.autoHaltTrainOnViolation] is `false` (default),
 *   no halt callback is called.
 *
 * @since Issue #613 (Goal 3 SP3)
 */
@Tag("integration-test")
@DisplayName("BlockEntryViolation warning delivery — #613")
class BlockEntryViolationWarningTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private fun singleTrainContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		context = ctx
		return ctx
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("BlockEntryViolation emitted via emitCustom is delivered to collision warning listeners")
	fun blockEntryViolationDeliveredToListeners() {
		val ctx = singleTrainContext()
		val received = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { received.add(it) }

		// Capture a real DynamicTrackBlock to use in the warning payload.
		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		val warning =
			CollisionWarning.BlockEntryViolation(
				trainId = "train-A",
				block = block,
				time = 0.0
			)

		// Simulate the emit-before-throw pattern: a process emits the BlockEntryViolation
		// via emitCustom (exactly as DynamicTrackBlock.enter() does before throwing).
		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(warning)
					terminate()
				}
			}
		)
		ctx.run()

		assertThat(received).containsExactly(warning)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("autoHaltTrainOnViolation=true invokes the registered halt callback")
	fun autoHaltCallbackIsInvokedWhenEnabled() {
		val ctx = singleTrainContext()
		val received = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { received.add(it) }

		val service = ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		service.autoHaltTrainOnViolation = true

		var haltCalled = false
		service.registerHaltCallback("train-A") { haltCalled = true }

		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		val warning =
			CollisionWarning.BlockEntryViolation(
				trainId = "train-A",
				block = block,
				time = 0.0
			)

		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(warning)
					terminate()
				}
			}
		)
		ctx.run()

		assertThat(received).containsExactly(warning)
		assertThat(haltCalled).isTrue()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("autoHaltTrainOnViolation=false (default) does not invoke the halt callback")
	fun haltCallbackNotCalledWhenDisabled() {
		val ctx = singleTrainContext()

		val service = ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		// autoHaltTrainOnViolation defaults to false — no explicit assignment needed.

		var haltCalled = false
		service.registerHaltCallback("train-A") { haltCalled = true }

		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		val warning =
			CollisionWarning.BlockEntryViolation(
				trainId = "train-A",
				block = block,
				time = 0.0
			)

		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(warning)
					terminate()
				}
			}
		)
		ctx.run()

		assertThat(haltCalled).isFalse()
	}
}
