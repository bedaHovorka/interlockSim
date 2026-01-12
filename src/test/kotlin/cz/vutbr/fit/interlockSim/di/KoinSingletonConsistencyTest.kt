/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.get

/**
 * Test XMLContextFactory singleton consistency between Koin DI and getInstance()
 *
 * This test addresses the backward compatibility concern raised in PR #63:
 * "The removed code used getContextFactory() which returns XMLContextFactory.getInstance()
 * (the singleton), but the new code injects editingContextFactory from Koin. These may be
 * different instances, breaking backward compatibility for any code that expects getInstance()
 * and getContextFactory() to return the same object."
 *
 * CRITICAL REQUIREMENTS:
 * - XMLContextFactory.getInstance() must return the same instance as Koin's singleton
 * - EditingContextFactory from Koin must be the same instance as XMLContextFactory.getInstance()
 * - SimulationContextFactory from Koin must be the same instance as XMLContextFactory.getInstance()
 * - Main.getInstance().getContextFactory() must return the same instance as XMLContextFactory.getInstance()
 *
 * These tests ensure backward compatibility is maintained when migrating from direct
 * getInstance() calls to Koin dependency injection.
 *
 * @see <a href="https://github.com/bedaHovorka/interlockSim/pull/63#discussion_r2680878970">PR #63 Review Comment</a>
 */
@Tag("integration-test")
class KoinSingletonConsistencyTest {

	@AfterEach
	fun tearDown() {
		// Clean up Koin context after each test
		stopKoin()
	}

	/**
	 * Verify XMLContextFactory singleton consistency
	 *
	 * Ensures that the XMLContextFactory instance retrieved from Koin
	 * is the exact same instance as XMLContextFactory.getInstance().
	 */
	@Test
	fun `Koin XMLContextFactory is same instance as singleton getInstance()`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get the singleton instance directly
		val singletonInstance = XMLContextFactory.getInstance()
		assertThat(singletonInstance).isNotNull()

		// Get XMLContextFactory from Koin DI container
		val koinInstance = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(koinInstance).isNotNull()

		// Verify they are the exact same instance (not just equal, but same reference)
		assertThat(koinInstance).isSameInstanceAs(singletonInstance)
	}

	/**
	 * Verify EditingContextFactory from Koin is the XMLContextFactory singleton
	 *
	 * Ensures that when code requests EditingContextFactory from Koin,
	 * it receives the XMLContextFactory singleton instance.
	 */
	@Test
	fun `Koin EditingContextFactory is same instance as XMLContextFactory singleton`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get the singleton instance directly
		val singletonInstance = XMLContextFactory.getInstance()
		assertThat(singletonInstance).isNotNull()

		// Get EditingContextFactory from Koin DI container
		val editingFactory = get<EditingContextFactory>(EditingContextFactory::class.java)
		assertThat(editingFactory).isNotNull()

		// Verify they are the exact same instance
		assertThat(editingFactory).isSameInstanceAs(singletonInstance)
	}

	/**
	 * Verify SimulationContextFactory from Koin is the XMLContextFactory singleton
	 *
	 * Ensures that when code requests SimulationContextFactory from Koin,
	 * it receives the XMLContextFactory singleton instance.
	 */
	@Test
	fun `Koin SimulationContextFactory is same instance as XMLContextFactory singleton`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get the singleton instance directly
		val singletonInstance = XMLContextFactory.getInstance()
		assertThat(singletonInstance).isNotNull()

		// Get SimulationContextFactory from Koin DI container
		val simulationFactory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
		assertThat(simulationFactory).isNotNull()

		// Verify they are the exact same instance
		assertThat(simulationFactory).isSameInstanceAs(singletonInstance)
	}

	/**
	 * Verify Main.getContextFactory() returns the XMLContextFactory singleton
	 *
	 * This is the critical backward compatibility test. It ensures that code
	 * using Main.getInstance().getContextFactory() gets the same instance as
	 * code using XMLContextFactory.getInstance() directly.
	 */
	@Test
	fun `Main getContextFactory returns same instance as XMLContextFactory singleton`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get the singleton instance directly
		val singletonInstance = XMLContextFactory.getInstance()
		assertThat(singletonInstance).isNotNull()

		// Get Main instance and call getContextFactory()
		val mainInstance = Main.getInstance()
		val contextFactoryFromMain = mainInstance.getContextFactory()
		assertThat(contextFactoryFromMain).isNotNull()

		// Verify they are the exact same instance
		assertThat(contextFactoryFromMain).isSameInstanceAs(singletonInstance)
	}

	/**
	 * Verify all factory access patterns return the same instance
	 *
	 * Comprehensive test that checks all different ways to access the factory
	 * return the same singleton instance.
	 */
	@Test
	fun `all factory access patterns return same XMLContextFactory singleton instance`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get instance through all different access patterns
		val singletonInstance = XMLContextFactory.getInstance()
		val koinXmlFactory = get<XMLContextFactory>(XMLContextFactory::class.java)
		val koinEditingFactory = get<EditingContextFactory>(EditingContextFactory::class.java)
		val koinSimulationFactory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
		val mainFactory = Main.getInstance().getContextFactory()

		// Verify all are non-null
		assertThat(singletonInstance).isNotNull()
		assertThat(koinXmlFactory).isNotNull()
		assertThat(koinEditingFactory).isNotNull()
		assertThat(koinSimulationFactory).isNotNull()
		assertThat(mainFactory).isNotNull()

		// Verify all are the exact same instance
		assertThat(koinXmlFactory).isSameInstanceAs(singletonInstance)
		assertThat(koinEditingFactory).isSameInstanceAs(singletonInstance)
		assertThat(koinSimulationFactory).isSameInstanceAs(singletonInstance)
		assertThat(mainFactory).isSameInstanceAs(singletonInstance)
	}
}
