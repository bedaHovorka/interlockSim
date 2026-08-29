/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #994: unsynchronised reads of ShuntingLoop.approwedTrains.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * Regression test for Issue #994, [ShuntingLoop] half.
 *
 * [ShuntingLoop.getApprovedTrains] is read off the simulation thread by
 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] on behalf of the Goal 10
 * dispatcher driver, which `ShuntingLoop.startAction` starts as a separate daemon thread.
 * The simulation thread meanwhile admits trains (`approveQueuedTrain`) into and retires
 * terminated trains from the same collection.
 *
 * Before the fix the getter copied the live mutable list, so a concurrent admission or
 * retirement could tear the copy — yielding a `null` element, an
 * `IndexOutOfBoundsException` from the single-element fast path, or a
 * `ConcurrentModificationException`.
 *
 * The test drives a real `vyhybna.xml` run on its own thread under the synchronous
 * dispatcher and hammers the getter from several reader threads.
 */
@Tag("integration-test")
@DisplayName("ShuntingLoop approved-train reads survive concurrent admission and retirement (#994)")
class ShuntingLoopApprovedTrainsRaceTest : KoinTestBase() {
	private companion object {
		/**
		 * Simulation end time. `vyhybna.xml` journeys take roughly 50 simulation seconds, so this
		 * gives the run enough admissions and retirements to expose the race.
		 */
		const val END_TIME: Long = 6000L

		/**
		 * Several readers multiply the chance of landing inside a mutation window. Kept small so
		 * the spinning readers cannot starve the simulation thread on a two-core CI runner.
		 */
		const val READER_THREADS: Int = 3

		val JOIN_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(8)
	}

	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("getApprovedTrains never tears while the simulation mutates the approved set")
	fun `approved train reads are safe off the simulation thread`() {
		val ctx: DefaultSimulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
		testContext = ctx
		val loop = prepareShuntingLoop(ctx, endTime = END_TIME)

		val simRunning = AtomicBoolean(true)
		// One representative per exception type: the JIT strips repeat stack traces, and a broken
		// read fails many times per second, so a flat list would drown the signal.
		val readerFailures = ConcurrentHashMap<String, String>()
		val readCount = AtomicLong(0)
		val startBarrier = CyclicBarrier(READER_THREADS + 1)

		val simThread =
			thread(name = "issue-994-shunting-sim", isDaemon = true) {
				startBarrier.await()
				try {
					ctx.run()
				} finally {
					simRunning.set(false)
				}
			}

		val readerThreads =
			List(READER_THREADS) { index ->
				thread(name = "issue-994-shunting-reader-$index", isDaemon = true) {
					startBarrier.await()
					// The read counter is thread-local and published once at the end: a shared
					// atomic in this loop costs more than the read under test and would shrink
					// the race window the test exists to hit.
					var localReads = 0L
					while (simRunning.get()) {
						try {
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
			}

		simThread.join(JOIN_TIMEOUT_MILLIS)
		simRunning.set(false)
		readerThreads.forEach { it.join(JOIN_TIMEOUT_MILLIS) }

		val failureSummary = readerFailures.values.sorted()
		logger.info {
			"Issue #994 ShuntingLoop race probe: reads=${readCount.get()} " +
				"entered=${loop.getTrainsEntered()} exited=${loop.getTrainsExited()} " +
				"maxConcurrent=${loop.getMaxConcurrentTrains()} failures=$failureSummary"
		}

		assertThat(readCount.get(), name = "the reader threads actually read the approved set").isGreaterThan(0L)
		assertThat(loop.getTrainsExited() > 0, name = "trains were retired during the run").isTrue()
		assertThat(failureSummary, name = "no exception escaped an off-thread approved-train read").isEmpty()
	}
}
