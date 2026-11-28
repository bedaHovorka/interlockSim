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
import java.util.Locale

/**
 * The #1467 spec §6 measurement of the planning snapshot on Praha (Issue #1468, the first SP4 leaf
 * that grows the snapshot; #1469, #1470 and #1472 re-run it and record the numbers in their PRs).
 *
 * `heavy-test` (review ruling on #1468): manual, run solo under the gate lock before merge, never in
 * CI. Both quantities depend on load and on what the JVM ran before.
 *
 * The test wraps `ShuntingLoop.snapshotCaptureHook` on a Praha Station control run (8 trains, 600 s,
 * seed 1468, 301 captures) and records, per capture, the `System.nanoTime` delta and the
 * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` delta of the simulation thread. It prints
 * median, p99, the run totals and the capture share of the run's wall time. Time is recorded only:
 * the wall-time budget belongs to the `:fast-sim` comparison of #738.
 *
 * **Tripwire, not a budget verdict:** the median allocation must stay at or below
 * [ALLOCATION_TRIPWIRE_BYTES] (the 64 KiB per-snapshot budget). The allocation delta includes the
 * capture's short-lived temporaries, so it is an upper bound of the retained snapshot. Crossing the
 * tripwire means "measure the retained size, for example with JOL", not "the snapshot is too big".
 *
 * Reference numbers (Issue #1468, `e53223fb`; compare like with like):
 * - solo, a fresh JVM: allocation median 27 904 B, p99 37 392 B; capture time median 24–57 µs, total
 *   share of the run 1.0–2.5 %;
 * - inside the full `:core:integrationTest` suite JVM: allocation median 49 248–51 360 B, p99 60 416 B
 *   (most likely JIT profile pollution: escape analysis no longer removes the temporaries; the
 *   retained snapshot is the same);
 * - run by `:core:heavyTest`, filtered to this class: recorded in the #1468 report.
 * The first capture allocates about 570–590 KB (class loading, the lazy registry lookup), which is
 * why p99 and max are printed and not checked.
 */
@Tag("heavy-test")
class PrahaPlanningSnapshotBudgetTest : KoinTestBase() {
	@Test
	fun aPrahaPlanningSnapshotStaysUnderTheAllocationTripwire() {
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
				"of a ${runNanos / NANOS_PER_MILLI} ms run (${"%.3f".format(Locale.ROOT, share * PERCENT)} %, " +
				"median-based ${"%.3f".format(Locale.ROOT, medianShare * PERCENT)} %)"
		)

		assertThat(nanos.size, name = "captures").isGreaterThan(0)
		assertThat(loop.getTrainsExited(), name = "trains exited (the run moved trains)").isGreaterThan(0)
		val tripwire = "median allocated bytes per snapshot (tripwire: crossing it means measure the retained size)"
		assertThat(bytes.percentile(MEDIAN), name = tripwire)
			.isLessThanOrEqualTo(ALLOCATION_TRIPWIRE_BYTES)
	}

	private fun List<Long>.percentile(fraction: Double): Long {
		val sorted = sorted()
		return sorted[((sorted.size - 1) * fraction).toInt()]
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1468L
		const val TRAINS: Int = 8
		const val ALLOCATION_TRIPWIRE_BYTES: Long = 64L * 1024L
		const val MEDIAN: Double = 0.5
		const val P99: Double = 0.99
		const val NANOS_PER_MICRO: Long = 1_000L
		const val NANOS_PER_MILLI: Long = 1_000_000L
		const val PERCENT: Double = 100.0
	}
}
