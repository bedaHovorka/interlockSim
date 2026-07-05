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
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Unit tests for [DefaultCollisionDetectionService] listener delivery and isolation.
 *
 * These exercise the SP1 thin-backbone contract directly (no simulation context), so they
 * run in `commonTest` and execute under both JVM and `:fast-sim`/native targets.
 *
 * @since Issue #611 (Goal 3 SP1)
 */
class DefaultCollisionDetectionServiceTest {
	/** A [PauseController] that counts [requestPause] calls. */
	private class CountingPauseController : PauseController {
		var calls: Int = 0
			private set

		override fun requestPause() {
			calls++
		}
	}

	@Test
	fun `multiple listeners all receive the warning in registration order`() {
		val service = DefaultCollisionDetectionService(CountingPauseController())
		val received = mutableListOf<Int>()

		service.onCollisionWarning { received.add(1) }
		service.onCollisionWarning { received.add(2) }
		service.onCollisionWarning { received.add(3) }

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))

		assertThat(received).containsExactly(1, 2, 3)
	}

	@Test
	fun `a throwing listener does not break remaining listeners or the pause request`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<String>()

		service.onCollisionWarning { throw IllegalStateException("boom") }
		service.onCollisionWarning { received.add("after-throw") }

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 1.0))

		// The listener after the throwing one still received the warning...
		assertThat(received).containsExactly("after-throw")
		// ...and the pause was still requested exactly once.
		assertThat(pauseController.calls).isEqualTo(1)
	}
}
