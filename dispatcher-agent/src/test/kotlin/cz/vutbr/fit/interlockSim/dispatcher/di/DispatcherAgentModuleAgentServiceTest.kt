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

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.inject

/**
 * Unit tests for [dispatcherAgentModule] SP1 bindings (SP1.2, Issue #547).
 *
 * Tests that the Koin DI module correctly wires AgentService.
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
class DispatcherAgentModuleAgentServiceTest {
	@BeforeEach
	fun setup() {
		startKoin {
			modules(dispatcherAgentModule)
		}
	}

	@AfterEach
	fun cleanup() {
		stopKoin()
	}

	@Test
	fun `AgentService is provided as singleton by dispatcherAgentModule`() {
		val service: AgentService by inject(AgentService::class.java)

		assertThat(service).isNotNull()
		assertThat(service).isInstanceOf(DefaultAgentService::class)
	}

	@Test
	fun `AgentService is reused as singleton (identity)`() {
		val service1: AgentService by inject(AgentService::class.java)
		val service2: AgentService by inject(AgentService::class.java)

		assertThat(service1).isNotNull()
		assertThat(service2).isNotNull()
		// Both should be the same instance (singleton scope)
		assert(service1 === service2) { "AgentService should be a singleton" }
	}
}
