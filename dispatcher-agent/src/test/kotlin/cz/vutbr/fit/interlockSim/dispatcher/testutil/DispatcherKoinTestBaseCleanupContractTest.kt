/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.testutil.buildMinimalSimulation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.GlobalContext

/**
 * Wiring contract of `:dispatcher-agent`'s [DispatcherKoinTestBase] on top of `ContextTracker`
 * (Issue #1042, follow-up to #1038/#1039): every context registered with `tracked()` is closed
 * by `tearDownKoin()`, the registry is empty afterwards, a context already closed by hand is
 * simply closed again — `Context.close()` is idempotent — and each test method starts with an
 * empty registry. A failing `close()` neither skips the other contexts nor gets swallowed, and
 * `stopKoin()` still runs afterwards (`tearDownKoin`'s `finally` block).
 *
 * The tracker contract itself is covered once by `ContextTrackerTest` (`:core` commonTest); this
 * test mirrors the equivalent contract tests of the other three Koin bases:
 * `core/src/jvmTest/.../KoinTestBaseCleanupContractTest.kt`,
 * `desktop-ui/.../KoinTestBaseCleanupContractTest.kt`, and
 * `core/src/commonTest/.../CommonKoinTestBaseCleanupContractTest.kt`.
 */
class DispatcherKoinTestBaseCleanupContractTest : DispatcherKoinTestBase() {
	@Test
	fun `tearDownKoin closes all tracked contexts`() {
		val a = buildMinimalSimulation().tracked()
		val b = buildMinimalSimulation().tracked()

		tearDownKoin()

		assertThat(a.scope.closed, name = "first tracked context closed").isTrue()
		assertThat(b.scope.closed, name = "second tracked context closed").isTrue()
		assertThat(trackedContextCount, name = "registry cleared").isEqualTo(0)
		// The automatic @AfterEach repeats tearDownKoin() on an empty registry — must not throw.
	}

	@Test
	fun `a context already closed by hand is closed again without error`() {
		val context = buildMinimalSimulation().tracked()
		context.close()

		tearDownKoin()

		assertThat(context.scope.closed, name = "scope closed").isTrue()
	}

	// Two probes with the same body: if the test class instance were shared between methods,
	// the second one to run would see two registered contexts.

	@Test
	fun `registry is empty at the start of each test - first probe`() {
		buildMinimalSimulation().tracked()

		assertThat(trackedContextCount, name = "one context registered").isEqualTo(1)
	}

	@Test
	fun `registry is empty at the start of each test - second probe`() {
		buildMinimalSimulation().tracked()

		assertThat(trackedContextCount, name = "one context registered").isEqualTo(1)
	}

	@Test
	fun `a failing close propagates, cleanup continues, and Koin is still stopped`() {
		val survivor = buildMinimalSimulation().tracked()
		// Tracked last, so it is closed first; the survivor must still be closed afterwards.
		FailingCloseContext(buildMinimalSimulation()).tracked()

		val failure = assertThrows<IllegalStateException> { tearDownKoin() }

		assertThat(failure.message).isEqualTo(FailingCloseContext.MESSAGE)
		assertThat(survivor.scope.closed, name = "survivor closed").isTrue()
		assertThat(trackedContextCount, name = "registry cleared after failure").isEqualTo(0)
		assertThat(GlobalContext.getOrNull(), name = "Koin stopped in finally").isNull()
	}

	/** Closes the real context, then fails — so the test itself leaks nothing. */
	private class FailingCloseContext(
		private val delegate: SimulationContext
	) : SimulationContext by delegate {
		override fun close() {
			delegate.close()
			throw IllegalStateException(MESSAGE)
		}

		companion object {
			const val MESSAGE = "close failed on purpose"
		}
	}
}
