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
 * Test XMLContextFactory singleton consistency in Koin DI
 *
 * This test verifies that Koin properly manages XMLContextFactory as a singleton
 * and that all factory interfaces (XMLContextFactory, EditingContextFactory,
 * SimulationContextFactory) resolve to the same singleton instance.
 *
 * CRITICAL REQUIREMENTS:
 * - XMLContextFactory from Koin must always return the same instance
 * - EditingContextFactory from Koin must be the same instance as XMLContextFactory
 * - SimulationContextFactory from Koin must be the same instance as XMLContextFactory
 * - Main.contextFactory must return the same instance as Koin's XMLContextFactory
 *
 * These tests ensure singleton consistency is maintained in the Koin DI container.
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
	 * Ensures that Koin returns the same XMLContextFactory instance
	 * every time it's requested.
	 */
	@Test
	fun `Koin XMLContextFactory always returns same singleton instance`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get XMLContextFactory from Koin DI container multiple times
		val instance1 = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(instance1).isNotNull()

		val instance2 = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(instance2).isNotNull()

		val instance3 = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(instance3).isNotNull()

		// Verify they are all the exact same instance (same reference)
		assertThat(instance2).isSameInstanceAs(instance1)
		assertThat(instance3).isSameInstanceAs(instance1)
	}

	/**
	 * Verify EditingContextFactory from Koin is the XMLContextFactory singleton
	 *
	 * Ensures that when code requests EditingContextFactory from Koin,
	 * it receives the XMLContextFactory singleton instance.
	 */
	@Test
	fun `Koin EditingContextFactory is same instance as XMLContextFactory`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get XMLContextFactory from Koin DI container
		val xmlFactory = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(xmlFactory).isNotNull()

		// Get EditingContextFactory from Koin DI container
		val editingFactory = get<EditingContextFactory>(EditingContextFactory::class.java)
		assertThat(editingFactory).isNotNull()

		// Verify they are the exact same instance
		assertThat(editingFactory).isSameInstanceAs(xmlFactory)
	}

	/**
	 * Verify SimulationContextFactory from Koin is the XMLContextFactory singleton
	 *
	 * Ensures that when code requests SimulationContextFactory from Koin,
	 * it receives the XMLContextFactory singleton instance.
	 */
	@Test
	fun `Koin SimulationContextFactory is same instance as XMLContextFactory`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get XMLContextFactory from Koin DI container
		val xmlFactory = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(xmlFactory).isNotNull()

		// Get SimulationContextFactory from Koin DI container
		val simulationFactory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
		assertThat(simulationFactory).isNotNull()

		// Verify they are the exact same instance
		assertThat(simulationFactory).isSameInstanceAs(xmlFactory)
	}

	/**
	 * Verify Main.contextFactory returns the XMLContextFactory singleton
	 *
	 * This ensures that code using Main.contextFactory gets the same instance
	 * as code requesting XMLContextFactory from Koin directly.
	 */
	@Test
	fun `Main contextFactory returns same instance as XMLContextFactory from Koin`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get XMLContextFactory from Koin DI container
		val xmlFactory = get<XMLContextFactory>(XMLContextFactory::class.java)
		assertThat(xmlFactory).isNotNull()

		// Get Main instance and access contextFactory property
		val mainInstance = get<Main>(Main::class.java)
		val contextFactoryFromMain = mainInstance.contextFactory
		assertThat(contextFactoryFromMain).isNotNull()

		// Verify they are the exact same instance
		assertThat(contextFactoryFromMain).isSameInstanceAs(xmlFactory)
	}

	/**
	 * Verify all factory access patterns return the same instance
	 *
	 * Comprehensive test that checks all different ways to access the factory
	 * from Koin return the same singleton instance.
	 */
	@Test
	fun `all factory access patterns return same XMLContextFactory singleton instance`() {
		// Initialize Koin with interlockSimModule
		startKoin {
			modules(interlockSimModule)
		}

		// Get instance through all different Koin access patterns
		val koinXmlFactory = get<XMLContextFactory>(XMLContextFactory::class.java)
		val koinEditingFactory = get<EditingContextFactory>(EditingContextFactory::class.java)
		val koinSimulationFactory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
		val mainFactory = get<Main>(Main::class.java).contextFactory

		// Verify all are non-null
		assertThat(koinXmlFactory).isNotNull()
		assertThat(koinEditingFactory).isNotNull()
		assertThat(koinSimulationFactory).isNotNull()
		assertThat(mainFactory).isNotNull()

		// Verify all are the exact same instance
		assertThat(koinEditingFactory).isSameInstanceAs(koinXmlFactory)
		assertThat(koinSimulationFactory).isSameInstanceAs(koinXmlFactory)
		assertThat(mainFactory).isSameInstanceAs(koinXmlFactory)
	}
}
