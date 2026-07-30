/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SemaphoreReading
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [DispatcherObservationProjector] (SP2c.1, #824).
 *
 * Uses a real `vyhybna.xml` [DefaultSimulationContext] (so [DispatcherObservationProjector]
 * exercises the real switch grid walk and a real [PathReservationRegistry]) combined with
 * hand-stubbed [NetworkPerceptionPort]/[DispatchLoopSensorPort] fakes (so every scenario is
 * exact and deterministic without running a live kDisco simulation). The one scenario that needs
 * a genuinely populated [PathReservationRegistry] (an active path reservation) is covered by
 * [DispatcherObservationProjectorLiveRunTest] instead, which drives a real [cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
 */
@DisplayName("DispatcherObservationProjector — sim-thread capture, sorting, determinism (#824)")
class DispatcherObservationProjectorTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	private fun newProjector(
		context: DefaultSimulationContext,
		perceptionPort: NetworkPerceptionPort,
		dispatchLoopSensorPort: DispatchLoopSensorPort
	): DispatcherObservationProjector =
		DispatcherObservationProjector(
			perceptionPort = perceptionPort,
			dispatchLoopSensorPort = dispatchLoopSensorPort,
			pathReservationRegistry = context.scope.get<PathReservationRegistry>(),
			environment = context
		)

	private fun perception(
		trainId: String,
		velocity: Double = 0.0,
		isDwelling: Boolean = velocity == 0.0,
		isStationDwelling: Boolean = false,
		destinationInOutName: String = "B",
		frontSectionName: String? = "kA"
	): TrainPerceptionReading =
		TrainPerceptionReading(
			trainId = trainId,
			signalAheadName = "doA1",
			signalAheadAspect = Signal.STOP,
			distanceToSignalAheadMetres = 12.0,
			currentSpeedLimitMps = 10.0,
			velocity = velocity,
			acceleration = 0.0,
			totalDistance = 5.0,
			frontSectionName = frontSectionName,
			destinationInOutName = destinationInOutName,
			scheduledArrivalTime = 0.0,
			isDwelling = isDwelling,
			isStationDwelling = isStationDwelling
		)

	private fun snapshotOf(
		simTime: Double,
		perceptions: List<TrainPerceptionReading>,
		blocks: List<BlockOccupancyReading> = emptyList(),
		semaphores: List<SemaphoreReading> = emptyList()
	): SimulationSnapshot =
		SimulationSnapshot(
			simTime = simTime,
			semaphores = semaphores,
			blocks = blocks,
			trainPositions =
				perceptions.map {
					TrainPositionReading(it.trainId, it.velocity, it.acceleration, it.totalDistance, it.frontSectionName)
				},
			timetables = emptyList(),
			trainPerceptions = perceptions
		)

	private fun dispatchSnapshotOf(queued: List<QueuedTrainObservation>): DispatchLoopSnapshot =
		DispatchLoopSnapshot(queuedTrains = queued, innerBlockInputs = emptyList(), outerBlockInputs = emptyList())

	private fun stubPorts(
		context: DefaultSimulationContext,
		snapshot: SimulationSnapshot,
		dispatchSnapshot: DispatchLoopSnapshot
	): DispatcherObservationProjector {
		val perceptionPort = mockk<NetworkPerceptionPort>()
		val sensorPort = mockk<DispatchLoopSensorPort>()
		every { perceptionPort.captureSnapshot() } returns snapshot
		every { sensorPort.snapshot() } returns dispatchSnapshot
		return newProjector(context, perceptionPort, sensorPort)
	}

	@Nested
	@DisplayName("publication")
	inner class Publication {
		@Test
		@DisplayName("latest() is EMPTY before the first captureOnSimThread() call")
		fun latestIsEmptyBeforeFirstCapture() {
			loadShuntingLoopContext().use { context ->
				val perceptionPort = mockk<NetworkPerceptionPort>()
				val sensorPort = mockk<DispatchLoopSensorPort>()
				val projector = newProjector(context, perceptionPort, sensorPort)

				assertThat(projector.latest()).isEqualTo(DispatcherObservation.EMPTY)
			}
		}

		@Test
		@DisplayName("captureOnSimThread() increments tick and publishes for latest()")
		fun captureOnSimThreadIncrementsTickAndPublishes() {
			loadShuntingLoopContext().use { context ->
				val perceptionPort = mockk<NetworkPerceptionPort>()
				val sensorPort = mockk<DispatchLoopSensorPort>()
				every { perceptionPort.captureSnapshot() } returns snapshotOf(simTime = 1.0, perceptions = emptyList())
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(emptyList())
				val projector = newProjector(context, perceptionPort, sensorPort)

				projector.captureOnSimThread()
				assertThat(projector.latest().tick).isEqualTo(1L)

				projector.captureOnSimThread()
				assertThat(projector.latest().tick).isEqualTo(2L)
			}
		}

		@Test
		@DisplayName("captureOnSimThread() from a second thread trips the debug-only thread-identity guard")
		fun capturingFromASecondThreadTripsTheGuard() {
			loadShuntingLoopContext().use { context ->
				val perceptionPort = mockk<NetworkPerceptionPort>()
				val sensorPort = mockk<DispatchLoopSensorPort>()
				every { perceptionPort.captureSnapshot() } returns snapshotOf(simTime = 1.0, perceptions = emptyList())
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(emptyList())
				val projector = newProjector(context, perceptionPort, sensorPort)

				// First call establishes the "sim thread" identity from the test thread itself.
				projector.captureOnSimThread()

				val captured = AtomicReference<Throwable>()
				val otherThread =
					Thread {
						projector.captureOnSimThread()
					}
				otherThread.setUncaughtExceptionHandler { _, throwable -> captured.set(throwable) }
				otherThread.start()
				otherThread.join()

				assertThat(captured.get()).isNotNull()
				assert(captured.get() is AssertionError) {
					"expected the debug-only guard to raise AssertionError on a second thread, got: ${captured.get()}"
				}
			}
		}
	}

	@Nested
	@DisplayName("determinism (#822 P8)")
	inner class Determinism {
		@Test
		@DisplayName("projecting the same tick twice from a frozen snapshot yields equal observations and equal digest()")
		fun projectingTwiceFromFrozenSnapshotYieldsEqualObservationAndDigest() {
			loadShuntingLoopContext().use { context ->
				val snapshot =
					snapshotOf(simTime = 42.0, perceptions = listOf(perception("Train #1", velocity = 5.0)))
				val dispatchSnapshot = dispatchSnapshotOf(listOf(QueuedTrainObservation("Train #2", "B")))
				val projector = stubPorts(context, snapshot, dispatchSnapshot)

				val first = projector.projectTick(5L)
				val second = projector.projectTick(5L)

				assertThat(first).isEqualTo(second)
				assertThat(first.digest()).isEqualTo(second.digest())
			}
		}

		@Test
		@DisplayName("shuffled input reading order yields an identical (sorted) observation")
		fun shuffledInputReadingOrderYieldsIdenticalObservation() {
			loadShuntingLoopContext().use { outerContext ->
				val perceptions =
					listOf(
						perception("Train #3", velocity = 1.0),
						perception("Train #1", velocity = 2.0),
						perception("Train #2", velocity = 0.0)
					)
				val blocks =
					listOf(
						BlockOccupancyReading("k2", TrackFacility.State.FREE, null),
						BlockOccupancyReading("k1", TrackFacility.State.OCCUPIED, "Train #1")
					)
				val semaphores =
					listOf(
						SemaphoreReading("zB", Signal.STOP),
						SemaphoreReading("zA", Signal.FREE, "A", "B")
					)
				val queued =
					listOf(
						QueuedTrainObservation("Train #5", "A"),
						QueuedTrainObservation("Train #4", "B")
					)

				fun project(
					perceptionsOrder: List<TrainPerceptionReading>,
					blocksOrder: List<BlockOccupancyReading>,
					semaphoresOrder: List<SemaphoreReading>,
					queuedOrder: List<QueuedTrainObservation>
				): DispatcherObservation =
					loadShuntingLoopContext().use { context ->
						val snapshot = snapshotOf(9.0, perceptionsOrder, blocksOrder, semaphoresOrder)
						val projector = stubPorts(context, snapshot, dispatchSnapshotOf(queuedOrder))
						projector.projectTick(1L)
					}

				val original = project(perceptions, blocks, semaphores, queued)
				val shuffled = project(perceptions.reversed(), blocks.reversed(), semaphores.reversed(), queued.reversed())

				assertThat(shuffled).isEqualTo(original)
			}
		}
	}

	@Nested
	@DisplayName("sorting (#824 hard requirement)")
	inner class Sorting {
		@Test
		@DisplayName("trains, blocks, signals, and queued are sorted by their documented natural key")
		fun outputListsAreSortedByNaturalKey() {
			loadShuntingLoopContext().use { context ->
				val perceptions =
					listOf(perception("Train #9", velocity = 1.0), perception("Train #2", velocity = 1.0))
				val blocks =
					listOf(
						BlockOccupancyReading("kB", TrackFacility.State.FREE, null),
						BlockOccupancyReading("k1", TrackFacility.State.FREE, null)
					)
				val semaphores = listOf(SemaphoreReading("zB", Signal.STOP), SemaphoreReading("doA1", Signal.STOP))
				val queued = listOf(QueuedTrainObservation("Train #7", "A"), QueuedTrainObservation("Train #3", "B"))

				val snapshot = snapshotOf(1.0, perceptions, blocks, semaphores)
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(queued))

				val observation = projector.projectTick(1L)

				// trains merges active perceptions AND queued trains into one sorted-by-trainId list.
				assertThat(observation.trains.map { it.trainId })
					.containsExactly("Train #2", "Train #3", "Train #7", "Train #9")
				assertThat(observation.blocks.map { it.blockId }).containsExactly("k1", "kB")
				assertThat(observation.signals.map { it.name }).containsExactly("doA1", "zB")
				assertThat(observation.queued.map { it.trainId }).containsExactly("Train #3", "Train #7")
			}
		}

		@Test
		@DisplayName("switches are sorted by switchName")
		fun switchesAreSortedByName() {
			loadShuntingLoopContext().use { context ->
				val snapshot = snapshotOf(simTime = 0.0, perceptions = emptyList())
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(emptyList()))

				val observation = projector.projectTick(1L)

				assertThat(observation.switches.map { it.switchName }).containsExactly("vA", "vB")
			}
		}
	}

	@Nested
	@DisplayName("switch grid walk (real vyhybna.xml topology)")
	inner class Switches {
		@Test
		@DisplayName("both named switches appear at their default MAIN position, unlocked")
		fun switchesReflectRealGridAndAreUnlockedByDefault() {
			loadShuntingLoopContext().use { context ->
				val snapshot = snapshotOf(simTime = 0.0, perceptions = emptyList())
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(emptyList()))

				val observation = projector.projectTick(1L)

				assertThat(observation.switches).containsExactly(
					SwitchView("vA", RailSwitch.Conf.MAIN, null),
					SwitchView("vB", RailSwitch.Conf.MAIN, null)
				)
			}
		}
	}

	@Nested
	@DisplayName("train phase derivation and wait-state tracking")
	inner class PhaseAndWaitTracking {
		@Test
		@DisplayName("QUEUED / RUNNING / HELD / DWELLING derive correctly; waitSeconds accumulates; EXITED fires once")
		fun phaseAndWaitTrackingAcrossTicks() {
			loadShuntingLoopContext().use { context ->
				val perceptionPort = mockk<NetworkPerceptionPort>()
				val sensorPort = mockk<DispatchLoopSensorPort>()
				val projector = newProjector(context, perceptionPort, sensorPort)

				// Tick 1 (simTime=10): #1 queued, #2 running, #3 held (STOP signal), #4 station-dwelling.
				every { perceptionPort.captureSnapshot() } returns
					snapshotOf(
						simTime = 10.0,
						perceptions =
							listOf(
								perception("Train #2", velocity = 3.0, isDwelling = false),
								perception("Train #3", velocity = 0.0, isDwelling = true, isStationDwelling = false),
								perception("Train #4", velocity = 0.0, isDwelling = true, isStationDwelling = true)
							)
					)
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(listOf(QueuedTrainObservation("Train #1", "B")))

				projector.captureOnSimThread()
				val tick1 = projector.latest().trains.associateBy { it.trainId }
				assertThat(tick1.getValue("Train #1").phase).isEqualTo(TrainPhase.QUEUED)
				assertThat(tick1.getValue("Train #1").waitSeconds).isEqualTo(0.0)
				assertThat(tick1.getValue("Train #1").waitingSinceSimTime).isEqualTo(10.0)
				assertThat(tick1.getValue("Train #2").phase).isEqualTo(TrainPhase.RUNNING)
				assertThat(tick1.getValue("Train #2").waitingSinceSimTime).isNull()
				assertThat(tick1.getValue("Train #3").phase).isEqualTo(TrainPhase.HELD)
				assertThat(tick1.getValue("Train #3").waitingSinceSimTime).isEqualTo(10.0)
				assertThat(tick1.getValue("Train #4").phase).isEqualTo(TrainPhase.DWELLING)

				// Tick 2 (simTime=15): same trains in the same states -> wait start unchanged, waitSeconds grows.
				every { perceptionPort.captureSnapshot() } returns
					snapshotOf(
						simTime = 15.0,
						perceptions =
							listOf(
								perception("Train #2", velocity = 3.0, isDwelling = false),
								perception("Train #3", velocity = 0.0, isDwelling = true, isStationDwelling = false),
								perception("Train #4", velocity = 0.0, isDwelling = true, isStationDwelling = true)
							)
					)
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(listOf(QueuedTrainObservation("Train #1", "B")))

				projector.captureOnSimThread()
				val tick2 = projector.latest().trains.associateBy { it.trainId }
				assertThat(tick2.getValue("Train #3").waitingSinceSimTime).isEqualTo(10.0)
				assertThat(tick2.getValue("Train #3").waitSeconds).isEqualTo(5.0)
				assertThat(tick2.getValue("Train #1").waitSeconds).isEqualTo(5.0)

				// Tick 3 (simTime=20): #1 approved+running, #3 now moving, #4 exited (neither active nor queued).
				every { perceptionPort.captureSnapshot() } returns
					snapshotOf(
						simTime = 20.0,
						perceptions =
							listOf(
								perception("Train #1", velocity = 2.0, isDwelling = false),
								perception("Train #2", velocity = 3.0, isDwelling = false),
								perception("Train #3", velocity = 4.0, isDwelling = false)
							)
					)
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(emptyList())

				projector.captureOnSimThread()
				val tick3 = projector.latest().trains.associateBy { it.trainId }
				assertThat(tick3.getValue("Train #3").phase).isEqualTo(TrainPhase.RUNNING)
				assertThat(tick3.getValue("Train #3").waitingSinceSimTime).isNull()
				assertThat(tick3.getValue("Train #3").waitSeconds).isEqualTo(0.0)
				assertThat(tick3.getValue("Train #4").phase).isEqualTo(TrainPhase.EXITED)
				assertThat(tick3.getValue("Train #4").waitingSinceSimTime).isNull()

				// Tick 4 (simTime=25): #4's EXITED view was one-shot — it no longer appears at all.
				every { perceptionPort.captureSnapshot() } returns
					snapshotOf(
						simTime = 25.0,
						perceptions =
							listOf(
								perception("Train #1", velocity = 2.0, isDwelling = false),
								perception("Train #2", velocity = 3.0, isDwelling = false),
								perception("Train #3", velocity = 4.0, isDwelling = false)
							)
					)
				every { sensorPort.snapshot() } returns dispatchSnapshotOf(emptyList())

				projector.captureOnSimThread()
				val tick4TrainIds = projector.latest().trains.map { it.trainId }
				assertThat(tick4TrainIds).doesNotContain("Train #4")
			}
		}
	}

	@Nested
	@DisplayName("reservations (fresh registry)")
	inner class Reservations {
		@Test
		@DisplayName("no reservations are reported when PathReservationRegistry has no entries")
		fun noReservationsWhenRegistryIsEmpty() {
			loadShuntingLoopContext().use { context ->
				val snapshot = snapshotOf(simTime = 0.0, perceptions = listOf(perception("Train #1", velocity = 1.0)))
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(emptyList()))

				val observation = projector.projectTick(1L)

				assertThat(observation.reservations).isEmpty()
			}
		}
	}

	@Nested
	@DisplayName("capacity and activeCount")
	inner class CapacityAndActiveCount {
		@Test
		@DisplayName(
			"capacity defaults to RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS; activeCount mirrors trainPositions size"
		)
		fun capacityAndActiveCountDefaults() {
			loadShuntingLoopContext().use { context ->
				val snapshot =
					snapshotOf(
						simTime = 0.0,
						perceptions = listOf(perception("Train #1", velocity = 1.0), perception("Train #2", velocity = 1.0))
					)
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(emptyList()))

				val observation = projector.projectTick(1L)

				assertThat(observation.capacity).isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
				assertThat(observation.activeCount).isEqualTo(2)
			}
		}
	}

	@Nested
	@DisplayName("appliedOutcomes (SP2c.17 placeholder)")
	inner class AppliedOutcomes {
		@Test
		@DisplayName("is always empty until the SP2c.17 outcome channel lands")
		fun appliedOutcomesIsAlwaysEmptyForNow() {
			loadShuntingLoopContext().use { context ->
				val snapshot = snapshotOf(simTime = 0.0, perceptions = emptyList())
				val projector = stubPorts(context, snapshot, dispatchSnapshotOf(emptyList()))

				assertThat(projector.projectTick(1L).appliedOutcomes).isEmpty()
			}
		}
	}
}
