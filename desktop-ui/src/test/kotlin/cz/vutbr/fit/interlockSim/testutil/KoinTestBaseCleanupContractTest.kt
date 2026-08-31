/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the `KoinTestBase` cleanup path every leak fix since Issue #1026 relies on:
 * a context registered in [testContext] must be closed by `tearDownKoin()`, the field must be
 * reset so the regular `@AfterEach` run of the same method stays safe, and a close before
 * teardown (the multi-context tests do that) must not break the teardown re-close.
 *
 * Uses the light [buildMinimalSimulation] fixture — the contract is about the Koin scope's
 * lifecycle, so no railway content is needed.
 */
class KoinTestBaseCleanupContractTest : KoinTestBase() {
	@Test
	fun `tearDownKoin closes the registered testContext and resets the field`() {
		val context = buildMinimalSimulation()
		testContext = context

		tearDownKoin()

		// Context.close() must have run — the Koin scope the context owns is released.
		assertThat(context.scope.closed, name = "koin scope closed by tearDownKoin()").isTrue()
		// The field is reset, so the @AfterEach invocation of the same method is a safe no-op.
		assertThat(testContext, name = "testContext reset for the @AfterEach rerun").isNull()
	}

	@Test
	fun `a context closed before teardown is safely re-closed by tearDownKoin`() {
		val context = buildMinimalSimulation()
		testContext = context

		// The multi-context tests close their extra contexts by hand before the teardown.
		context.close()

		// Must not throw, and the scope stays closed.
		tearDownKoin()
		assertThat(context.scope.closed, name = "koin scope closed").isTrue()
	}
}
