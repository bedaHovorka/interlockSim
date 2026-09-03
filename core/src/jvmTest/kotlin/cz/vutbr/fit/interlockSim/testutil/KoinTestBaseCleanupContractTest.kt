/*
 * Brno University of Technology
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
 * Wiring contract of the `:core` JVM [KoinTestBase] on top of [ContextTracker] (Issue #1038):
 * every context registered with `tracked()` is closed by `tearDownKoin()`, and the registry is
 * empty afterwards. The tracker contract itself is covered by `ContextTrackerTest` (commonTest).
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
}
