/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DefaultSnapshotSignal].
 *
 * Verifies the two contracts [AgentLoopDriver] depends on (see [SnapshotSignal]'s
 * KDoc, Issue #746 / SP0.11c):
 * - [SnapshotSignal.await] blocks until [SnapshotSignal.signal] is called, and returns
 *   `true` when it was.
 * - [SnapshotSignal.await] returns `false` (does not block forever) when no signal
 *   arrives within the bounded timeout — the shutdown safety net that lets a driver
 *   thread parked here notice the simulation has stopped signaling instead of leaking
 *   a permanently-blocked thread.
 * - [SnapshotSignal.signal]'s "at-most-one-pending" semantics: multiple signals before
 *   a single [SnapshotSignal.await] collapse to one wake-up, not a queued backlog.
 *
 * @since Issue #746 (SP0.11c — Goal 10)
 */
@DisplayName("SnapshotSignal — sim-to-driver pacing signal")
@Timeout(30, unit = TimeUnit.SECONDS)
class SnapshotSignalTest {
	companion object {
		/** Short timeout so timeout-path tests stay fast without flaking on CI load. */
		private const val SHORT_TIMEOUT_MILLIS = 20L
	}

	// ── Basic signal/await protocol ──────────────────────────────────────────

	@Test
	@DisplayName("await returns true once signal is called")
	fun awaitReturnsTrueAfterSignal() {
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = SHORT_TIMEOUT_MILLIS)

		signal.signal()

		assertThat(signal.await()).isTrue()
	}

	@Test
	@DisplayName("await blocks a waiting thread until signal is called from another thread")
	fun awaitBlocksUntilSignalledFromAnotherThread() {
		// Long timeout relative to the test's own signal delay: this test exercises the
		// "signal arrives before timeout" path, not the timeout path.
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = TimeUnit.SECONDS.toMillis(5))
		val executor = Executors.newSingleThreadExecutor()
		try {
			val awaitStarted = CountDownLatch(1)
			val future =
				executor.submit<Boolean> {
					awaitStarted.countDown()
					signal.await()
				}

			assertThat(awaitStarted.await(5, TimeUnit.SECONDS)).isTrue()
			// Give the await() call a moment to actually park before signalling, so this
			// test exercises the "wakes an already-blocked waiter" path, not a lucky race.
			Thread.sleep(50)
			signal.signal()

			assertThat(future.get(5, TimeUnit.SECONDS)).isTrue()
		} finally {
			executor.shutdownNow()
		}
	}

	// ── Bounded-timeout shutdown safety net ──────────────────────────────────

	@Test
	@DisplayName("await returns false when no signal arrives within the timeout")
	fun awaitReturnsFalseOnTimeout() {
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = SHORT_TIMEOUT_MILLIS)

		assertThat(signal.await()).isFalse()
	}

	@Test
	@DisplayName("a signal pending before a timed-out await is still delivered to the next await")
	fun pendingSignalSurvivesAPriorTimeout() {
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = SHORT_TIMEOUT_MILLIS)

		// No signal() call yet — this await() must time out.
		assertThat(signal.await()).isFalse()

		// Now signal, and confirm the NEXT await() call picks it up — no decision
		// opportunity is permanently lost just because one await() call timed out first.
		signal.signal()
		assertThat(signal.await()).isTrue()
	}

	// ── At-most-one-pending coalescing ────────────────────────────────────────

	@Test
	@DisplayName("multiple signals before one await collapse to a single pending permit")
	fun multipleSignalsCollapseToOnePermit() {
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = SHORT_TIMEOUT_MILLIS)

		signal.signal()
		signal.signal()
		signal.signal()

		// One await() consumes the single coalesced permit.
		assertThat(signal.await()).isTrue()
		// A second await() with no further signal() must time out — proving the three
		// earlier signal() calls did not queue up three permits.
		assertThat(signal.await()).isFalse()
	}

	@RepeatedTest(20)
	@DisplayName("concurrent signal/await round-trips never lose or duplicate a wake-up")
	fun concurrentSignalAwaitRoundTrips() {
		// Long timeout: this test's producer/consumer pacing is handshake-driven (via
		// [ack] below), not wall-clock-driven, so await() should never actually need to
		// wait anywhere near this long — it is a safety net against a genuine hang, not a
		// budget the test is expected to run close to.
		val signal = DefaultSnapshotSignal(awaitTimeoutMillis = TimeUnit.SECONDS.toMillis(10))
		val executor = Executors.newSingleThreadExecutor()
		val rounds = 50
		val awaitTrueCount = AtomicInteger(0)
		// Consumer→producer handshake: the producer only sends the next signal() once the
		// consumer's await() call for the CURRENT round has already returned. Without this,
		// a producer racing ahead of a slow-to-schedule consumer (e.g. under CI/CPU
		// contention from other tasks running concurrently) can legitimately coalesce two
		// signal() calls into one — that IS this class's documented "at-most-one-pending"
		// contract (see SnapshotSignal's KDoc), not a bug, so asserting round-for-round
		// delivery without this handshake was asserting a guarantee the class never made.
		val ack = java.util.concurrent.Semaphore(0)
		try {
			val future =
				executor.submit {
					repeat(rounds) {
						if (signal.await()) {
							awaitTrueCount.incrementAndGet()
						}
						ack.release()
					}
				}

			repeat(rounds) {
				signal.signal()
				ack.acquireUninterruptibly()
			}

			future.get(30, TimeUnit.SECONDS)
			assertThat(awaitTrueCount.get()).isEqualTo(rounds)
		} finally {
			executor.shutdownNow()
		}
	}

	/**
	 * Issue #847 round 4 (R4-1): make the coalescing loss measurable.
	 *
	 * Round 3 reported ~301 control ticks against 20-29 planner cycles per run and could not
	 * reconcile them. The gap is this class's at-most-one-pending-permit rule, which is correct and
	 * deliberate — but while the driver is blocked in a 10-25 s inference, every tick that elapses
	 * silently overwrites the previous permit and vanishes without a trace.
	 *
	 * Counting the drained permits turns "I could not reconcile the counters" into a number the
	 * end-of-run summary can print, which is what #847's sweep needs to judge decision rate.
	 */
	@Test
	@DisplayName("signals dropped by coalescing are counted so the tick-to-cycle gap is measurable")
	fun coalescedSignalsAreCounted() {
		val signal = DefaultSnapshotSignal()

		// Four ticks elapse while the driver is busy; only the last one survives as a permit.
		repeat(4) { signal.signal() }

		assertThat(signal.signalCount, "signalCount").isEqualTo(4L)
		assertThat(signal.coalescedCount, "coalescedCount").isEqualTo(3L)
		assertThat(signal.await(), "the surviving permit still wakes the driver").isTrue()
	}

	@Test
	@DisplayName("a signal consumed before the next one drops nothing")
	fun consumedSignalsAreNotCountedAsCoalesced() {
		val signal = DefaultSnapshotSignal()

		repeat(3) {
			signal.signal()
			signal.await()
		}

		assertThat(signal.signalCount, "signalCount").isEqualTo(3L)
		assertThat(signal.coalescedCount, "coalescedCount").isEqualTo(0L)
	}
}
