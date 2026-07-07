/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultNetworkPerceptionPort].
 *
 * All simulation objects are mocked with MockK so tests run without a live kDisco
 * simulation.  The grid is set up as a 3×1 strip; semaphore cells are placed at
 * specific positions during each test.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
@DisplayName("DefaultNetworkPerceptionPort — unit coverage")
class DefaultNetworkPerceptionPortTest {
	// ── Helpers ────────────────────────────────────────────────────────────

	private fun semaphore(
		name: String,
		signal: Signal = Signal.STOP
	): DynamicRailSemaphore =
		mockk<DynamicRailSemaphore>(relaxed = true).also {
			every { it.name } returns name
			every { it.signal } returns signal
		}

	private fun block(
		name: String? = null,
		state: TrackFacility.State = TrackFacility.State.FREE,
		trainName: String? = null,
		occupantName: String? = null
	): DynamicTrackBlock =
		mockk<DynamicTrackBlock>(relaxed = true).also {
			every { it.name } returns name
			every { it.getState() } returns state
			every { it.trainName } returns trainName
			if (occupantName != null) {
				val occ = mockk<TrackOccupant>(relaxed = true)
				every { occ.name } returns occupantName
				every { it.occupant } returns occ
			} else {
				every { it.occupant } returns null
			}
			every { it.ends() } returns emptyArray<PathSeparator>()
		}

	private fun train(
		name: String,
		velocity: Double = 0.0,
		acceleration: Double = 0.0,
		totalDistance: Double = 0.0,
		frontSection: TrackSection? = null,
		originName: String = "A",
		destName: String = "B",
		departureTime: Double = 0.0,
		arrivalTime: Double = 60.0
	): Train =
		mockk<Train>(relaxed = true).also {
			every { it.name } returns name
			every { it.getVelocity() } returns velocity
			every { it.getAcceleration() } returns acceleration
			every { it.totalDistance } returns totalDistance
			every { it.frontSection } returns frontSection
			every { it.timetableOriginName } returns originName
			every { it.timetableDestinationName } returns destName
			every { it.scheduledDepartureTime } returns departureTime
			every { it.scheduledArrivalTime } returns arrivalTime
		}

	/**
	 * Builds a [SimulationEnvironment] stub backed by a 3×1 grid.
	 *
	 * [cells] maps (col, row) → [Cell]; unspecified positions return `null`.
	 * [blocks] is the collection returned by `getGraph().values()`.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun env(
		cells: Map<Pair<Int, Int>, Cell?> = emptyMap(),
		blocks: Collection<DynamicTrackBlock> = emptyList()
	): SimulationEnvironment {
		val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { grid.cols } returns 3
		every { grid.rows } returns 1
		for (c in 0 until 3) {
			every { grid.getCellAt(c, 0) } returns cells[c to 0]
		}

		val graph = mockk<ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment>>(relaxed = true)
		every { graph.values() } returns blocks

		val e = mockk<SimulationEnvironment>(relaxed = true)
		every { e.getRailWayNetGrid() } returns grid
		every { e.getGraph() } returns graph
		return e
	}

	// ── Signal aspects ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("signalAspect()")
	inner class SignalAspect {
		@Test
		@DisplayName("returns SemaphoreReading for a known semaphore")
		fun knownSemaphoreReturnsReading() {
			val sem = semaphore("zA", Signal.FREE)
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((0 to 0) to sem)),
					activeTrains = { emptyList() }
				)

			val result = port.signalAspect("zA")

			assertThat(result).isEqualTo(SemaphoreReading(name = "zA", signal = Signal.FREE))
		}

		@Test
		@DisplayName("returns null for an unknown semaphore name")
		fun unknownSemaphoreReturnsNull() {
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = emptyMap()),
					activeTrains = { emptyList() }
				)

			assertThat(port.signalAspect("noSuchSem")).isNull()
		}
	}

	@Nested
	@DisplayName("allSignalAspects()")
	inner class AllSignalAspects {
		@Test
		@DisplayName("returns one reading per semaphore in the grid")
		fun returnsTwoSemaphores() {
			val semA = semaphore("zA", Signal.STOP)
			val semB = semaphore("doB1", Signal.S60)
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((0 to 0) to semA, (1 to 0) to semB)),
					activeTrains = { emptyList() }
				)

			val result = port.allSignalAspects()

			assertThat(result).containsExactlyInAnyOrder(
				SemaphoreReading("zA", Signal.STOP),
				SemaphoreReading("doB1", Signal.S60)
			)
		}

		@Test
		@DisplayName("returns empty list when no semaphores in the grid")
		fun emptyGridReturnsEmptyList() {
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = emptyMap()),
					activeTrains = { emptyList() }
				)

			assertThat(port.allSignalAspects()).isEmpty()
		}
	}

	// ── Block occupancy ────────────────────────────────────────────────────

	@Nested
	@DisplayName("blockOccupancy()")
	inner class BlockOccupancy {
		@Test
		@DisplayName("FREE block returns reading with null trainId")
		fun freeBlockTrainIdIsNull() {
			val b = block(name = "k1", state = TrackFacility.State.FREE)
			val port = DefaultNetworkPerceptionPort(env(blocks = listOf(b)), { emptyList() })

			val result = port.blockOccupancy("k1")

			assertThat(result).isEqualTo(
				BlockOccupancyReading(blockId = "k1", state = TrackFacility.State.FREE, trainId = null)
			)
		}

		@Test
		@DisplayName("RESERVED block returns reading with trainName as trainId")
		fun reservedBlockTrainIdIsTrainName() {
			val b = block(name = "kA", state = TrackFacility.State.RESERVED, trainName = "Train #1")
			val port = DefaultNetworkPerceptionPort(env(blocks = listOf(b)), { emptyList() })

			val result = port.blockOccupancy("kA")

			assertThat(result).isEqualTo(
				BlockOccupancyReading(blockId = "kA", state = TrackFacility.State.RESERVED, trainId = "Train #1")
			)
		}

		@Test
		@DisplayName("OCCUPIED block returns reading with occupant name as trainId")
		fun occupiedBlockTrainIdIsOccupantName() {
			val b =
				block(
					name = "k2",
					state = TrackFacility.State.OCCUPIED,
					occupantName = "Train #2"
				)
			val port = DefaultNetworkPerceptionPort(env(blocks = listOf(b)), { emptyList() })

			val result = port.blockOccupancy("k2")

			assertThat(result).isEqualTo(
				BlockOccupancyReading(blockId = "k2", state = TrackFacility.State.OCCUPIED, trainId = "Train #2")
			)
		}

		@Test
		@DisplayName("returns null for an unknown block id")
		fun unknownBlockReturnsNull() {
			val port = DefaultNetworkPerceptionPort(env(blocks = emptyList()), { emptyList() })

			assertThat(port.blockOccupancy("noSuchBlock")).isNull()
		}
	}

	@Nested
	@DisplayName("allBlockOccupancies()")
	inner class AllBlockOccupancies {
		@Test
		@DisplayName("returns one reading per block")
		fun returnsTwoBlocks() {
			val b1 = block(name = "k1", state = TrackFacility.State.FREE)
			val b2 = block(name = "k2", state = TrackFacility.State.RESERVED, trainName = "Train #1")
			val port = DefaultNetworkPerceptionPort(env(blocks = listOf(b1, b2)), { emptyList() })

			val result = port.allBlockOccupancies()

			assertThat(result).containsExactlyInAnyOrder(
				BlockOccupancyReading("k1", TrackFacility.State.FREE, null),
				BlockOccupancyReading("k2", TrackFacility.State.RESERVED, "Train #1")
			)
		}

		@Test
		@DisplayName("returns empty list when no blocks in the graph")
		fun emptyGraphReturnsEmptyList() {
			val port = DefaultNetworkPerceptionPort(env(blocks = emptyList()), { emptyList() })

			assertThat(port.allBlockOccupancies()).isEmpty()
		}
	}

	// ── Train position ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("trainPosition()")
	inner class TrainPosition {
		@Test
		@DisplayName("returns reading for an active train")
		fun activeTrainReturnsReading() {
			val t = train("Train #1", velocity = 20.0, acceleration = 1.5, totalDistance = 300.0)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPosition("Train #1")

			assertThat(result).isEqualTo(
				TrainPositionReading(
					trainId = "Train #1",
					velocity = 20.0,
					acceleration = 1.5,
					totalDistance = 300.0,
					frontSectionName = null
				)
			)
		}

		@Test
		@DisplayName("returns null for a train not in the active list")
		fun inactiveTrainReturnsNull() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.trainPosition("Train #1")).isNull()
		}

		@Test
		@DisplayName("frontSectionName is the DynamicTrackBlock name when front is on a named block")
		fun frontSectionNameFromNamedBlock() {
			val namedBlock = block(name = "k1")
			val section = mockk<TrackSection>(relaxed = true)
			every { section.getTrackBlock() } returns namedBlock
			val t = train("Train #1", frontSection = section)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPosition("Train #1")

			assertThat(result?.frontSectionName).isEqualTo("k1")
		}

		@Test
		@DisplayName("frontSectionName is null when train has no front section yet")
		fun noFrontSectionIsNull() {
			val t = train("Train #1", frontSection = null)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			assertThat(port.trainPosition("Train #1")?.frontSectionName).isNull()
		}
	}

	@Nested
	@DisplayName("allTrainPositions()")
	inner class AllTrainPositions {
		@Test
		@DisplayName("returns one reading per active train")
		fun returnsAllActiveTrains() {
			val t1 = train("Train #1", velocity = 10.0)
			val t2 = train("Train #2", velocity = 20.0)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t1, t2) })

			val result = port.allTrainPositions()

			assertThat(result.map { it.trainId }).containsExactlyInAnyOrder("Train #1", "Train #2")
		}

		@Test
		@DisplayName("returns empty list when no active trains")
		fun noActiveTrainsReturnsEmpty() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.allTrainPositions()).isEmpty()
		}
	}

	// ── Train timetable ────────────────────────────────────────────────────

	@Nested
	@DisplayName("trainTimetable()")
	inner class TrainTimetable {
		@Test
		@DisplayName("returns timetable reading for an active train")
		fun activeTrainReturnsTimetable() {
			val t =
				train(
					"Train #1",
					originName = "A",
					destName = "B",
					departureTime = 10.0,
					arrivalTime = 120.0
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainTimetable("Train #1")

			assertThat(result).isEqualTo(
				TimetableReading(
					trainId = "Train #1",
					originInOutName = "A",
					destinationInOutName = "B",
					scheduledDepartureTime = 10.0,
					scheduledArrivalTime = 120.0
				)
			)
		}

		@Test
		@DisplayName("returns null for a train not in the active list")
		fun inactiveTrainReturnsNull() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.trainTimetable("Train #1")).isNull()
		}
	}

	@Nested
	@DisplayName("allTrainTimetables()")
	inner class AllTrainTimetables {
		@Test
		@DisplayName("returns one reading per active train")
		fun returnsAllActiveTimetables() {
			val t1 = train("Train #1", originName = "A", destName = "B")
			val t2 = train("Train #2", originName = "B", destName = "A")
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t1, t2) })

			val result = port.allTrainTimetables()

			assertThat(result.map { it.trainId }).containsExactlyInAnyOrder("Train #1", "Train #2")
		}

		@Test
		@DisplayName("returns empty list when no active trains")
		fun noActiveTrainsReturnsEmpty() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.allTrainTimetables()).isEmpty()
		}
	}

	// ── snapshot() ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("snapshot()")
	inner class Snapshot {
		@Test
		@DisplayName("snapshot captures all semaphores, blocks, positions and timetables")
		fun snapshotContainsAllFacets() {
			val sem = semaphore("zA", Signal.FREE)
			val b = block(name = "k1", state = TrackFacility.State.RESERVED, trainName = "Train #1")
			val t =
				train(
					"Train #1",
					velocity = 15.0,
					originName = "A",
					destName = "B",
					departureTime = 5.0,
					arrivalTime = 90.0
				)
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((0 to 0) to sem), blocks = listOf(b)),
					activeTrains = { listOf(t) }
				)

			val snap = port.snapshot()

			assertThat(snap.semaphores).containsExactlyInAnyOrder(SemaphoreReading("zA", Signal.FREE))
			assertThat(snap.blocks).containsExactlyInAnyOrder(
				BlockOccupancyReading("k1", TrackFacility.State.RESERVED, "Train #1")
			)
			assertThat(snap.trainPositions.map { it.trainId }).containsExactlyInAnyOrder("Train #1")
			assertThat(snap.timetables.map { it.trainId }).containsExactlyInAnyOrder("Train #1")
		}

		@Test
		@DisplayName("snapshot.simTime falls back to 0.0 outside an active kDisco simulation")
		fun simTimeFallsBackOutsideSimulation() {
			// Process.time() throws outside an active simulation; the port must return 0.0.
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			val snap = port.snapshot()

			// 0.0 is the documented fallback when called outside kDisco.
			assertThat(snap.simTime).isEqualTo(0.0)
		}

		@Test
		@DisplayName("snapshot is empty when network has no semaphores, blocks, or trains")
		fun emptyNetworkProducesEmptySnapshot() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			val snap = port.snapshot()

			assertThat(snap.semaphores).isEmpty()
			assertThat(snap.blocks).isEmpty()
			assertThat(snap.trainPositions).isEmpty()
			assertThat(snap.timetables).isEmpty()
		}

		@Test
		@DisplayName("snapshot timetable entry matches allTrainTimetables output")
		fun snapshotTimetableMatchesDirectQuery() {
			val t =
				train(
					"Train #3",
					originName = "X",
					destName = "Y",
					departureTime = 10.0,
					arrivalTime = 120.0
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val snap = port.snapshot()
			val directTimetable = port.trainTimetable("Train #3")

			assertThat(snap.timetables).containsExactlyInAnyOrder(directTimetable!!)
		}

		@Test
		@DisplayName("snapshot semaphore entry matches allSignalAspects output")
		fun snapshotSemaphoreMatchesDirectQuery() {
			val sem = semaphore("doB1", Signal.S60)
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((2 to 0) to sem)),
					activeTrains = { emptyList() }
				)

			val snap = port.snapshot()

			assertThat(snap.semaphores).containsExactlyInAnyOrder(SemaphoreReading("doB1", Signal.S60))
		}
	}
}
