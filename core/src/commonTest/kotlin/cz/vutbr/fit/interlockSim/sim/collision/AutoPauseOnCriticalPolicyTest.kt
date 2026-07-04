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
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Unit tests for the Goal 3 SP5 [DefaultCollisionDetectionService.autoPauseOnCritical] policy.
 *
 * These are purely headless tests (no simulation context, no kDisco engine) exercising the
 * emission machinery directly via [DefaultCollisionDetectionService.emitWarning].
 * They run in `commonTest` and execute under both JVM and `:fast-sim`/native targets.
 *
 * ## Acceptance criteria (Issue #615)
 * - A [CollisionWarning.Severity.CRITICAL] warning pauses the controller when
 *   [DefaultCollisionDetectionService.autoPauseOnCritical] is `true` (the default).
 * - Setting [DefaultCollisionDetectionService.autoPauseOnCritical] to `false` suppresses
 *   all pause requests.
 * - A [CollisionWarning.Severity.WARNING]-severity warning does **not** pause the controller
 *   even when `autoPauseOnCritical` is `true`.
 * - A no-op [PauseController] silently ignores pause requests without throwing.
 *
 * Tests that require a real [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock]
 * (needed for [CollisionWarning.BlockEntryViolation] payloads) are kept in the JVM-only
 * `BlockEntryViolationWarningTest` to avoid Koin/context setup overhead in commonTest.
 *
 * @since Issue #615 (Goal 3 SP5)
 */
class AutoPauseOnCriticalPolicyTest {
	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Counts [PauseController.requestPause] calls. */
	private class CountingPauseController : PauseController {
		var calls: Int = 0
			private set

		override fun requestPause() {
			calls++
		}
	}

	/** Silently ignores [PauseController.requestPause] — stand-in for NoOpSimulationController. */
	private object NoOpPauseController : PauseController {
		override fun requestPause() {
			// intentional no-op
		}
	}

	// ── autoPauseOnCritical = true (default) ─────────────────────────────────

	@Test
	fun `ReservationConflict with CRITICAL severity pauses controller when autoPauseOnCritical is true`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		// autoPauseOnCritical defaults to true; ReservationConflict defaults to CRITICAL.

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))

		assertThat(pauseController.calls).isEqualTo(1)
	}

	@Test
	fun `PredictiveCollision with CRITICAL severity pauses controller when autoPauseOnCritical is true`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)

		service.emitWarning(CollisionWarning.PredictiveCollision("T1", "T2", time = 0.0))

		assertThat(pauseController.calls).isEqualTo(1)
	}

	@Test
	fun `multiple CRITICAL warnings each trigger a separate pause request`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))
		service.emitWarning(CollisionWarning.ReservationConflict("T2", "T3", time = 1.0))

		assertThat(pauseController.calls).isEqualTo(2)
	}

	// ── autoPauseOnCritical = false ───────────────────────────────────────────

	@Test
	fun `no pause is requested when autoPauseOnCritical is false`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		service.autoPauseOnCritical = false

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))
		service.emitWarning(CollisionWarning.PredictiveCollision("T1", "T2", time = 1.0))

		assertThat(pauseController.calls).isEqualTo(0)
	}

	// ── WARNING severity ──────────────────────────────────────────────────────

	@Test
	fun `WARNING severity warning does not pause controller even when autoPauseOnCritical is true`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		// autoPauseOnCritical = true (default), but we supply a WARNING-level severity.

		service.emitWarning(
			CollisionWarning.ReservationConflict(
				trainId = "T1",
				conflictingTrainId = "T2",
				time = 0.0,
				severity = CollisionWarning.Severity.WARNING
			)
		)

		assertThat(pauseController.calls).isEqualTo(0)
	}

	// ── No-op controller is silent ────────────────────────────────────────────

	@Test
	fun `NoOpPauseController ignores pause requests without error`() {
		val service = DefaultCollisionDetectionService(NoOpPauseController)

		// Must not throw any exception.
		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))
		service.emitWarning(CollisionWarning.PredictiveCollision("T1", "T2", time = 1.0))
	}

	// ── Listener delivery is independent of pause policy ─────────────────────

	@Test
	fun `listeners still receive warnings even when autoPauseOnCritical is false`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		service.autoPauseOnCritical = false

		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))

		assertThat(received.size).isEqualTo(1)
		assertThat(pauseController.calls).isEqualTo(0)
	}
}
