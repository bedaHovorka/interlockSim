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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

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
 * the collision-detection path. Which of the two surfaces depends on JIT state, so the test
 * records one representative per exception type and fails on any of them.
 *
 * This test runs a real multi-train simulation on its own thread while a second thread
 * hammers both readers, and asserts that nothing escapes.
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
				trainSpecs =
					(0 until TRAIN_COUNT).map { index ->
						MultiTrainLoop.TrainSpec(
							inName = "A",
							outName = "B",
							inTime = index * TRAIN_INTERVAL,
							length = TRAIN_LENGTH
						)
					},
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS
			)
		ctx.setMainProcess(loop)

		val simRunning = AtomicBoolean(true)
		// One representative per exception type: the JIT strips repeat stack traces, and a
		// broken read fails thousands of times per second, so a flat list would drown the signal.
		val readerFailures = ConcurrentHashMap<String, String>()
		val readCount = AtomicLong(0)
		val startBarrier = CyclicBarrier(2)

		val simThread =
			thread(name = "issue-994-sim", isDaemon = true) {
				startBarrier.await()
				try {
					ctx.run()
				} finally {
					simRunning.set(false)
				}
			}

		val readerThread =
			thread(name = "issue-994-reader", isDaemon = true) {
				startBarrier.await()
				// The read counter is thread-local and published once at the end: a shared atomic
				// in this loop costs more than the reads under test and would shrink the race
				// window the test exists to hit.
				var localReads = 0L
				while (simRunning.get()) {
					try {
						loop.getTrainSnapshot(ABSENT_TRAIN_ID)
						loop.getApprovedTrains().forEach { train -> train.name }
						localReads++
					} catch (failure: Throwable) {
						readerFailures.putIfAbsent(
							failure::class.simpleName.orEmpty(),
							"${failure::class.simpleName} thrown at ${failure.stackTrace.firstOrNull()}"
						)
					}
				}
				readCount.addAndGet(localReads)
			}

		simThread.join(JOIN_TIMEOUT_MILLIS)
		simRunning.set(false)
		readerThread.join(JOIN_TIMEOUT_MILLIS)

		val failureSummary = readerFailures.values.sorted()
		logger.info {
			"Issue #994 race probe: reads=${readCount.get()} entered=${loop.getTrainsEntered()} " +
				"exited=${loop.getTrainsExited()} maxConcurrent=${loop.getMaxConcurrentTrains()} " +
				"failures=$failureSummary"
		}

		assertThat(readCount.get(), name = "the reader thread actually read the approved set").isGreaterThan(0L)
		assertThat(loop.getTrainsExited() > 0, name = "trains were retired during the run").isTrue()
		assertThat(failureSummary, name = "no exception escaped an off-thread approved-train read").isEmpty()
	}
}
