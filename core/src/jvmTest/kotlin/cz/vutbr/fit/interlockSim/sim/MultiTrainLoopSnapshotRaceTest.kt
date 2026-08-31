/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #994: unsynchronised reads of MultiTrainLoop.approvedTrains.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.probeConcurrentReads
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Regression test for Issue #994.
 *
 * [MultiTrainLoop.getTrainSnapshot] is the registered snapshot provider for
 * [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService], and
 * [MultiTrainLoop.getApprovedTrains] backs the animation and dispatcher perception ports.
 * Both are called from threads other than the kDisco simulation thread — the production
 * `Frame`/`SimulationRunner` architecture always runs the simulation off the control thread,
 * and `ExampleRegistryCollisionWiringTest` polls the snapshot provider exactly that way.
 *
 * The simulation thread meanwhile admits trains into and retires trains from the same
 * collection on every iteration. Before the fix both readers walked the live mutable list, so
 * a mutation landing inside a read escaped as either a [java.util.ConcurrentModificationException]
 * or a [NullPointerException] on a slot the simulation thread had just cleared — straight out of
 * the collision-detection path. Which of the two surfaces depends on JIT state, so the probe
 * records one representative per exception type and fails on any of them.
 *
 * This test runs a real multi-train simulation on its own thread while a second thread
 * hammers both readers, and asserts that nothing escapes. The thread harness lives in
 * [probeConcurrentReads]; this test supplies only the topology, the workload and the asserts.
 */
@Tag("integration-test")
@DisplayName("MultiTrainLoop approved-train reads survive concurrent admission and retirement (#994)")
class MultiTrainLoopSnapshotRaceTest : KoinTestBase() {
	private companion object {
		/** Number of trains injected — every completion retires an entry from `approvedTrains`. */
		const val TRAIN_COUNT: Int = 60

		/** Simulation seconds between two consecutive train injections. */
		const val TRAIN_INTERVAL: Double = 1.0

		/** Short trains complete quickly, which maximises the admit/retire churn rate. */
		const val TRAIN_LENGTH: Double = 20.0

		/** Simulation end time — long enough for every injected train to finish. */
		const val END_TIME: Long = 2000L

		/** Several trains approved at once, so the readers always walk a multi-element list. */
		const val MAX_CONCURRENT_TRAINS: Int = 30

		/**
		 * A train id that is never approved. The collision path queries retired and not-yet-approved
		 * trains this way, and the miss forces a full scan of the list — the widest realistic read.
		 */
		const val ABSENT_TRAIN_ID: String = "Train #-1"

		val JOIN_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(4)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("getTrainSnapshot and getApprovedTrains never throw while the simulation mutates the approved set")
	fun `approved train readers are safe off the simulation thread`() {
		val ctx: DefaultSimulationContext = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		ctx.getInOuts()
		testContext = ctx

		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = multiTrainSpecs(TRAIN_COUNT, TRAIN_INTERVAL, TRAIN_LENGTH),
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS
			)
		ctx.setMainProcess(loop)

		val result =
			probeConcurrentReads(
				readerCount = 1,
				joinTimeoutMillis = JOIN_TIMEOUT_MILLIS,
				threadNamePrefix = "issue-994",
				readOnce = {
					loop.getTrainSnapshot(ABSENT_TRAIN_ID)
					loop.getApprovedTrains().forEach { train -> train.name }
				},
				runSimulation = { ctx.run() }
			)

		logger.info {
			"Issue #994 race probe: reads=${result.totalReads} entered=${loop.getTrainsEntered()} " +
				"exited=${loop.getTrainsExited()} maxConcurrent=${loop.getMaxConcurrentTrains()} " +
				"failures=${result.failures}"
		}

		assertThat(result.totalReads, name = "the reader thread actually read the approved set").isGreaterThan(0L)
		assertThat(loop.getTrainsExited() > 0, name = "trains were retired during the run").isTrue()
		assertThat(result.failures, name = "no exception escaped an off-thread approved-train read").isEmpty()
	}
}
