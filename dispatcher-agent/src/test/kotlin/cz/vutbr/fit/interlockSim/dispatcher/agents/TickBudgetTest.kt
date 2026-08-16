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
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit coverage for the [TickBudget] implementations (SP2c.5, Issue #828; SP2c.10, Issue #833).
 *
 * [DispatchTickLoop][cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] treats a `null`
 * return from [TickBudget.withBudget] as "deadline missed" and substitutes a
 * `TIMEOUT_NOOP`, so the two implementations must differ in exactly one respect: whether a
 * slow block can produce `null`.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
@DisplayName("SP2c.5/SP2c.10 — TickBudget implementations (#828, #833)")
@Timeout(30, unit = TimeUnit.SECONDS)
class TickBudgetTest {
	@Test
	@DisplayName("DeadlineTickBudget passes a fast block's result through unchanged")
	fun deadlineBudgetPassesFastResult() {
		val result = runBlocking { DeadlineTickBudget(timeoutMillis = 5_000L).withBudget { "done" } }

		assertThat(result).isEqualTo("done")
	}

	/**
	 * Would fail if [DeadlineTickBudget] stopped wrapping the block in `withTimeoutOrNull` — the
	 * loop would then hang on a stalled LLM instead of substituting a `TIMEOUT_NOOP`.
	 */
	@Test
	@DisplayName("DeadlineTickBudget returns null when the block overruns the deadline")
	fun deadlineBudgetTimesOut() {
		val result =
			runBlocking {
				DeadlineTickBudget(timeoutMillis = 20L).withBudget {
					delay(5_000L)
					"never reached"
				}
			}

		assertThat(result).isNull()
	}

	@Test
	@DisplayName("DeadlineTickBudget cancels the overrunning block rather than letting it finish")
	fun deadlineBudgetCancelsTheBlock() {
		val completed = AtomicBoolean(false)

		runBlocking {
			DeadlineTickBudget(timeoutMillis = 20L).withBudget {
				delay(2_000L)
				completed.set(true)
				"never reached"
			}
		}

		assertThat(completed.get()).isFalse()
	}

	@Test
	@DisplayName("DeadlineTickBudget propagates a block's own null result")
	fun deadlineBudgetPropagatesNullResult() {
		val result = runBlocking { DeadlineTickBudget(timeoutMillis = 5_000L).withBudget<String> { null } }

		assertThat(result).isNull()
	}

	@Test
	@DisplayName("NoTimeoutBudget lets a slow block finish and returns its result")
	fun noTimeoutBudgetLetsSlowBlockFinish() {
		val completed = AtomicBoolean(false)

		val result =
			runBlocking {
				NoTimeoutBudget.withBudget {
					delay(60L)
					completed.set(true)
					"finished"
				}
			}

		assertThat(completed.get()).isTrue()
		assertThat(result).isEqualTo("finished")
	}

	@Test
	@DisplayName("NoTimeoutBudget propagates a block's own null result")
	fun noTimeoutBudgetPropagatesNullResult() {
		assertThat(runBlocking { NoTimeoutBudget.withBudget<String> { null } }).isNull()
	}

	@Test
	@DisplayName("NoTimeoutBudget propagates an exception thrown by the block")
	fun noTimeoutBudgetPropagatesExceptions() {
		var caught: IllegalStateException? = null

		try {
			runBlocking { NoTimeoutBudget.withBudget<String> { error("boom") } }
		} catch (e: IllegalStateException) {
			caught = e
		}

		assertThat(caught?.message).isEqualTo("boom")
	}

	@Test
	@DisplayName("DeadlineTickBudget propagates an exception thrown before the deadline")
	fun deadlineBudgetPropagatesExceptions() {
		var caught: IllegalStateException? = null

		try {
			runBlocking { DeadlineTickBudget(timeoutMillis = 5_000L).withBudget<String> { error("boom") } }
		} catch (e: IllegalStateException) {
			caught = e
		}

		assertThat(caught?.message).isEqualTo("boom")
	}

	@Test
	@DisplayName("NoTimeoutBudget is a stateless singleton reusable across ticks")
	fun noTimeoutBudgetIsReusable() {
		val results =
			runBlocking {
				listOf(
					NoTimeoutBudget.withBudget { 1 },
					NoTimeoutBudget.withBudget { 2 },
					NoTimeoutBudget.withBudget { 3 }
				)
			}

		assertThat(results).isEqualTo(listOf(1, 2, 3))
	}

	@Test
	@DisplayName("DeadlineTickBudget re-arms its deadline on every call")
	fun deadlineBudgetReArmsPerCall() {
		val budget = DeadlineTickBudget(timeoutMillis = 200L)

		val results =
			runBlocking {
				listOf(
					budget.withBudget {
						delay(10L)
						"first"
					},
					budget.withBudget {
						delay(10L)
						"second"
					}
				)
			}

		assertThat(results).isEqualTo(listOf("first", "second"))
	}

	// ── PausedClockTickBudget (F1) ────────────────────────────────────────────────────────

	/**
	 * Tests for [PausedClockTickBudget] (SP2c.10, Issue #833).
	 *
	 * The binding constraints from SP2c.26 (`docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`):
	 * - C4: resume must be in a `finally` — verified by the exception-safety tests below.
	 * - The pause/resume pair must bracket **only** the block (C1).
	 */
	@Nested
	@DisplayName("PausedClockTickBudget (F1 paused-clock mode, SP2c.10 #833)")
	inner class PausedClockTickBudgetTests {
		/** Recording [SimulationController] that tracks pause/resume calls in order. */
		private inner class RecordingController : SimulationController {
			val calls = mutableListOf<String>()

			@Volatile
			var paused: Boolean = false

			override fun isPaused(): Boolean = paused

			override fun requestPause() {
				calls += "pause"
				paused = true
			}

			override fun requestResume() {
				calls += "resume"
				paused = false
			}

			override suspend fun awaitIfPaused() = Unit

			override fun pollStepEvent(): Boolean = false

			override fun pollStepTime(): Double? = null

			override fun throttle(simDeltaSeconds: Double) = Unit

			override fun currentSpeedMultiplier(): Double = 1.0
		}

		@Test
		@DisplayName("F1: requestPause is called before the block and requestResume after (C1, C4)")
		fun pausedBeforeBlockResumedAfter() {
			val controller = RecordingController()
			val budget = PausedClockTickBudget(controller)

			runBlocking { budget.withBudget { "ok" } }

			assertThat(controller.calls).isEqualTo(listOf("pause", "resume"))
		}

		@Test
		@DisplayName("F1: the block's result is returned unchanged")
		fun blockResultPassedThrough() {
			val result = runBlocking { PausedClockTickBudget(RecordingController()).withBudget { "done" } }

			assertThat(result).isEqualTo("done")
		}

		@Test
		@DisplayName("F1: a null block result is returned unchanged")
		fun nullBlockResultPassedThrough() {
			val result = runBlocking { PausedClockTickBudget(RecordingController()).withBudget<String> { null } }

			assertThat(result).isNull()
		}

		@Test
		@DisplayName("F1: requestResume is called in finally — even when the block throws (C4)")
		fun resumeCalledEvenOnException() {
			val controller = RecordingController()
			val budget = PausedClockTickBudget(controller)
			var caught: IllegalStateException? = null

			try {
				runBlocking { budget.withBudget<String> { error("boom") } }
			} catch (e: IllegalStateException) {
				caught = e
			}

			// Exception surfaces to the caller...
			assertThat(caught?.message).isEqualTo("boom")
			// ...and the simulation is unpaused (resume was in finally).
			assertThat(controller.calls).isEqualTo(listOf("pause", "resume"))
			assertThat(controller.paused).isFalse()
		}

		@Test
		@DisplayName("F1: the simulation is paused while the block runs (C1)")
		fun simulationIsPausedDuringBlock() {
			val controller = RecordingController()
			val budget = PausedClockTickBudget(controller)
			val pausedDuringBlock = AtomicBoolean(false)

			runBlocking {
				budget.withBudget {
					pausedDuringBlock.set(controller.isPaused())
					"ok"
				}
			}

			assertThat(pausedDuringBlock.get()).isTrue()
		}

		@Test
		@DisplayName("F1: the simulation is not paused after withBudget returns normally")
		fun simulationNotPausedAfterNormalReturn() {
			val controller = RecordingController()
			val budget = PausedClockTickBudget(controller)

			runBlocking { budget.withBudget { "ok" } }

			assertThat(controller.isPaused()).isFalse()
		}

		@Test
		@DisplayName("F1: reusable across ticks — each call pairs its own pause/resume")
		fun reusableAcrossTicks() {
			val controller = RecordingController()
			val budget = PausedClockTickBudget(controller)

			runBlocking {
				budget.withBudget { "tick-1" }
				budget.withBudget { "tick-2" }
			}

			assertThat(controller.calls).isEqualTo(listOf("pause", "resume", "pause", "resume"))
		}
	}
}
