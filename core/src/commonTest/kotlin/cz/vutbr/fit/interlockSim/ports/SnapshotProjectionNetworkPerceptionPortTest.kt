/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for SnapshotProjectionNetworkPerceptionPort (SP1.7, Issue #774).
 * Verifies that all projection methods correctly read from the snapshot provider,
 * that snapshot() delegates to the provider, and that captureSnapshot() throws.
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [SnapshotProjectionNetworkPerceptionPort].
 *
 * Covers:
 * - All 8 projection methods read from the snapshot returned by the provider.
 * - Name-based (single-entry) lookups return `null` for unknown identifiers.
 * - [SnapshotProjectionNetworkPerceptionPort.snapshot] delegates to the provider.
 * - [SnapshotProjectionNetworkPerceptionPort.captureSnapshot] throws [UnsupportedOperationException].
 * - Provider is invoked on every call (not cached inside the port).
 *
 * All tests run on the common platform (KMP `commonTest`) — no JUnit 5 or MockK.
 *
 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
 */
class SnapshotProjectionNetworkPerceptionPortTest {
	// ── Test helpers ──────────────────────────────────────────────────────────

	private fun emptySnapshot(simTime: Double = 0.0) =
		SimulationSnapshot(
			simTime = simTime,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = emptyList()
		)

	private fun makePort(snapshot: SimulationSnapshot): SnapshotProjectionNetworkPerceptionPort =
		SnapshotProjectionNetworkPerceptionPort { snapshot }

	// ── signalAspect / allSignalAspects ───────────────────────────────────────

	@Test
	fun `signalAspect returns matching semaphore reading from snapshot`() {
		val reading = SemaphoreReading("zA", Signal.FREE)
		val snap = emptySnapshot().copy(semaphores = listOf(reading))
		val port = makePort(snap)

		assertThat(port.signalAspect("zA")).isEqualTo(reading)
	}

	@Test
	fun `signalAspect returns null for unknown semaphore name`() {
		val port = makePort(emptySnapshot())

		assertThat(port.signalAspect("unknownSem")).isNull()
	}

	@Test
	fun `signalAspect returns first match when snapshot contains multiple semaphores`() {
		val r1 = SemaphoreReading("zA", Signal.FREE)
		val r2 = SemaphoreReading("zB", Signal.STOP)
		val snap = emptySnapshot().copy(semaphores = listOf(r1, r2))
		val port = makePort(snap)

		assertThat(port.signalAspect("zB")).isEqualTo(r2)
	}

	@Test
	fun `allSignalAspects returns all semaphore readings from snapshot`() {
		val r1 = SemaphoreReading("zA", Signal.FREE)
		val r2 = SemaphoreReading("zB", Signal.STOP)
		val snap = emptySnapshot().copy(semaphores = listOf(r1, r2))
		val port = makePort(snap)

		assertThat(port.allSignalAspects()).containsExactly(r1, r2)
	}

	@Test
	fun `allSignalAspects returns empty list when snapshot has no semaphores`() {
		val port = makePort(emptySnapshot())

		assertThat(port.allSignalAspects()).isEmpty()
	}

	// ── blockOccupancy / allBlockOccupancies ──────────────────────────────────

	@Test
	fun `blockOccupancy returns matching block reading from snapshot`() {
		val reading = BlockOccupancyReading("k1", TrackFacility.State.RESERVED, "Train #1")
		val snap = emptySnapshot().copy(blocks = listOf(reading))
		val port = makePort(snap)

		assertThat(port.blockOccupancy("k1")).isEqualTo(reading)
	}

	@Test
	fun `blockOccupancy returns null for unknown block id`() {
		val port = makePort(emptySnapshot())

		assertThat(port.blockOccupancy("unknownBlock")).isNull()
	}

	@Test
	fun `allBlockOccupancies returns all block readings from snapshot`() {
		val r1 = BlockOccupancyReading("k1", TrackFacility.State.FREE, null)
		val r2 = BlockOccupancyReading("k2", TrackFacility.State.OCCUPIED, "Train #2")
		val snap = emptySnapshot().copy(blocks = listOf(r1, r2))
		val port = makePort(snap)

		assertThat(port.allBlockOccupancies()).containsExactly(r1, r2)
	}

	@Test
	fun `allBlockOccupancies returns empty list when snapshot has no blocks`() {
		val port = makePort(emptySnapshot())

		assertThat(port.allBlockOccupancies()).isEmpty()
	}

	// ── trainPosition / allTrainPositions ─────────────────────────────────────

	@Test
	fun `trainPosition returns matching train position from snapshot`() {
		val reading = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
		val snap = emptySnapshot().copy(trainPositions = listOf(reading))
		val port = makePort(snap)

		assertThat(port.trainPosition("Train #1")).isEqualTo(reading)
	}

	@Test
	fun `trainPosition returns null for unknown train id`() {
		val port = makePort(emptySnapshot())

		assertThat(port.trainPosition("unknownTrain")).isNull()
	}

	@Test
	fun `allTrainPositions returns all train position readings from snapshot`() {
		val r1 = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
		val r2 = TrainPositionReading("Train #2", 0.0, 0.0, 0.0, null)
		val snap = emptySnapshot().copy(trainPositions = listOf(r1, r2))
		val port = makePort(snap)

		assertThat(port.allTrainPositions()).containsExactly(r1, r2)
	}

	@Test
	fun `allTrainPositions returns empty list when snapshot has no trains`() {
		val port = makePort(emptySnapshot())

		assertThat(port.allTrainPositions()).isEmpty()
	}

	// ── trainTimetable / allTrainTimetables ───────────────────────────────────

	@Test
	fun `trainTimetable returns matching timetable from snapshot`() {
		val reading = TimetableReading("Train #1", "A", "B", 0.0, 60.0)
		val snap = emptySnapshot().copy(timetables = listOf(reading))
		val port = makePort(snap)

		assertThat(port.trainTimetable("Train #1")).isEqualTo(reading)
	}

	@Test
	fun `trainTimetable returns null for unknown train id`() {
		val port = makePort(emptySnapshot())

		assertThat(port.trainTimetable("unknownTrain")).isNull()
	}

	@Test
	fun `allTrainTimetables returns all timetable readings from snapshot`() {
		val r1 = TimetableReading("Train #1", "A", "B", 0.0, 60.0)
		val r2 = TimetableReading("Train #2", "C", "D", 30.0, 120.0)
		val snap = emptySnapshot().copy(timetables = listOf(r1, r2))
		val port = makePort(snap)

		assertThat(port.allTrainTimetables()).containsExactly(r1, r2)
	}

	@Test
	fun `allTrainTimetables returns empty list when snapshot has no timetables`() {
		val port = makePort(emptySnapshot())

		assertThat(port.allTrainTimetables()).isEmpty()
	}

	// ── snapshot() delegation ─────────────────────────────────────────────────

	@Test
	fun `snapshot returns the snapshot from the provider`() {
		val snap = emptySnapshot(simTime = 42.0)
		val port = makePort(snap)

		assertThat(port.snapshot()).isEqualTo(snap)
	}

	@Test
	fun `snapshot reflects provider update when provider is mutable`() {
		var current = emptySnapshot(simTime = 1.0)
		val port = SnapshotProjectionNetworkPerceptionPort { current }

		assertThat(port.snapshot().simTime).isEqualTo(1.0)

		current = emptySnapshot(simTime = 2.0)
		assertThat(port.snapshot().simTime).isEqualTo(2.0)
	}

	// ── Provider called on each query (not cached) ────────────────────────────

	@Test
	fun `projection methods reflect provider update between calls`() {
		val r1 = SemaphoreReading("zA", Signal.STOP)
		val r2 = SemaphoreReading("zA", Signal.FREE)
		var current = emptySnapshot().copy(semaphores = listOf(r1))
		val port = SnapshotProjectionNetworkPerceptionPort { current }

		assertThat(port.signalAspect("zA")).isEqualTo(r1)

		// Simulate sim thread publishing a new snapshot
		current = emptySnapshot().copy(semaphores = listOf(r2))
		assertThat(port.signalAspect("zA")).isEqualTo(r2)
	}

	// ── captureSnapshot() throws ──────────────────────────────────────────────

	@Test
	fun `captureSnapshot throws UnsupportedOperationException`() {
		val port = makePort(emptySnapshot())

		assertFailsWith<UnsupportedOperationException> {
			port.captureSnapshot()
		}
	}
}
