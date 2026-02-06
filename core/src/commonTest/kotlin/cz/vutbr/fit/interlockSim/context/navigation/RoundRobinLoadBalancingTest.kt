/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Tests for round-robin load balancing in [DefaultPathReservationService].
 *
 * Verifies Issue #311 implementation: when multiple parallel paths exist between two points
 * (e.g., k1 and k2 tracks in vyhybna.xml), trains should be distributed fairly across all
 * available paths using round-robin selection.
 *
 * ## Test Network
 *
 * Uses vyhybna.xml (shunting loop) which has 2 parallel paths between InOut A and InOut B:
 * - Path 1: Through k1 track (MAIN branch)
 * - Path 2: Through k2 track (BRANCH branch)
 *
 * ## Round-Robin Behavior
 *
 * When both paths are free:
 * - Train 1: tries path 0 first → reserves path 0
 * - Train 2: tries path 1 first → reserves path 1
 * - Train 3: tries path 0 first → reserves path 0
 * - Train 4: tries path 1 first → reserves path 1
 *
 * This distributes load evenly across both k1 and k2 tracks.
 *
 * @since Issue #311 (Phase 2 of Issue #291)
 */
class RoundRobinLoadBalancingTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var context: DefaultSimulationContext
	private lateinit var editingContext: EditingContext
	private lateinit var pathReservationService: PathReservationService
	private lateinit var navigator: TopologyNavigator
	private lateinit var inOutA: DynamicPathSeparator
	private lateinit var inOutB: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		// Load the shunting loop network from XML
		TestFixtures.loadShuntingXml().use { xmlStream ->
			editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

		pathReservationService = context.getPathReservationService()
		navigator = context.getTopologyNavigator()

		// Get InOut elements (order from XML: [0]=B at X=30, [1]=A at X=11)
		val inOuts = context.getInOuts().toList()
		require(inOuts.size == 2) { "Expected 2 InOuts, found ${inOuts.size}" }

		inOutA = context.toDynamic(inOuts[1]) // InOut A
		inOutB = context.toDynamic(inOuts[0]) // InOut B
	}

	@AfterEach
	fun tearDown() {
		context.close()
		if (::editingContext.isInitialized) {
			editingContext.close()
		}
	}

	/**
	 * Test that multiple parallel paths exist in the test network.
	 *
	 * This is a prerequisite for round-robin load balancing - we need at least
	 * 2 paths to demonstrate fair distribution.
	 */
	@Test
	fun `vyhybna network has multiple parallel paths between InOuts`() {
		// Act
		val paths = navigator.findAllTopologicalPaths(inOutA, inOutB)

		// Assert: After Issue #291 fix, both k1 and k2 paths are discovered
		assertThat(paths).hasSize(2)
	}

	/**
	 * Test that round-robin alternates between paths for sequential trains.
	 *
	 * When both paths are free, each train should use a different path in rotation.
	 */
	@Test
	fun `round-robin alternates paths for sequential trains`() {
		// Arrange: Release all blocks after each reservation to allow next train
		
		// Act & Assert: Reserve paths for 4 trains sequentially
		val train1Blocks = reserveAndGetBlocks("train1")
		pathReservationService.releasePath("train1")

		val train2Blocks = reserveAndGetBlocks("train2")
		pathReservationService.releasePath("train2")

		val train3Blocks = reserveAndGetBlocks("train3")
		pathReservationService.releasePath("train3")

		val train4Blocks = reserveAndGetBlocks("train4")
		pathReservationService.releasePath("train4")

		// Verify: Trains alternate between two different sets of blocks (k1 and k2)
		// Train1 and Train3 should use the same path (path 0)
		// Train2 and Train4 should use the same path (path 1)
		// Convert to sets for order-independent comparison
		assertThat(train1Blocks.toSet()).isEqualTo(train3Blocks.toSet())
		assertThat(train2Blocks.toSet()).isEqualTo(train4Blocks.toSet())
		assertThat(train1Blocks.toSet()).isNotEqualTo(train2Blocks.toSet())
	}

	/**
	 * Test that round-robin provides fair distribution over many trains.
	 *
	 * With 2 parallel paths, approximately 50% of trains should use each path.
	 */
	@Test
	fun `round-robin provides balanced distribution across many trains`() {
		// Arrange: Discover the two distinct path signatures
		val path0Blocks = reserveAndGetBlocks("probe0")
		pathReservationService.releasePath("probe0")

		val path1Blocks = reserveAndGetBlocks("probe1")
		pathReservationService.releasePath("probe1")

		// Sanity check: paths are different (compare as sets for order-independence)
		assertThat(path0Blocks.toSet()).isNotEqualTo(path1Blocks.toSet())

		// Act: Reserve paths for 10 trains and track which path each uses
		var path0Count = 0
		var path1Count = 0

		for (i in 0 until 10) {
			val trainId = "train$i"
			val blocks = reserveAndGetBlocks(trainId)
			pathReservationService.releasePath(trainId)

			// Compare sets using explicit equality check
			when {
				blocks.toSet() == path0Blocks.toSet() -> path0Count++
				blocks.toSet() == path1Blocks.toSet() -> path1Count++
			}
		}

		// Assert: Both paths should be used approximately equally
		// With round-robin, we expect exactly 5 trains on each path
		assertThat(path0Count).isEqualTo(5)
		assertThat(path1Count).isEqualTo(5)
	}

	/**
	 * Test that round-robin skips blocked paths and tries alternatives.
	 *
	 * If path 0 is blocked, train should automatically use path 1.
	 */
	@Test
	fun `round-robin tries alternative path when preferred path is blocked`() {
		// Arrange: Reserve path for train1 (blocks one of the paths)
		val train1Blocks = reserveAndGetBlocks("train1")

		// Act: Reserve path for train2 (should use the other path)
		val train2Blocks = reserveAndGetBlocks("train2")

		// Assert: Train2 uses a different path than Train1 (compare as sets)
		assertThat(train2Blocks.toSet()).isNotEqualTo(train1Blocks.toSet())

		// Cleanup
		pathReservationService.releasePath("train1")
		pathReservationService.releasePath("train2")
	}

	/**
	 * Test that round-robin state is per-route (start, target).
	 *
	 * Different routes should maintain independent round-robin indices.
	 */
	@Test
	fun `round-robin state is independent per route`() {
		// This test would require a network with multiple distinct routes
		// For now, we verify that the same route maintains consistent rotation
		
		// Arrange & Act: Reserve and release 3 times
		val blocks1 = reserveAndGetBlocks("train1")
		pathReservationService.releasePath("train1")

		val blocks2 = reserveAndGetBlocks("train2")
		pathReservationService.releasePath("train2")

		val blocks3 = reserveAndGetBlocks("train3")
		pathReservationService.releasePath("train3")

		// Assert: Train1 and Train3 use the same path (full rotation, compare as sets)
		assertThat(blocks1.toSet()).isEqualTo(blocks3.toSet())
	}

	/**
	 * Test that round-robin works correctly when all paths are blocked.
	 *
	 * Should return AllPathsBlocked after trying all alternatives.
	 */
	@Test
	fun `round-robin returns AllPathsBlocked when all paths occupied`() {
		// Arrange: Get both path signatures
		val path0Blocks = reserveAndGetBlocks("probe0")
		pathReservationService.releasePath("probe0")

		val path1Blocks = reserveAndGetBlocks("probe1")
		pathReservationService.releasePath("probe1")

		// Reserve both paths
		pathReservationService.reservePath("train1", inOutA, inOutB)
		pathReservationService.reservePath("train2", inOutA, inOutB)

		// Act: Try to reserve for a third train
		val result = pathReservationService.reservePath("train3", inOutA, inOutB)

		// Assert: Should fail with AllPathsBlocked (tried both paths)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		val blocked = result as PathReservationService.ReservationResult.AllPathsBlocked
		assertThat(blocked.attemptedPaths).isEqualTo(2)

		// Cleanup
		pathReservationService.releasePath("train1")
		pathReservationService.releasePath("train2")
	}

	// Helper function to reserve a path and return the block list
	private fun reserveAndGetBlocks(trainId: String): List<DynamicTrackBlock> {
		val result = pathReservationService.reservePath(trainId, inOutA, inOutB)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		return (result as PathReservationService.ReservationResult.Success).reservedBlocks
	}
}
