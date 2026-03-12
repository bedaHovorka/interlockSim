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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.test.inject
import java.io.InputStream

/**
 * Comprehensive tests for PathReservationRegistry PathInfo merging logic.
 *
 * ## Test Coverage
 *
 * This test suite validates the PathInfo merging algorithm used by
 * PathReservationRegistry.registerPathInfo() and mergePathInfo().
 *
 * Key scenarios tested:
 * - Normal overlap (old.target == new.start)
 * - No overlap (old.target != new.start)
 * - Single-element paths
 * - Entry direction merging
 * - Circular route rejection (new feature)
 * - Three-way merges (old → middle → new)
 *
 * ## Why This Matters
 *
 * PathInfo merging is critical for Issue #296 (Train Tail Double-Leave Bug).
 * When a train's Front reserves a new path, we must EXTEND the existing PathInfo
 * (not overwrite it) to preserve the Tail's navigation context.
 *
 * Incorrect merging can cause:
 * - Train Tail navigating in wrong direction
 * - Double-leave bugs (Tail leaves same block twice)
 * - Lost path segments (Tail can't find next block)
 *
 * @since Code Quality Plan 2026-02-02 (Phase 2)
 */
@DisplayName("PathReservationRegistry PathInfo Merging Tests")
class PathReservationRegistryMergingTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var navigator: TopologyNavigator

	// Test network elements from vyhybna.xml (extracted by name for strong assertions)
	// Network: B (30,8) → zB (27,8) → vB (26,8) → doB1 (25,8) → ... → vA (15,8) → zA (14,8) → A (11,8)
	private lateinit var inOutB: DynamicPathSeparator // InOut at (30, 8)
	private lateinit var inOutA: DynamicPathSeparator // InOut at (11, 8)
	private lateinit var semaphoreZB: DynamicPathSeparator // Semaphore "zB" at (27, 8)
	private lateinit var switchVB: DynamicPathSeparator // Switch "vB" at (26, 8)
	private lateinit var semaphoreDoB1: DynamicPathSeparator // Semaphore "doB1" at (25, 8)
	private lateinit var switchVA: DynamicPathSeparator // Switch "vA" at (15, 8)
	private lateinit var semaphoreZA: DynamicPathSeparator // Semaphore "zA" at (14, 8)

	// Track blocks between separators (extracted from path)
	private lateinit var trackBtoZB: DynamicTrackBlock // B → zB
	private lateinit var trackZBtoVB: DynamicTrackBlock // zB → vB
	private lateinit var trackVBtoDoB1: DynamicTrackBlock // vB → doB1

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml from resources
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		// Get registry and navigator from context scope
		registry = simulationContext.scope.get()
		navigator = simulationContext.scope.get()

		// Get InOut elements by name (vyhybna.xml has "A" and "B")
		val inOuts = simulationContext.getInOuts().toList()
		inOutB = inOuts.find { it.name == "B" } as? DynamicPathSeparator
			?: throw IllegalStateException("InOut 'B' not found in vyhybna.xml")
		inOutA = inOuts.find { it.name == "A" } as? DynamicPathSeparator
			?: throw IllegalStateException("InOut 'A' not found in vyhybna.xml")

		// Get named separators from the grid using known coordinates from vyhybna.xml
		// vyhybna.xml coordinates: zB (27,8), vB (26,8), doB1 (25,8), vA (15,8), zA (14,8)
		val grid = simulationContext.getRailWayNetGrid()

		// Extract separators by coordinates and convert to dynamic
		semaphoreZB = (grid.getCellAt(27, 8) as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Semaphore 'zB' not found at (27,8)")
		switchVB = (grid.getCellAt(26, 8) as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Switch 'vB' not found at (26,8)")
		semaphoreDoB1 = (grid.getCellAt(25, 8) as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Semaphore 'doB1' not found at (25,8)")
		switchVA = (grid.getCellAt(15, 8) as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Switch 'vA' not found at (15,8)")
		semaphoreZA = (grid.getCellAt(14, 8) as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Semaphore 'zA' not found at (14,8)")

		// Get track blocks by finding paths between separators
		// Path: B → zB → vB → doB1
		val pathBtoZB =
			navigator.findAllTopologicalPaths(inOutB, semaphoreZB).firstOrNull()
				?: throw IllegalStateException("No path from B to zB")
		val pathZBtoVB =
			navigator.findAllTopologicalPaths(semaphoreZB, switchVB).firstOrNull()
				?: throw IllegalStateException("No path from zB to vB")
		val pathVBtoDoB1 =
			navigator.findAllTopologicalPaths(switchVB, semaphoreDoB1).firstOrNull()
				?: throw IllegalStateException("No path from vB to doB1")

		// Extract track blocks from paths (path contains alternating separators and tracks)
		// Path structure can vary, so we filter for TrackBlocks
		trackBtoZB = pathBtoZB
			.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull() ?: throw IllegalStateException("No track block from B to zB")

		trackZBtoVB = pathZBtoVB
			.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull() ?: throw IllegalStateException("No track block from zB to vB")

		trackVBtoDoB1 = pathVBtoDoB1
			.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull() ?: throw IllegalStateException("No track block from vB to doB1")
	}

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	@Nested
	@DisplayName("First Registration")
	inner class FirstRegistration {
		@Test
		fun `registerPathInfo stores initial path when no previous PathInfo exists`() {
			// Given: A train ID with no previous PathInfo
			val trainId = "train1"

			// When: Register first PathInfo for path B → zB
			// Path: [InOut B, track(B→zB), Semaphore zB]
			val pathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, pathInfo)

			// Then: PathInfo should be stored correctly
			val retrieved = registry.getPathInfo(trainId)
			assertThat(retrieved).isNotNull()
			assertThat(retrieved!!.start).isEqualTo(inOutB) // Tail position at B
			assertThat(retrieved.target).isEqualTo(semaphoreZB) // Front position at zB
			assertThat(retrieved.reservedPath.size).isEqualTo(3) // [B, track, zB]
		}

		@Test
		fun `getPathInfo returns null for unregistered train`() {
			// Given: An empty registry
			// When: Query for non-existent train
			val retrieved = registry.getPathInfo("nonExistent")

			// Then: Should return null
			assertThat(retrieved).isNull()
		}
	}

	@Nested
	@DisplayName("Normal Overlap Merging")
	inner class NormalOverlapMerging {
		@Test
		fun `mergePathInfo with normal overlap preserves start and updates target`() {
			// Given: Train with existing path B → zB
			// Path: [InOut B, track(B→zB), Semaphore zB]
			val trainId = "train1"
			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path zB → vB (overlaps with old.target)
			// Path: [Semaphore zB, track(zB→vB), Switch vB]
			// Overlap: old.target (zB) == new.start (zB) → SKIP zB in merge
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB, // Same as old.target
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Merged path preserves original start (B) and updates to new target (vB)
			// Merged: [B, track(B→zB), zB] + [track(zB→vB), vB] (skip zB)
			// Expected size: 3 + 3 - 1 = 5
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original Tail at B
			assertThat(merged.target).isEqualTo(switchVB) // New Front at vB
			assertThat(merged.reservedPath.size).isEqualTo(5) // 3 + 3 - 1 (overlap)
		}

		@Test
		fun `three-way merge preserves original start and final target`() {
			// Given: Initial path B → zB
			val trainId = "train1"
			val path1 =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, path1)

			// When: Add middle segment zB → vB (overlaps with path1.target)
			val path2 =
				createPathInfo(
					start = semaphoreZB, // Overlap with path1.target
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			registry.registerPathInfo(trainId, path2)

			// And: Add final segment vB → doB1 (overlaps with path2.target)
			val path3 =
				createPathInfo(
					start = switchVB, // Overlap with path2.target
					target = semaphoreDoB1,
					path = listOf(switchVB, trackVBtoDoB1, semaphoreDoB1)
				)
			registry.registerPathInfo(trainId, path3)

			// Then: Final merged path has original start (B) and final target (doB1)
			// Merge 1: [B, track1, zB] + [track2, vB] = size 5
			// Merge 2: [B, track1, zB, track2, vB] + [track3, doB1] = size 7
			// Expected size: 3 + (3-1) + (3-1) = 7
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start at B
			assertThat(merged.target).isEqualTo(semaphoreDoB1) // Final target at doB1
			assertThat(merged.reservedPath.size).isEqualTo(7) // 3 + (3-1) + (3-1)
		}
	}

	@Nested
	@DisplayName("No Overlap Cases")
	inner class NoOverlapCases {
		@Test
		fun `mergePathInfo with no overlap appends full path`() {
			// Given: Train with path B → zB (using named elements)
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB) // [B, track(B→zB), zB]
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register path starting from DIFFERENT separator (no overlap)
			// Use switchVA → zA (not adjacent to zB, creates no-overlap scenario)
			val newPathInfo =
				createPathInfo(
					start = switchVA,
					target = semaphoreZA,
					path = listOf(switchVA, trackZBtoVB, semaphoreZA) // [vA, track, zA] - using available track
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Both paths should be concatenated without skipping
			// No overlap: old.target (zB) != new.start (vA)
			// Merged: [B, track(B→zB), zB] + [vA, track, zA]
			// Expected size: 3 + 3 = 6 (no overlap, no skip)
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start at B
			assertThat(merged.target).isEqualTo(semaphoreZA) // New target at zA
			assertThat(merged.reservedPath.size).isEqualTo(6) // 3 + 3 (no overlap)
		}
	}

	@Nested
	@DisplayName("Single-Element Paths")
	inner class SingleElementPaths {
		@Test
		fun `mergePathInfo with single-element old path works correctly`() {
			// Given: Train with single-element path (just a separator)
			// Valid: path=[inOutB], start=inOutB, target=inOutB ✅
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = inOutB, // ✅ Valid: single separator, target == start
					path = listOf(inOutB) // ✅ Valid: single separator path
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path from different separator (no overlap)
			// Use zB → vB (not adjacent to inOutB)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB) // [zB, track(zB→vB), vB]
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Both paths concatenated (no overlap)
			// Merged: [B] + [zB, track(zB→vB), vB]
			// Expected size: 1 + 3 = 4
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start preserved
			assertThat(merged.target).isEqualTo(switchVB) // New target at vB
			assertThat(merged.reservedPath.size).isEqualTo(4) // 1 + 3
		}

		@Test
		fun `mergePathInfo with single-element new path works correctly`() {
			// Given: Train with normal path B → zB
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB) // [B, track(B→zB), zB]
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register single-element new path (no overlap)
			// Use switchVB (different from zB, no overlap)
			val newPathInfo =
				createPathInfo(
					start = switchVB,
					target = switchVB, // ✅ Valid: single separator
					path = listOf(switchVB) // ✅ Valid: single separator path
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Should merge correctly (no overlap)
			// Merged: [B, track(B→zB), zB] + [vB]
			// Expected size: 3 + 1 = 4
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start preserved
			assertThat(merged.target).isEqualTo(switchVB) // Target updated to vB
			assertThat(merged.reservedPath.size).isEqualTo(4) // 3 + 1
		}
	}

	@Nested
	@DisplayName("Entry Direction Merging")
	inner class EntryDirectionMerging {
		@Test
		fun `mergePathInfo preserves entry directions correctly`() {
			// Given: Train with path B → zB containing entry directions
			// NOTE: Entry directions are only stored for DynamicTrackBlock elements, not separators
			val trainId = "train1"

			// Get TrackSection for trackBtoZB to use as entry direction
			val track1Section =
				trackBtoZB.getNextTrackSection(inOutB, null)
					?: throw IllegalStateException("trackBtoZB has no TrackSections from inOutB")

			val oldDirections: Map<DynamicTrackBlock, TrackSection> = mapOf(trackBtoZB to track1Section)
			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB), // [B, track(B→zB), zB]
					entryDirections = oldDirections
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path zB → vB with different entry directions (overlaps with old.target)
			val track2Section =
				trackZBtoVB.getNextTrackSection(semaphoreZB, null)
					?: throw IllegalStateException("trackZBtoVB has no TrackSections from semaphoreZB")

			val newDirections: Map<DynamicTrackBlock, TrackSection> = mapOf(trackZBtoVB to track2Section)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB, // Overlap with old.target
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB), // [zB, track(zB→vB), vB]
					entryDirections = newDirections
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Should merge successfully with both entry directions preserved
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.entryDirections).contains(trackBtoZB to track1Section)
			assertThat(merged.entryDirections).contains(trackZBtoVB to track2Section)
		}

		@Test
		fun `mergePathInfo with overlapping entry direction overwrites with new`() {
			// Given: Train with entry direction for trackBtoZB
			val trainId = "train1"

			val track1Section =
				trackBtoZB.getNextTrackSection(inOutB, null)
					?: throw IllegalStateException("trackBtoZB has no TrackSections")

			val oldDirections: Map<DynamicTrackBlock, TrackSection> = mapOf(trackBtoZB to track1Section)
			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB),
					entryDirections = oldDirections
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path with DIFFERENT entry direction for trackBtoZB
			val track2Section =
				trackZBtoVB.getNextTrackSection(semaphoreZB, null)
					?: throw IllegalStateException("trackZBtoVB has no TrackSections")

			// New direction for trackBtoZB uses track2Section (different from old)
			val newDirections: Map<DynamicTrackBlock, TrackSection> = mapOf(trackBtoZB to track2Section)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB, // Overlap with old.target
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB),
					entryDirections = newDirections
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: New direction should overwrite old
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.entryDirections[trackBtoZB]).isEqualTo(track2Section) // New overwrites old
		}

		@Test
		fun `mergePathInfo with empty entry directions works correctly`() {
			// Given: Train with no entry directions, path B → zB
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB),
					entryDirections = emptyMap()
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path zB → vB also without entry directions (overlap)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB, // Overlap with old.target
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB),
					entryDirections = emptyMap()
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Should merge successfully with empty entry directions
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.entryDirections).isEmpty()
		}
	}

	@Nested
	@DisplayName("Circular Route Validation")
	inner class CircularRouteValidation {
		@Test
		fun `mergePathInfo rejects circular route with multiple start occurrences`() {
			// Given: Train with existing path B → zB
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Attempt to register path where start appears multiple times (circular)
			// Create a path manually where semaphoreZB appears twice: [zB, track, zB]
			val circularArrayPath = ArrayPath(simulationContext)
			circularArrayPath.add(semaphoreZB) // Start
			circularArrayPath.add(trackZBtoVB.getNextTrackSection(semaphoreZB, null)!!) // Track section
			circularArrayPath.add(semaphoreZB) // Same separator again! (circular)

			val circularPath =
				PathInfo(
					start = semaphoreZB,
					target = semaphoreZB,
					reservedPath = circularArrayPath,
					entryDirections = emptyMap()
				)

			// Then: Should throw IllegalStateException with clear message
			val exception =
				assertThrows<IllegalStateException> {
					registry.registerPathInfo(trainId, circularPath)
				}
			assertThat(exception.message).isNotNull()
			assertThat(exception.message!!).contains("Circular routes not supported")
			assertThat(exception.message!!).contains("appears 2 times")
		}

		@Test
		fun `mergePathInfo allows start appearing once at path beginning`() {
			// Given: Train with existing path B → zB
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register path where start appears exactly once (at beginning): zB → vB
			val validPath =
				createPathInfo(
					start = semaphoreZB, // Appears once at beginning
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			registry.registerPathInfo(trainId, validPath)

			// Then: Should succeed
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start preserved
			assertThat(merged.target).isEqualTo(switchVB) // New target
		}

		@Test
		fun `registerPathInfo first call does not trigger validation`() {
			// Given: Empty registry
			val trainId = "train1"

			// When: Register first path B → zB (no merging, no validation)
			val pathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, pathInfo)

			// Then: Should succeed (validation only happens during merging)
			val retrieved = registry.getPathInfo(trainId)
			assertThat(retrieved).isNotNull()
			assertThat(retrieved!!.start).isEqualTo(inOutB)
			assertThat(retrieved.target).isEqualTo(semaphoreZB)
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {
		@Test
		fun `mergePathInfo handles path with only separators (no track sections)`() {
			// Given: Path with only separators (unusual but possible)
			// Single separator path: [inOutB], start=inOutB, target=inOutB ✅
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = inOutB, // ✅ Valid: single separator, target == start
					path = listOf(inOutB) // ✅ Valid: single separator
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path also with single separator (no overlap)
			// Use semaphoreZB (different from inOutB)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB,
					target = semaphoreZB, // ✅ Valid: single separator
					path = listOf(semaphoreZB) // ✅ Valid: single separator
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Should merge correctly (no overlap)
			// Merged: [inOutB] + [semaphoreZB]
			// Expected size: 1 + 1 = 2
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB)
			assertThat(merged.target).isEqualTo(semaphoreZB)
			assertThat(merged.reservedPath.size).isEqualTo(2) // 1 + 1
		}

		@Test
		fun `mergePathInfo preserves old tail position`() {
			// Given: Train with path at B (Tail position)
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB, // Tail position at B
					target = inOutB,
					path = listOf(inOutB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path zB → vB (no overlap with B)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Merged path should preserve original start (Tail position at B)
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original Tail position preserved
			assertThat(merged.target).isEqualTo(switchVB) // New Front position
		}

		@Test
		fun `mergePathInfo updates to new front position`() {
			// Given: Train with single separator path at B (Old Front position)
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = inOutB, // Old Front position at B
					path = listOf(inOutB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register new path zB → vB with new Front position (no overlap)
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB,
					target = switchVB, // New Front position at vB
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Merged path should update target to new Front position
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start
			assertThat(merged.target).isEqualTo(switchVB) // New Front position at vB
		}
	}

	@Nested
	@DisplayName("Multiple Trains Independence")
	inner class MultipleTrainsIndependence {
		@Test
		fun `registerPathInfo for different trains maintains independent PathInfo`() {
			// Given: Two trains with different paths
			val train1 = "train1"
			val train2 = "train2"

			// Train1: Single separator at B
			val path1 =
				createPathInfo(
					start = inOutB,
					target = inOutB,
					path = listOf(inOutB)
				)

			// Train2: Path zB → vB
			val path2 =
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)

			// When: Register paths for both trains
			registry.registerPathInfo(train1, path1)
			registry.registerPathInfo(train2, path2)

			// Then: Each train should have its own PathInfo
			val retrieved1 = registry.getPathInfo(train1)
			val retrieved2 = registry.getPathInfo(train2)

			assertThat(retrieved1).isNotNull()
			assertThat(retrieved2).isNotNull()
			assertThat(retrieved1!!.start).isEqualTo(inOutB)
			assertThat(retrieved1.target).isEqualTo(inOutB)
			assertThat(retrieved2!!.start).isEqualTo(semaphoreZB)
			assertThat(retrieved2.target).isEqualTo(switchVB)
		}

		@Test
		fun `unregister removes PathInfo for specific train only`() {
			// Given: Two trains with registered PathInfo and blocks
			val train1 = "train1"
			val train2 = "train2"

			// Train1 uses trackBtoZB
			val path1 =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)

			// Train2 uses trackZBtoVB
			val path2 =
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)

			// Register both PathInfo and blocks (registry needs both for unregister to work)
			registry.registerAtomic(train1, listOf(trackBtoZB))
			registry.registerPathInfo(train1, path1)

			registry.registerAtomic(train2, listOf(trackZBtoVB))
			registry.registerPathInfo(train2, path2)

			// When: Unregister train1 (removes both blocks and PathInfo)
			registry.unregister(train1)

			// Then: train1 PathInfo removed, train2 PathInfo preserved
			assertThat(registry.getPathInfo(train1)).isNull()
			assertThat(registry.getPathInfo(train2)).isNotNull()
			assertThat(registry.getPathInfo(train2)!!.start).isEqualTo(semaphoreZB)
			assertThat(registry.getPathInfo(train2)!!.target).isEqualTo(switchVB)
		}
	}

	// Helper function to create PathInfo for testing
	private fun createPathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<Any>, // DynamicPathSeparator or DynamicTrackBlock
		entryDirections: Map<DynamicTrackBlock, TrackSection> = emptyMap()
	): PathInfo {
		val arrayPath = ArrayPath(simulationContext)
		path.forEach { element ->
			when (element) {
				is DynamicPathSeparator -> arrayPath.add(element)
				is TrackSection -> arrayPath.add(element)
				else -> throw IllegalArgumentException("Invalid path element: ${element.javaClass}")
			}
		}

		return PathInfo(
			start = start,
			target = target,
			reservedPath = arrayPath,
			entryDirections = entryDirections
		)
	}
}
