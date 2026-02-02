/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Train Movement Integration Tests
 * Issue #295 - Actual Train behavior with path reservation
 * 2026-01-29
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

private val logger = KotlinLogging.logger {}

/**
 * Integration tests for actual Train movement with path reservation.
 *
 * ## Purpose
 *
 * These tests verify that actual Train instances (sim/ package) correctly interact
 * with the path reservation system during simulation execution. Unlike
 * TrainPathReservationIntegrationTest which tests services in isolation, these
 * tests run real trains through the jDisco simulation.
 *
 * ## Test Scenarios
 *
 * Uses simple linear track configurations:
 * - InOut A → Track → Semaphore → Track → InOut B
 * - Tests train movement, stopping, waiting, and resuming
 *
 * ## Conservative Approach
 *
 * Per CLAUDE.md guidance for sim/ package:
 * - Uses existing vyhybna.xml network (realistic topology)
 * - Short simulation times (10-60 seconds)
 * - Validates train state without modifying Train class
 * - Tests observe behavior through public APIs only
 *
 * ## What These Tests Validate
 *
 * 1. **Train follows reserved path** - Train moves from A to B when path is reserved
 * 2. **Train stops when path not reserved** - Train halts at semaphore when blocks not reserved
 * 3. **Train waits for conflicting train** - Train waits when another train holds the path
 * 4. **Train resumes when path available** - Train continues after blocking path is released
 *
 * ## Railway Context
 *
 * These tests validate the complete signaling and interlocking system:
 * - Absolute block signaling (one train per block)
 * - Semaphore control (trains obey signals)
 * - Path reservation ownership (exclusive access)
 * - Safe train separation (collision avoidance)
 *
 * @since 2026-01-29 (Issue #295 - Option B)
 */
@Tag("integration-test")
@DisplayName("Train Movement - Integration Tests with jDisco Simulation")
class TrainMovementIntegrationTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: DefaultSimulationContext
	private lateinit var reservationService: PathReservationService

	@BeforeEach
	fun setUp() {
		logger.info { "Loading vyhybna.xml for train movement integration testing" }

		// Load vyhybna.xml - realistic railway network
		val xml =
			javaClass.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
			)
		requireNotNull(xml) { "vyhybna.xml must exist in resources" }

		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		reservationService = context.getPathReservationService()

		logger.info { "Train movement integration test setup complete" }
	}

	@AfterEach
	fun tearDown() {
		if (::context.isInitialized) {
			context.close()
		}
	}

	// ==================== Test 1: Train follows reserved path ====================

	/**
	 * Test: Train successfully travels from InOut A to InOut B when path is reserved.
	 *
	 * ## Scenario
	 *
	 * 1. Reserve path from InOut A to InOut B for train
	 * 2. Create Train with timetable (A → B)
	 * 3. Activate train in simulation
	 * 4. Run simulation for limited time
	 * 5. Verify train reaches destination or makes progress
	 *
	 * ## Expected Behavior
	 *
	 * - Train should start at InOut A
	 * - Train should enter reserved blocks
	 * - Train should make forward progress (velocity > 0)
	 * - Train should not encounter blocking semaphores
	 *
	 * ## Railway Context
	 *
	 * This validates the complete "happy path" - train with reserved route
	 * travels unimpeded from origin to destination.
	 *
	 * ## Note on Simulation Time
	 *
	 * Short simulation time (10 seconds) to verify train starts moving.
	 * Full journey validation would require longer times and is deferred.
	 *
	 * ## TODO: Disabled - Awaiting Simplified Test Infrastructure
	 *
	 * This test is disabled because running individual Train instances directly
	 * requires infrastructure that doesn't currently exist.
	 *
	 * **Why These Tests Don't Compile:**
	 *
	 * 1. `train.activate()` doesn't exist - Train is a jDisco Process that must be
	 *    created and managed by a parent Process (like ShuntingLoop creates Generator,
	 *    which creates Trains).
	 *
	 * 2. `context.run(time)` doesn't exist - The SimulationContext.run() method takes
	 *    NO parameters and runs until the mainProcess completes. Simulation time is
	 *    controlled by the Process itself (e.g., ShuntingLoop's endTime parameter).
	 *
	 * **The Correct Pattern (see SimulationExecutionTest.kt):**
	 *
	 * ```kotlin
	 * // 1. Create a main Process coordinator (e.g., ShuntingLoop)
	 * val shuntingLoop = ShuntingLoop(context, endTime = 30L)
	 *
	 * // 2. Set it as the main process
	 * context.setMainProcess(shuntingLoop)
	 *
	 * // 3. Run simulation (no parameters - runs until mainProcess completes)
	 * context.run()
	 *
	 * // 4. Validate results after simulation completes
	 * assertThat(context.getInOuts()).isNotEmpty()
	 * ```
	 *
	 * **What's Needed to Implement These Tests:**
	 *
	 * Create a SimpleLinearTrackTestProcess that extends jDisco Process and:
	 * - Accepts network configuration (linear track A→B)
	 * - Creates Train instances with specified timetables
	 * - Manages train lifecycle (entry, movement, exit)
	 * - Provides test hooks for validation (e.g., getTrainById(), getTrainState())
	 * - Runs for a specified simulation time
	 *
	 * **Alternative Approach:**
	 *
	 * Use existing ShuntingLoop but with simpler network configurations (just A→B).
	 * This requires creating test XML files with linear track layouts.
	 */
	@Test
	@Disabled("Awaiting simplified test infrastructure - Train requires Process coordinator")
	fun `train successfully travels through reserved path`() {
		// Given: Network with two InOuts
		val inOuts = context.getInOuts().toList()
		if (inOuts.size < 2) {
			logger.info { "Test skipped: Network requires at least 2 InOuts" }
			return
		}

		val startInOut = inOuts[0]
		val targetInOut = inOuts[1]

		logger.info { "Testing train movement from ${startInOut.name} to ${targetInOut.name}" }

		// Reserve path for the train
		val trainId = "Train #1"
		val result = reservationService.reservePath(trainId, startInOut, targetInOut)

		// Skip if no path exists
		if (result is PathReservationService.ReservationResult.NoPathExists) {
			logger.info { "Test skipped: No path exists between InOuts" }
			return
		}

		// Verify reservation succeeded
		result as? PathReservationService.ReservationResult.Success
			?: throw AssertionError("Expected successful path reservation, got: $result")

		logger.info { "Path reserved: ${result.reservedBlocks.size} blocks" }

		// Create timetable and train
		val timetable = createTimetable(startInOut, targetInOut, trainLength = 100.0)
		val train = Train(context, timetable)

		assertThat(train).isNotNull()
		assertThat(train.getLength()).isEqualTo(100.0)
		assertThat(train.getVelocity()).isEqualTo(0.0)

		// TODO: Need to create a SimpleLinearTrackTestProcess that:
		// 1. Extends jDisco Process
		// 2. Creates and manages this Train instance
		// 3. Can be set as mainProcess via context.setMainProcess(testProcess)
		// 4. Runs for a specified simulation time
		//
		// Then the test would look like:
		// val testProcess = SimpleLinearTrackTestProcess(context, train, endTime = 10.0)
		// context.setMainProcess(testProcess)
		// context.run()
		//
		// val finalVelocity = train.getVelocity()
		// assertThat(finalVelocity).isGreaterThan(0.0)

		logger.info { "Test structure preserved - awaiting test infrastructure implementation" }
	}

	// ==================== Test 2: Train stops when path not reserved ====================

	/**
	 * Test: Train stops at semaphore when path is not reserved.
	 *
	 * ## Scenario
	 *
	 * 1. Do NOT reserve path for train
	 * 2. Create Train with timetable (A → B)
	 * 3. Activate train in simulation
	 * 4. Run simulation for limited time
	 * 5. Verify train does not make progress (blocks at entry)
	 *
	 * ## Expected Behavior
	 *
	 * - Train should wait at entry InOut
	 * - Train velocity should remain 0 (cannot enter unreserved blocks)
	 * - InOutWorker should not allow entry without reservation
	 *
	 * ## Railway Context
	 *
	 * This validates safety: trains cannot enter blocks they haven't reserved.
	 * This is the core principle of absolute block signaling.
	 *
	 * ## Implementation Note
	 *
	 * The InOutWorker (not Train directly) checks if path is reserved before
	 * allowing train entry. Train.actions() waits until path is reserved via:
	 * `waitUntil { trainNavService.isPathReservedForTrain(name, inout) }`
	 *
	 * ## TODO: Disabled - Awaiting Simplified Test Infrastructure
	 *
	 * See first test's TODO comment for full explanation. Same infrastructure
	 * limitations apply: needs Process coordinator, context.run() no parameters.
	 */
	@Test
	@Disabled("Awaiting simplified test infrastructure - Train requires Process coordinator")
	fun `train stops at entry when path not reserved`() {
		// Given: Network with two InOuts
		val inOuts = context.getInOuts().toList()
		if (inOuts.size < 2) {
			logger.info { "Test skipped: Network requires at least 2 InOuts" }
			return
		}

		val startInOut = inOuts[0]
		val targetInOut = inOuts[1]

		logger.info { "Testing train blocking at ${startInOut.name} (no path reservation)" }

		// DO NOT reserve path - train should not be able to enter

		// Create timetable and train
		val timetable = createTimetable(startInOut, targetInOut, trainLength = 100.0)
		val train = Train(context, timetable)

		assertThat(train.getVelocity()).isEqualTo(0.0)

		// TODO: Need SimpleLinearTrackTestProcess to run this test
		// See first test's TODO for complete infrastructure requirements

		logger.info { "Test structure preserved - awaiting test infrastructure implementation" }
	}

	// ==================== Test 3: Train waits for conflicting train ====================

	/**
	 * Test: Second train waits when first train holds the path.
	 *
	 * ## Scenario
	 *
	 * 1. Reserve path from A to B for Train #1
	 * 2. Create Train #1 and activate (occupies path)
	 * 3. Create Train #2 with same route
	 * 4. Activate Train #2 (should wait)
	 * 5. Run simulation
	 * 6. Verify Train #2 does not enter (blocked by Train #1)
	 *
	 * ## Expected Behavior
	 *
	 * - Train #1 should enter and move
	 * - Train #2 should remain at entry (velocity = 0)
	 * - Path remains reserved for Train #1
	 *
	 * ## Railway Context
	 *
	 * This validates mutual exclusion - only one train can hold a path at a time.
	 * The second train must wait until the first clears the route.
	 *
	 * ## Simplified Scenario
	 *
	 * For now, we just verify that Train #2 cannot enter while Train #1 holds
	 * the path. Full handover testing (Train #1 exits, Train #2 enters) is
	 * deferred to future tests.
	 *
	 * ## TODO: Disabled - Awaiting Simplified Test Infrastructure
	 *
	 * See first test's TODO comment for full explanation. Same infrastructure
	 * limitations apply: needs Process coordinator, context.run() no parameters.
	 */
	@Test
	@Disabled("Awaiting simplified test infrastructure - Train requires Process coordinator")
	fun `second train waits when first train holds path`() {
		// Given: Network with two InOuts
		val inOuts = context.getInOuts().toList()
		if (inOuts.size < 2) {
			logger.info { "Test skipped: Network requires at least 2 InOuts" }
			return
		}

		val startInOut = inOuts[0]
		val targetInOut = inOuts[1]

		logger.info { "Testing train conflict: Train #1 holds path, Train #2 waits" }

		// Reserve path for Train #1
		val trainId1 = "Train #1"
		val result = reservationService.reservePath(trainId1, startInOut, targetInOut)

		// Skip if no path exists
		if (result is PathReservationService.ReservationResult.NoPathExists) {
			logger.info { "Test skipped: No path exists" }
			return
		}

		result as? PathReservationService.ReservationResult.Success
			?: throw AssertionError("Expected successful reservation, got: $result")

		logger.info { "Path reserved for Train #1" }

		// TODO: Need SimpleLinearTrackTestProcess to run this multi-train test
		// Infrastructure must support:
		// 1. Creating multiple Train instances
		// 2. Managing their lifecycles independently
		// 3. Validating individual train states during simulation
		//
		// See first test's TODO for complete infrastructure requirements

		logger.info { "Test structure preserved - awaiting test infrastructure implementation" }
	}

	// ==================== Test 4: Train resumes when path becomes available ====================

	/**
	 * Test: Train resumes movement when blocking path is released.
	 *
	 * ## Scenario
	 *
	 * 1. Create Train #1, reserve path, activate
	 * 2. Run simulation briefly (Train #1 enters)
	 * 3. Create Train #2 (will be blocked)
	 * 4. Release Train #1's path reservation
	 * 5. Reserve path for Train #2
	 * 6. Continue simulation
	 * 7. Verify Train #2 starts moving
	 *
	 * ## Expected Behavior
	 *
	 * - Initially: Train #1 moving, Train #2 blocked
	 * - After handover: Train #2 should start moving
	 *
	 * ## Railway Context
	 *
	 * This validates path handover - once a train clears the route, the next
	 * train can reserve and enter.
	 *
	 * ## Simplified Scenario
	 *
	 * This test manually simulates the handover by:
	 * 1. Releasing Train #1's reservation (simulates train exiting)
	 * 2. Reserving path for Train #2
	 * 3. Verifying Train #2 can now proceed
	 *
	 * Full automatic handover (train releases as it exits) is the actual
	 * system behavior but harder to test without complex timing.
	 *
	 * ## TODO: Disabled - Awaiting Simplified Test Infrastructure
	 *
	 * See first test's TODO comment for full explanation. Same infrastructure
	 * limitations apply: needs Process coordinator, context.run() no parameters.
	 */
	@Test
	@Disabled("Awaiting simplified test infrastructure - Train requires Process coordinator")
	fun `train resumes after path becomes available`() {
		// Given: Network with two InOuts
		val inOuts = context.getInOuts().toList()
		if (inOuts.size < 2) {
			logger.info { "Test skipped: Network requires at least 2 InOuts" }
			return
		}

		val startInOut = inOuts[0]
		val targetInOut = inOuts[1]

		logger.info { "Testing path handover: Train #1 releases, Train #2 proceeds" }

		// Reserve path for Train #1
		val trainId1 = "Train #1"
		val result1 = reservationService.reservePath(trainId1, startInOut, targetInOut)

		if (result1 is PathReservationService.ReservationResult.NoPathExists) {
			logger.info { "Test skipped: No path exists" }
			return
		}

		result1 as? PathReservationService.ReservationResult.Success
			?: throw AssertionError("Expected successful reservation, got: $result1")

		// TODO: Need SimpleLinearTrackTestProcess to run this path handover test
		// This is the most complex test scenario requiring:
		// 1. Running simulation in phases (train1 enters, train2 waits, handover, train2 proceeds)
		// 2. Pausing simulation to manually trigger path release and re-reservation
		// 3. Validating train states at multiple checkpoints
		//
		// See first test's TODO for complete infrastructure requirements

		logger.info { "Test structure preserved - awaiting test infrastructure implementation" }
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a timetable for testing.
	 *
	 * @param inRef Entry point
	 * @param outRef Exit point
	 * @param trainLength Train length in meters
	 * @return Timetable configuration
	 */
	private fun createTimetable(
		inRef: DynamicInOut,
		outRef: DynamicInOut,
		trainLength: Double = 100.0
	): Timetable =
		Timetable(
			inRef,
			outRef,
			Time(0.0), // Start time
			Time(300.0), // End time (5 minutes)
			trainLength
		)
}
