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
import kotlin.test.Test

/**
 * Wiring contract of [CommonKoinTestBase] on top of [ContextTracker] (Issue #1038): every
 * context registered with `tracked()` is closed by `tearDownKoin()`, and each test method starts
 * with an empty registry — which pins the one-instance-per-test lifecycle of kotlin.test on
 * linuxX64 as well as on the JVM.
 *
 * The tracker contract itself (order, clearing, failure handling) lives in [ContextTrackerTest].
 */
class CommonKoinTestBaseCleanupContractTest : CommonKoinTestBase() {
	@Test
	fun tearDownKoinClosesAllTrackedContexts() {
		val a = buildMinimalSimulation().tracked()
		val b = buildMinimalSimulation().tracked()

		tearDownKoin()

		assertThat(a.scope.closed, name = "first tracked context closed").isTrue()
		assertThat(b.scope.closed, name = "second tracked context closed").isTrue()
		// The automatic @AfterTest repeats tearDownKoin() on an empty registry — must not throw.
	}

	// Two probes with the same body: if the test class instance were shared between methods,
	// the second one to run would see two registered contexts.

	@Test
	fun registryIsEmptyAtTheStartOfEachTestFirstProbe() {
		buildMinimalSimulation().tracked()

		assertThat(trackedContextCount).isEqualTo(1)
	}

	@Test
	fun registryIsEmptyAtTheStartOfEachTestSecondProbe() {
		buildMinimalSimulation().tracked()

		assertThat(trackedContextCount).isEqualTo(1)
	}
}
