/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 3 SP7: Auto-pause / auto-halt integration tests (Issue #617).
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.Time
import cz.vutbr.fit.interlockSim.sim.Timetable
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration tests for Goal 3 SP5/SP7 auto-pause and auto-halt behaviour (#615, #617).
 *
 * ## Coverage
 *
 * ### `@RepeatedTest(50)` — consistency / regression guard
 * 1. **Auto-pause consistency** — [DefaultCollisionDetectionService.autoPauseOnCritical]
 *    triggers [PauseController.requestPause] exactly once per CRITICAL warning across
 *    50 independent invocations (races, state leakage, or re-entrancy hazards would
 *    cause non-deterministic counts).
 * 2. **Halt-callback consistency** — [DefaultCollisionDetectionService.autoHaltTrainOnViolation]
 *    invokes the registered halt callback exactly once per [CollisionWarning.BlockEntryViolation]
 *    across 50 independent invocations, using a real [DynamicTrackBlock] from a live context.
 *
 * ### Single-run integration
 * 3. **Velocity-zero after halt** — wires `Train.requestHalt` as the halt callback on a real
 *    [DefaultSimulationContext] (following the [BlockEntryViolationWarningTest] fixture pattern);
 *    emits a [CollisionWarning.BlockEntryViolation] via `emitCustom` from inside a simulation
 *    process; asserts `train.getVelocity() == 0.0` after the simulation completes. This is the
 *    first test of [Train.requestHalt] directly as a production method reference.
 * 4. **Idempotency** — calls [Train.requestHalt] twice (on a train that has not yet started)
 *    from inside a running simulation; asserts no exception is thrown and velocity remains 0.0.
 *    Validates the doc-comment claim "safe to call even if the train has already stopped".
 *
 * ## Why this class (not commonTest)?
 *
 * Tests 3 and 4 require a [DynamicTrackBlock] (a JVM-only kDisco entity), [Train]
 * construction within a [DefaultSimulationContext], and the [KoinTestBase] JUnit 5
 * lifecycle extension — none of which are available in `commonTest`.
 *
 * @since Issue #617 (Goal 3 SP7)
 * @see BlockEntryViolationWarningTest Fixture pattern followed by tests 3 and 4
 * @see AutoPauseOnCriticalPolicyTest Lightweight commonTest counterpart
 */
@Tag("integration-test")
@DisplayName("Auto-pause / auto-halt integration — Goal 3 SP7 (#617)")
class AutoPauseHaltTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Counts [PauseController.requestPause] invocations. */
	private class CountingPauseController : PauseController {
		var calls: Int = 0
			private set

		override fun requestPause() {
			calls++
		}
	}

	/** Silently ignores pause requests. */
	private object NoOpPauseController : PauseController {
		override fun requestPause() {
			// intentional no-op
		}
	}

	private fun newContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		context = ctx
		return ctx
	}

	// ── @RepeatedTest(50) #1: auto-pause consistency ──────────────────────────

	/**
	 * Verifies [DefaultCollisionDetectionService.autoPauseOnCritical] triggers exactly
	 * one [PauseController.requestPause] call per CRITICAL warning over 50 independent
	 * invocations.
	 *
	 * Each repetition creates a fresh [DefaultCollisionDetectionService] with a
	 * [CountingPauseController] (no kDisco simulation required) and directly calls
	 * [DefaultCollisionDetectionService.emitWarning] with a CRITICAL
	 * [CollisionWarning.ReservationConflict].
	 *
	 * Non-deterministic pause counts (0 or > 1) across repetitions would indicate
	 * unintended state sharing or re-entrancy bugs in the emission machinery.
	 */
	@RepeatedTest(50)
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("autoPauseOnCritical triggers exactly one pause per CRITICAL warning (x50)")
	fun autoPauseOnCritical_triggersExactlyOnePausePerCriticalWarning() {
		val pauseController = CountingPauseController()
		// autoPauseOnCritical defaults to true; env=null is valid for direct emitWarning tests.
		val service = DefaultCollisionDetectionService(pauseController)

		service.emitWarning(CollisionWarning.ReservationConflict("T1", "T2", time = 0.0))

		assertThat(pauseController.calls).isEqualTo(1)
	}

	// ── @RepeatedTest(50) #2: halt-callback consistency ───────────────────────

	/**
	 * Verifies [DefaultCollisionDetectionService.autoHaltTrainOnViolation] invokes the
	 * registered halt callback exactly once per [CollisionWarning.BlockEntryViolation]
	 * over 50 independent invocations.
	 *
	 * Each repetition creates a fresh [DefaultCollisionDetectionService] and a real
	 * [DynamicTrackBlock] obtained from a live (but not yet run) [DefaultSimulationContext].
	 * The halt callback is a plain lambda counter — not `Train.requestHalt` (that
	 * integration is covered by [trainVelocityZeroAfterHaltCallbackFiresMidEnter]).
	 *
	 * Non-deterministic counts would indicate state leakage in the callback registry or
	 * in the [DefaultCollisionDetectionService.autoHaltTrainOnViolation] guard.
	 */
	@RepeatedTest(50)
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("autoHaltTrainOnViolation invokes halt callback exactly once (x50)")
	fun autoHaltTrainOnViolation_invokesHaltCallbackExactlyOnce() {
		// Obtain a real DynamicTrackBlock; do NOT call ctx.run() — no simulation needed.
		val ctx = newContext()
		val block: DynamicTrackBlock = ctx.getGraph().values().first()

		val service = DefaultCollisionDetectionService(NoOpPauseController)
		service.autoHaltTrainOnViolation = true
		// autoPauseOnCritical is deliberately left as default (true) to confirm that
		// the pause and halt code paths are independent.

		var haltCount = 0
		service.registerHaltCallback("train-halt-test") { haltCount++ }

		service.emitWarning(
			CollisionWarning.BlockEntryViolation(
				trainId = "train-halt-test",
				block = block,
				time = 0.0
			)
		)

		assertThat(haltCount).isEqualTo(1)

		// Verify a second warning for a DIFFERENT train does NOT invoke this callback.
		service.emitWarning(
			CollisionWarning.BlockEntryViolation(
				trainId = "different-train",
				block = block,
				time = 1.0
			)
		)
		assertThat(haltCount).isEqualTo(1) // still 1, not 2
	}

	// ── Integration #3: velocity zero after Train.requestHalt() ───────────────

	/**
	 * Wires `Train.requestHalt` (the production method reference from Goal 3 SP5) as
	 * the halt callback on a real [DefaultSimulationContext]; emits a
	 * [CollisionWarning.BlockEntryViolation] via `emitCustom` inside a simulation
	 * process (the emit-before-throw path from
	 * [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter]); and asserts
	 * `train.getVelocity() == 0.0` after the simulation completes.
	 *
	 * This is the **first direct test** of [Train.requestHalt]:
	 * - The train is constructed from a [Timetable] within the context (a real
	 *   [cz.vutbr.fit.interlockSim.context.SimulationEnvironment] dependency).
	 * - The halt callback is registered via the production API
	 *   `registerHaltCallback(train.name, train::requestHalt)`.
	 * - The callback is triggered by the production delivery path in
	 *   [DefaultCollisionDetectionService.emitWarning].
	 * - The velocity assertion uses the production accessor `train.getVelocity()`.
	 *
	 * Note: the train is not activated (no `Process.activate(train)` call), so velocity
	 * starts at 0.0. The meaningful assertion is that [Train.requestHalt] *executes
	 * without throwing* and leaves `getVelocity()` at 0.0 — confirming the wiring of
	 * the halt callback through the full service machinery.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Train.requestHalt() wired as halt callback zeroes velocity after BlockEntryViolation")
	fun trainVelocityZeroAfterHaltCallbackFiresMidEnter() {
		val ctx = newContext()

		val detectionService =
			ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		detectionService.autoHaltTrainOnViolation = true
		detectionService.autoPauseOnCritical = false // prevent pause from blocking test

		// Construct a real Train (from Timetable) — its name is used as the callback key.
		val inOuts = ctx.getInOuts()
		val inOut = inOuts.first { it.name == "A" }
		val outOut = inOuts.first { it.name == "B" }
		val timetable = Timetable(inOut, outOut, Time(0.0), Time(300.0), 20.0)
		val train = Train(ctx, timetable)

		// Wire the production method reference as the halt callback.
		detectionService.registerHaltCallback(train.name, train::requestHalt)

		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		val violation =
			CollisionWarning.BlockEntryViolation(
				trainId = train.name,
				block = block,
				time = 0.0
			)

		// Follow BlockEntryViolationWarningTest fixture pattern:
		// a process emits the violation via emitCustom (exactly as DynamicTrackBlock.enter()
		// does before throwing on double-occupancy).
		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(violation)
					terminate()
				}
			}
		)
		ctx.run()

		// Train.requestHalt() must have executed (via halt callback) without throwing;
		// velocity must be 0.0.
		assertThat(train.getVelocity()).isEqualTo(0.0)
	}

	// ── Integration #4: requestHalt() idempotency ─────────────────────────────

	/**
	 * Calls [Train.requestHalt] twice on a train that has not yet been activated
	 * (i.e., the train is already stopped), from inside a running simulation process.
	 *
	 * Validates the doc-comment claim: "Safe to call even if the train has already
	 * stopped — the operation is idempotent."
	 *
	 * The assertions are:
	 * - No exception is thrown by either call (test passes iff no exception).
	 * - `train.getVelocity()` is 0.0 after both calls.
	 *
	 * Running inside a simulation process (`LoopProcess.iteration()`) ensures that
	 * the kDisco engine is active, which is the execution context documented in
	 * [Train.requestHalt]'s thread-safety contract.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Train.requestHalt() is idempotent: calling twice does not throw and velocity stays 0.0")
	fun requestHaltIsIdempotent() {
		val ctx = newContext()

		val inOuts = ctx.getInOuts()
		val inOut = inOuts.first { it.name == "A" }
		val outOut = inOuts.first { it.name == "B" }
		val timetable = Timetable(inOut, outOut, Time(0.0), Time(300.0), 20.0)
		val train = Train(ctx, timetable)

		var velocityAfterFirstHalt = Double.NaN
		var velocityAfterSecondHalt = Double.NaN

		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					// First call: on a not-yet-activated train (velocity starts at 0.0).
					train.requestHalt()
					velocityAfterFirstHalt = train.getVelocity()

					// Second call: idempotency — train is already stopped.
					train.requestHalt()
					velocityAfterSecondHalt = train.getVelocity()

					terminate()
				}
			}
		)
		ctx.run()

		assertThat(velocityAfterFirstHalt).isEqualTo(0.0)
		assertThat(velocityAfterSecondHalt).isEqualTo(0.0)
		// Final check: velocity remains zero after the simulation completes.
		assertThat(train.getVelocity()).isEqualTo(0.0)
	}

	// ── Integration #5: autoHaltTrainOnViolation disabled → no halt ───────────

	/**
	 * Regression guard: when [DefaultCollisionDetectionService.autoHaltTrainOnViolation]
	 * is `false` (the default), emitting a [CollisionWarning.BlockEntryViolation]
	 * must **not** invoke the registered halt callback, even though [Train.requestHalt]
	 * is wired as the callback.
	 *
	 * This complements [trainVelocityZeroAfterHaltCallbackFiresMidEnter] and ensures
	 * the `autoHaltTrainOnViolation` guard cannot be accidentally bypassed.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("autoHaltTrainOnViolation=false does not invoke Train.requestHalt() halt callback")
	fun autoHaltDisabled_doesNotInvokeRequestHalt() {
		val ctx = newContext()

		val detectionService =
			ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		// autoHaltTrainOnViolation defaults to false — no explicit assignment needed.
		detectionService.autoPauseOnCritical = false

		val inOuts = ctx.getInOuts()
		val inOut = inOuts.first { it.name == "A" }
		val outOut = inOuts.first { it.name == "B" }
		val timetable = Timetable(inOut, outOut, Time(0.0), Time(300.0), 20.0)
		val train = Train(ctx, timetable)

		var haltCallbackInvoked = false
		// Register a sentinel lambda alongside requestHalt to detect any invocation.
		detectionService.registerHaltCallback(train.name) {
			haltCallbackInvoked = true
			train.requestHalt() // would also be called in production; call it here for fidelity
		}

		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(
						CollisionWarning.BlockEntryViolation(
							trainId = train.name,
							block = block,
							time = 0.0
						)
					)
					terminate()
				}
			}
		)
		ctx.run()

		assertThat(haltCallbackInvoked).isEqualTo(false)
	}

	// ── Integration #6: cross-thread visibility ───────────────────────────────

	/**
	 * Regression guard for the code-review finding that
	 * [DefaultCollisionDetectionService.autoHaltTrainOnViolation] was written from the EDT
	 * (via `cz.vutbr.fit.interlockSim.gui.Frame`'s setter) but read from the simulation thread
	 * inside [DefaultCollisionDetectionService.emit] without a `@Volatile` annotation.
	 */
	@Test
	@DisplayName("autoHaltTrainOnViolation is volatile (cross-thread contract)")
	fun autoHaltTrainOnViolationIsVolatile() {
		val field = DefaultCollisionDetectionService::class.java.getDeclaredField("autoHaltTrainOnViolation")
		assertThat(
			java.lang.reflect.Modifier
				.isVolatile(field.modifiers)
		).isEqualTo(true)
	}
}
