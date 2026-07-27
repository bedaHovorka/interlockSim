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
import cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
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
			// Stub direction to a fixed canonical value so toReading() and separatorAspect()
			// produce deterministic SemaphoreReading results (Issue #812).
			every { it.direction() } returns Cell.Segment.A
			// isAllowingFor: simplified stub for port-level tests — authorizes any direction
			// when the signal is allowing (direction-correctness is tested in DynamicRailSemaphoreTest).
			every { it.isAllowingFor(any(), any()) } returns signal.isAllowing()
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

	private fun inOut(
		name: String,
		signal: Signal = Signal.STOP
	): DynamicInOut =
		mockk<DynamicInOut>(relaxed = true).also {
			every { it.name } returns name
			every { it.outSemaphore } returns semaphore("out-$name", signal)
			// Stub direction to a fixed canonical value so separatorAspect()'s anti(sep.direction())
			// call (Issue #812) resolves against a real segment rather than a relaxed-mock default.
			every { it.direction() } returns Cell.Segment.A
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
		arrivalTime: Double = 60.0,
		nextSemaphore: OrientedPathSeparator? = null,
		secondSemaphore: OrientedPathSeparator? = null,
		distanceToSemaphore: Double = 0.0,
		speedLimitMps: Double = ABSOLUTE_MAX_SPEED,
		dwelling: Boolean = true,
		stationDwelling: Boolean = false
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
			// SP2a.1 perception facets (Issue #552). nextSemaphore()/secondSemaphoreAhead() are
			// stubbed so the port's single nextSemaphore() call + shared mapping (separatorName/
			// separatorAspect, exercised for real on the mocked separator) drives the reading.
			every { it.nextSemaphore() } returns nextSemaphore
			every { it.secondSemaphoreAhead(any()) } returns secondSemaphore
			every { it.distanceToSemaphore() } returns distanceToSemaphore
			every { it.currentSpeedLimitMps } returns speedLimitMps
			every { it.isDwelling } returns dwelling
			// SP2a.3 (Issue #554): commanded station dwell, narrower than isDwelling
			every { it.isStationDwelling } returns stationDwelling
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

			assertThat(result).isEqualTo(
				SemaphoreReading(name = "zA", signal = Signal.FREE, authorizedFrom = "F", authorizedTo = "A")
			)
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
				SemaphoreReading("doB1", Signal.S60, authorizedFrom = "F", authorizedTo = "A")
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

	// ── Train perception (SP2a.1) ──────────────────────────────────────────

	@Nested
	@DisplayName("trainPerception() / allTrainPerceptions() — SP2a.1")
	inner class TrainPerception {
		@Test
		@DisplayName("round-trips a fully-populated reading through trainPerception()")
		fun fullyPopulatedReadingRoundTrips() {
			val namedBlock = block(name = "k1")
			val section = mockk<TrackSection>(relaxed = true)
			every { section.getTrackBlock() } returns namedBlock
			val t =
				train(
					"Train #1",
					velocity = 25.0,
					acceleration = -0.5,
					totalDistance = 1000.0,
					frontSection = section,
					destName = "B",
					arrivalTime = 120.0,
					nextSemaphore = semaphore("doB1", Signal.S60),
					secondSemaphore = semaphore("doB2", Signal.STOP),
					distanceToSemaphore = 150.0,
					speedLimitMps = 30.0,
					dwelling = false
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result).isEqualTo(
				TrainPerceptionReading(
					trainId = "Train #1",
					signalAheadName = "doB1",
					signalAheadAspect = Signal.S60,
					distanceToSignalAheadMetres = 150.0,
					currentSpeedLimitMps = 30.0,
					velocity = 25.0,
					acceleration = -0.5,
					totalDistance = 1000.0,
					frontSectionName = "k1",
					destinationInOutName = "B",
					scheduledArrivalTime = 120.0,
					isDwelling = false,
					nextSignalAheadName = "doB2",
					nextSignalAheadAspect = Signal.STOP
				)
			)
		}

		@Test
		@DisplayName("returns null for a train not in the active list")
		fun inactiveTrainReturnsNull() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.trainPerception("Train #1")).isNull()
		}

		@Test
		@DisplayName("no reserved path → null signal facets, ABSOLUTE_MAX_SPEED, dwelling, zero distance")
		fun noReservedPathProducesNullSignalsAndMaxSpeed() {
			val t = train("Train #1", velocity = 0.0) // nextSemaphore defaults null
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.signalAheadName).isNull()
			assertThat(result?.signalAheadAspect).isNull()
			assertThat(result?.nextSignalAheadName).isNull()
			assertThat(result?.nextSignalAheadAspect).isNull()
			assertThat(result?.currentSpeedLimitMps).isEqualTo(ABSOLUTE_MAX_SPEED)
			assertThat(result?.isDwelling).isEqualTo(true)
			assertThat(result?.distanceToSignalAheadMetres).isEqualTo(0.0)
		}

		@Test
		@DisplayName("DynamicRailSemaphore destination → aspect from the semaphore's own signal")
		fun dynamicRailSemaphoreDestinationAspectFromSignal() {
			val t = train("Train #1", nextSemaphore = semaphore("doB1", Signal.S60))
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.signalAheadName).isEqualTo("doB1")
			assertThat(result?.signalAheadAspect).isEqualTo(Signal.S60)
		}

		@Test
		@DisplayName("DynamicInOut destination → aspect from outSemaphore.signal (I3 invariant)")
		fun dynamicInOutDestinationAspectFromOutSemaphore() {
			// DynamicInOut.outSemaphore is non-null by construction; separatorAspect reads
			// outSemaphore.signal for an InOut endpoint.
			val t = train("Train #1", nextSemaphore = inOut("B", Signal.FREE))
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.signalAheadName).isEqualTo("B")
			assertThat(result?.signalAheadAspect).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("second signal present → nextSignalAheadName/Aspect populated")
		fun secondSignalAheadPopulated() {
			val t =
				train(
					"Train #1",
					nextSemaphore = semaphore("doB1", Signal.FREE),
					secondSemaphore = semaphore("doB2", Signal.STOP)
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.nextSignalAheadName).isEqualTo("doB2")
			assertThat(result?.nextSignalAheadAspect).isEqualTo(Signal.STOP)
		}

		@Test
		@DisplayName("second signal absent (within one semaphore of destination) → null nextSignalAhead*")
		fun secondSignalAheadAbsent() {
			val t = train("Train #1", nextSemaphore = semaphore("doB1", Signal.FREE))
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.nextSignalAheadName).isNull()
			assertThat(result?.nextSignalAheadAspect).isNull()
		}

		@Test
		@DisplayName("dwelling at STOP → isDwelling true and STOP aspect (blocked by signal)")
		fun dwellingAtStopSignal() {
			val t =
				train(
					"Train #1",
					velocity = 0.0,
					nextSemaphore = semaphore("doB1", Signal.STOP),
					dwelling = true
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.isDwelling).isEqualTo(true)
			assertThat(result?.signalAheadAspect).isEqualTo(Signal.STOP)
		}

		@Test
		@DisplayName("dwelling with FREE aspect → isDwelling true but not blocked by a signal (station dwell)")
		fun dwellingAtStation() {
			val t =
				train(
					"Train #1",
					velocity = 0.0,
					nextSemaphore = semaphore("doB1", Signal.FREE),
					dwelling = true
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.isDwelling).isEqualTo(true)
			assertThat(result?.signalAheadAspect).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("commanded station dwell in progress → isStationDwelling projected true")
		fun stationDwellInProgressIsProjected() {
			val t =
				train(
					"Train #1",
					velocity = 0.0,
					nextSemaphore = semaphore("doB1", Signal.FREE),
					dwelling = true,
					stationDwelling = true
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			assertThat(result?.isStationDwelling).isEqualTo(true)
		}

		@Test
		@DisplayName("stopped at a signal (no commanded dwell) → isDwelling true but isStationDwelling false")
		fun stoppedWithoutCommandedDwellIsNotStationDwelling() {
			val t =
				train(
					"Train #1",
					velocity = 0.0,
					nextSemaphore = semaphore("doB1", Signal.STOP),
					dwelling = true,
					stationDwelling = false
				)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			val result = port.trainPerception("Train #1")

			// The two flags are independent: at rest for any reason vs. commanded station dwell
			assertThat(result?.isDwelling).isEqualTo(true)
			assertThat(result?.isStationDwelling).isEqualTo(false)
		}

		@Test
		@DisplayName("no front section yet → frontSectionName null")
		fun noFrontSectionProducesNullName() {
			val t = train("Train #1", frontSection = null)
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t) })

			assertThat(port.trainPerception("Train #1")?.frontSectionName).isNull()
		}

		@Test
		@DisplayName("allTrainPerceptions() returns one reading per active train")
		fun allTrainPerceptionsReturnsAll() {
			val t1 = train("Train #1", nextSemaphore = semaphore("zA", Signal.FREE))
			val t2 = train("Train #2", nextSemaphore = semaphore("zB", Signal.STOP))
			val port = DefaultNetworkPerceptionPort(env(), { listOf(t1, t2) })

			val result = port.allTrainPerceptions()

			assertThat(result.map { it.trainId }).containsExactlyInAnyOrder("Train #1", "Train #2")
		}

		@Test
		@DisplayName("perception in a captured snapshot is frozen from later source mutation")
		fun snapshotPerceptionIsFrozenFromLaterSourceMutation() {
			val t =
				train(
					"Train #1",
					velocity = 15.0,
					nextSemaphore = semaphore("doB1", Signal.FREE),
					secondSemaphore = semaphore("doB2", Signal.STOP)
				)
			val active = mutableListOf(t)
			val port = DefaultNetworkPerceptionPort(env(), { active.toList() })

			val snap = port.captureSnapshot()

			// Mutate the underlying source state after capture.
			every { t.getVelocity() } returns 99.0
			every { t.nextSemaphore() } returns semaphore("doB1", Signal.STOP)
			every { t.secondSemaphoreAhead(any()) } returns semaphore("doB2", Signal.FREE)
			active.clear()

			// The captured perception must reflect the pre-mutation state.
			val perception = snap.trainPerceptions.single()
			assertThat(perception.velocity).isEqualTo(15.0)
			assertThat(perception.signalAheadAspect).isEqualTo(Signal.FREE)
			assertThat(perception.nextSignalAheadAspect).isEqualTo(Signal.STOP)
		}
	}

	// ── captureSnapshot() (fresh, on-thread) and snapshot() (cached, off-thread) ─

	@Nested
	@DisplayName("captureSnapshot() (fresh) and snapshot() (cached)")
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

			val snap = port.captureSnapshot()

			assertThat(snap.semaphores).containsExactlyInAnyOrder(
				SemaphoreReading("zA", Signal.FREE, authorizedFrom = "F", authorizedTo = "A")
			)
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

			val snap = port.captureSnapshot()

			// 0.0 is the documented fallback when called outside kDisco.
			assertThat(snap.simTime).isEqualTo(0.0)
		}

		@Test
		@DisplayName("snapshot is empty when network has no semaphores, blocks, or trains")
		fun emptyNetworkProducesEmptySnapshot() {
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			val snap = port.captureSnapshot()

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

			val snap = port.captureSnapshot()
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

			val snap = port.captureSnapshot()

			assertThat(snap.semaphores).containsExactlyInAnyOrder(
				SemaphoreReading("doB1", Signal.S60, authorizedFrom = "F", authorizedTo = "A")
			)
		}

		@Test
		@DisplayName("snapshot is frozen — later source-state mutation does not change captured readings")
		fun snapshotIsFrozenFromLaterSourceMutation() {
			val sem = semaphore("zA", Signal.FREE)
			val b = block(name = "k1", state = TrackFacility.State.RESERVED, trainName = "Train #1")
			val t = train("Train #1", velocity = 15.0, originName = "A", destName = "B")
			val active = mutableListOf(t)
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((0 to 0) to sem), blocks = listOf(b)),
					activeTrains = { active.toList() }
				)

			val snap = port.captureSnapshot()

			// Mutate the underlying source state after capture.
			every { sem.signal } returns Signal.STOP
			every { b.getState() } returns TrackFacility.State.FREE
			every { b.trainName } returns null
			every { t.getVelocity() } returns 99.0
			active.clear()

			// The already-captured snapshot must reflect the pre-mutation state.
			assertThat(snap.semaphores).containsExactlyInAnyOrder(
				SemaphoreReading("zA", Signal.FREE, authorizedFrom = "F", authorizedTo = "A")
			)
			assertThat(snap.blocks).containsExactlyInAnyOrder(
				BlockOccupancyReading("k1", TrackFacility.State.RESERVED, "Train #1")
			)
			assertThat(snap.trainPositions.map { it.velocity }).containsExactlyInAnyOrder(15.0)
			assertThat(snap.timetables.map { it.trainId }).containsExactlyInAnyOrder("Train #1")
		}

		@Test
		@DisplayName("snapshot() returns the empty default before any captureSnapshot()")
		fun snapshotReturnsEmptyBeforeFirstCapture() {
			// snapshot() is the off-thread-safe accessor: before the first on-thread
			// captureSnapshot() it must return SimulationSnapshot.EMPTY, never reading
			// live state and never throwing.
			val port = DefaultNetworkPerceptionPort(env(), { emptyList() })

			assertThat(port.snapshot()).isEqualTo(SimulationSnapshot.EMPTY)
		}

		@Test
		@DisplayName("snapshot() returns the last captureSnapshot() result and stays frozen until the next capture")
		fun snapshotReturnsLastCaptureAndStaysFrozen() {
			val sem = semaphore("zA", Signal.FREE)
			val b = block(name = "k1", state = TrackFacility.State.RESERVED, trainName = "Train #1")
			val active = mutableListOf(train("Train #1", velocity = 15.0, originName = "A", destName = "B"))
			val port =
				DefaultNetworkPerceptionPort(
					env(cells = mapOf((0 to 0) to sem), blocks = listOf(b)),
					activeTrains = { active.toList() }
				)

			// On-thread capture publishes a fresh snapshot.
			val captured = port.captureSnapshot()
			// Off-thread accessor returns the same instance just published.
			assertThat(port.snapshot()).isEqualTo(captured)

			// Mutate the underlying source state after capture.
			every { sem.signal } returns Signal.STOP
			every { b.getState() } returns TrackFacility.State.FREE
			active.clear()

			// snapshot() is still the frozen, previously-captured value — the off-thread
			// caller never observed the mutation because it does not re-read live state.
			assertThat(port.snapshot()).isEqualTo(captured)
			assertThat(port.snapshot().semaphores).containsExactlyInAnyOrder(
				SemaphoreReading("zA", Signal.FREE, authorizedFrom = "F", authorizedTo = "A")
			)
		}
	}
}
