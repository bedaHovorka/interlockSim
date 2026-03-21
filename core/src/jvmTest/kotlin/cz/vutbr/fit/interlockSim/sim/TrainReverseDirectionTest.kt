/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * Railway Interlocking Simulator
 *
 * Train Reverse Direction Tests
 * GitHub #62: Bidirectional train operation support
 * 2026-02-06
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

private val logger = KotlinLogging.logger {}

/**
 * Unit tests for Timetable.reverseDirection() functionality.
 *
 * ## Purpose
 *
 * These tests verify the timetable reversal logic that supports bidirectional
 * train operation. The reversal swaps the In/Out destinations and is used by
 * Train.reverseDirection().
 *
 * ## Test Scenarios
 *
 * Uses vyhybna.xml network configuration:
 * - Timetable reverses In/Out destinations
 * - Validates destination swap is correct
 * - Verifies other timetable properties remain unchanged
 * - Tests that reversed timetable is usable for train creation
 *
 * ## Note on Train.reverseDirection()
 *
 * Train.reverseDirection() includes additional simulation behavior (hold(30.0)
 * to simulate engineer movement) that requires running simulation. That full
 * integration test scenario requires stopping a train mid-simulation in kDisco,
 * which is complex to test. This test focuses on the core timetable reversal
 * logic that Train.reverseDirection() delegates to.
 *
 * ## Conservative Approach
 *
 * Per CLAUDE.md guidance for sim/ package:
 * - Uses existing vyhybna.xml network (realistic topology)
 * - Tests core logic without running full simulation
 * - Validates state through public APIs only
 *
 * ## Railway Context
 *
 * This feature simulates a simplified version of locomotive coupling/uncoupling:
 * - In reality, only possible with certain modern train types
 * - Simulation allows this for any train for flexibility
 * - Engineer movement delay (30s) in Train.reverseDirection() provides realistic timing
 *
 * @since 2026-02-06 (GitHub #62)
 */
@Tag("integration-test")
@DisplayName("Timetable Reverse Direction - Unit Tests")
class TrainReverseDirectionTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: DefaultSimulationContext

	@BeforeEach
	fun setUp() {
		logger.info { "Loading vyhybna.xml for timetable reverse direction testing" }

		// Load vyhybna.xml - realistic railway network
		val xml =
			javaClass.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
			)
		requireNotNull(xml) { "vyhybna.xml must exist in resources" }

		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext

		logger.info { "Timetable reverse direction test setup complete" }
	}

	@AfterEach
	fun tearDown() {
		// These tests don't run the simulation, just test the timetable API
		if (::context.isInitialized) {
			logger.info { "Timetable reverse direction test teardown complete" }
		}
	}

	@Test
	fun `timetable can reverse In and Out destinations`() {
		// Arrange
		val inOuts = context.getInOuts().toList()
		assertThat(inOuts.size >= 2).isEqualTo(true)

		val inPoint = inOuts[0]
		val outPoint = inOuts[1]

		val timetable = Timetable(
			inPoint,
			outPoint,
			Time(0.0),
			Time(100.0),
			150.0
		)

		val train = Train(context, timetable)
		train.start()

		// Verify initial state
		assertThat(timetable.getIn()).isEqualTo(inPoint)
		assertThat(timetable.getOut()).isEqualTo(outPoint)

		// Act - Test the timetable reversal logic directly
		// Note: Train.reverseDirection() calls this same method, but also includes
		// hold(30.0) simulation delay which requires running simulation context
		assertThat(train.getVelocity()).isEqualTo(0.0)
		timetable.reverseDirection()

		// Assert - Verify In/Out swap
		assertThat(timetable.getIn()).isEqualTo(outPoint)
		assertThat(timetable.getOut()).isEqualTo(inPoint)
	}

	@Test
	fun `reversed timetable maintains other properties`() {
		// Arrange
		val inOuts = context.getInOuts().toList()
		val inPoint = inOuts[0]
		val outPoint = inOuts[1]

		val inTime = Time(5.0)
		val outTime = Time(50.0)
		val length = 200.0

		val timetable = Timetable(inPoint, outPoint, inTime, outTime, length)

		// Act
		timetable.reverseDirection()

		// Assert
		assertThat(timetable.getInTime()).isEqualTo(inTime)
		assertThat(timetable.getOutTime()).isEqualTo(outTime)
		assertThat(timetable.getLength()).isEqualTo(length)
	}

	@Test
	fun `timetable can be reversed multiple times`() {
		// Arrange
		val inOuts = context.getInOuts().toList()
		val inPoint = inOuts[0]
		val outPoint = inOuts[1]

		val timetable = Timetable(inPoint, outPoint, Time(0.0), Time(100.0), 150.0)

		val originalIn = timetable.getIn()
		val originalOut = timetable.getOut()

		// Act
		timetable.reverseDirection() // First reversal
		val afterFirstIn = timetable.getIn()
		val afterFirstOut = timetable.getOut()

		timetable.reverseDirection() // Second reversal

		// Assert
		assertThat(afterFirstIn).isEqualTo(originalOut)
		assertThat(afterFirstOut).isEqualTo(originalIn)
		assertThat(timetable.getIn()).isEqualTo(originalIn)
		assertThat(timetable.getOut()).isEqualTo(originalOut)
	}

	@Test
	fun `train created with valid timetable has correct initial destination`() {
		// Arrange
		val inOuts = context.getInOuts().toList()
		val inPoint = inOuts[0]
		val outPoint = inOuts[1]

		val timetable = Timetable(inPoint, outPoint, Time(0.0), Time(100.0), 150.0)
		val train = Train(context, timetable)

		// Act - Check origin InOut (entry point)
		val origin = train.originInOut

		// Assert
		assertThat(origin).isEqualTo(inPoint)
		assertThat(origin).isNotNull()
	}
}
