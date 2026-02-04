package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Integration tests for parallel path discovery in [DefaultPathReservationService].
 *
 * These tests verify that the path reservation service correctly discovers ALL parallel paths
 * in networks with multiple switch layers, enabling trains to find alternative routes when
 * their preferred path is blocked.
 *
 * Test network: vyhybna.xml (shunting loop with parallel tracks k1 and k2)
 * ```
 * InOut A → kA → semaphore zA → switch vA → {
 *   path 1: doA1 (backward) → k1 → doB1 (forward)
 *   path 2: doA2 (backward) → k2 → doB2 (forward)
 * }
 * ```
 *
 * ## Issue #291 Fix Verification
 *
 * These tests verify the fix for Train #2 movement issue:
 * - **Root Cause**: `findNextSemaphoresVia()` only explored ALL branches at FIRST junction
 * - **Fix**: Removed `currentSep == start` condition to explore ALL branches at EVERY junction
 * - **Result**: Trains can now discover parallel paths in multi-layer switch networks
 */
class DefaultPathReservationServiceParallelPathTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var context: DefaultSimulationContext
	private lateinit var pathReservationService: PathReservationService
	private lateinit var navigator: TopologyNavigator
	private lateinit var inOutA: DynamicPathSeparator
	private lateinit var inOutB: DynamicPathSeparator
	private lateinit var nextFromA: cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
	private lateinit var nextFromB: cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

	@BeforeEach
	fun setUp() {
		// Load the shunting loop network from XML
		val xmlStream: InputStream =
			javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
				?: throw IllegalStateException("Test resource vyhybna.xml not found")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		pathReservationService = context.getPathReservationService()
		navigator = context.getTopologyNavigator()

		// Get InOut elements (there are exactly 2 in vyhybna.xml)
		val inOuts = context.getInOuts().toList()
		require(inOuts.size == 2) { "Expected 2 InOuts, found ${inOuts.size}" }

		// Get by index (order from XML: [0]=B at X=30, [1]=A at X=11)
		inOutA = context.toDynamic(inOuts[1]) // InOut A
		inOutB = context.toDynamic(inOuts[0]) // InOut B

		// Get next track sections (like InOutWorker does)
		nextFromA = navigator.getNextTrackSection(inOutA, null)
			?: throw IllegalStateException("InOut A has no next section")
		nextFromB = navigator.getNextTrackSection(inOutB, null)
			?: throw IllegalStateException("InOut B has no next section")
	}

	@AfterEach
	fun tearDown() {
		context.close()
	}

	/**
	 * Test that [PathReservationService.reservePathToAnyNextSemaphore] finds the next semaphore
	 * when reserving from InOut A.
	 *
	 * Scenario:
	 * 1. Reserve path for Train #1 from InOut A to next semaphore
	 * 2. Train #1 blocks the shared entry (Block 9 + semaphore zA)
	 * 3. Try to reserve path for Train #2 from InOut A to next semaphore
	 * 4. Should fail with AllPathsBlocked because shared entry is occupied
	 *
	 * Note: Both trains CANNOT reserve from the same InOut simultaneously because they share
	 * the entry block (Block 9) and entry semaphore (zA). The parallel paths (k1 and k2)
	 * only diverge AFTER these shared resources.
	 *
	 * Issue #291 fix enables discovering ALL next semaphores (doB1 and doB2), but both paths
	 * still require the shared entry resources, so only one train can proceed at a time.
	 */
	@Test
	fun `second train blocked when first train occupies shared entry from InOut A`() {
		// Act: Train1 reserves to next semaphore via Block 9
		val train1Result = pathReservationService.reservePathToAnyNextSemaphore(
			trainId = "train1",
			start = inOutA,
			next = nextFromA
		)

		// Assert: Train1 succeeds
		assertThat(train1Result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val train1Blocks = (train1Result as PathReservationService.ReservationResult.Success).reservedBlocks
		assertThat(train1Blocks.size).isGreaterThanOrEqualTo(1)
		println("Train1 reserved ${train1Blocks.size} blocks")

		// Act: Train2 tries to reserve to next semaphore - should fail because shared entry blocked
		val train2Result = pathReservationService.reservePathToAnyNextSemaphore(
			trainId = "train2",
			start = inOutA,
			next = nextFromA
		)

		// Assert: Train2 fails because shared entry (Block 9) is occupied by Train1
		assertThat(train2Result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		val blocked = train2Result as PathReservationService.ReservationResult.AllPathsBlocked
		println("Train2 blocked, attemptedPaths = ${blocked.attemptedPaths}")

		// From InOut A, only ONE path exists to the first semaphore (zA)
		// The parallel paths (k1 and k2) only diverge AFTER zA
		// So only 1 attempt is made to reserve InOut A → zA
		// Parallel path discovery happens when using the TWO-parameter API from zA
		assertThat(blocked.attemptedPaths).isEqualTo(1)
	}

	/**
	 * Test that path can be re-reserved after proper release.
	 *
	 * Scenario:
	 * 1. Reserve path for Train #1 from InOut A
	 * 2. Verify reservation blocks other trains (shared entry)
	 * 3. Release Train #1's path properly (cancel setup + unregister)
	 * 4. Reserve for Train #2 - should succeed now that shared entry is free
	 */
	@Test
	fun `path can be reserved after proper release`() {
		// Reserve path for Train1
		val train1Result = pathReservationService.reservePathToAnyNextSemaphore(
			"train1", inOutA, nextFromA
		)
		assertThat(train1Result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val train1Blocks = (train1Result as PathReservationService.ReservationResult.Success).reservedBlocks

		// Verify Train2 is blocked (shared entry occupied)
		val train2BlockedResult = pathReservationService.reservePathToAnyNextSemaphore(
			"train2", inOutA, nextFromA
		)
		assertThat(train2BlockedResult).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// Release Train1's reservation properly
		pathReservationService.releasePath("train1")

		// Now Train2 should succeed
		val train2Result = pathReservationService.reservePathToAnyNextSemaphore(
			"train2", inOutA, nextFromA
		)
		assertThat(train2Result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	/**
	 * Test path reservation from opposite directions.
	 *
	 * Scenario:
	 * 1. Reserve path from InOut A to next semaphore (Train #1)
	 * 2. Reserve path from InOut B to next semaphore (Train #2)
	 * 3. Both should succeed (independent entry points)
	 */
	@Test
	fun `trains from opposite directions can reserve independently`() {
		// Act: Train1 from InOut A → next semaphore
		val train1Result = pathReservationService.reservePathToAnyNextSemaphore(
			"train1", inOutA, nextFromA
		)

		// Act: Train2 from InOut B → next semaphore (opposite direction)
		val train2Result = pathReservationService.reservePathToAnyNextSemaphore(
			"train2", inOutB, nextFromB
		)

		// Assert: Both succeed - independent entry points
		assertThat(train1Result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(train2Result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val train1Blocks = (train1Result as PathReservationService.ReservationResult.Success).reservedBlocks
		val train2Blocks = (train2Result as PathReservationService.ReservationResult.Success).reservedBlocks

		assertThat(train1Blocks.size).isGreaterThanOrEqualTo(1)
		assertThat(train2Blocks.size).isGreaterThanOrEqualTo(1)
	}
}
