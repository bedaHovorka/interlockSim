/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get
import java.lang.management.ManagementFactory

/**
 * The #1467 spec §6 measurement of the planning snapshot on Praha (Issue #1468, the first SP4 leaf
 * that grows the snapshot; #1469, #1470 and #1472 re-run it and record the numbers in their PRs).
 *
 * The test wraps `ShuntingLoop.snapshotCaptureHook` on a Praha Station control run and records, per
 * capture, the `System.nanoTime` delta and the `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`
 * delta of the simulation thread. The allocated bytes are an upper bound of the snapshot's size: they
 * include the short-lived temporaries of the capture. It prints median, p99 and the run totals, and
 * asserts the two spec budgets:
 * - at most [MAX_BYTES_PER_SNAPSHOT] (64 KiB) allocated per snapshot, at p99;
 * - the captures take at most [MAX_CAPTURE_SHARE] (3 %) of the run's wall time, counted as the median
 *   capture time times the number of captures. The median is used because a single capture the OS
 *   preempts under machine load adds milliseconds that the capture itself did not spend; the raw total
 *   is printed next to it.
 *
 * The `:fast-sim` wall-time check stays the #738 gate; this test is the per-capture view.
 */
@Tag("integration-test")
class PrahaPlanningSnapshotBudgetTest : KoinTestBase() {
	@Test
	fun aPrahaPlanningSnapshotStaysWithinItsSizeAndTimeBudget() {
		val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
		assertThat(threads.isThreadAllocatedMemorySupported, name = "thread allocation measurement").isTrue()
		threads.isThreadAllocatedMemoryEnabled = true

		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get()).tracked()
		ctx.getInOuts()
		val loop =
			ShuntingLoop(
				ctx,
				END_TIME,
				seed = SEED,
				maxTrains = TRAINS,
				switchLegalPairsOnly = true
			)
		wireSynchronousDispatcher(ctx, loop)
		val capture = checkNotNull(loop.snapshotCaptureHook)
		val nanos = mutableListOf<Long>()
		val bytes = mutableListOf<Long>()
		var largest = 0
		loop.snapshotCaptureHook = {
			val threadId = Thread.currentThread().threadId()
			val bytesBefore = threads.getThreadAllocatedBytes(threadId)
			val start = System.nanoTime()
			val snapshot = capture()
			nanos += System.nanoTime() - start
			bytes += threads.getThreadAllocatedBytes(threadId) - bytesBefore
			largest = maxOf(largest, snapshot.switches.size + snapshot.blocks.size + snapshot.semaphores.size)
			snapshot
		}
		ctx.setMainProcess(loop)

		val runStart = System.nanoTime()
		ctx.run()
		val runNanos = System.nanoTime() - runStart

		val captureNanos = nanos.sum()
		val share = captureNanos.toDouble() / runNanos
		val medianShare = nanos.percentile(MEDIAN).toDouble() * nanos.size / runNanos
		// stdout, not the logger: test logging is WARN-only, and the numbers belong in the JUnit XML.
		println(
			"Praha planning snapshot (#1467 spec §6): ${nanos.size} captures, " +
				"elements per snapshot up to $largest, trains exited ${loop.getTrainsExited()}; " +
				"allocated bytes per capture median ${bytes.percentile(MEDIAN)} p99 ${bytes.percentile(P99)} " +
				"max ${bytes.max()}; capture time median ${nanos.percentile(MEDIAN) / NANOS_PER_MICRO} us " +
				"p99 ${nanos.percentile(P99) / NANOS_PER_MICRO} us; captures total ${captureNanos / NANOS_PER_MILLI} ms " +
				"of a ${runNanos / NANOS_PER_MILLI} ms run (${"%.3f".format(share * PERCENT)} %, " +
				"median-based ${"%.3f".format(medianShare * PERCENT)} %)"
		)

		assertThat(nanos.size, name = "captures").isGreaterThan(0)
		assertThat(loop.getTrainsExited(), name = "trains exited (the run moved trains)").isGreaterThan(0)
		assertThat(bytes.percentile(P99), name = "p99 allocated bytes per snapshot")
			.isLessThanOrEqualTo(MAX_BYTES_PER_SNAPSHOT)
		assertThat(medianShare, name = "median-based capture share of the run wall time")
			.isLessThanOrEqualTo(MAX_CAPTURE_SHARE)
	}

	private fun List<Long>.percentile(fraction: Double): Long {
		val sorted = sorted()
		return sorted[((sorted.size - 1) * fraction).toInt()]
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1468L
		const val TRAINS: Int = 8
		const val MAX_BYTES_PER_SNAPSHOT: Long = 64L * 1024L
		const val MAX_CAPTURE_SHARE: Double = 0.03
		const val MEDIAN: Double = 0.5
		const val P99: Double = 0.99
		const val NANOS_PER_MICRO: Long = 1_000L
		const val NANOS_PER_MILLI: Long = 1_000_000L
		const val PERCENT: Double = 100.0
	}
}
