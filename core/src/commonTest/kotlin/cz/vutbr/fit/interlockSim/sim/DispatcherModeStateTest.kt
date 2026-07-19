/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [DispatcherMode] and [DispatcherModeState] (SP2b.4 — Issue #559).
 *
 * Verifies:
 * 1. The three modes ([DispatcherMode.AUTO], [DispatcherMode.SEMI_AUTO],
 *    [DispatcherMode.MANUAL]) are defined and distinct.
 * 2. [DispatcherModeState] defaults to [DispatcherMode.AUTO] with no override.
 * 3. Setting an override changes [DispatcherModeState.getEffectiveMode] and reports
 *    [DispatcherModeState.hasOverride] `true`.
 * 4. Overrides persist across arbitrarily many reads (the state does not decay).
 * 5. Clearing the override returns to the default mode.
 * 6. A custom `defaultMode` is honoured when no override is active.
 * 7. Multiple concurrent writers observe a well-defined last-writer-wins state.
 *
 * @since Issue #559 (SP2b.4 — Goal 10)
 */
class DispatcherModeStateTest {
	// ── DispatcherMode enum ───────────────────────────────────────────────────

	@Test
	fun `DispatcherMode defines AUTO SEMI_AUTO and MANUAL`() {
		assertThat(DispatcherMode.entries.toList())
			.containsExactlyInAnyOrder(
				DispatcherMode.AUTO,
				DispatcherMode.SEMI_AUTO,
				DispatcherMode.MANUAL
			)
	}

	@Test
	fun `DispatcherMode DEFAULT is AUTO`() {
		assertThat(DispatcherMode.DEFAULT).isEqualTo(DispatcherMode.AUTO)
	}

	// ── Initial state ─────────────────────────────────────────────────────────

	@Test
	fun `new state defaults to AUTO with no override`() {
		val state = DispatcherModeState()

		assertThat(state.defaultMode).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.hasOverride()).isFalse()
	}

	@Test
	fun `custom defaultMode is honoured when no override is active`() {
		val state = DispatcherModeState(defaultMode = DispatcherMode.MANUAL)

		assertThat(state.defaultMode).isEqualTo(DispatcherMode.MANUAL)
		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)
		assertThat(state.hasOverride()).isFalse()
	}

	// ── Setting overrides ─────────────────────────────────────────────────────

	@Test
	fun `setOverride to MANUAL changes effective mode and reports hasOverride`() {
		val state = DispatcherModeState()

		state.setOverride(DispatcherMode.MANUAL)

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)
		assertThat(state.hasOverride()).isTrue()
		// Default is unchanged.
		assertThat(state.defaultMode).isEqualTo(DispatcherMode.AUTO)
	}

	@Test
	fun `setOverride to SEMI_AUTO changes effective mode`() {
		val state = DispatcherModeState()

		state.setOverride(DispatcherMode.SEMI_AUTO)

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.SEMI_AUTO)
		assertThat(state.hasOverride()).isTrue()
	}

	@Test
	fun `setOverride replaces previous override`() {
		val state = DispatcherModeState()

		state.setOverride(DispatcherMode.MANUAL)
		state.setOverride(DispatcherMode.SEMI_AUTO)

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.SEMI_AUTO)
		assertThat(state.hasOverride()).isTrue()
	}

	// ── Override persistence (Issue #559 core requirement) ───────────────────

	@Test
	fun `override persists across many reads without decaying`() {
		val state = DispatcherModeState()
		state.setOverride(DispatcherMode.MANUAL)

		// Reading the mode many times must not clear or decay the override.
		repeat(1_000) {
			assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)
			assertThat(state.hasOverride()).isTrue()
		}
	}

	@Test
	fun `override to same value as default still counts as active override`() {
		// Documented behavior: setOverride records state regardless of value; hasOverride
		// reports the presence of an override, not whether the mode differs from default.
		val state = DispatcherModeState(defaultMode = DispatcherMode.AUTO)

		state.setOverride(DispatcherMode.AUTO)

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.hasOverride()).isTrue()
	}

	// ── Clearing overrides ────────────────────────────────────────────────────

	@Test
	fun `clearOverride returns effective mode to default`() {
		val state = DispatcherModeState()
		state.setOverride(DispatcherMode.MANUAL)

		state.clearOverride()

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.hasOverride()).isFalse()
	}

	@Test
	fun `clearOverride honours custom defaultMode`() {
		val state = DispatcherModeState(defaultMode = DispatcherMode.SEMI_AUTO)
		state.setOverride(DispatcherMode.MANUAL)

		state.clearOverride()

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.SEMI_AUTO)
		assertThat(state.hasOverride()).isFalse()
	}

	@Test
	fun `clearOverride is a no-op when no override is active`() {
		val state = DispatcherModeState()

		state.clearOverride()
		state.clearOverride()

		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.hasOverride()).isFalse()
	}

	@Test
	fun `set then clear then set again works correctly`() {
		val state = DispatcherModeState()

		state.setOverride(DispatcherMode.MANUAL)
		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)

		state.clearOverride()
		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(state.hasOverride()).isFalse()

		state.setOverride(DispatcherMode.SEMI_AUTO)
		assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.SEMI_AUTO)
		assertThat(state.hasOverride()).isTrue()
	}

	// ── Concurrency (KDoc bullet 7: last-writer-wins) ─────────────────────────

	@Test
	fun `concurrent writers observe a well-defined last-writer-wins state`() =
		runBlocking {
			val state = DispatcherModeState()
			// Writers only ever set SEMI_AUTO or MANUAL — deliberately excluding AUTO
			// (the default). A correct last-writer-wins implementation therefore ends
			// with an override equal to one of these; a lost/corrupted override would
			// fall back to AUTO and fail the post-join assertions below.
			val writtenModes = listOf(DispatcherMode.SEMI_AUTO, DispatcherMode.MANUAL)
			val writers = 16
			val iterations = 1_000

			withTimeout(10.seconds) {
				val jobs =
					(0 until writers).map { i ->
						launch(Dispatchers.Default) {
							repeat(iterations) {
								state.setOverride(writtenModes[i % writtenModes.size])
							}
						}
					}
				jobs.joinAll()
			}

			// An override is active and the effective mode is one the writers actually
			// wrote — never the default AUTO.
			assertThat(state.hasOverride()).isTrue()
			assertTrue(state.getEffectiveMode() in writtenModes)
		}

	// ── Independence between instances ────────────────────────────────────────

	@Test
	fun `separate instances have independent state`() {
		val a = DispatcherModeState()
		val b = DispatcherModeState()

		a.setOverride(DispatcherMode.MANUAL)

		assertThat(a.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)
		assertThat(b.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
		assertThat(b.hasOverride()).isFalse()
	}
}
