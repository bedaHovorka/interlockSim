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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.buildMinimalSimulation
import org.junit.jupiter.api.Test

/**
 * Wiring contract of `:dispatcher-agent`'s [DispatcherKoinTestBase] on top of `ContextTracker`
 * (Issue #1042, follow-up to #1038/#1039): every context registered with `tracked()` is closed by
 * `tearDownKoin()`, and the registry is empty afterwards. The tracker contract itself is covered
 * once by `ContextTrackerTest` (`:core` commonTest); this test mirrors the equivalent contract
 * test for the other three Koin bases (`core/src/jvmTest/.../KoinTestBaseCleanupContractTest.kt`,
 * `desktop-ui`'s and `CommonKoinTestBase`'s).
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
}
