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
import cz.vutbr.fit.interlockSim.testutil.probeConcurrentReads
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

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
 * dispatcher and hammers the getter from several reader threads. The thread harness lives in
 * [probeConcurrentReads]; this test supplies only the fixture, the read and the asserts.
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
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val loop = prepareShuntingLoop(ctx, endTime = END_TIME)

		val result =
			probeConcurrentReads(
				readerCount = READER_THREADS,
				joinTimeoutMillis = JOIN_TIMEOUT_MILLIS,
				threadNamePrefix = "issue-994-shunting",
				readOnce = { loop.getApprovedTrains().forEach { train -> train.name } },
				runSimulation = { ctx.run() }
			)

		logger.info {
			"Issue #994 ShuntingLoop race probe: reads=${result.totalReads} " +
				"entered=${loop.getTrainsEntered()} exited=${loop.getTrainsExited()} " +
				"maxConcurrent=${loop.getMaxConcurrentTrains()} failures=${result.failures}"
		}

		assertThat(result.totalReads, name = "the reader threads actually read the approved set").isGreaterThan(0L)
		assertThat(loop.getTrainsExited() > 0, name = "trains were retired during the run").isTrue()
		assertThat(result.failures, name = "no exception escaped an off-thread approved-train read").isEmpty()
	}
}
