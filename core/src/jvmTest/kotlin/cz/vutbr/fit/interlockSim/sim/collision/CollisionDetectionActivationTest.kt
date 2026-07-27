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
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationController
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
 * Regression test proving [DefaultCollisionDetectionService] activates during
 * [DefaultSimulationContext.run] even when the caller never registers an
 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.onCollisionWarning] listener and
 * never otherwise touches [DefaultSimulationContext.getCollisionServices] before calling `run`.
 *
 * This mirrors every headless/CLI entry point (`Main.loadSim`/`Main.runExample`, fast-sim),
 * none of which register a collision warning listener. Before the fix, `run()` only forced
 * the `collisionDetectionServiceInstance` lazy when `pendingCollisionWarningListeners` was
 * non-empty, so the service's `init {}` block (which subscribes to `onBlockEvent`/
 * `onSimulationEvent`) never ran and detection was silently inert.
 *
 * @since Issue #611 (Goal 3), code-review follow-up
 */
@Tag("integration-test")
@DisplayName("Collision detection activation — headless run() with zero pre-registered listeners")
class CollisionDetectionActivationTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private class ObservingController : SimulationController {
		var requestPauseCalled: Boolean = false

		override suspend fun awaitIfPaused() {
			// No-op: never paused
		}

		override fun throttle(simDeltaSeconds: Double) {
			// No-op: no wall-clock pacing
		}

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() {
			requestPauseCalled = true
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("A CRITICAL BlockEntryViolation still triggers auto-pause with no listener registered before run()")
	fun collisionDetectionActivatesWithoutPreRegisteredListener() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx

		// Deliberately do NOT call ctx.getCollisionServices(), onCollisionWarning(), or
		// registerHaltCallback() here — any of those would force the lazy through a different
		// path and mask the bug under test. The only interaction with the context before run()
		// is reading a real DynamicTrackBlock to build the warning payload.
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

		val controller = ObservingController()
		ctx.run(controller)

		// autoPauseOnCritical defaults to true and BlockEntryViolation is CRITICAL severity,
		// so requestPause() must have been invoked — proving DefaultCollisionDetectionService
		// was constructed and its init{} block subscribed to simulation events before the
		// warning was emitted.
		assertThat(controller.requestPauseCalled).isTrue()
	}
}
