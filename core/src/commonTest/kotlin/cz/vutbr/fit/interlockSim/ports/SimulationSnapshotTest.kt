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

		override fun snapshot(): SimulationSnapshot =
			SimulationSnapshot(
				simTime = 99.0,
				semaphores = allSignalAspects(),
				blocks = allBlockOccupancies(),
				trainPositions = allTrainPositions(),
				timetables = allTrainTimetables()
			)
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

				override fun snapshot(): SimulationSnapshot =
					SimulationSnapshot(
						simTime = 0.0,
						semaphores = allSignalAspects(),
						blocks = allBlockOccupancies(),
						trainPositions = allTrainPositions(),
						timetables = allTrainTimetables()
					)
			}
		val snap = emptyPort.snapshot()

		assertThat(snap.semaphores).isEmpty()
		assertThat(snap.blocks).isEmpty()
		assertThat(snap.trainPositions).isEmpty()
		assertThat(snap.timetables).isEmpty()
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
					fromInOutName: String,
					toInOutName: String
				): RouteRequestResult = RouteRequestResult.NoRouteExists(fromInOutName, toInOutName)

				override fun releaseRoute(trainName: String): Boolean = false

				override fun setSwitchPosition(
					switchName: String,
					position: RailSwitch.Conf
				): Boolean = false

				override fun setSignalAspect(
					semaphoreName: String,
					signal: Signal
				): Boolean = false
			}

		val result = actuator.requestRoute("T1", "IN", "OUT")
		assertThat(result is RouteRequestResult.NoRouteExists).isTrue()
	}
}
