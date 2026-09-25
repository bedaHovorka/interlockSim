/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication

/**
 * Verifies the process-exit half of the Issue #1072 lifecycle: closing the Koin application that
 * owns [dispatcherAgentModule] closes the shared [OllamaSimpleExecutor] singleton through its
 * `onClose` callback.
 *
 * Uses an isolated [koinApplication] rather than the global container, so the test does not
 * interfere with other Koin-based tests. [OllamaSimpleExecutor.close] is terminal, so a
 * [OllamaSimpleExecutor.getExecutor] call after the container closes must fail fast with
 * [IllegalStateException] without touching the network.
 *
 * @since Issue #1072
 */
class OllamaExecutorKoinCloseTest {
	@Test
	fun `closing the Koin application closes the shared Ollama executor`() {
		val app = koinApplication { modules(dispatcherAgentModule) }
		val executor = app.koin.get<OllamaSimpleExecutor>()
		assertThat(app.koin.get<OllamaSimpleExecutor>()).isSameInstanceAs(executor)

		app.close()

		assertFailure { executor.getExecutor() }.isInstanceOf(IllegalStateException::class)
	}
}
