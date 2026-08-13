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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
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
 * - Normal continuation (old.target == new.start) — the only shape that actually merges
 * - Non-contiguous new path (old.target != new.start) — merge ABORTS, stored PathInfo untouched
 * - Single-element paths
 * - Entry direction merging
 * - Duplicated new start (circular route) — merge ABORTS, no exception
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
 * ## Behaviour change (Issue #834, SP2c.11)
 *
 * Until #834 this suite asserted two shapes that were in fact defects:
 *
 * 1. **Concatenation of a non-contiguous new path.** `old.target != new.start` used to be
 *    concatenated verbatim, producing two adjacent `PathSeparator`s. Navigation readers
 *    truncate safely at that seam, so the train never teleports — but the reservation's tail
 *    stays RESERVED behind a cleared START aspect that the train will never reach.
 *    `mergePathInfo` now ABORTS such a merge and keeps the stored PathInfo unchanged.
 * 2. **`IllegalStateException` on a duplicated new start.** That throw escaped
 *    `reservePath` *after* blocks were reserved and a signal was already cleared, and it
 *    killed the kDisco simulation thread while the run still wrote a well-formed result
 *    file. `mergePathInfo` must never throw; the same WARN-and-`return old` fail-safe abort
 *    the cycle guard already used is now used for this case too.
 *
 * The abort WARNs themselves are asserted in `dispatcher-agent`'s
 * `MergeAbortSimSurvivalTest` (Logback is only on that module's test compile classpath).
 *
 * @since Code Quality Plan 2026-02-02 (Phase 2); abort semantics from Issue #834 (SP2c.11)
 */
@DisplayName("PathReservationRegistry PathInfo Merging Tests")
class PathReservationRegistryMergingTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
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
	@DisplayName("Non-Contiguous Merge Abort (Issue #834)")
	inner class NonContiguousMergeAbort {
		@Test
		fun `mergePathInfo with a non-contiguous new path keeps the stored PathInfo unchanged`() {
			// Given: Train with path B → zB (using named elements)
			val trainId = "train1"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB) // [B, track(B→zB), zB]
				)
			)
			val stored = storedPathInfo(trainId)

			// When: Register path starting from a DIFFERENT separator (not contiguous)
			// Use switchVA → zA (nowhere near zB, so old.target != new.start)
			val newPathInfo =
				createPathInfo(
					start = switchVA,
					target = semaphoreZA,
					path = listOf(switchVA, trackZBtoVB, semaphoreZA) // [vA, track, zA] - using available track
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: the merge is ABORTED. Concatenating would have produced two adjacent
			// separators (zB followed by vA) and an orphaned RESERVED tail behind a cleared
			// aspect the train can never reach. The stored PathInfo must be the *same object*
			// as before — not a truncated or partially merged copy (#316 rule).
			assertStoredIsExactly(trainId, stored)
			assertThat(stored.target).isEqualTo(semaphoreZB) // still the OLD target
			assertThat(stored.reservedPath.size).isEqualTo(3) // 3, not 3 + 3
		}

		@Test
		fun `registerPathInfo reports Aborted for a non-contiguous new path`() {
			// Issue #904: the caller (reservePath) needs a typed signal that the merge was
			// aborted, not just the stored PathInfo staying unchanged, so it can release the
			// resources it just acquired for the candidate that produced the aborted merge.
			val trainId = "train_904_abort_signal"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			)

			val outcome =
				registry.registerPathInfo(
					trainId,
					createPathInfo(
						start = switchVA,
						target = semaphoreZA,
						path = listOf(switchVA, trackZBtoVB, semaphoreZA)
					)
				)

			assertThat(outcome).isInstanceOf<PathReservationRegistry.MergeOutcome.Aborted>()
		}

		@Test
		fun `non-contiguous single-element new path leaves the stored PathInfo unchanged`() {
			// Given: Train with normal path B → zB
			val trainId = "train1"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB) // [B, track(B→zB), zB]
				)
			)
			val stored = storedPathInfo(trainId)

			// When: Register a single-element new path that does not continue from zB
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = switchVB,
					target = switchVB,
					path = listOf(switchVB)
				)
			)

			// Then: abort — the front position is NOT advanced to vB
			assertStoredIsExactly(trainId, stored)
			assertThat(stored.target).isEqualTo(semaphoreZB)
			assertThat(stored.reservedPath.size).isEqualTo(3)
		}

		@Test
		fun `non-contiguous new path against a single-element old path leaves it unchanged`() {
			// Given: Train with single-element path (just a separator): start == target == B
			val trainId = "train1"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = inOutB,
					path = listOf(inOutB)
				)
			)
			val stored = storedPathInfo(trainId)

			// When: Register a new path from a separator that is not B
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			)

			// Then: abort — the single-element PathInfo survives intact
			assertStoredIsExactly(trainId, stored)
			assertThat(stored.reservedPath.size).isEqualTo(1)
		}

		@Test
		fun `non-contiguous separator-only paths leave the stored PathInfo unchanged`() {
			// Given: separator-only PathInfo (unusual but legal: start == target, path = [B])
			val trainId = "train1"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = inOutB,
					path = listOf(inOutB)
				)
			)
			val stored = storedPathInfo(trainId)

			// When: Register another separator-only path that does not continue from B
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = semaphoreZB,
					target = semaphoreZB,
					path = listOf(semaphoreZB)
				)
			)

			// Then: abort — no [B, zB] separator pair is ever stored
			assertStoredIsExactly(trainId, stored)
			assertThat(stored.reservedPath.size).isEqualTo(1)
		}

		@Test
		fun `non-contiguous merge disturbs neither the tail nor the front position`() {
			// Given: Train whose Tail sits at B
			val trainId = "train1"

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB, // Tail position at B
					target = inOutB, // Front position at B
					path = listOf(inOutB)
				)
			)
			val stored = storedPathInfo(trainId)

			// When: Register a new path that starts somewhere else entirely
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			)

			// Then: both ends are exactly where they were — the abort is not a partial update
			assertStoredIsExactly(trainId, stored)
			assertThat(stored.start).isEqualTo(inOutB) // Tail unchanged
			assertThat(stored.target).isEqualTo(inOutB) // Front NOT advanced to vB
		}
	}

	@Nested
	@DisplayName("Single-Element Paths (contiguous)")
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

			// When: Register a new path that continues from B (old.target == new.start)
			val newPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB) // [B, track(B→zB), zB]
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: the duplicated B is skipped and the rest appended
			// Merged: [B] + [track(B→zB), zB]
			// Expected size: 1 + (3 - 1) = 3
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start preserved
			assertThat(merged.target).isEqualTo(semaphoreZB) // New target at zB
			assertThat(merged.reservedPath.size).isEqualTo(3) // 1 + (3 - 1)
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

			// When: Register a single-element new path AT the current front (zB).
			// This is the degenerate continuation: the only element is the overlap itself.
			val newPathInfo =
				createPathInfo(
					start = semaphoreZB,
					target = semaphoreZB, // ✅ Valid: single separator
					path = listOf(semaphoreZB) // ✅ Valid: single separator path
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: the overlap is skipped, so nothing is appended
			// Merged: [B, track(B→zB), zB] + [] = size 3
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original start preserved
			assertThat(merged.target).isEqualTo(semaphoreZB) // Front stays at zB
			assertThat(merged.reservedPath.size).isEqualTo(3) // 3 + (1 - 1)
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
	@DisplayName("Duplicated New Start (never throws — Issue #834)")
	inner class CircularRouteValidation {
		@Test
		fun `mergePathInfo aborts instead of throwing when the new start appears twice`() {
			// Given: Train with existing path B → zB
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)
			val stored = storedPathInfo(trainId)

			// When: Attempt to register a CONTIGUOUS path (new.start == old.target == zB) whose
			// start also appears a second time inside its own reserved path: [zB, track, zB].
			// Contiguity holds, so this reaches the duplicated-start check specifically.
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

			// Then: NO exception. Before Issue #834 this threw IllegalStateException out of
			// registerPathInfo — i.e. out of reservePath Step 2i, after blocks were reserved and
			// a START signal was already cleared — which killed the kDisco simulation thread
			// while the run still produced a well-formed result file. The merge must abort
			// fail-safe instead, exactly like the cycle guard: WARN and keep `old`.
			registry.registerPathInfo(trainId, circularPath)

			assertStoredIsExactly(trainId, stored)
			assertThat(stored.target).isEqualTo(semaphoreZB)
			assertThat(stored.reservedPath.size).isEqualTo(3)
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

			// When: Register a contiguous separator-only continuation from B
			val newPathInfo =
				createPathInfo(
					start = inOutB,
					target = inOutB, // ✅ Valid: single separator
					path = listOf(inOutB) // ✅ Valid: single separator
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: the overlap is skipped, so the stored path stays a single separator.
			// No [B, B] separator pair is ever produced.
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB)
			assertThat(merged.target).isEqualTo(inOutB)
			assertThat(merged.reservedPath.size).isEqualTo(1) // 1 + (1 - 1)
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

			// When: Register a contiguous continuation B → zB
			val newPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, newPathInfo)

			// Then: Merged path should preserve original start (Tail position at B)
			val merged = registry.getPathInfo(trainId)
			assertThat(merged).isNotNull()
			assertThat(merged!!.start).isEqualTo(inOutB) // Original Tail position preserved
			assertThat(merged.target).isEqualTo(semaphoreZB) // New Front position
		}

		@Test
		fun `mergePathInfo updates to new front position`() {
			// Given: Train with path B → zB (Old Front position at zB)
			val trainId = "train1"

			val oldPathInfo =
				createPathInfo(
					start = inOutB,
					target = semaphoreZB, // Old Front position at zB
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			registry.registerPathInfo(trainId, oldPathInfo)

			// When: Register a contiguous continuation zB → vB with a new Front position
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

	@Nested
	@DisplayName("restorePathInfo (transaction rollback)")
	inner class RestorePathInfo {
		@Test
		fun `restorePathInfo with null removes the entry`() {
			// Given: a train with a registered PathInfo
			val trainId = "train1"
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			)

			// When: the transaction is rolled back to "no PathInfo"
			registry.restorePathInfo(trainId, null)

			// Then: the entry is gone, as if the candidate had never been attempted
			assertThat(registry.getPathInfo(trainId)).isNull()
		}

		@Test
		fun `restorePathInfo replaces a merged PathInfo with the exact snapshot`() {
			// Given: a train whose PathInfo was merged across two registrations
			val trainId = "train1"
			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = inOutB,
					target = semaphoreZB,
					path = listOf(inOutB, trackBtoZB, semaphoreZB)
				)
			)
			val snapshot = registry.getPathInfo(trainId)
			val snapshotSize = snapshot!!.reservedPath.size

			registry.registerPathInfo(
				trainId,
				createPathInfo(
					start = semaphoreZB,
					target = switchVB,
					path = listOf(semaphoreZB, trackZBtoVB, switchVB)
				)
			)
			// Sanity: the merge really happened (3 + 3 - 1 overlap = 5 elements)
			assertThat(registry.getPathInfo(trainId)!!.reservedPath.size).isEqualTo(5)

			// When: the second registration is rolled back
			registry.restorePathInfo(trainId, snapshot)

			// Then: the original, unmerged PathInfo is back — merge() is not invertible by
			// subtraction (cycle-guard aborts, entry-direction overwrites), so rollback restores
			// the stored object rather than undoing the merge.
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(snapshot)
			assertThat(registry.getPathInfo(trainId)!!.reservedPath.size).isEqualTo(snapshotSize)
		}

		@Test
		fun `restorePathInfo with null on a train with no entry is a no-op`() {
			registry.restorePathInfo("train-with-no-pathinfo", null)
			assertThat(registry.getPathInfo("train-with-no-pathinfo")).isNull()
		}
	}

	/** The instance currently stored for [trainId]; fails the test if there is none. */
	private fun storedPathInfo(trainId: String): PathInfo =
		registry.getPathInfo(trainId) ?: throw AssertionError("No PathInfo stored for '$trainId'")

	/**
	 * Asserts that the merge left the stored [PathInfo] *identical by reference* to [expected].
	 *
	 * Reference identity, not structural equality: invariant I2 (#316) is that an aborted merge
	 * never writes anything back — not that it writes back something that happens to look the
	 * same. A truncated or partially merged copy would satisfy an equality check on `start` and
	 * `target` alone, which is exactly the bug shape #316 was about.
	 */
	private fun assertStoredIsExactly(
		trainId: String,
		expected: PathInfo
	) {
		assertThat(registry.getPathInfo(trainId)).isNotNull()
		assertThat(registry.getPathInfo(trainId)!!).isSameInstanceAs(expected)
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
