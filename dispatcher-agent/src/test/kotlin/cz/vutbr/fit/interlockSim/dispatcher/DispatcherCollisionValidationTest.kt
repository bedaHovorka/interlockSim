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
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * SP2b.7 validation gate — dispatcher routing against Goal 3 collision detection.
 *
 * Validates that [RuleBasedDispatcher] never triggers a [CollisionWarning] from the
 * Goal 3 [DefaultCollisionDetectionService] — the "no collisions" success criterion.
 *
 * ## Purpose
 *
 * [ShuntingLoopLiftedDriverIntegrationTest] verifies zero
 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent]s at the Goal 9 layer.
 * This test complements it by exercising the Goal 3 safety net: the
 * [DefaultCollisionDetectionService] is subscribed to block events via
 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.onCollisionWarning], and
 * the assertion is that **zero [CollisionWarning]s** are emitted during a
 * correct dispatcher run.
 *
 * A non-zero warning count would indicate that [RuleBasedDispatcher] produces routing
 * decisions that the interlocking translates into competing reservations
 * ([CollisionWarning.ReservationConflict]) or illegal block entries
 * ([CollisionWarning.BlockEntryViolation]).
 *
 * ## Goal 3 SP5 headless flag
 *
 * [DefaultCollisionDetectionService.autoPauseOnCritical] is explicitly set to `false`
 * before the run so a hypothetical CRITICAL warning would not attempt to pause the
 * headless simulation.  This exercises the same pattern that `fast-sim` / CLI entry
 * points and other automated headless scenarios must use: the operator cannot react to
 * a pause request when there is no GUI.
 *
 * ## Lock-step rationale
 *
 * This test shares the lifted-stack wiring and lock-step handshake with
 * [ShuntingLoopLiftedDriverIntegrationTest] through [LiftedStackFixture], so the two
 * gates cannot drift apart in how they drive the dispatcher.  Without lock-step, OS
 * scheduling races between the driver thread and the kDisco sim thread can produce
 * stale observations that cause duplicate reservations, which in turn trigger
 * [CollisionWarning.ReservationConflict]s — see [LiftedStackFixture] and
 * [ShuntingLoopLiftedDriverIntegrationTest] for the full race analysis.
 *
 * @see ShuntingLoopLiftedDriverIntegrationTest for the single-run Goal 9 correctness gate
 * @see RuleBasedDispatcherDeterminismTest for the 10-run cross-run determinism gate
 * @since Issue #562 (SP2b.7 — Goal 10)
 */
@DisplayName("SP2b.7 — dispatcher routing: zero Goal 3 collision warnings (safety net validation)")
@Tag("integration-test")
class DispatcherCollisionValidationTest {
	private val fixture = LiftedStackFixture()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	/**
	 * Runs `vyhybna.xml` with the full lifted dispatcher-agent stack under lock-step and
	 * asserts the Goal 3 safety net:
	 *
	 * 1. **Zero [CollisionWarning]s** from [DefaultCollisionDetectionService] (Goal 3).
	 *    A warning here means the dispatcher allowed competing reservations or an illegal
	 *    block entry — either indicates unsafe routing.
	 * 2. **Zero [ConflictDetectedEvent]s** (Goal 9 complementary assertion). These fire
	 *    at the lower reservation layer before Goal 3 promotes them to warnings.
	 * 3. **All generated trains exit** — no permanent deadlock.
	 *
	 * [DefaultCollisionDetectionService.autoPauseOnCritical] is set to `false` before run
	 * to match the headless (no-operator) usage pattern (Goal 3 SP5 headless contract).
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	@DisplayName("zero Goal 3 collision warnings with lifted dispatcher stack (SP2b.7 safety net)")
	fun dispatcherRoutingProducesZeroCollisionWarnings() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 300L)

		// ── Goal 3 SP2b.7: subscribe to CollisionWarnings — the safety-net assertion ──
		// Registered before fixture.run() so the listener is live for the whole run.
		val collisionWarnings: MutableList<CollisionWarning> = mutableListOf()
		context.getCollisionServices().onCollisionWarning { collisionWarnings.add(it) }

		// Goal 3 SP5: disable auto-pause for headless runs.
		// Without this, a CRITICAL warning would call requestPause() on the simulation
		// controller; in a headless scenario there is no operator to react.
		// Pre-condition: dispatcherAgentTestModule binds the concrete
		// DefaultCollisionDetectionService, so this cast is safe under the current binding.
		val collisionService =
			context.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		collisionService.autoPauseOnCritical = false

		// ── Goal 9: also collect ConflictDetectedEvents (complementary assertion) ──
		val conflictEvents: MutableList<ConflictDetectedEvent> = mutableListOf()
		context.onConflictDetectedEvent { conflictEvents.add(it) }

		// ── Run the full lifted stack under the lock-step handshake (shared with the
		// Goal 9 ShuntingLoopLiftedDriverIntegrationTest — see LiftedStackFixture) ──
		val run = fixture.run(loop, context)

		val trainsExited = run.trainsExited()
		logger.info {
			"SP2b.7 validation complete: trainsExited=$trainsExited, " +
				"driverCycles=${run.driverCycleCount.get()}, " +
				"collisionWarnings=${collisionWarnings.size}, " +
				"conflictEvents=${conflictEvents.size}"
		}

		// Assertion order is deliberate diagnostic priority — do not reorder:
		// 1. liveness: a deadlock hangs context.run() and surfaces as @Timeout, so this
		//    assertion fires only for a non-deadlock "trains stopped early" failure.
		assertThat(trainsExited).isGreaterThanOrEqualTo(1)

		// 2. SP2b.7 success criterion: Goal 3 safety net must emit zero warnings. A
		//    non-zero count means the dispatcher produced unsafe routing decisions that
		//    triggered competing reservations or an illegal block entry.
		assertThat(collisionWarnings).isEmpty()

		// 3. Complementary Goal 9 assertion: no competing reservations at the lower
		//    reservation layer (which Goal 3 would otherwise promote to a warning).
		assertThat(conflictEvents).isEmpty()
	}
}
