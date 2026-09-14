/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * Lifecycle contract of [TestContextBuilder.buildSimulationContext]: the builder's editing context
 * owns a Koin scope of its own, so the conversion must close it, while the returned simulation
 * context stays open for the caller to close.
 */
class TestContextBuilderLifecycleTest : CommonKoinTestBase() {
	@Test
	fun buildSimulationContextClosesTheEditingContextScope() {
		val builder =
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 2, 1, false)
		val editingContext = builder.buildEditingContext()

		builder.buildSimulationContext().use { simulationContext ->
			assertThat(editingContext.scope.closed, name = "editing context closed").isTrue()
			assertThat(simulationContext.scope.closed, name = "simulation context closed").isFalse()
		}
	}
}
