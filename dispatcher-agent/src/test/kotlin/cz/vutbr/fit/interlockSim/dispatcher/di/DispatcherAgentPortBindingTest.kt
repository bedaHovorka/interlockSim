/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatformTools

/**
 * SP1.4 (#549) — verifies the per-[DefaultSimulationContext] Koin scoped bindings added to the
 * production [dispatcherAgentModule] actually resolve from a real context scope.
 *
 * `DispatcherAgentModuleSp13Test` only exercises the singleton bindings (it starts Koin with
 * `dispatcherAgentModule` alone and never creates a [DefaultSimulationContext]), so the
 * `scope<DefaultSimulationContext> { scoped<NetworkPerceptionPort> / scoped<NetworkActuatorPort>
 * / scoped<KoogAgentFactory> }` bindings — including the `getSource<DefaultSimulationContext>()
 * ?: throw IllegalStateException(...)` path — went unverified. This class closes that gap by
 * building a real simulation context from `vyhybna.xml` and resolving each scoped binding from
 * the context's own Koin scope.
 *
 * Loads [dispatcherAgentModule] (the production SP1.4 bindings) together with
 * [commonCoreTestModule] (which supplies [cz.vutbr.fit.interlockSim.context.SimulationProcessFactory]
 * for context construction but, unlike [cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule],
 * does not re-declare the port bindings, so there is no duplicate-definition conflict).
 *
 * @since Issue #549 (SP1.4 — Goal 10)
 */
@DisplayName("SP1.4 port bindings resolve from a DefaultSimulationContext Koin scope (#549)")
class DispatcherAgentPortBindingTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentModule, commonCoreTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	@Test
	@DisplayName("scope resolves DefaultNetworkPerceptionPort, DefaultNetworkActuatorPort and KoogAgentFactory")
	fun scopeResolvesSp14PortBindings() {
		loadShuntingLoopContext().use { simCtx ->
			val perceptionPort = simCtx.scope.get<NetworkPerceptionPort>()
			val actuatorPort = simCtx.scope.get<NetworkActuatorPort>()
			val agentFactory = simCtx.scope.get<KoogAgentFactory>()

			assertThat(perceptionPort).isInstanceOf<DefaultNetworkPerceptionPort>()
			assertThat(actuatorPort).isInstanceOf<DefaultNetworkActuatorPort>()
			assertThat(agentFactory).isNotNull()
		}
	}

	@Test
	@DisplayName("scoped port binding throws when no DefaultSimulationContext source is present in the scope")
	fun scopedPortBindingThrowsWithoutContextSource() {
		// A scope created without a source mirrors the failure path the scoped bindings guard
		// against: getSource<DefaultSimulationContext>() returns null → IllegalStateException.
		// Koin wraps the factory's IllegalStateException in an InstanceCreationException, so the
		// assertion walks the cause chain to verify the documented guard fired.
		val scope =
			KoinPlatformTools
				.defaultContext()
				.get()
				.createScope(scopeId = "no-source-test", qualifier = named<DefaultSimulationContext>())
		try {
			val ex = assertThrows<InstanceCreationException> { scope.get<NetworkPerceptionPort>() }
			val cause = assertInstanceOf(IllegalStateException::class.java, ex.cause)
			assertEquals("DefaultSimulationContext source not found in scope", cause.message)
		} finally {
			scope.close()
		}
	}
}
