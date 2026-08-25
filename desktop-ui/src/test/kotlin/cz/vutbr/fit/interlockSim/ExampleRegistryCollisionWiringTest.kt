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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.Time
import cz.vutbr.fit.interlockSim.sim.Timetable
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.TrainSnapshot
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Regression test for the code-review finding that [ExampleRegistry]'s built-in examples never
 * wired [DefaultCollisionDetectionService.registerTrainSnapshotProvider] into the
 * [MultiTrainLoop]/[cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop] instances they create — the
 * predictive time-to-collision (TTC) detection rule (Goal 3 SP4) was fully implemented and unit
 * tested, but unreachable from any real entry point because nothing ever called it in production.
 *
 * Invokes the private `createMultiTrainLoopExample` factory method reflectively (same technique
 * as [ExampleLoadingTest]), then proves the collision service's snapshot provider resolves real,
 * live train data from the exact [MultiTrainLoop] instance the factory created — rather than the
 * default `{ null }` provider, which would make [cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning.PredictiveCollision]
 * unreachable regardless of listener wiring.
 */
@DisplayName("ExampleRegistry collision predictive-TTC wiring — code review follow-up")
class ExampleRegistryCollisionWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("createMultiTrainLoopExample wires the predictive TTC snapshot provider into the collision service")
	fun multiTrainLoopExampleWiresSnapshotProvider() {
		val registry = get<ExampleRegistry>()
		val context = createExampleContext(registry, get(), "createMultiTrainLoopExample", "multiTrainLoop", "60")
		val mainProcess = context.getMainProcess() as MultiTrainLoop

		// Predict the next train name the same way MultiTrainLoopTest does: Train's counter is a
		// simple shared incrementing field, so a throwaway probe constructed here reveals the
		// number the example's first generated train will be assigned.
		val inOuts = context.getInOuts()
		val probeTimetable =
			Timetable(
				inOuts.first { it.name == "A" },
				inOuts.first { it.name == "B" },
				Time(0.0),
				Time(1.0),
				1.0
			)
		val probeNumber = Train(context, probeTimetable).name.substringAfter("Train #").toInt()
		val expectedTrainName = "Train #${probeNumber + 1}"

		val detectionService =
			context.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		val snapshotProviderField = DefaultCollisionDetectionService::class.java.getDeclaredField("snapshotProvider")
		snapshotProviderField.isAccessible = true
		@Suppress("UNCHECKED_CAST")
		val snapshotProvider = snapshotProviderField.get(detectionService) as (String) -> TrainSnapshot?

		// Run the example on a background thread (mirroring the production Frame/SimulationRunner
		// architecture, which always runs the simulation off the control thread) and poll the
		// service's snapshot provider — not mainProcess.getTrainSnapshot directly, since that
		// would pass regardless of whether ExampleRegistry ever wired anything into the service.
		val simThread = Thread({ context.run() }, "collision-wiring-test-sim")
		simThread.isDaemon = true
		simThread.start()
		try {
			val deadlineMillis = System.currentTimeMillis() + 10_000
			var snapshot: TrainSnapshot? = null
			while (snapshot == null && System.currentTimeMillis() < deadlineMillis) {
				snapshot = snapshotProvider(expectedTrainName)
				if (snapshot == null) Thread.sleep(20)
			}
			assertThat(snapshot).isNotNull()
			assertThat(snapshot!!.trainId).isEqualTo(expectedTrainName)
		} finally {
			context.stop()
			simThread.join(TimeUnit.SECONDS.toMillis(10))
		}
	}
}
