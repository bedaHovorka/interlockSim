/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Train.setTargetSpeed's "cleared path ahead" safety gate
 * Goal 10 code-review fix — Issue #545 (SP0.6) gate correctness
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import cz.hovorka.kdisco.DiscoException
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject

private val logger = KotlinLogging.logger {}

/**
 * Verifies the "must not move without a cleared path ahead" gate in [Train.setTargetSpeed].
 *
 * The gate must distinguish "train owns no blocks at all" from "train owns blocks but every
 * one of them is already occupied" (i.e. nothing reserved-but-not-yet-occupied ahead) — both
 * must reject a positive target speed. Only a train holding at least one reserved-and-not-yet-occupied
 * block (a cleared block ahead) may accelerate.
 *
 * Uses a real [PathReservationService]/[PathReservationRegistry] backed by vyhybna.xml,
 * wrapped in [MockSimulationContext] to avoid running the full kDisco event loop — the same
 * approach as [TrainPathReservationIntegrationTest].
 */
@DisplayName("Train.setTargetSpeed — cleared-path-ahead gate")
class TrainSetTargetSpeedGateTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: SimulationContext
	private lateinit var reservationService: PathReservationService

	@BeforeEach
	fun setUp() {
		val xml = TestFixtures.loadShuntingXml()
		requireNotNull(xml) { "vyhybna.xml must exist in resources" }

		val loadedContext = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		context = MockSimulationContext(loadedContext)
		reservationService = context.getRoutingServices().getPathReservationService()
	}

	@Test
	fun `train with no blocks at all rejects positive target speed`() {
		val train = Train(context, createTimetableWithLength(50.0))

		train.setTargetSpeed(5.0)

		assertThat(reservationService.getReservedBlocks(train.name)).isEmpty()
		assertThat(train.getVelocity()).isEqualTo(0.0)
	}

	@Test
	fun `train that only occupies its current block rejects positive target speed`() {
		val train = Train(context, createTimetableWithLength(50.0))
		val inOuts = context.getInOuts().toList()
		assertThat(inOuts).isNotEmpty()
		val startInOut = inOuts[0]
		val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]

		val result = reservationService.reservePath(train.name, startInOut, targetInOut)
		if (result !is PathReservationService.ReservationResult.Success) {
			logger.info { "Test skipped: no path exists between ${startInOut.name} and ${targetInOut.name}" }
			return
		}

		// Regression case: the train physically occupies every block it holds — nothing left
		// reserved-but-not-yet-occupied ahead of it.
		result.reservedBlocks.forEach { it.enter(train) }

		train.setTargetSpeed(5.0)

		val reserved = reservationService.getReservedBlocks(train.name)
		val occupied = reservationService.getOccupiedBlocks(train.name)
		assertThat(reserved.size).isEqualTo(occupied.size)
		assertThat(train.getVelocity()).isEqualTo(0.0)
	}

	@Test
	fun `train with a cleared block ahead accepts positive target speed`() {
		val train = Train(context, createTimetableWithLength(50.0))
		val inOuts = context.getInOuts().toList()
		val startInOut = inOuts[0]
		val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]

		val result = reservationService.reservePath(train.name, startInOut, targetInOut)
		if (result !is PathReservationService.ReservationResult.Success || result.reservedBlocks.size < 2) {
			logger.info { "Test skipped: path too short to leave a cleared block ahead" }
			return
		}

		// Occupy only the first block — the remaining reserved blocks are cleared ahead.
		result.reservedBlocks.first().enter(train)

		val reserved = reservationService.getReservedBlocks(train.name)
		val occupied = reservationService.getOccupiedBlocks(train.name)
		assertThat(reserved.size).isGreaterThan(occupied.size)

		// The gate must NOT early-return here — it should fall through to Motor.accelerateTo(),
		// which requires a live kDisco simulation and throws DiscoException("Not inside a
		// simulation") outside one. That specific exception (rather than a silent early return)
		// is the proof the gate passed the train through instead of rejecting it.
		assertFailure { train.setTargetSpeed(5.0) }
			.isInstanceOf<DiscoException>()
	}

	private fun createTimetableWithLength(length: Double): Timetable {
		val inOuts = context.getInOuts().toList()
		return Timetable(inOuts[0], inOuts.getOrNull(1) ?: inOuts[0], Time(0.0), Time(60.0), length)
	}
}
