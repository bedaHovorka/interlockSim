/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Wiring contract of the `:desktop-ui` [KoinTestBase] on top of [ContextTracker], which every
 * leak fix since Issue #1026 relies on: every context registered with [KoinTestBase.tracked] is
 * closed by `tearDownKoin()`, the registry is empty afterwards (so the automatic `@AfterEach`
 * re-run is a no-op), and a context closed by hand earlier is simply closed again — `Context.close()`
 * is idempotent.
 *
 * The tracker contract itself (order, clearing, failure handling) is covered once, by
 * `ContextTrackerTest` in `:core` commonTest. Uses the light [buildMinimalSimulation] fixture —
 * the contract is about the Koin scope's lifecycle, so no railway content is needed.
 */
class KoinTestBaseCleanupContractTest : KoinTestBase() {
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
}
