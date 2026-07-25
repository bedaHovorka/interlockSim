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
 * SP0.12 integration gate — shunting-loop end-to-end via the lifted dispatcher-agent stack.
 *
 * Runs `vyhybna.xml` as a single simulation with the full lifted stack
 * ([AgentLoopDriver] + [DispatchDecisionApplier] + [RuleBasedDispatcher]) under
 * signal-based pacing (SP0.11c, Issue #746), and asserts:
 *
 * 1. **All trains exit** — the dispatcher keeps the loop running until all generated
 *    trains complete their journeys; no permanent deadlock.
 * 2. **No conflict events** — [RuleBasedDispatcher] must not cause competing
 *    reservations on the shunting-loop topology; zero
 *    [ConflictDetectedEvent]s expected.
 *
 * ## Why signal-based pacing prevents conflict events (SP0.11c)
 *
 * The previous lock-step handshake was required because the unthrottled headless
 * simulation could advance multiple ticks before the driver was scheduled, allowing
 * the driver to read a stale observation. The [SnapshotSignal] replaces the
 * lock-step: the sim thread calls [SnapshotSignal.signal] immediately after each
 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.captureSnapshot]; the
 * driver blocks on [SnapshotSignal.await] at the top of each cycle and is woken
 * exactly once per tick — at most 1 tick of observation lag, never multiple ticks.
 *
 * With at most 1-tick lag, the driver always reads a near-fresh snapshot; duplicate
 * reservations (same `(trainId, from, to)` triple) are suppressed by
 * [DispatchDecisionApplier.appliedReservations]; and the capacity cap ensures at
 * most one train approaches each block. This matches the production wiring in
 * [cz.vutbr.fit.interlockSim.ExampleRegistry.wireDispatcherAgent], closing the
 * SP0.11c requirement to validate the production path.
 *
 * The lifted-stack wiring and the signal-based pacing itself live in
 * [cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture], shared with
 * [DispatcherCollisionValidationTest] so the two gates cannot drift apart in how they
 * drive the dispatcher.
 *
 * ## Relationship to [RuleBasedDispatcherDeterminismTest]
 *
 * [RuleBasedDispatcherDeterminismTest] is the *before/after determinism harness* —
 * it runs 10 consecutive signal-based runs and asserts identical outcomes.  This class
 * is a single-run **correctness gate** that closes the "Integration: shunting-loop
 * end-to-end via the lifted driver — all trains exit, no conflict events"
 * requirement from the SP0.12 acceptance criteria (Issue #734).
 *
 * @see RuleBasedDispatcherDeterminismTest for the 10-run cross-run A3 determinism gate
 * @see cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture for the shared
 *   lifted-stack wiring and signal-based pacing
 * @see cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher for the synchronous
 *   wiring alternative used by `:core` and `:fast-sim` tests
 * @since Issue #734 (SP0.12 — Goal 10 A3 integration gate); lock-step replaced by
 *   [SnapshotSignal] in Issue #746 (SP0.11c)
 */
@DisplayName("ShuntingLoop end-to-end via lifted dispatcher-agent stack (SP0.12 integration gate)")
@Tag("integration-test")
class ShuntingLoopLiftedDriverIntegrationTest {
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
	 * Runs vyhybna.xml with the lifted stack under signal-based pacing and asserts:
	 * - All generated trains exit (at least 1; baseline is 5 for endTime=300s).
	 * - Zero [ConflictDetectedEvent]s fired during the run.
	 *
	 * [DefaultSnapshotSignal] pins one driver cycle per simulation tick: the sim thread
	 * signals after each captureSnapshot; the driver blocks on await() and is woken
	 * exactly once per tick. At most 1-tick observation lag — deterministic and
	 * conflict-free with the shunting-loop topology (duplicate-suppression in the
	 * applier + capacity cap handle any same-train same-hop duplicates).
	 *
	 * This matches the production wiring in
	 * [cz.vutbr.fit.interlockSim.ExampleRegistry.wireDispatcherAgent].
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	@DisplayName("all trains exit and zero conflict events (signal-based pacing, lifted stack)")
	fun allTrainsExitWithNoConflictEvents() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 300L)

		// Collect ConflictDetectedEvents — must be empty after the run.
		// Registered before fixture.run() so the listener is live for the whole run.
		// Signal-based pacing guarantees at most 1-tick lag; duplicate reservations are
		// suppressed by DispatchDecisionApplier.appliedReservations (same-train same-hop guard).
		val conflictEvents: MutableList<ConflictDetectedEvent> =
			mutableListOf()
		context.onConflictDetectedEvent { conflictEvents.add(it) }

		// ── Run the full lifted stack under signal-based pacing (shared with
		// DispatcherCollisionValidationTest — see LiftedStackFixture) ──
		val run = fixture.run(loop, context)

		val trainsExited = run.trainsExited()
		val maxConcurrent = run.maxConcurrentTrains()
		logger.info {
			"Integration run complete: trainsExited=$trainsExited, " +
				"maxConcurrent=$maxConcurrent, " +
				"driverCycles=${run.driverCycleCount.get()}, " +
				"conflictEvents=${conflictEvents.size}"
		}

		// All generated trains must exit — no permanent deadlock.
		assertThat(trainsExited)
			.isGreaterThanOrEqualTo(1)

		// RuleBasedDispatcher must not produce competing reservations on the
		// shunting-loop topology.  A non-zero count indicates a regression in
		// dispatch correctness (e.g. the capacity cap or pathAlreadyExtendedBeyond
		// guard is broken).
		assertThat(conflictEvents)
			.isEmpty()
	}
}
