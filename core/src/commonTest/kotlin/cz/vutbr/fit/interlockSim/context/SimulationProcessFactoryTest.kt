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
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.Generator
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.koin.core.component.inject
import kotlin.test.Test

/**
 * Tests for SimulationProcessFactory pattern implementation.
 *
 * Verifies that:
 * - Factory creates correct process types
 * - Factory is injectable from Koin
 * - Contexts use factory for process creation
 */
class SimulationProcessFactoryTest : CommonKoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	@Test
	fun factoryIsInjectable() {
		// Assert
		assertThat(processFactory).isInstanceOf<DefaultSimulationProcessFactory>()
	}

	@Test
	fun createMainProcessReturnsGenerator() {
		// Arrange
		val mockContext = createMockSimulationContext()

		// Act
		val mainProcess = processFactory.createMainProcess(mockContext)

		// Assert
		assertThat(mainProcess).isInstanceOf<Generator>()
	}

	@Test
	fun createInOutWorkerReturnsInOutWorker() {
		// Arrange - Create a context with a properly connected InOut
		val context =
			cz.vutbr.fit.interlockSim.testutil.buildConnectedInOut(
				inOutName = "TestInOut",
				isEntry = true
			)
		val inOut = context.getInOuts().first()

		// Act
		val worker = processFactory.createInOutWorker(context, inOut)

		// Assert
		assertThat(worker).isInstanceOf<InOutWorker>()
	}
}
