/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the `KoinTestBase` cleanup path every leak fix since Issue #1026 relies on:
 * every context registered via [KoinTestBase.tracked] must be closed by `tearDownKoin()`, the
 * tracked list must be cleared so the automatic `@AfterEach` re-run is a safe no-op, and a
 * context closed by hand before teardown must not abort the remaining cleanup.
 *
 * Uses the light [buildMinimalSimulation] fixture — the contract is about the Koin scope's
 * lifecycle, so no railway content is needed.
 */
class KoinTestBaseCleanupContractTest : KoinTestBase() {
	@Test
	fun `tearDownKoin closes all tracked contexts`() {
		val a = buildMinimalSimulation().tracked()
		val b = buildMinimalSimulation().tracked()

		tearDownKoin()

		assertThat(a.scope.closed, name = "first tracked context closed").isTrue()
		assertThat(b.scope.closed, name = "second tracked context closed").isTrue()
	}

	@Test
	fun `tearDownKoin is safe to call again after clearing the list`() {
		// After tearDownKoin() clears trackedContexts, the automatic @AfterEach invocation of
		// tearDownKoin() finds an empty list and calls stopKoin() on an already-stopped Koin —
		// both must be no-ops. Trigger that scenario by calling tearDownKoin manually here; the
		// @AfterEach will repeat it.
		val context = buildMinimalSimulation().tracked()

		tearDownKoin()
		assertThat(context.scope.closed, name = "closed after first teardown").isTrue()
		// @AfterEach will call tearDownKoin() again with an empty list — must not throw.
	}

	@Test
	fun `a context closed before teardown is safely re-closed by tearDownKoin`() {
		val context = buildMinimalSimulation().tracked()

		// Closing a context before teardown should not abort the remaining tracked cleanup.
		context.close()

		// Must not throw.
		tearDownKoin()
		assertThat(context.scope.closed, name = "scope closed").isTrue()
	}
}
