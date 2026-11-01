/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Issue #673 (Goal 6 SP2): [MetricsCollectionService.latestSnapshot] read from another thread while
 * a real `ShuntingLoop` runs never yields a torn value.
 *
 * A reader thread polls `latestSnapshot()` for the whole run and checks every value it gets for
 * internal consistency — the derived KPIs agree with the counters they derive from, and time and the
 * cumulative counters never go backwards between two reads. A torn snapshot (fields of two instants)
 * would break one of those checks.
 *
 * Free polling alone could, on a fast machine, finish before it has read anything mid-run, so the
 * first [HANDSHAKES] publications are also handed over explicitly: the snapshot listener (simulation
 * thread) passes the snapshot it just received to the reader and waits until the reader has read
 * `latestSnapshot()`; with the simulation thread parked, that read must return the very same
 * instance. No wall-clock number is asserted.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
@DisplayName("Metrics latest snapshot across threads (Integration)")
@Tag("integration-test")
class MetricsLatestSnapshotConcurrencyTest : KoinTestBase() {
	private companion object {
		/** Long enough for several trains and hundreds of block events on `vyhybna.xml` (409 measured). */
		const val END_TIME = 600L

		/** Publications handed to the reader explicitly. */
		const val HANDSHAKES = 25

		/** Upper bound for one handover; a failure, not a measurement. */
		const val HANDOVER_TIMEOUT_SECONDS = 10L

		/** The reader wakes at least this often when idle, so a stop is always honoured. */
		const val POLL_SECONDS = 1L
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a reader thread never sees a torn latest snapshot while a ShuntingLoop runs`() {
		val context = TestFixtures.loadShuntingSimulationContext(getKoin().get<SimulationContextFactory>())
		prepareShuntingLoop(context, END_TIME)
		val metrics = context.getMetricsServices().getMetricsCollectionService()

		val handover = ArrayBlockingQueue<MetricsSnapshot>(1)
		val handoverRead = ArrayBlockingQueue<MetricsSnapshot>(1)
		val handshakesDone = AtomicInteger(0)
		val violations = ConcurrentLinkedQueue<String>()
		val runFinished = AtomicBoolean(false)

		metrics.onSnapshot { published ->
			if (handshakesDone.get() < HANDSHAKES) {
				handover.put(published)
				val seen = handoverRead.poll(HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				when {
					seen == null -> violations += "handover timed out while reading $published"
					seen !== published -> violations += "handover read $seen, published $published"
				}
				handshakesDone.incrementAndGet()
			}
		}

		val reader =
			thread(name = "metrics-latest-snapshot-reader", isDaemon = true) {
				var previous: MetricsSnapshot? = null
				while (!runFinished.get()) {
					// Bounded even when idle: an empty queue would otherwise park the poll forever
					// and no finally block can wake a poll that waits on an empty queue.
					val handed = handover.poll(POLL_SECONDS, TimeUnit.SECONDS)
					val seen = metrics.latestSnapshot()
					violations += inconsistencies(previous, seen)
					previous = seen
					if (handed != null) handoverRead.put(seen) else Thread.onSpinWait()
				}
			}

		try {
			context.run()
		} finally {
			// A failed or timed-out run must not leave the daemon reader spinning for the rest of the
			// JVM, nor a publisher parked behind a full handover queue (Issue #1309 Copilot thread).
			runFinished.set(true)
			handover.clear()
			handoverRead.clear()
			reader.join(TimeUnit.SECONDS.toMillis(HANDOVER_TIMEOUT_SECONDS))
		}

		assertThat(reader.isAlive, "reader still running").isFalse()
		assertThat(handshakesDone.get(), "handovers").isEqualTo(HANDSHAKES)
		assertThat(violations.toList()).isEmpty()
		assertThat(metrics.latestSnapshot(), "after the run").isEqualTo(metrics.getSnapshot())
	}

	/** What is wrong with [current], alone and against the [previous] read; empty when consistent. */
	private fun inconsistencies(
		previous: MetricsSnapshot?,
		current: MetricsSnapshot
	): List<String> {
		val problems = mutableListOf<String>()
		val throughput = if (current.time > 0.0) current.completedTrains / current.time else 0.0
		if (current.throughput != throughput) problems += "throughput != completed / time in $current"
		val utilization =
			if (current.totalBlocks > 0) current.occupiedBlocks.toDouble() / current.totalBlocks else 0.0
		if (current.utilization != utilization) problems += "utilization != occupied / total in $current"
		if (current.totalBlocks > 0 && current.occupiedBlocks > current.totalBlocks) {
			problems += "more blocks occupied than exist in $current"
		}
		if (previous != null) {
			if (current.time < previous.time) problems += "time went back: $previous then $current"
			if (current.completedTrains < previous.completedTrains) {
				problems += "completed trains went back: $previous then $current"
			}
			if (current.conflictCount < previous.conflictCount) {
				problems += "conflicts went back: $previous then $current"
			}
			// Only ever increments (run-end accounting); the two reservation counters above it are
			// deliberately excluded — they legitimately fall on a release.
			if (current.unresolvedAtRunEndCount < previous.unresolvedAtRunEndCount) {
				problems += "unresolved-at-run-end went back: $previous then $current"
			}
		}
		// A train sits in the holding map only while it holds at least one block (the count map
		// drops a train as it reaches zero), and blocks are exclusive, so this invariant holds at
		// every instant.
		if (current.heldBlocks < current.trainsHoldingReservations) {
			problems += "fewer held blocks than holding trains in $current"
		}
		return problems
	}
}
