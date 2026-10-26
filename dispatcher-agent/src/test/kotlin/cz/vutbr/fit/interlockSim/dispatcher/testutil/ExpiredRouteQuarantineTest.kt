/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.single
import assertk.assertions.startsWith
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * [ExpiredRouteQuarantine], the test-source reference consumer of [ReservationExpired] (Issue #1243):
 * two strikes on the same footprint. No simulation runs: the inner dispatcher is a stub that returns
 * a fixed decision list, and the events are built by hand.
 */
class ExpiredRouteQuarantineTest {
	private val kA = namedBlock("kA")
	private val kB = namedBlock("kB")
	private val reserveT = DispatchDecision.ReservePath(TRAIN, "zA", "doB1")
	private val reserveOther = DispatchDecision.ReservePath(OTHER, "zB", "doA2")
	private val approveOther = DispatchDecision.ApproveTrain(OTHER)

	private lateinit var logger: Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var previousLevel: Level? = null

	/** `logback-test.xml` keeps the dispatcher package at WARN; the INFO line is opened up here. */
	@BeforeEach
	fun attachAppender() {
		logger = LoggerFactory.getLogger(ExpiredRouteQuarantine::class.java) as Logger
		previousLevel = logger.level
		logger.level = Level.INFO
		appender = ListAppender<ILoggingEvent>().apply { start() }
		logger.addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		logger.detachAppender(appender)
		logger.level = previousLevel
	}

	@Test
	fun `the first expiry of a train is forgiven`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))

		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		assertThat(quarantine.decide(observation(standing = mapOf(TRAIN to setOf("kA")))))
			.containsExactly(reserveT)
	}

	@Test
	fun `a second expiry on the same footprint drops only that train's ReservePath`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT, reserveOther, approveOther))
		val standing = mapOf(TRAIN to setOf("kA"), OTHER to setOf("kZ"))

		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		// Another train's ReservePath and every non-ReservePath decision pass.
		assertThat(quarantine.decide(observation(standing))).containsExactly(reserveOther, approveOther)
	}

	@Test
	fun `a quarantined train's only decision becomes NoAction`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))

		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		assertThat(quarantine.decide(observation(mapOf(TRAIN to setOf("kA")))))
			.containsExactly(DispatchDecision.NoAction)
	}

	@Test
	fun `a train that moved is offered routes again and its next expiry is a first strike`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		// It now stands on kB: it moved, so the quarantine is lifted.
		val onKb = observation(mapOf(TRAIN to setOf("kB")))
		assertThat(quarantine.decide(onKb)).containsExactly(reserveT)
		// An expiry on the new footprint is a first strike, forgiven.
		quarantine.onReservationExpired(tailExpired(TRAIN, kB))
		assertThat(quarantine.decide(onKb)).containsExactly(reserveT)
	}

	@Test
	fun `an expiry on a new footprint before the observation shows the move is a first strike`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		// It moved to kB and expired there before any decide saw it move: a changed footprint lifts it.
		quarantine.onReservationExpired(tailExpired(TRAIN, kB))

		val onKb = observation(mapOf(TRAIN to setOf("kB")))
		assertThat(quarantine.decide(onKb)).containsExactly(reserveT)
		// One more expiry on kB is the second strike on that footprint.
		quarantine.onReservationExpired(tailExpired(TRAIN, kB))
		assertThat(quarantine.decide(onKb)).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	fun `a train standing on a platform and a switch block is compared on every block it occupies`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		val switchBlock = namedBlock("sw")
		quarantine.onReservationExpired(tailExpired(TRAIN, kA, switchBlock))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA, switchBlock))

		// The loop publishes an input only for kA, but the snapshot reads both blocks as the train's.
		val straddling =
			observation(standing = mapOf(TRAIN to setOf("kA", "sw")), inputs = mapOf(TRAIN to setOf("kA")))
		assertThat(quarantine.decide(straddling)).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	fun `an observation with trains and no block readings fails fast, naming the adapter issue`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		val noBlocks = observation(mapOf(TRAIN to setOf("kA")))
		val withoutBlocks = noBlocks.copy(snapshot = noBlocks.snapshot.copy(blocks = emptyList()))

		assertFailure { quarantine.decide(withoutBlocks) }
			.isInstanceOf<IllegalStateException>()
			.messageContains("Issue #1249")
	}

	@Test
	fun `a train that left the snapshot is forgotten`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		assertThat(quarantine.decide(observation(standing = emptyMap(), present = emptySet())))
			.containsExactly(reserveT)
		// Back again on kA: its old strikes are gone, so one expiry is a first strike.
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		assertThat(quarantine.decide(observation(mapOf(TRAIN to setOf("kA"))))).containsExactly(reserveT)
	}

	@Test
	fun `two whole-holding expiries quarantine a present train with no OCCUPIED block until it leaves`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))
		quarantine.onReservationExpired(wholeHoldingExpired(TRAIN))
		quarantine.onReservationExpired(wholeHoldingExpired(TRAIN))

		// Present, standing on nothing: the empty footprint matches the empty one of both events.
		val presentOnNothing = observation(standing = emptyMap(), present = setOf(TRAIN))
		assertThat(quarantine.decide(presentOnNothing)).containsExactly(DispatchDecision.NoAction)
		// Gone from the snapshot: forgotten.
		assertThat(quarantine.decide(observation(standing = emptyMap(), present = emptySet())))
			.containsExactly(reserveT)
	}

	@Test
	fun `the quarantine is logged once, in English, naming the train and the footprint`() {
		val quarantine = ExpiredRouteQuarantine(innerReturning(reserveT))

		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		assertThat(infoLines()).isEmpty()
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))
		// A third strike on the same footprint is no new quarantine: still one line.
		quarantine.onReservationExpired(tailExpired(TRAIN, kA))

		assertThat(infoLines()).hasSize(1)
		assertThat(infoLines())
			.single()
			.startsWith("Train '$TRAIN' is not offered a route: its reservation expired twice while it stood on [kA]")
	}

	private fun infoLines(): List<String> = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

	private fun innerReturning(vararg decisions: DispatchDecision): Dispatcher = Dispatcher { decisions.toList() }

	private fun tailExpired(
		trainId: String,
		vararg standingOn: DynamicTrackBlock
	) = ReservationExpired(
		trainId = trainId,
		kind = ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL,
		releasedBlocks = listOf(namedBlock("tail")),
		keptBlocks = standingOn.toList(),
		stalledForSimSeconds = TTL,
		ttlSimSeconds = TTL,
		time = TTL
	)

	private fun wholeHoldingExpired(trainId: String) =
		ReservationExpired(
			trainId = trainId,
			kind = ReservationExpired.Kind.WHOLE_HOLDING,
			releasedBlocks = listOf(namedBlock("route")),
			keptBlocks = emptyList(),
			stalledForSimSeconds = TTL,
			ttlSimSeconds = TTL,
			time = TTL
		)

	/**
	 * An observation in which every train of [present] is in `snapshot.trainPositions`, each train of
	 * [standing] owns an OCCUPIED `snapshot.blocks` reading for each of its block ids (next to one FREE
	 * block, so the readings are never empty), and each train of [inputs] owns an OCCUPIED inner input
	 * for each of its block ids, as the loop publishes them.
	 */
	private fun observation(
		standing: Map<String, Set<String>>,
		present: Set<String> = standing.keys,
		inputs: Map<String, Set<String>> = standing
	): DispatchObservation =
		DispatchObservation(
			snapshot =
				SimulationSnapshot(
					simTime = TTL,
					semaphores = emptyList(),
					blocks = blockReadings(standing),
					trainPositions =
						present.map { trainId ->
							TrainPositionReading(
								trainId = trainId,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						},
					timetables = emptyList()
				),
			unapprovedTrains = emptyList(),
			innerBlockInputs =
				inputs.flatMap { (trainId, blockIds) ->
					blockIds.map { blockId -> occupiedInput(blockId, trainId) }
				}
		)

	private fun blockReadings(standing: Map<String, Set<String>>): List<BlockOccupancyReading> =
		standing.flatMap { (trainId, blockIds) ->
			blockIds.map { blockId -> BlockOccupancyReading(blockId, TrackFacility.State.OCCUPIED, trainId) }
		} + BlockOccupancyReading("free", TrackFacility.State.FREE, null)

	private fun occupiedInput(
		blockId: String,
		ownerTrainId: String
	) = BlockInputObservation(
		blockId = blockId,
		towardSemaphoreName = "zA",
		state = TrackFacility.State.OCCUPIED,
		ownerTrainId = ownerTrainId,
		isApproachingThisInput = true,
		pathSetUpTowardThisInput = false,
		pathAlreadyExtendedBeyond = false
	)

	private fun namedBlock(name: String): DynamicTrackBlock =
		mockk<DynamicTrackBlock>(relaxed = true) {
			every { this@mockk.name } returns name
		}

	private companion object {
		const val TRAIN = "T"
		const val OTHER = "U"
		const val TTL = 60.0
	}
}
