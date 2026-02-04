/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.Generator
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Tests for SimulationProcessFactory pattern implementation.
 *
 * Verifies that:
 * - Factory creates correct process types
 * - Factory is injectable from Koin
 * - Contexts use factory for process creation
 */
@DisplayName("SimulationProcessFactory")
class SimulationProcessFactoryTest : KoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	@Test
	@DisplayName("factory is injectable from Koin")
	fun factoryIsInjectable() {
		// Assert
		assertThat(processFactory).isNotNull()
		assertThat(processFactory).isInstanceOf<DefaultSimulationProcessFactory>()
	}

	@Test
	@DisplayName("createMainProcess returns Generator")
	fun createMainProcessReturnsGenerator() {
		// Arrange
		val mockContext = createMockSimulationContext()

		// Act
		val mainProcess = processFactory.createMainProcess(mockContext)

		// Assert
		assertThat(mainProcess).isNotNull()
		assertThat(mainProcess).isInstanceOf<Generator>()
	}

	@Test
	@DisplayName("createInOutWorker returns InOutWorker")
	fun createInOutWorkerReturnsInOutWorker() {
		// Arrange - Create a context with a properly connected InOut
		val context = cz.vutbr.fit.interlockSim.testutil.buildConnectedInOut(
			inOutName = "TestInOut",
			isEntry = true
		)
		val inOut = context.getInOuts().first()

		// Act
		val worker = processFactory.createInOutWorker(context, inOut)

		// Assert
		assertThat(worker).isNotNull()
		assertThat(worker).isInstanceOf<InOutWorker>()
	}
}
