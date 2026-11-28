/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.ModelfileHashSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get

/**
 * Issue #1342 review finding 3: the `/api/show` fake is the structural default of every
 * [KoinTestBase] test, not something each test module has to remember. A test module that includes
 * `dispatcherAgentModule` and nothing else still resolves [testModelfileHashSource], so no test
 * that builds an LLM example reads a live Ollama.
 */
@DisplayName("KoinTestBase binds the Modelfile hash fake by default (Issue #1342)")
class KoinTestBaseModelfileHashSourceTest : KoinTestBase() {
	override fun getTestModule(): Module = module { includes(dispatcherAgentModule) }

	@Test
	fun `a bare dispatcherAgentModule test module resolves the fake source`() {
		assertThat(get<ModelfileHashSource>()).isSameInstanceAs(testModelfileHashSource)
	}
}
