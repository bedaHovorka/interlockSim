/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for SimulationSnapshot (SP0.4, Issue #543).
 * Verifies the data-class contract, field exhaustiveness, and the
 * NetworkPerceptionPort.snapshot() minimal-impl contract.
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import kotlin.test.Test

/**
 * Unit tests for [SimulationSnapshot] and the [NetworkPerceptionPort.snapshot] contract.
 *
 * All tests run on the common platform (KMP `commonTest`) without MockK or JUnit 5
 * so they are executable on both JVM and native targets.
 *
 * @since Issue #543 (SP0.4 — Goal 10 observable simulation state)
 */
class SimulationSnapshotTest {
	// ── Data-class field contract ──────────────────────────────────────────

	@Test
	fun `SimulationSnapshot stores simTime`() {
		val snap =
			SimulationSnapshot(
				simTime = 42.0,
				semaphores = emptyList(),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
		assertThat(snap.simTime).isEqualTo(42.0)
	}

	@Test
	fun `SimulationSnapshot stores semaphore readings`() {
		val reading = SemaphoreReading("zA", Signal.FREE)
		val snap =
			SimulationSnapshot(
				simTime = 0.0,
				semaphores = listOf(reading),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
		assertThat(snap.semaphores).containsExactlyInAnyOrder(reading)
	}

	@Test
	fun `SimulationSnapshot stores block occupancy readings`() {
		val reading = BlockOccupancyReading("k1", TrackFacility.State.RESERVED, "Train #1")
		val snap =
			SimulationSnapshot(
				simTime = 0.0,
				semaphores = emptyList(),
				blocks = listOf(reading),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
		assertThat(snap.blocks).containsExactlyInAnyOrder(reading)
	}

	@Test
	fun `SimulationSnapshot stores train position readings`() {
		val reading = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
		val snap =
			SimulationSnapshot(
				simTime = 0.0,
				semaphores = emptyList(),
				blocks = emptyList(),
				trainPositions = listOf(reading),
				timetables = emptyList()
			)
		assertThat(snap.trainPositions).containsExactlyInAnyOrder(reading)
	}

	@Test
	fun `SimulationSnapshot stores timetable readings`() {
		val reading = TimetableReading("Train #1", "A", "B", 0.0, 60.0)
		val snap =
			SimulationSnapshot(
				simTime = 0.0,
				semaphores = emptyList(),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = listOf(reading)
			)
		assertThat(snap.timetables).containsExactlyInAnyOrder(reading)
	}

	@Test
	fun `SimulationSnapshot equality is structural`() {
		val snap1 =
			SimulationSnapshot(
				simTime = 10.0,
				semaphores = listOf(SemaphoreReading("zA", Signal.STOP)),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
		val snap2 =
			SimulationSnapshot(
				simTime = 10.0,
				semaphores = listOf(SemaphoreReading("zA", Signal.STOP)),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
		assertThat(snap1).isEqualTo(snap2)
	}

	@Test
	fun `SimulationSnapshot copy with different simTime is not equal`() {
		val snap1 = SimulationSnapshot(1.0, emptyList(), emptyList(), emptyList(), emptyList())
		val snap2 = snap1.copy(simTime = 2.0)
		assertThat(snap1 == snap2).isFalse()
	}

	// ── NetworkPerceptionPort.snapshot() stub contract ─────────────────────

	/**
	 * A minimal stub implementation that returns fixed values.
	 * Verifies that the snapshot() method is callable and returns a [SimulationSnapshot].
	 */
	private class StubNetworkPerceptionPort : NetworkPerceptionPort {
		override fun signalAspect(semaphoreName: String): SemaphoreReading? = null

		override fun allSignalAspects(): List<SemaphoreReading> = listOf(SemaphoreReading("stubSem", Signal.STOP))

		override fun blockOccupancy(blockId: String): BlockOccupancyReading? = null

		override fun allBlockOccupancies(): List<BlockOccupancyReading> =
			listOf(BlockOccupancyReading("stubBlock", TrackFacility.State.FREE, null))

		override fun trainPosition(trainId: String): TrainPositionReading? = null

		override fun allTrainPositions(): List<TrainPositionReading> =
			listOf(TrainPositionReading("stubTrain", 0.0, 0.0, 0.0, null))

		override fun trainTimetable(trainId: String): TimetableReading? = null

		override fun allTrainTimetables(): List<TimetableReading> = listOf(TimetableReading("stubTrain", "A", "B", 0.0, 60.0))

		override fun trainPerception(trainId: String): TrainPerceptionReading? = null

		override fun allTrainPerceptions(): List<TrainPerceptionReading> = emptyList()

		override fun snapshot(): SimulationSnapshot =
			SimulationSnapshot(
				simTime = 99.0,
				semaphores = allSignalAspects(),
				blocks = allBlockOccupancies(),
				trainPositions = allTrainPositions(),
				timetables = allTrainTimetables()
			)

		// Stub is not a caching port; both accessors return the same fixed snapshot.
		override fun captureSnapshot(): SimulationSnapshot = snapshot()
	}

	@Test
	fun `NetworkPerceptionPort stub snapshot carries all bulk-query results`() {
		val port = StubNetworkPerceptionPort()
		val snap = port.snapshot()

		assertThat(snap.simTime).isEqualTo(99.0)
		assertThat(snap.semaphores).containsExactlyInAnyOrder(SemaphoreReading("stubSem", Signal.STOP))
		assertThat(snap.blocks).containsExactlyInAnyOrder(
			BlockOccupancyReading("stubBlock", TrackFacility.State.FREE, null)
		)
		assertThat(snap.trainPositions.map { it.trainId }).containsExactlyInAnyOrder("stubTrain")
		assertThat(snap.timetables.map { it.trainId }).containsExactlyInAnyOrder("stubTrain")
	}

	@Test
	fun `NetworkPerceptionPort stub snapshot is empty for an empty network`() {
		val emptyPort =
			object : NetworkPerceptionPort {
				override fun signalAspect(semaphoreName: String): SemaphoreReading? = null

				override fun allSignalAspects(): List<SemaphoreReading> = emptyList()

				override fun blockOccupancy(blockId: String): BlockOccupancyReading? = null

				override fun allBlockOccupancies(): List<BlockOccupancyReading> = emptyList()

				override fun trainPosition(trainId: String): TrainPositionReading? = null

				override fun allTrainPositions(): List<TrainPositionReading> = emptyList()

				override fun trainTimetable(trainId: String): TimetableReading? = null

				override fun allTrainTimetables(): List<TimetableReading> = emptyList()

				override fun trainPerception(trainId: String): TrainPerceptionReading? = null

				override fun allTrainPerceptions(): List<TrainPerceptionReading> = emptyList()

				override fun snapshot(): SimulationSnapshot =
					SimulationSnapshot(
						simTime = 0.0,
						semaphores = allSignalAspects(),
						blocks = allBlockOccupancies(),
						trainPositions = allTrainPositions(),
						timetables = allTrainTimetables()
					)

				// Stub is not a caching port; both accessors return the same fixed snapshot.
				override fun captureSnapshot(): SimulationSnapshot = snapshot()
			}
		val snap = emptyPort.snapshot()

		assertThat(snap.semaphores).isEmpty()
		assertThat(snap.blocks).isEmpty()
		assertThat(snap.trainPositions).isEmpty()
		assertThat(snap.timetables).isEmpty()
	}

	// ── Map-backed lookups (#967) ──────────────────────────────────────────

	@Test
	fun `signalAspect returns matching semaphore reading`() {
		val reading = SemaphoreReading("zA", Signal.FREE)
		val snap = SimulationSnapshot(0.0, listOf(reading), emptyList(), emptyList(), emptyList())

		assertThat(snap.signalAspect("zA")).isEqualTo(reading)
	}

	@Test
	fun `signalAspect returns null for unknown semaphore name`() {
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())

		assertThat(snap.signalAspect("unknownSem")).isNull()
	}

	@Test
	fun `signalAspect keeps the first reading for a duplicate semaphore name`() {
		// associateBy() alone would keep the LAST entry on a duplicate key; the snapshot must
		// preserve the old firstOrNull-scan semantics instead (first match wins).
		val first = SemaphoreReading("zA", Signal.FREE)
		val second = SemaphoreReading("zA", Signal.STOP)
		val snap = SimulationSnapshot(0.0, listOf(first, second), emptyList(), emptyList(), emptyList())

		assertThat(snap.signalAspect("zA")).isEqualTo(first)
	}

	@Test
	fun `blockOccupancy returns matching block reading`() {
		val reading = BlockOccupancyReading("k1", TrackFacility.State.RESERVED, "Train #1")
		val snap = SimulationSnapshot(0.0, emptyList(), listOf(reading), emptyList(), emptyList())

		assertThat(snap.blockOccupancy("k1")).isEqualTo(reading)
	}

	@Test
	fun `blockOccupancy returns null for unknown block id`() {
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())

		assertThat(snap.blockOccupancy("unknownBlock")).isNull()
	}

	@Test
	fun `blockOccupancy keeps the first reading for a duplicate block id`() {
		val first = BlockOccupancyReading("k1", TrackFacility.State.FREE, null)
		val second = BlockOccupancyReading("k1", TrackFacility.State.OCCUPIED, "Train #1")
		val snap = SimulationSnapshot(0.0, emptyList(), listOf(first, second), emptyList(), emptyList())

		assertThat(snap.blockOccupancy("k1")).isEqualTo(first)
	}

	@Test
	fun `trainPosition returns matching train position`() {
		val reading = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), listOf(reading), emptyList())

		assertThat(snap.trainPosition("Train #1")).isEqualTo(reading)
	}

	@Test
	fun `trainPosition returns null for unknown train id`() {
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())

		assertThat(snap.trainPosition("unknownTrain")).isNull()
	}

	@Test
	fun `trainPosition keeps the first reading for a duplicate train id`() {
		val first = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
		val second = TrainPositionReading("Train #1", 20.0, 0.0, 500.0, "k2")
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), listOf(first, second), emptyList())

		assertThat(snap.trainPosition("Train #1")).isEqualTo(first)
	}

	@Test
	fun `trainTimetable returns matching timetable`() {
		val reading = TimetableReading("Train #1", "A", "B", 0.0, 60.0)
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), listOf(reading))

		assertThat(snap.trainTimetable("Train #1")).isEqualTo(reading)
	}

	@Test
	fun `trainTimetable returns null for unknown train id`() {
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())

		assertThat(snap.trainTimetable("unknownTrain")).isNull()
	}

	@Test
	fun `trainTimetable keeps the first reading for a duplicate train id`() {
		val first = TimetableReading("Train #1", "A", "B", 0.0, 60.0)
		val second = TimetableReading("Train #1", "C", "D", 30.0, 120.0)
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), listOf(first, second))

		assertThat(snap.trainTimetable("Train #1")).isEqualTo(first)
	}

	@Test
	fun `trainPerception returns matching perception reading`() {
		val reading =
			TrainPerceptionReading(
				trainId = "Train #1",
				signalAheadName = "zA",
				signalAheadAspect = Signal.FREE,
				distanceToSignalAheadMetres = 50.0,
				currentSpeedLimitMps = 20.0,
				velocity = 10.0,
				acceleration = 0.5,
				totalDistance = 200.0,
				frontSectionName = "k1",
				destinationInOutName = "B",
				scheduledArrivalTime = 120.0,
				isDwelling = false
			)
		val snap =
			SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList(), trainPerceptions = listOf(reading))

		assertThat(snap.trainPerception("Train #1")).isEqualTo(reading)
	}

	@Test
	fun `trainPerception returns null for unknown train id`() {
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())

		assertThat(snap.trainPerception("unknownTrain")).isNull()
	}

	@Test
	fun `map-backed lookups do not affect equals hashCode or toString`() {
		// The lazy lookup maps live in the class body (not the primary constructor), so they
		// must never leak into the generated data-class members even after being populated.
		val semaphores = listOf(SemaphoreReading("zA", Signal.FREE))
		val snap1 = SimulationSnapshot(0.0, semaphores, emptyList(), emptyList(), emptyList())
		val snap2 = SimulationSnapshot(0.0, semaphores, emptyList(), emptyList(), emptyList())

		// Populate snap1's lazy maps before comparing, so a leak would show up as inequality
		// or a hashCode/toString mismatch against the untouched snap2.
		snap1.signalAspect("zA")

		assertThat(snap1).isEqualTo(snap2)
		assertThat(snap1.hashCode()).isEqualTo(snap2.hashCode())
		assertThat(snap1.toString()).isEqualTo(snap2.toString())
	}

	// ── RouteRequestResult — verify no collision with SP0.3 types ─────────

	@Test
	fun `SimulationSnapshot coexists with RouteRequestResult sealed type from SP0-3`() {
		// Compiling both in the same test class ensures the SP0.3 and SP0.4 types
		// are in the same package without collision.
		val routeResult: RouteRequestResult = RouteRequestResult.Reserved("T1", 3)
		val snap = SimulationSnapshot(0.0, emptyList(), emptyList(), emptyList(), emptyList())
		assertThat(snap.simTime).isEqualTo(0.0)
		assertThat(routeResult is RouteRequestResult.Reserved).isTrue()
	}

	// ── NetworkActuatorPort stub — verify SP0.3 types still compile ────────

	@Test
	fun `NetworkActuatorPort stub compiles alongside SP0-4 types`() {
		val actuator =
			object : NetworkActuatorPort {
				override fun requestRoute(
					trainName: String,
					fromEndpointName: String,
					toEndpointName: String
				): RouteRequestResult = RouteRequestResult.NoRouteExists(fromEndpointName, toEndpointName)

				override fun releaseRoute(trainName: String): Boolean = false

				override fun setSwitchPosition(
					switchName: String,
					position: RailSwitch.Conf
				): Boolean = false

				override fun setSignalAspect(
					semaphoreName: String,
					signal: Signal
				): Boolean = false

				// Attributed form required by the abstract 3-arg contract; this compile-check
				// stub does not attribute writes (G5), so it returns false like the 2-arg form.
				override fun setSignalAspect(
					semaphoreName: String,
					signal: Signal,
					trainName: String?
				): Boolean = false
			}

		val result = actuator.requestRoute("T1", "IN", "OUT")
		assertThat(result is RouteRequestResult.NoRouteExists).isTrue()
		// Exercise the attributed 3-arg override so it is covered (the abstract contract requires
		// it to exist; an unexercised override would fail the Sonar new-code coverage gate).
		assertThat(actuator.setSignalAspect("S", Signal.FREE, "T1")).isFalse()
	}
}
