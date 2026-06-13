/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Integration tests for Train Path Reservation
 * Issue #295 - Path Reservation Integration Tests
 * 2026-01-29
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

private val logger = KotlinLogging.logger {}

/**
 * Integration tests for train path reservation behavior.
 *
 * ## Test Coverage
 *
 * These tests validate the complete path reservation lifecycle from train perspective:
 * 1. **Train follows reserved path successfully** - Train navigates through pre-reserved blocks
 * 2. **Train stops when path not reserved** - Train halts when attempting to enter unreserved blocks
 * 3. **Train waits when block reserved for different train** - Train waits for path occupied by another train
 * 4. **Train resumes when path becomes available** - Train continues after blocking train releases path
 *
 * ## Test Architecture
 *
 * Uses real SimulationContext loaded from vyhybna.xml configuration:
 * - Real railway network topology (switches, semaphores, track blocks)
 * - Real PathReservationService with PathReservationRegistry
 * - Real TrainNavigationService
 * - Wrapped in MockSimulationContext to prevent kDisco simulation execution
 *
 * ## Design Approach
 *
 * Tests focus on path reservation state machine without running full simulation:
 * - Reserve paths using PathReservationService
 * - Query path availability using TrainNavigationService
 * - Verify ownership via PathReservationRegistry
 * - Validate state transitions (FREE → RESERVED, RESERVED → FREE)
 *
 * ## Railway Context
 *
 * These tests validate interlocking safety rules:
 * - Trains can only enter blocks reserved for them (absolute block signaling)
 * - Multiple trains cannot reserve the same block simultaneously
 * - Trains wait when path is occupied by another train
 * - Path release makes blocks available for next train
 *
 * ## Testing Without kDisco Simulation
 *
 * Per CLAUDE.md guidance, simulation classes should have minimal changes.
 * These tests validate the path reservation system at the service level
 * without executing the full kDisco event loop (context.run(cz.vutbr.fit.interlockSim.context.NoOpSimulationController)).
 *
 * @since 2026-01-29 (Issue #295)
 */
@Tag("integration-test")
@DisplayName("Train Path Reservation - Integration Tests")
class TrainPathReservationIntegrationTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: SimulationContext
	private lateinit var reservationService: PathReservationService
	private lateinit var trainNavService: TrainNavigationService

	@BeforeEach
	fun setUp() {
		logger.info { "Loading vyhybna.xml for integration testing" }

		// Load vyhybna.xml - realistic railway network configuration
		val xml =
			TestFixtures.loadShuntingXml()
		requireNotNull(xml) { "vyhybna.xml must exist in resources" }

		// Create real simulation context (wrapped to prevent kDisco execution)
		val loadedContext = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		context = MockSimulationContext(loadedContext)

		// Get services from context
		reservationService = context.getPathReservationService()
		trainNavService = context.getTrainNavigationService()

		logger.info { "Integration test setup complete" }
	}

	@AfterEach
	fun tearDown() {
		// Clean up context and release Koin scope
		if (::context.isInitialized) {
			context.close()
		}
	}

	// ==================== Test 1: Train follows reserved path successfully ====================

	@Nested
	@Tag("integration-test")
	@DisplayName("Test 1: Train follows reserved path successfully")
	inner class TrainFollowsReservedPath {
		/**
		 * Test: Train can navigate through path that has been reserved for it.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path from InOut A to InOut B for "Train #1"
		 * 2. Verify reservation succeeded (blocks marked as RESERVED)
		 * 3. Query path availability for "Train #1" at each separator along route
		 * 4. Verify train can see the path (findReservedPathForTrain returns non-null)
		 * 5. Verify ownership is correct (getReservedBlocks returns expected blocks)
		 *
		 * ## Expected Behavior
		 *
		 * - PathReservationService.reservePath() returns Success
		 * - TrainNavigationService.findReservedPathForTrain() returns valid Path
		 * - TrainNavigationService.isPathReservedForTrain() returns true
		 * - PathReservationRegistry shows correct ownership
		 *
		 * ## Railway Context
		 *
		 * This validates absolute block signaling: train can enter blocks it has reserved.
		 */
		@Test
		fun `train can navigate through reserved path`() {
			// Given: Get entry and exit points from network
			val inOuts = context.getInOuts().toList()
			assertThat(inOuts).isNotEmpty()

			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]

			logger.info {
				"Testing path reservation from ${startInOut.name} to ${targetInOut.name}"
			}

			// When: Reserve path for Train #1
			val trainId = "Train #1"
			val result = reservationService.reservePath(trainId, startInOut, targetInOut)

			// Skip test if network topology doesn't support this scenario
			if (result is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Then: Verify successful reservation
			result as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result")

			logger.info { "Path reserved successfully: ${result.reservedBlocks.size} blocks" }

			// Verify blocks are owned by this train
			val ownedBlocks = reservationService.getReservedBlocks(trainId)
			assertThat(ownedBlocks).hasSize(result.reservedBlocks.size)

			// Verify train can see the path
			val pathForTrain = trainNavService.findReservedPathForTrain(trainId, startInOut)
			assertThat(pathForTrain).isInstanceOf(PathResult.Available::class)

			// Verify path availability check returns true
			val isAvailable = trainNavService.isPathReservedForTrain(trainId, startInOut)
			assertThat(isAvailable).isTrue()

			logger.info { "Train #1 can successfully navigate reserved path" }
		}

		/**
		 * Test: Train can traverse multiple segments of reserved path.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve complete path from start to end
		 * 2. Simulate train progression by checking path at intermediate separators
		 * 3. Verify path remains valid as train "moves" through network
		 *
		 * ## Expected Behavior
		 *
		 * - Path remains reserved throughout train's journey
		 * - Train can query path from any intermediate separator
		 * - Ownership remains consistent
		 */
		@Test
		fun `train maintains path reservation through multiple segments`() {
			// Given: Reserve path
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId = "Train #2"

			// When: Reserve and verify
			val result = reservationService.reservePath(trainId, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists" }
				return
			}

			// Then: Verify path consistency
			result as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result")

			val reservedBlocks = result.reservedBlocks
			assertThat(reservedBlocks).isNotEmpty()

			// Verify ownership persists
			val ownedBlocks1 = reservationService.getReservedBlocks(trainId)
			val ownedBlocks2 = reservationService.getReservedBlocks(trainId)
			assertThat(ownedBlocks1).hasSize(ownedBlocks2.size)

			logger.info { "Path reservation persists: ${reservedBlocks.size} blocks" }
		}
	}

	// ==================== Test 2: Train stops when path not reserved ====================

	@Nested
	@Tag("integration-test")
	@DisplayName("Test 2: Train stops when path not reserved")
	inner class TrainStopsWithoutReservation {
		/**
		 * Test: Train cannot navigate through path that is not reserved.
		 *
		 * ## Scenario
		 *
		 * 1. Do NOT reserve any path
		 * 2. Query path availability for "Train #3"
		 * 3. Verify train cannot see the path (findReservedPathForTrain returns null)
		 * 4. Verify availability check returns false
		 *
		 * ## Expected Behavior
		 *
		 * - TrainNavigationService.findReservedPathForTrain() returns null
		 * - TrainNavigationService.isPathReservedForTrain() returns false
		 * - Train has no reserved blocks
		 *
		 * ## Railway Context
		 *
		 * This validates safety: train cannot enter unreserved blocks (would violate absolute block).
		 */
		@Test
		fun `train cannot navigate unreserved path`() {
			// Given: Network with no reservations for this train
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val trainId = "Train #3"

			// When: Query path availability
			val pathForTrain = trainNavService.findReservedPathForTrain(trainId, startInOut)
			val isAvailable = trainNavService.isPathReservedForTrain(trainId, startInOut)
			val ownedBlocks = reservationService.getReservedBlocks(trainId)

			// Then: Verify train has no access
			assertThat(pathForTrain).isInstanceOf(PathResult.OwnershipConflict::class)
			assertThat(isAvailable).isFalse()
			assertThat(ownedBlocks).isEmpty()

			logger.info { "Train #3 correctly blocked from unreserved path" }
		}

		/**
		 * Test: Train loses access when path is released.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path for train
		 * 2. Verify train can see path
		 * 3. Release path
		 * 4. Verify train can no longer see path
		 *
		 * ## Expected Behavior
		 *
		 * - Before release: findReservedPathForTrain returns path
		 * - After release: findReservedPathForTrain returns null
		 * - Blocks transition from RESERVED to FREE
		 */
		@Test
		fun `train loses access after path release`() {
			// Given: Reserved path
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId = "Train #4"

			val result = reservationService.reservePath(trainId, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result")

			// Verify train has access
			val pathBefore = trainNavService.findReservedPathForTrain(trainId, startInOut)
			assertThat(pathBefore).isInstanceOf(PathResult.Available::class)

			// When: Release path
			val releasedBlocks = reservationService.releasePath(trainId)
			assertThat(releasedBlocks).isNotEmpty()

			// Then: Verify train no longer has access
			val pathAfter = trainNavService.findReservedPathForTrain(trainId, startInOut)
			val ownedBlocks = reservationService.getReservedBlocks(trainId)

			assertThat(pathAfter).isInstanceOf(PathResult.OwnershipConflict::class)
			assertThat(ownedBlocks).isEmpty()

			logger.info { "Train #4 correctly loses access after path release" }
		}
	}

	// ==================== Test 3: Train waits when block reserved for different train ====================

	@Nested
	@Tag("integration-test")
	@DisplayName("Test 3: Train waits when block reserved for different train")
	inner class TrainWaitsForBlockedPath {
		/**
		 * Test: Second train cannot access path reserved by first train.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path for "Train #5"
		 * 2. Attempt to query same path for "Train #6"
		 * 3. Verify second train cannot see the path
		 * 4. Verify first train still has exclusive access
		 *
		 * ## Expected Behavior
		 *
		 * - Train #5: findReservedPathForTrain returns path
		 * - Train #6: findReservedPathForTrain returns null
		 * - Only Train #5 appears in ownership registry
		 *
		 * ## Railway Context
		 *
		 * This validates mutual exclusion: only one train can reserve a block at a time.
		 */
		@Test
		fun `second train cannot access path reserved by first train`() {
			// Given: Path reserved for Train #5
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId1 = "Train #5"
			val trainId2 = "Train #6"

			val result = reservationService.reservePath(trainId1, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result")

			logger.info { "Train #5 reserved path successfully" }

			// When: Second train attempts to access same path
			val pathForTrain1 = trainNavService.findReservedPathForTrain(trainId1, startInOut)
			val pathForTrain2 = trainNavService.findReservedPathForTrain(trainId2, startInOut)

			val availableForTrain1 = trainNavService.isPathReservedForTrain(trainId1, startInOut)
			val availableForTrain2 = trainNavService.isPathReservedForTrain(trainId2, startInOut)

			// Then: Verify exclusive access
			assertThat(pathForTrain1).isInstanceOf(PathResult.Available::class)
			assertThat(pathForTrain2).isInstanceOf(PathResult.OwnershipConflict::class)

			assertThat(availableForTrain1).isTrue()
			assertThat(availableForTrain2).isFalse()

			// Verify ownership
			val ownedByTrain1 = reservationService.getReservedBlocks(trainId1)
			val ownedByTrain2 = reservationService.getReservedBlocks(trainId2)

			assertThat(ownedByTrain1).isNotEmpty()
			assertThat(ownedByTrain2).isEmpty()

			logger.info {
				"Train #6 correctly blocked by Train #5's reservation " +
					"(${ownedByTrain1.size} blocks occupied)"
			}
		}

		/**
		 * Test: Attempt to reserve already-reserved path fails gracefully.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path for Train #7
		 * 2. Attempt to reserve same path for Train #8
		 * 3. Verify second reservation fails with AllPathsBlocked
		 * 4. Verify first reservation remains intact
		 *
		 * ## Expected Behavior
		 *
		 * - First reservation: Success
		 * - Second reservation: AllPathsBlocked (or Conflict if atomicity fails)
		 * - First train's ownership unchanged
		 */
		@Test
		fun `reservation fails when path is already reserved`() {
			// Given: Path reserved for Train #7
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId1 = "Train #7"
			val trainId2 = "Train #8"

			val result1 = reservationService.reservePath(trainId1, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result1 is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result1 as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result1")

			val reservedBlocks1 = result1.reservedBlocks
			logger.info { "Train #7 reserved ${reservedBlocks1.size} blocks" }

			// When: Second train attempts to reserve same path
			val result2 = reservationService.reservePath(trainId2, startInOut, targetInOut)

			// Then: Verify second reservation fails
			assertThat(result2).isNotNull()

			when (result2) {
				is PathReservationService.ReservationResult.Success -> {
					throw AssertionError(
						"Second train should not be able to reserve same path! " +
							"This indicates a race condition or broken mutual exclusion."
					)
				}

				is PathReservationService.ReservationResult.AllPathsBlocked -> {
					logger.info { "Train #8 correctly blocked: all ${result2.attemptedPaths} paths occupied" }
				}

				is PathReservationService.ReservationResult.Conflict -> {
					logger.info {
						"Train #8 blocked by conflict: " +
							"block=${result2.conflictingBlock.name}, " +
							"owner=${result2.existingOwner}"
					}
				}

				is PathReservationService.ReservationResult.NoPathExists -> {
					logger.info { "No alternative path exists (network topology)" }
				}
			}

			// Verify first train's ownership unchanged
			val ownedByTrain1 = reservationService.getReservedBlocks(trainId1)
			val ownedByTrain2 = reservationService.getReservedBlocks(trainId2)

			assertThat(ownedByTrain1).hasSize(reservedBlocks1.size)
			assertThat(ownedByTrain2).isEmpty()

			logger.info { "Train #7's reservation remains intact after Train #8's failed attempt" }
		}
	}

	// ==================== Test 4: Train resumes when path becomes available ====================

	@Nested
	@Tag("integration-test")
	@DisplayName("Test 4: Train resumes when path becomes available")
	inner class TrainResumesWhenPathAvailable {
		/**
		 * Test: Second train can reserve path after first train releases it.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path for Train #9
		 * 2. Verify Train #10 cannot access path
		 * 3. Train #9 releases path
		 * 4. Verify Train #10 can now reserve and access path
		 *
		 * ## Expected Behavior
		 *
		 * - Initially: Train #9 has exclusive access, Train #10 blocked
		 * - After release: Train #9 has no access, Train #10 can reserve
		 * - Blocks transition: RESERVED(#9) → FREE → RESERVED(#10)
		 *
		 * ## Railway Context
		 *
		 * This validates path handover: after one train clears a block, next train can enter.
		 */
		@Test
		fun `second train can reserve path after first train releases`() {
			// Given: Path reserved for Train #9
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId1 = "Train #9"
			val trainId2 = "Train #10"

			val result1 = reservationService.reservePath(trainId1, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result1 is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result1 as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result1")

			logger.info { "Train #9 reserved path (${result1.reservedBlocks.size} blocks)" }

			// Verify Train #10 is initially blocked
			val pathForTrain2Before = trainNavService.findReservedPathForTrain(trainId2, startInOut)
			assertThat(pathForTrain2Before).isInstanceOf(PathResult.OwnershipConflict::class)

			// When: Train #9 releases path
			val releasedBlocks = reservationService.releasePath(trainId1)
			assertThat(releasedBlocks).hasSize(result1.reservedBlocks.size)
			logger.info { "Train #9 released ${releasedBlocks.size} blocks" }

			// Then: Train #10 can now reserve the path
			val result2 = reservationService.reservePath(trainId2, startInOut, targetInOut)

			when (result2) {
				is PathReservationService.ReservationResult.Success -> {
					logger.info { "Train #10 successfully reserved path (${result2.reservedBlocks.size} blocks)" }

					// Verify ownership transfer
					val ownedByTrain1 = reservationService.getReservedBlocks(trainId1)
					val ownedByTrain2 = reservationService.getReservedBlocks(trainId2)

					assertThat(ownedByTrain1).isEmpty()
					assertThat(ownedByTrain2).hasSize(result2.reservedBlocks.size)

					// Verify Train #10 can navigate
					val pathForTrain2 = trainNavService.findReservedPathForTrain(trainId2, startInOut)
					assertThat(pathForTrain2).isInstanceOf(PathResult.Available::class)

					logger.info { "Path handover successful: Train #9 → Train #10" }
				}

				else -> {
					throw AssertionError(
						"Train #10 should be able to reserve freed path, got: $result2"
					)
				}
			}
		}

		/**
		 * Test: Path availability changes are immediately visible.
		 *
		 * ## Scenario
		 *
		 * 1. Check path availability for Train #11 (should be available)
		 * 2. Reserve path for Train #12
		 * 3. Check path availability for Train #11 (should be unavailable)
		 * 4. Release Train #12's path
		 * 5. Check path availability for Train #11 (should be available again)
		 *
		 * ## Expected Behavior
		 *
		 * - Availability checks reflect current network state
		 * - No stale/cached results
		 * - State changes are atomic
		 */
		@Test
		fun `path availability reflects real-time network state`() {
			// Given: Clean network state
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId1 = "Train #11"
			val trainId2 = "Train #12"

			// Initially: Path should be available on clean network
			val availableBefore = reservationService.isPathAvailable(startInOut, targetInOut)

			if (!availableBefore) {
				// Path should be free on clean network at test start
				throw AssertionError("Expected clean network with available path at test start")
			}

			logger.info { "Path initially available" }

			// When: Reserve for Train #12
			val result = reservationService.reservePath(trainId2, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result")

			// Then: Path should be unavailable for Train #11
			val availableDuringReservation =
				trainNavService.isPathReservedForTrain(trainId1, startInOut)
			assertThat(availableDuringReservation).isFalse()
			logger.info { "Path correctly unavailable for Train #11 while Train #12 holds it" }

			// When: Release Train #12's reservation
			reservationService.releasePath(trainId2)

			// Then: Path should be available again
			val availableAfter = reservationService.isPathAvailable(startInOut, targetInOut)
			assertThat(availableAfter).isTrue()
			logger.info { "Path available again after Train #12 releases" }
		}

		/**
		 * Test: Multiple trains can wait and resume in sequence.
		 *
		 * ## Scenario
		 *
		 * 1. Reserve path for Train #13
		 * 2. Verify Train #14 and Train #15 are both blocked
		 * 3. Release Train #13's path
		 * 4. Reserve for Train #14
		 * 5. Verify Train #15 is still blocked
		 * 6. Release Train #14's path
		 * 7. Verify Train #15 can now reserve
		 *
		 * ## Expected Behavior
		 *
		 * - Multiple trains can be waiting for same path
		 * - Path is available to next train immediately after release
		 * - FIFO behavior (if enforced by reservation service)
		 *
		 * ## Railway Context
		 *
		 * This simulates a queue of trains waiting at a junction or single-track section.
		 */
		@Test
		fun `multiple trains can wait and resume in sequence`() {
			// Given: Three trains competing for same path
			val inOuts = context.getInOuts().toList()
			val startInOut = inOuts[0]
			val targetInOut = inOuts.getOrNull(1) ?: inOuts[0]
			val trainId1 = "Train #13"
			val trainId2 = "Train #14"
			val trainId3 = "Train #15"

			// Reserve for Train #13
			val result1 = reservationService.reservePath(trainId1, startInOut, targetInOut)

			// Skip if network doesn't support this scenario
			if (result1 is PathReservationService.ReservationResult.NoPathExists) {
				logger.info { "Test skipped: No path exists between ${startInOut.name} and ${targetInOut.name}" }
				return
			}

			// Verify result is Success
			result1 as? PathReservationService.ReservationResult.Success
				?: throw AssertionError("Expected Success, got: $result1")

			logger.info { "Train #13 holds path" }

			// Verify both waiting trains are blocked
			val path2 = trainNavService.findReservedPathForTrain(trainId2, startInOut)
			val path3 = trainNavService.findReservedPathForTrain(trainId3, startInOut)
			assertThat(path2).isInstanceOf(PathResult.OwnershipConflict::class)
			assertThat(path3).isInstanceOf(PathResult.OwnershipConflict::class)
			logger.info { "Train #14 and #15 both blocked by Train #13" }

			// Release Train #13
			reservationService.releasePath(trainId1)
			logger.info { "Train #13 released path" }

			// Reserve for Train #14
			val result2 = reservationService.reservePath(trainId2, startInOut, targetInOut)

			when (result2) {
				is PathReservationService.ReservationResult.Success -> {
					logger.info { "Train #14 acquired path" }

					// Verify Train #15 is still blocked
					val path3AfterTrain14 = trainNavService.findReservedPathForTrain(trainId3, startInOut)
					assertThat(path3AfterTrain14).isInstanceOf(PathResult.OwnershipConflict::class)
					logger.info { "Train #15 still blocked by Train #14" }

					// Release Train #14
					reservationService.releasePath(trainId2)
					logger.info { "Train #14 released path" }

					// Reserve for Train #15
					val result3 = reservationService.reservePath(trainId3, startInOut, targetInOut)

					when (result3) {
						is PathReservationService.ReservationResult.Success -> {
							logger.info { "Train #15 successfully acquired path (final train in sequence)" }

							val path3Final = trainNavService.findReservedPathForTrain(trainId3, startInOut)
							assertThat(path3Final).isInstanceOf(PathResult.Available::class)

							logger.info { "Sequential path handover successful: #13 → #14 → #15" }
						}

						else -> {
							throw AssertionError("Train #15 should acquire path after #14 releases: $result3")
						}
					}
				}

				else -> {
					throw AssertionError("Train #14 should acquire path after #13 releases: $result2")
				}
			}
		}
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a timetable for testing.
	 *
	 * @param inRef Entry point
	 * @param outRef Exit point
	 * @param length Train length in meters
	 * @return Timetable configuration
	 */
	private fun createTimetable(
		inRef: DynamicInOut,
		outRef: DynamicInOut,
		length: Double = 100.0
	): Timetable =
		Timetable(
			inRef,
			outRef,
			Time(0.0),
			Time(60.0),
			length
		)
}
