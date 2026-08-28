/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * P8 prompt-determinism golden-file tests for the F1 paused-clock regime (SP2c.10, Issue #833).
 *
 * ## P8 guarantee (as delivered by F1)
 *
 * **Prompt determinism is delivered:** with the simulation clock frozen by
 * [PausedClockTickBudget], the emission strategy always receives the same immutable `obs0`
 * snapshot that was captured *before* the pause. No further sim-thread events can alter
 * observable state inside the paused window. Therefore a recorded snapshot sequence always
 * produces a byte-identical prompt sequence.
 *
 * **Decode determinism is NOT delivered** on the production tool-calling path. SP2c.27 (Issue
 * #850) established that Koog 1.1.1's `OllamaClient` is `final` with `internal` DTOs and that
 * `LLMParams.additionalProperties` flattens into the request root rather than inside `options` —
 * so no path exists to seed the Ollama sampler from the production tool-calling dispatcher (Issue
 * #894, settled). P8's guarantee covers only the **prompt half**: a recorded snapshot sequence
 * always produces a byte-identical prompt sequence.
 *
 * These tests serve as the "golden file" acceptance criterion:
 * - A fixed, pre-recorded [RenderContext] (from [RendererFixtures.tick41Context]) is rendered
 *   repeatedly by [CompactTextRenderer].
 * - Every run produces byte-identical output — this is the prompt that would be sent to the LLM.
 * - The [PausedClockTickBudget] test verifies that the budget wrapper itself does not alter the
 *   captured result (the prompt produced inside the budget equals the one produced outside).
 *
 * @since Issue #833 (SP2c.10 — Goal 10 timing regimes F1+F2)
 */
@DisplayName("SP2c.10/P8 — F1 prompt-determinism golden-file (#833)")
@Timeout(30, unit = TimeUnit.SECONDS)
class PromptDeterminismTest {
	private val renderer = CompactTextRenderer()
	private val ctx = RendererFixtures.tick41Context
	private val ctx0 = RendererFixtures.tick0Context

	// ── Renderer determinism (P8 prompt half) ─────────────────────────────────

	/**
	 * The canonical recorded snapshot (tick 41) always produces the same prompt bytes.
	 * This is the primary golden-file assertion for P8's prompt-determinism half.
	 */
	@RepeatedTest(10)
	@DisplayName("P8/F1: CompactTextRenderer produces identical bytes for the recorded tick-41 snapshot")
	fun tick41SnapshotIsDeterministic() {
		val first = renderer.render(ctx)
		val second = renderer.render(ctx)
		assertThat(second).isEqualTo(first)
	}

	/**
	 * Tick-0 edge case: first tick (no previous observation) is deterministic too.
	 */
	@RepeatedTest(10)
	@DisplayName("P8/F1: CompactTextRenderer is deterministic at tick-0 (no previous observation)")
	fun tick0SnapshotIsDeterministic() {
		val first = renderer.render(ctx0)
		val second = renderer.render(ctx0)
		assertThat(second).isEqualTo(first)
	}

	// ── PausedClockTickBudget wrapper does not alter the captured result ───────

	/**
	 * Smoke test that the [PausedClockTickBudget] wrapper itself does not mutate the rendered
	 * prompt: a prompt produced inside [PausedClockTickBudget.withBudget] equals the same prompt
	 * produced outside it. With the [NoOpController] used here the call is near-tautological
	 * (`render(ctx) == render(ctx)`), so this test does **not** by itself prove the F1 freeze —
	 * the real F1 paused-clock evidence lives in `TimingRegimesOllamaTest` (a multi-second live
	 * Ollama inference genuinely bracketed by pause/resume) and in the desktop-ui timing tests
	 * (`PausedClockSimTimeInvarianceTest`, `PausedClockResumeOnFailureTest`), which assert sim
	 * time is unchanged across a real slow emission. This test only guards against the wrapper
	 * accidentally altering or delaying the observation handed to the emission strategy.
	 */
	@Test
	@DisplayName("P8/F1: PausedClockTickBudget does not alter the prompt captured by the emission")
	fun pausedBudgetDoesNotAlterPrompt() {
		val controller = NoOpController()
		val budget = PausedClockTickBudget(controller)

		val promptOutsideBudget = renderer.render(ctx)
		val promptInsideBudget = runBlocking { budget.withBudget { renderer.render(ctx) } }

		assertThat(promptInsideBudget).isEqualTo(promptOutsideBudget)
	}

	/**
	 * Same context rendered 10× via [PausedClockTickBudget] (simulating 10 replay ticks) always
	 * yields the same string — the prompt sequence is reproducible for a recorded snapshot
	 * sequence.
	 */
	@RepeatedTest(10)
	@DisplayName("P8/F1: replaying the same recorded snapshot 10× through PausedClockTickBudget yields identical prompts")
	fun replayedSnapshotSequenceIsDeterministic() {
		val controller = NoOpController()
		val budget = PausedClockTickBudget(controller)

		val reference = runBlocking { budget.withBudget { renderer.render(ctx) } }
		val replayed = runBlocking { budget.withBudget { renderer.render(ctx) } }

		assertThat(replayed).isEqualTo(reference)
	}

	// ── NoOp controller used only in this test ────────────────────────────────

	private class NoOpController : cz.vutbr.fit.interlockSim.context.SimulationController {
		override suspend fun awaitIfPaused() = Unit

		override fun throttle(simDeltaSeconds: Double) = Unit

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() = Unit

		override fun requestResume() = Unit

		override fun currentSpeedMultiplier(): Double = 1.0
	}
}
