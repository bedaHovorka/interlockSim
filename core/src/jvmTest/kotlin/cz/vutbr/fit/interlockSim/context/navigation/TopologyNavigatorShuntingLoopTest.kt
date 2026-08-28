/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	TopologyNavigator ShuntingLoop Historical Paths Test
	Phase 4 of ShuntingLoop Migration (Issue #296)

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	2026-01-26
*/

package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import kotlin.getValue

private val logger = KotlinLogging.logger {}

/**
 * Comprehensive test suite verifying TopologyNavigator discovers paths in the
 * vyhybna.xml shunting loop network (Issue #296 Phase 4).
 *
 * ## Background
 *
 * ShuntingLoop Phase 4 migrated from manual path construction (~100 lines)
 * to TopologyNavigator-based dynamic path discovery. This test verifies that
 * TopologyNavigator can discover paths in the complex vyhybna.xml network.
 *
 * ## vyhybna.xml Network Structure
 *
 * Grid coordinates and element mapping:
 * - (11,8): InOut A (entry)
 * - (14,8): zA (entry semaphore, orientation false)
 * - (15,8): vA (switch, SIMPLE_RIGHT_FALSE)
 * - (16,8): doA1 (upper route semaphore, orientation true)
 * - (17,9): doA2 (lower route semaphore, orientation true)
 * - (24,9): doB2 (lower route semaphore, orientation false)
 * - (25,8): doB1 (upper route semaphore, orientation false)
 * - (26,8): vB (switch, SIMPLE_LEFT_TRUE)
 * - (27,8): zB (exit semaphore, orientation true)
 * - (30,8): InOut B (exit)
 *
 * ## Track Blocks
 *
 * - **kA**: InOut A (11,8) ↔ zA (14,8) - entry approach
 * - **k1**: doA1 (16,8) ↔ doB1 (25,8) - upper route
 * - **k2**: doA2 (17,9) ↔ doB2 (24,9) - lower route
 * - **kB**: zB (27,8) ↔ InOut B (30,8) - exit approach
 *
 * ## Test Scope
 *
 * Due to TopologyNavigator's design (static topology only, no segment context),
 * tests focus on InOut-to-InOut connectivity. Intermediate semaphore navigation
 * requires segment direction hints that BFS doesn't maintain.
 *
 * @since 2026-01-26 (Issue #296 Phase 4)
 */
@DisplayName("TopologyNavigator - ShuntingLoop Path Discovery (Issue #296)")
class TopologyNavigatorShuntingLoopTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

	private lateinit var context: EditingContext
	private lateinit var navigator: TopologyNavigator

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml fixture
		context = TestFixtures.loadShuntingEditingContext(editingContextFactory)

		// Get TopologyNavigator from context's scope
		navigator = context.scope.get()
	}

	// ========================================================================
	// InOut-to-InOut Connectivity Tests
	// ========================================================================

	@Nested
	@DisplayName("InOut-to-InOut Path Discovery")
	inner class InOutConnectivityTests {
		/**
		 * Test: Forward path from entry A to exit B.
		 *
		 * Historical paths 1, 3, 6, 8 all connect A → B through different routes.
		 * TopologyNavigator should discover at least one forward path.
		 */
		@Test
		fun `testForwardPath_AtoB - discovers at least one path through network`() {
			// Arrange: Get entry and exit InOuts
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Act: Find all topological paths from A to B
			val paths = navigator.findAllTopologicalPaths(inOutA, inOutB, maxDepth = 100)

			// Assert: At least one forward path exists
			assertThat(paths).isNotEmpty()
			// Verify each path is non-empty and contains TrackSections
			paths.forEach { path ->
				assertThat(path).isNotEmpty()
				path.forEach { section ->
					assertThat(section).isInstanceOf<TrackSection>()
				}
			}

			// Log discovered paths for debugging
			logger.info { "✓ Forward paths A → B: Found ${paths.size} path(s)" }
			paths.forEachIndexed { index, path ->
				val coords = extractSeparatorCoordinates(path)
				logger.info { "  Path $index: ${coords.joinToString(" → ")}" }
			}
		}

		/**
		 * Test: Reverse path from exit B to entry A.
		 *
		 * Historical paths 2, 4, 5, 7 all connect B → A through different routes.
		 * TopologyNavigator should discover at least one reverse path.
		 */
		@Test
		fun `testReversePath_BtoA - discovers at least one path through network`() {
			// Arrange: Get entry and exit InOuts
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Act: Find all topological paths from B to A
			val paths = navigator.findAllTopologicalPaths(inOutB, inOutA, maxDepth = 100)

			// Assert: At least one reverse path exists
			assertThat(paths).isNotEmpty()
			// Verify each path is non-empty and contains TrackSections
			paths.forEach { path ->
				assertThat(path).isNotEmpty()
				path.forEach { section ->
					assertThat(section).isInstanceOf<TrackSection>()
				}
			}

			// Log discovered paths for debugging
			logger.info { "✓ Reverse paths B → A: Found ${paths.size} path(s)" }
			paths.forEachIndexed { index, path ->
				val coords = extractSeparatorCoordinates(path)
				logger.info { "  Path $index: ${coords.joinToString(" → ")}" }
			}
		}

		/**
		 * Test: Network has multiple possible paths (due to switches).
		 *
		 * The vyhybna.xml network has two switches (vA and vB) creating
		 * upper and lower routes. Top ologyNavigator should discover
		 * multiple topologically distinct paths.
		 */
		@Test
		fun `testMultiplePaths - discovers multiple routes through switches`() {
			// Arrange: Get entry and exit InOuts
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Act: Find all forward paths
			val forwardPaths = navigator.findAllTopologicalPaths(inOutA, inOutB, maxDepth = 100)

			// Assert: Multiple paths exist (due to switches providing alternatives)
			// The vyhybna.xml has 2 switches, so expect at least 2 distinct paths
			assertThat(forwardPaths.size).isGreaterThan(1)
			// Verify all paths are non-empty and contain TrackSections
			forwardPaths.forEach { path ->
				assertThat(path).isNotEmpty()
				path.forEach { section ->
					assertThat(section).isInstanceOf<TrackSection>()
				}
			}

			logger.info { "✓ Multiple paths A → B: ${forwardPaths.size} path(s) discovered" }

			// Validate: Each path is acyclic (no repeated separators)
			forwardPaths.forEach { path ->
				val separatorCoords = extractSeparatorCoordinates(path)
				val uniqueCoords = separatorCoords.toSet()
				assertThat(separatorCoords.size).isEqualTo(uniqueCoords.size)
			}
		}
	}

	// ========================================================================
	// Path Structure Validation
	// ========================================================================

	@Nested
	@DisplayName("Path Structure Validation")
	inner class PathStructureValidation {
		/**
		 * Test: All discovered paths are acyclic (no loops).
		 *
		 * Validates BFS cycle detection works correctly.
		 */
		@Test
		fun `testAllPathsAreAcyclic - no repeated separators in any path`() {
			// Arrange: Get entry and exit InOuts
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Act: Find all paths in both directions
			val forwardPaths = navigator.findAllTopologicalPaths(inOutA, inOutB, maxDepth = 100)
			val reversePaths = navigator.findAllTopologicalPaths(inOutB, inOutA, maxDepth = 100)
			val allPaths = forwardPaths + reversePaths

			// Assert: All paths are acyclic
			allPaths.forEach { path ->
				val separatorCoords = extractSeparatorCoordinates(path)
				val uniqueCoords = separatorCoords.toSet()

				// Assert: Each separator appears at most once (acyclic)
				assertThat(separatorCoords.size).isEqualTo(uniqueCoords.size)
			}

			logger.info { "✓ All ${allPaths.size} paths are acyclic (no repeated separators)" }
		}

		/**
		 * Test: Forward and reverse paths have similar complexity.
		 *
		 * In a symmetric shunting loop, forward and reverse paths should
		 * have comparable lengths.
		 */
		@Test
		fun `testSymmetricPaths - forward and reverse paths have similar lengths`() {
			// Arrange: Get entry and exit InOuts
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Act: Find paths in both directions
			val forwardPaths = navigator.findAllTopologicalPaths(inOutA, inOutB, maxDepth = 100)
			val reversePaths = navigator.findAllTopologicalPaths(inOutB, inOutA, maxDepth = 100)

			// Assert: Both directions have at least one path
			assertThat(forwardPaths).isNotEmpty()
			assertThat(reversePaths).isNotEmpty()

			// Calculate average path lengths
			val avgForwardLength = forwardPaths.map { it.size }.average()
			val avgReverseLength = reversePaths.map { it.size }.average()

			logger.info {
				"✓ Path symmetry: Forward avg=${avgForwardLength.format(1)}, " +
					"Reverse avg=${avgReverseLength.format(1)}"
			}

			// Assert: Lengths are reasonably symmetric (within 50% of each other)
			val ratio = avgForwardLength / avgReverseLength
			assertThat(ratio).isGreaterThan(0.5)
			assertThat(ratio < 2.0).isEqualTo(true)
		}

		private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
	}

	// ========================================================================
	// Network Topology Verification
	// ========================================================================

	/**
	 * Holds all separator coordinates from vyhybna.xml network.
	 */
	private data class NetworkCoordinates(
		// Entry semaphore
		val zA: Point,
		// Entry switch
		val vA: Point,
		// Upper entry route semaphore
		val doA1: Point,
		// Lower entry route semaphore
		val doA2: Point,
		// Lower exit route semaphore
		val doB2: Point,
		// Upper exit route semaphore
		val doB1: Point,
		// Exit switch
		val vB: Point,
		// Exit semaphore
		val zB: Point
	)

	@Nested
	@DisplayName("Network Topology Verification")
	inner class TopologyVerification {
		/**
		 * Test: vyhybna.xml network has expected structure.
		 *
		 * Verifies that the loaded network has the expected separators
		 * at the documented grid coordinates.
		 */
		@Test
		fun `testNetworkStructure - verifies expected separators exist`() {
			// Act: Query separators at known coordinates
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")
			val zA = getSeparatorAt(14, 8)
			val vA = getSeparatorAt(15, 8)
			val doA1 = getSeparatorAt(16, 8)
			val doA2 = getSeparatorAt(17, 9)
			val doB2 = getSeparatorAt(24, 9)
			val doB1 = getSeparatorAt(25, 8)
			val vB = getSeparatorAt(26, 8)
			val zB = getSeparatorAt(27, 8)

			// Assert: All separators exist
			assertThat(inOutA).isNotNull()
			assertThat(inOutB).isNotNull()
			assertThat(zA).isNotNull()
			assertThat(vA).isNotNull()
			assertThat(doA1).isNotNull()
			assertThat(doA2).isNotNull()
			assertThat(doB2).isNotNull()
			assertThat(doB1).isNotNull()
			assertThat(vB).isNotNull()
			assertThat(zB).isNotNull()

			logger.info { "✓ Network structure verified: 10 separators found at expected coordinates" }
		}

		/**
		 * Test: Discovered paths contain expected intermediate separators from historical paths.
		 *
		 * This test validates that TopologyNavigator discovers paths containing the same
		 * network elements as the 8 historical paths that were manually constructed
		 * in the old ShuntingLoop code (Issue #296).
		 *
		 * ## Historical Path Patterns (from GitHub issue #296 comment)
		 *
		 * **Upper Route (via doA1/doB1):**
		 * 1. zA → vA → doA1 → k1 → doB1 (forward inner)
		 * 2. doA1 → vA → zA → kA → A (reverse entry)
		 * 5. zB → vB → doB1 → k1 → doA1 (reverse inner)
		 * 6. doB1 → vB → zB → kB → B (forward exit)
		 *
		 * **Lower Route (via doA2/doB2):**
		 * 3. zA → vA → doA2 → k2 → doB2 (forward inner)
		 * 4. doA2 → vA → zA → kA → A (reverse entry)
		 * 7. zB → vB → doB2 → k2 → doA2 (reverse inner)
		 * 8. doB2 → vB → zB → kB → B (forward exit)
		 *
		 * ## Actual TopologyNavigator Behavior
		 *
		 * TopologyNavigator respects current switch positions and discovers paths through
		 * the configured route. In vyhybna.xml, switches default to the upper route,
		 * so discovered paths traverse doA1/doB1 rather than doA2/doB2.
		 *
		 * This test verifies that discovered paths contain the key network elements
		 * (entry/exit semaphores, switches) that historical paths traversed.
		 */
		@Test
		fun `testHistoricalPathPatterns - discovered paths contain expected intermediate separators`() {
			// Arrange: Get InOut endpoints
			val inOutA = getInOut("A")
			val inOutB = getInOut("B")

			// Known separator coordinates from vyhybna.xml
			val coords =
				NetworkCoordinates(
					zA = Point(14, 8),
					vA = Point(15, 8),
					doA1 = Point(16, 8),
					doA2 = Point(17, 9),
					doB2 = Point(24, 9),
					doB1 = Point(25, 8),
					vB = Point(26, 8),
					zB = Point(27, 8)
				)

			// Act: Find all paths in both directions
			val forwardPaths = navigator.findAllTopologicalPaths(inOutA, inOutB, maxDepth = 100)
			val reversePaths = navigator.findAllTopologicalPaths(inOutB, inOutA, maxDepth = 100)

			// Assert: Paths were found
			assertThat(forwardPaths).isNotEmpty()
			assertThat(reversePaths).isNotEmpty()

			logger.info { "\n=== Historical Path Pattern Verification ===" }
			logger.info { "Forward paths (A → B): ${forwardPaths.size}" }
			logger.info { "Reverse paths (B → A): ${reversePaths.size}" }

			// Extract coordinates from all paths
			val forwardCoordSets = forwardPaths.map { extractSeparatorCoordinates(it).toSet() }
			val reverseCoordSets = reversePaths.map { extractSeparatorCoordinates(it).toSet() }

			// Debug: Print discovered paths
			logDiscoveredPaths("Forward", "A → B", forwardPaths)
			logDiscoveredPaths("Reverse", "B → A", reversePaths)

			// Verify Forward Paths contain expected patterns
			val (upperRouteForward, lowerRouteForward) =
				verifyForwardPathPatterns(
					forwardCoordSets,
					coords
				)

			// Verify Reverse Paths contain expected patterns
			val (upperRouteReverse, lowerRouteReverse) =
				verifyReversePathPatterns(
					reverseCoordSets,
					coords
				)

			// Verify Key Network Separators Are Present
			verifyKeySeparatorCoverage(
				forwardCoordSets,
				reverseCoordSets,
				coords,
				upperRouteForward,
				upperRouteReverse
			)
		}

		private fun containsAllCoord(
			forwardCoordSets: List<Set<Point>>,
			coordinateZA: Point,
			coordinateVA: Point,
			coordinateDoA2: Point,
			coordinateDoB2: Point,
			coordinateVB: Point,
			coordinateZB: Point
		): Boolean =
			forwardCoordSets.any { coords ->
				coords.contains(coordinateZA) &&
					coords.contains(coordinateVA) &&
					coords.contains(coordinateDoA2) &&
					coords.contains(coordinateDoB2) &&
					coords.contains(coordinateVB) &&
					coords.contains(coordinateZB)
			}

		/**
		 * Logs discovered paths with direction label.
		 */
		private fun logDiscoveredPaths(
			direction: String,
			arrow: String,
			paths: List<List<TrackSection>>
		) {
			logger.info { "\n--- Discovered $direction Paths ($arrow) ---" }
			paths.forEachIndexed { index, path ->
				val coords = extractSeparatorCoordinates(path)
				logger.info { "$direction path $index: ${coords.joinToString(" → ")}" }
			}
		}

		/**
		 * Verifies forward path patterns (A → B) through upper or lower route.
		 *
		 * @return Pair(upperRouteFound, lowerRouteFound)
		 */
		private fun verifyForwardPathPatterns(
			forwardCoordSets: List<Set<Point>>,
			coords: NetworkCoordinates
		): Pair<Boolean, Boolean> {
			logger.info { "\n--- Forward Path Pattern Verification (A → B) ---" }

			// Check upper route
			val upperRoute =
				containsAllCoord(
					forwardCoordSets,
					coords.zA,
					coords.vA,
					coords.doA1,
					coords.doB1,
					coords.vB,
					coords.zB
				)

			// Check lower route
			val lowerRoute =
				containsAllCoord(
					forwardCoordSets,
					coords.zA,
					coords.vA,
					coords.doA2,
					coords.doB2,
					coords.vB,
					coords.zB
				)

			// Assert at least one route exists
			assertThat(upperRoute || lowerRoute).isEqualTo(true)

			if (upperRoute) {
				logger.info { "✓ Upper route forward path found (zA → vA → doA1 → doB1 → vB → zB)" }
			}
			if (lowerRoute) {
				logger.info { "✓ Lower route forward path found (zA → vA → doA2 → doB2 → vB → zB)" }
			}
			logger.info { "  Forward route discovered: ${if (upperRoute) "upper" else "lower"}" }

			return upperRoute to lowerRoute
		}

		/**
		 * Verifies reverse path patterns (B → A) through upper or lower route.
		 *
		 * @return Pair(upperRouteFound, lowerRouteFound)
		 */
		private fun verifyReversePathPatterns(
			reverseCoordSets: List<Set<Point>>,
			coords: NetworkCoordinates
		): Pair<Boolean, Boolean> {
			logger.info { "\n--- Reverse Path Pattern Verification (B → A) ---" }

			// Check upper route
			val upperRoute =
				containsAllCoord(
					reverseCoordSets,
					coords.zB,
					coords.vB,
					coords.doB1,
					coords.doA1,
					coords.vA,
					coords.zA
				)

			// Check lower route
			val lowerRoute =
				containsAllCoord(
					reverseCoordSets,
					coords.zB,
					coords.vB,
					coords.doB2,
					coords.doA2,
					coords.vA,
					coords.zA
				)

			// Assert at least one route exists
			assertThat(upperRoute || lowerRoute).isEqualTo(true)

			if (upperRoute) {
				logger.info { "✓ Upper route reverse path found (zB → vB → doB1 → doA1 → vA → zA)" }
			}
			if (lowerRoute) {
				logger.info { "✓ Lower route reverse path found (zB → vB → doB2 → doA2 → vA → zA)" }
			}
			logger.info { "  Reverse route discovered: ${if (upperRoute) "upper" else "lower"}" }

			return upperRoute to lowerRoute
		}

		/**
		 * Verifies that all key network separators are present in discovered paths.
		 */
		private fun verifyKeySeparatorCoverage(
			forwardCoordSets: List<Set<Point>>,
			reverseCoordSets: List<Set<Point>>,
			coords: NetworkCoordinates,
			upperRouteForward: Boolean,
			upperRouteReverse: Boolean
		) {
			logger.info { "\n--- Key Network Separator Coverage ---" }
			val allCoordinates = (forwardCoordSets + reverseCoordSets).flatten().toSet()

			// Common separators (entry/exit infrastructure)
			val commonSeparators =
				listOf(
					coords.zA to "zA (entry semaphore)",
					coords.vA to "vA (entry switch)",
					coords.vB to "vB (exit switch)",
					coords.zB to "zB (exit semaphore)"
				)

			// Route-specific separators (depending on which route was discovered)
			val routeSpecificSeparators =
				if (upperRouteForward || upperRouteReverse) {
					listOf(
						coords.doA1 to "doA1 (upper entry route)",
						coords.doB1 to "doB1 (upper exit route)"
					)
				} else {
					listOf(
						coords.doA2 to "doA2 (lower entry route)",
						coords.doB2 to "doB2 (lower exit route)"
					)
				}

			// Verify common separators
			commonSeparators.forEach { (coord, name) ->
				assertThat(allCoordinates.contains(coord)).isEqualTo(true)
				logger.info { "✓ $name at $coord" }
			}

			// Verify route-specific separators
			routeSpecificSeparators.forEach { (coord, name) ->
				assertThat(allCoordinates.contains(coord)).isEqualTo(true)
				logger.info { "✓ $name at $coord" }
			}

			// Final Summary
			logFinalSummary(upperRouteForward, upperRouteReverse)
		}

		/**
		 * Logs final summary of path discovery results.
		 */
		private fun logFinalSummary(
			upperRouteForward: Boolean,
			upperRouteReverse: Boolean
		) {
			logger.info { "\n=== Summary ===" }
			val forwardMsg = if (upperRouteForward) "upper (paths 1, 6)" else "lower (paths 3, 8)"
			val reverseMsg = if (upperRouteReverse) "upper (paths 2, 5)" else "lower (paths 4, 7)"
			logger.info { "✓ Forward route discovered: $forwardMsg" }
			logger.info { "✓ Reverse route discovered: $reverseMsg" }
			logger.info { "✓ All 8 critical separators present in network" }
			logger.info { "✓ Discovered paths traverse historical network elements" }
			logger.info { "✓ TopologyNavigator successfully discovers paths matching historical structure" }
			logger.info { "" }
			logger.info {
				"Note: TopologyNavigator respects current switch positions " +
					"(upper route in vyhybna.xml)."
			}
			logger.info { "Historical paths 1, 2, 5, 6 (upper route) are directly replicated." }
			logger.info {
				"Lower route paths (3, 4, 7, 8) would be discovered " +
					"if switches were set differently."
			}
		}
	}

	// ========================================================================
	// Helper Methods
	// ========================================================================

	/**
	 * Queries grid for separator at specified coordinates.
	 *
	 * @param x grid x coordinate
	 * @param y grid y coordinate
	 * @return PathSeparator (InOut, RailSemaphore, or RailSwitch)
	 * @throws AssertionError if cell not found or not a PathSeparator
	 */
	private fun getSeparatorAt(
		x: Int,
		y: Int
	): PathSeparator {
		val grid = context.getRailWayNetGrid()
		val cell = grid.getCellAt(x, y)

		requireNotNull(cell) { "Cell at ($x, $y) not found in grid" }
		require(cell is PathSeparator) { "Cell at ($x, $y) is not a PathSeparator, got ${cell.javaClass.simpleName}" }

		return cell
	}

	/**
	 * Queries context for named InOut element.
	 *
	 * @param name InOut name ("A" or "B")
	 * @return PathSeparator (DynamicInOut wrapper for simulation context)
	 * @throws AssertionError if InOut not found
	 */
	private fun getInOut(name: String): PathSeparator {
		val inOuts = context.getInOuts()
		val inOut = inOuts.find { it.getName() == name }

		requireNotNull(inOut) { "InOut '$name' not found in context" }

		// Return as PathSeparator (DynamicInOut implements PathSeparator via OrientedPathSeparator)
		return inOut as PathSeparator
	}

	/**
	 * Extracts ordered list of separator coordinates from path.
	 *
	 * Helper for debugging and validation.
	 *
	 * @param path list of TrackSections
	 * @return list of Point(x, y) in traversal order
	 */
	private fun extractSeparatorCoordinates(path: List<TrackSection>): List<Point> {
		val grid = context.getRailWayNetGrid()
		val coordinates = mutableListOf<Point>()
		var previousEnd: PathSeparator? = null

		// Extract all separators from the path by tracking block endpoints
		path.forEach { section ->
			val block = section.getTrackBlock()
			val ends = block.ends()

			// First section: add the start separator
			if (previousEnd == null) {
				// Add first endpoint
				val firstEnd = ends[0]
				extractCoordinate(firstEnd, grid)?.let { coordinates.add(it) }
				previousEnd = firstEnd
			}

			// Add the other endpoint (the one we're moving to)
			val nextEnd = if (ends[0] == previousEnd) ends[1] else ends[0]
			extractCoordinate(nextEnd, grid)?.let { coordinates.add(it) }
			previousEnd = nextEnd
		}

		return coordinates
	}

	/**
	 * Extracts grid coordinate from a PathSeparator.
	 *
	 * Handles both static NodeCell and Dynamic wrapper types.
	 *
	 * @param separator PathSeparator to extract coordinate from
	 * @param grid Railway network grid
	 * @return Point coordinate or null if not found in grid
	 */
	private fun extractCoordinate(
		separator: PathSeparator,
		grid: cz.vutbr.fit.interlockSim.context.RailwayNetGrid<Cell>
	): Point? {
		// Unwrap DynamicPathSeparator to NodeCell for grid lookup
		val staticSeparator =
			when (separator) {
				is Cell -> separator
			}

		return staticSeparator.let { grid.getLocation(it) }
	}
}
