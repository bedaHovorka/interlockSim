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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.sim.Interlocking
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Verifies that `DefaultSimulationContext.onConflictDetectedEvent` silently drops listeners
 * registered **after** `run()` has been called, as stated in the [DefaultSimulationContext] KDoc
 * and required by the SP2c.4 acceptance criteria (Issue #827):
 *
 * > "Conflict listener registered in wireDispatcherAgent before run(); test proves a late
 * > registration would be dropped."
 *
 * ## How the test proves the guarantee
 *
 * `DefaultSimulationContext` uses an internal `simulationHasStarted: Boolean` guard. Once
 * `run()` sets it to `true`, every subsequent call to `onConflictDetectedEvent` returns
 * immediately without touching the listener list. This test exercises that code path by:
 *
 * 1. Registering a listener **before** `run()` — verifying it is buffered.
 * 2. Running a minimal simulation to completion — setting `simulationHasStarted = true`.
 * 3. Attempting to register a second listener **after** `run()` returns.
 * 4. Verifying via reflection on the `pendingConflictEventListeners` private field that
 *    only the pre-run listener is present — the post-run listener was silently dropped.
 *
 * The reflection approach is intentional: it directly verifies the mechanism rather than
 * inferring it from the absence of event calls (which would be ambiguous — a simulation that
 * produces no conflicts would also produce zero calls to a well-registered late listener).
 *
 * @since Issue #827 (SP2c.4 — Goal 10 conflict listener wiring)
 */
@Tag("integration-test")
@DisplayName("ConflictListenerWiring: late registration after run() is silently dropped (#827)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConflictListenerWiringTest : DispatcherKoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	@Test
	@DisplayName("Listener registered after run() is not added to pendingConflictEventListeners")
	fun listenerRegisteredAfterRunIsDropped() {
		val ctx = TestFixtures.newShuntingSimulationContext()
		context = ctx

		// Step 1: Register a listener BEFORE run()
		val preRunEvents = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { preRunEvents.add(it) }

		// Verify: pendingConflictEventListeners must have exactly 1 entry at this point
		val listenerField =
			DefaultSimulationContext::class.java
				.getDeclaredField("pendingConflictEventListeners")
		listenerField.isAccessible = true

		@Suppress("UNCHECKED_CAST")
		val listenersBefore = listenerField.get(ctx) as List<*>
		assertThat(listenersBefore).hasSize(1)

		// Step 2: Run a minimal simulation to completion (stops immediately on first iteration)
		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}
		ctx.setMainProcess(process)
		ctx.run()

		// Step 3: Attempt to register a listener AFTER run() — must be silently dropped
		val postRunEvents = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { postRunEvents.add(it) }

		// Step 4: Verify pendingConflictEventListeners still has exactly 1 entry
		@Suppress("UNCHECKED_CAST")
		val listenersAfter = listenerField.get(ctx) as List<*>
		assertThat(listenersAfter).hasSize(1)

		// Additional check: the count did not change
		assertThat(listenersAfter.size).isEqualTo(listenersBefore.size)
	}
}
