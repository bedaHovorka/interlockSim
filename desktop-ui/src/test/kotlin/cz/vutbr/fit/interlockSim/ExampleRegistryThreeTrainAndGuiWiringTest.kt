/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.get

/**
 * Wiring tests for the four [ExampleRegistry] factories that no other test class reaches:
 * [ExampleRegistry.createShuntingLoopGuiExample], [ExampleRegistry.createThreeTrainLoopExample],
 * [ExampleRegistry.createMultiTrainLoopGuiExample], and
 * [ExampleRegistry.createThreeTrainLoopGuiExample].
 *
 * Follows the same reflection technique as [ExampleRegistryCollisionWiringTest].
 */
@DisplayName("ExampleRegistry wiring — untested factory methods")
class ExampleRegistryThreeTrainAndGuiWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun enableRealTimeSync(loop: MultiTrainLoop): Boolean {
		val field = MultiTrainLoop::class.java.getDeclaredField("enableRealTimeSync")
		field.isAccessible = true
		return field.getBoolean(loop)
	}

	@Test
	@DisplayName("createShuntingLoopGuiExample wires the scoped DelegatingSimulationController")
	fun shuntingLoopGuiExampleWiresDelegatingController() {
		val registry = get<ExampleRegistry>()
		val context = createExampleContext(registry, get(), "createShuntingLoopGuiExample", "shuntingLoop", "60")

		assertThat(context.mainProcess).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat(context.scope.get<DelegatingSimulationController>()).isNotNull()
	}

	@Test
	@DisplayName("createThreeTrainLoopExample wires the predictive TTC snapshot provider into the collision service")
	fun threeTrainLoopExampleWiresSnapshotProvider() {
		val registry = get<ExampleRegistry>()
		val context = createExampleContext(registry, get(), "createThreeTrainLoopExample", "threeTrainLoop", "60")
		val mainProcess = context.mainProcess as ThreeTrainLoop

		assertThat(enableRealTimeSync(mainProcess)).isFalse()
		val detectionService =
			context.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		val snapshotProviderField = DefaultCollisionDetectionService::class.java.getDeclaredField("snapshotProvider")
		snapshotProviderField.isAccessible = true
		assertThat(snapshotProviderField.get(detectionService)).isNotNull()
	}

	@Test
	@DisplayName("createMultiTrainLoopGuiExample builds a real-time-synced MultiTrainLoop")
	fun multiTrainLoopGuiExampleEnablesRealTimeSync() {
		val registry = get<ExampleRegistry>()
		val context = createExampleContext(registry, get(), "createMultiTrainLoopGuiExample", "multiTrainLoop", "60")
		val mainProcess = context.mainProcess as MultiTrainLoop

		assertThat(enableRealTimeSync(mainProcess)).isTrue()
	}

	@Test
	@DisplayName("createThreeTrainLoopGuiExample builds a real-time-synced ThreeTrainLoop")
	fun threeTrainLoopGuiExampleEnablesRealTimeSync() {
		val registry = get<ExampleRegistry>()
		val context = createExampleContext(registry, get(), "createThreeTrainLoopGuiExample", "threeTrainLoop", "60")
		val mainProcess = context.mainProcess as ThreeTrainLoop

		assertThat(enableRealTimeSync(mainProcess)).isTrue()
	}
}
