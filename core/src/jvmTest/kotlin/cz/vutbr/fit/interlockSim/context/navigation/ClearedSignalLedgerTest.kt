/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests of [ClearedSignalLedger] (Issue #1169), the cleared-signal bookkeeping moved out of
 * [DefaultPathReservationService]. Real `vyhybna.xml` semaphores are lit and read directly; no
 * reservation runs, so every aspect change seen here is the ledger's own.
 */
class ClearedSignalLedgerTest : PathReservationServiceTestBase() {
	private val ledger = ClearedSignalLedger()

	private fun lit(name: String): DynamicRailSemaphore = findSemaphoreByName(name).also { it.signal = Signal.S80 }

	@Test
	fun `a semaphore still at STOP is not recorded`() {
		ledger.recordClearedSemaphore("t1", findSemaphoreByName("zA"))

		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `resetClearedSemaphores returns every owned semaphore to STOP and forgets the train`() {
		val zA = lit("zA")
		val doB1 = lit("doB1")
		ledger.recordClearedSemaphore("t1", zA)
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetClearedSemaphores("t1")

		assertThat(zA.signal).isEqualTo(Signal.STOP)
		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `a semaphore re-cleared for another train is left lit by the first train's reset`() {
		val zA = lit("zA")
		ledger.recordClearedSemaphore("t1", zA)
		ledger.recordClearedSemaphore("t2", zA)

		ledger.resetClearedSemaphores("t1")

		assertThat(zA.signal).isEqualTo(Signal.S80)
		assertThat(ledger.hasClearedSignals("t2")).isTrue()
	}

	@Test
	fun `resetSemaphoreSet resets only the given subset and keeps the rest recorded`() {
		val zA = lit("zA")
		val doB1 = lit("doB1")
		ledger.recordClearedSemaphore("t1", zA)
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetSemaphoreSet("t1", setOf(doB1))

		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(zA.signal).isEqualTo(Signal.S80)
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(zA)
	}

	@Test
	fun `resetSemaphoreSet drops the train's entry once its set empties`() {
		val zA = lit("zA")
		ledger.recordClearedSemaphore("t1", zA)

		ledger.resetSemaphoreSet("t1", setOf(zA))

		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `snapshotClearedSemaphores is a copy, empty for an unknown train`() {
		val zA = lit("zA")
		ledger.recordClearedSemaphore("t1", zA)
		val snapshot = ledger.snapshotClearedSemaphores("t1")

		ledger.resetClearedSemaphores("t1")

		assertThat(snapshot.toList()).containsExactly(zA)
		assertThat(ledger.snapshotClearedSemaphores("unknown")).isEmpty()
	}

	@Test
	fun `resetUnrecordedStartSignal resets a lit START nobody recorded`() {
		val doA1 = lit("doA1")

		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = emptySet())

		assertThat(doA1.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `resetUnrecordedStartSignal resets an InOut START's inSemaphore`() {
		val inOutA = inOutNamed("A")
		inOutA.inSemaphore.signal = Signal.S80

		ledger.resetUnrecordedStartSignal("t1", inOutA, clearedBeforeStart = emptySet())

		assertThat(inOutA.inSemaphore.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `resetUnrecordedStartSignal leaves a START that was cleared before the attempt`() {
		val doA1 = lit("doA1")
		ledger.recordClearedSemaphore("t1", doA1)

		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = setOf(doA1))

		assertThat(doA1.signal).isEqualTo(Signal.S80)
		assertThat(ledger.hasClearedSignals("t1")).isTrue()
	}

	@Test
	fun `resetUnrecordedStartSignal leaves a START owned by another train`() {
		val doA1 = lit("doA1")
		ledger.recordClearedSemaphore("t2", doA1)

		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = emptySet())

		assertThat(doA1.signal).isEqualTo(Signal.S80)
	}

	@Test
	fun `resetCandidateSignals resets what the candidate recorded since the snapshot`() {
		val zA = lit("zA")
		ledger.recordClearedSemaphore("t1", zA)
		val before = ledger.snapshotClearedSemaphores("t1")
		val doB1 = lit("doB1")
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetCandidateSignals("t1", findSemaphoreByName("doA1"), before)

		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(zA.signal).isEqualTo(Signal.S80)
	}

	@Test
	fun `resetSignalsIfMergeAborted resets only on an aborted merge`() {
		val pathInfo = mockk<PathInfo>()
		val doB1 = lit("doB1")
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetSignalsIfMergeAborted(
			PathReservationRegistry.MergeOutcome.Merged(pathInfo),
			"t1",
			findSemaphoreByName("doA1"),
			emptySet()
		)
		assertThat(doB1.signal).isEqualTo(Signal.S80)

		ledger.resetSignalsIfMergeAborted(
			PathReservationRegistry.MergeOutcome.Aborted(pathInfo, "test abort"),
			"t1",
			findSemaphoreByName("doA1"),
			emptySet()
		)
		assertThat(doB1.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `resetSemaphoresForReleasedBlocks resets owned semaphores bounding the released blocks`() {
		val doB1 = lit("doB1")
		val zA = lit("zA")
		ledger.recordClearedSemaphore("t1", doB1)
		ledger.recordClearedSemaphore("t1", zA)

		ledger.resetSemaphoresForReleasedBlocks("t1", listOf(blockBetween("doB1", "vB")))

		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(zA.signal).isEqualTo(Signal.S80)
	}

	// Issue #1103's producer contract: a listener must not throw, and every producer of a signal
	// write must contain a throwing listener. These three pins hold the ledger to that contract
	// on each of its reset paths. The injected failure sits on the semaphore's listener list, so
	// the `signal = STOP` write itself misbehaves mid-flight (the field is already written when
	// the listener fires), and the ledger must still finish its bookkeeping.

	@Test
	fun `resetClearedSemaphores survives a throwing STOP write and still resets the rest`() {
		val zA = lit("zA")
		val doB1 = lit("doB1")
		zA.addPropertyChangeListener { throw IllegalStateException("injected signal-write listener failure") }
		ledger.recordClearedSemaphore("t1", zA)
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetClearedSemaphores("t1")

		assertThat(zA.signal).isEqualTo(Signal.STOP)
		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `resetSemaphoreSet survives a throwing STOP write and still resets the rest of the set`() {
		val zA = lit("zA")
		val doB1 = lit("doB1")
		doB1.addPropertyChangeListener { throw IllegalStateException("injected signal-write listener failure") }
		ledger.recordClearedSemaphore("t1", zA)
		ledger.recordClearedSemaphore("t1", doB1)

		ledger.resetSemaphoreSet("t1", setOf(zA, doB1))

		assertThat(zA.signal).isEqualTo(Signal.STOP)
		assertThat(doB1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `resetUnrecordedStartSignal survives a throwing STOP write`() {
		val doA1 = lit("doA1")
		doA1.addPropertyChangeListener { throw IllegalStateException("injected signal-write listener failure") }

		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = emptySet())

		assertThat(doA1.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `resetUnrecordedStartSignal ignores a switch START that carries no signal`() {
		ledger.resetUnrecordedStartSignal("t1", findSwitchByName("vA"), clearedBeforeStart = emptySet())

		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}

	@Test
	fun `resetUnrecordedStartSignal keeps the train's other cleared semaphores`() {
		val zA = lit("zA")
		val doA1 = lit("doA1")
		ledger.recordClearedSemaphore("t1", zA)

		// doA1 stays unrecorded: the partial-write shape (lit by this attempt, never recorded).
		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = emptySet())

		assertThat(doA1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(zA)
	}

	@Test
	fun `resetUnrecordedStartSignal drops the train's entry when its own set empties`() {
		val doA1 = lit("doA1")
		ledger.recordClearedSemaphore("t1", doA1)

		ledger.resetUnrecordedStartSignal("t1", doA1, clearedBeforeStart = emptySet())

		assertThat(doA1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.hasClearedSignals("t1")).isFalse()
	}
}
