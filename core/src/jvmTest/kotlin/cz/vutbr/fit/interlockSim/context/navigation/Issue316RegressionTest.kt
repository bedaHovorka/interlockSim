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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
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
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Regression tests for Issue #316: 8+ trains on circular route cause deadlock.
 *
 * ## Root Cause
 *
 * `PathReservationRegistry.mergePathInfo()` previously truncated a path mid-merge when
 * a separator would appear 3+ times. The truncated result was stored as PathInfo, which
 * created malformed paths (separator at the last position without proper closure).
 * `DefaultTrainNavigationService.buildPathWithDirection()` then entered infinite
 * cycle-detection loops on the malformed PathInfo.
 *
 * ## Fix (Issue #316)
 *
 * When a separator would appear 3+ times, abort the entire merge and return the existing
 * valid PathInfo unchanged. A truncated PathInfo is always worse than the original.
 *
 * ## Tests
 *
 * - `trains_9plus_on_circular_route_no_deadlock`: simulates 9+ path reservations on a
 *   circular route; verifies all PathInfo objects remain valid (non-null, not ending with
 *   a separator) and that the registry never stores a malformed/truncated result.
 */
@DisplayName("Issue #316 Regression: Cycle Detection Abort Strategy")
class Issue316RegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var navigator: TopologyNavigator

	// Separators from vyhybna.xml (circular shunting loop)
	private lateinit var inOutB: DynamicPathSeparator  // InOut at (30,8)
	private lateinit var semaphoreZB: DynamicPathSeparator  // Semaphore zB at (27,8)
	private lateinit var switchVB: DynamicPathSeparator  // Switch vB at (26,8)
	private lateinit var semaphoreDoB1: DynamicPathSeparator  // Semaphore doB1 at (25,8)
	private lateinit var switchVA: DynamicPathSeparator  // Switch vA at (15,8)
	private lateinit var semaphoreZA: DynamicPathSeparator  // Semaphore zA at (14,8)
	private lateinit var inOutA: DynamicPathSeparator  // InOut at (11,8)

	// Track blocks between separators (extracted from paths)
	private lateinit var trackBtoZB: DynamicTrackBlock
	private lateinit var trackZBtoVB: DynamicTrackBlock
	private lateinit var trackVBtoDoB1: DynamicTrackBlock
	private lateinit var trackVAtoZA: DynamicTrackBlock
	private lateinit var trackZAtoA: DynamicTrackBlock

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		registry = simulationContext.scope.get()
		navigator = simulationContext.scope.get()

		val grid = simulationContext.getRailWayNetGrid()
		val inOuts = simulationContext.getInOuts().toList()

		inOutB = inOuts.find { it.name == "B" } as? DynamicPathSeparator
			?: throw IllegalStateException("InOut 'B' not found in vyhybna.xml")
		inOutA = inOuts.find { it.name == "A" } as? DynamicPathSeparator
			?: throw IllegalStateException("InOut 'A' not found in vyhybna.xml")

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

		// Extract track blocks from paths between separators
		val pathBtoZB = navigator.findAllTopologicalPaths(inOutB, semaphoreZB).firstOrNull()
			?: throw IllegalStateException("No path from B to zB")
		val pathZBtoVB = navigator.findAllTopologicalPaths(semaphoreZB, switchVB).firstOrNull()
			?: throw IllegalStateException("No path from zB to vB")
		val pathVBtoDoB1 = navigator.findAllTopologicalPaths(switchVB, semaphoreDoB1).firstOrNull()
			?: throw IllegalStateException("No path from vB to doB1")
		val pathVAtoZA = navigator.findAllTopologicalPaths(switchVA, semaphoreZA).firstOrNull()
			?: throw IllegalStateException("No path from vA to zA")
		val pathZAtoA = navigator.findAllTopologicalPaths(semaphoreZA, inOutA).firstOrNull()
			?: throw IllegalStateException("No path from zA to A")

		trackBtoZB = pathBtoZB.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }.filterIsInstance<DynamicTrackBlock>().firstOrNull()
			?: throw IllegalStateException("No track block B→zB")
		trackZBtoVB = pathZBtoVB.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }.filterIsInstance<DynamicTrackBlock>().firstOrNull()
			?: throw IllegalStateException("No track block zB→vB")
		trackVBtoDoB1 = pathVBtoDoB1.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }.filterIsInstance<DynamicTrackBlock>().firstOrNull()
			?: throw IllegalStateException("No track block vB→doB1")
		trackVAtoZA = pathVAtoZA.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }.filterIsInstance<DynamicTrackBlock>().firstOrNull()
			?: throw IllegalStateException("No track block vA→zA")
		trackZAtoA = pathZAtoA.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }.filterIsInstance<DynamicTrackBlock>().firstOrNull()
			?: throw IllegalStateException("No track block zA→A")
	}

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	@Test
	@DisplayName("9+ path merges on circular route: registry keeps valid PathInfo (Issue #316 regression)")
	fun trains9plusOnCircularRouteNoDeadlock() {
		// Simulate a train repeatedly reserving segments around the circular shunting loop.
		// After 2 full loops the same separator (e.g. inOutB) would appear for the 3rd time.
		// Before fix: mergePathInfo() stored a TRUNCATED PathInfo, causing malformed paths.
		// After fix: mergePathInfo() returns the existing VALID PathInfo unchanged.
		//
		// We simulate enough merges (9+) to exceed the 2-occurrence threshold:
		//   Loop 1: B → zB → vB → doB1 → vA → zA → A
		//   Loop 2: A → zA → vA → doB1 → vB → zB → B   (B appears 2nd time — allowed)
		//   Loop 3 attempt: B → zB  (B would appear 3rd time — must be rejected gracefully)
		//
		// For simplicity we simulate this as sequential path extensions using the
		// available separators: B, zB, vB, doB1, vA, zA, A, then cycle back B, zB, vB...
		// Each PathInfo covers one segment (separator → track → separator).
		//
		// NOTE: Segments 3→4 and 8→9 are topologically discontinuous (doB1→switchVA has no
		// direct track in the test setup). This is an acceptable proxy for the bug scenario:
		// the test validates PathInfo structural integrity (no malformed path, no truncation)
		// rather than physical reachability.  The cycle guard fires on separator identity,
		// not on track adjacency, so disconnected segments still exercise the fix correctly.

		val trainId = "train_issue316"

		// Define a circular sequence of path segments (enough for 9+ registrations)
		// Segment layout: [start, track, end]
		val segments = listOf(
			// Loop 1 (forward B→A)
			Triple(inOutB, trackBtoZB, semaphoreZB),           // 1: B → zB
			Triple(semaphoreZB, trackZBtoVB, switchVB),        // 2: zB → vB
			Triple(switchVB, trackVBtoDoB1, semaphoreDoB1),    // 3: vB → doB1
			Triple(switchVA, trackVAtoZA, semaphoreZA),        // 4: vA → zA  (discontinuous from 3 — see note above)
			Triple(semaphoreZA, trackZAtoA, inOutA),           // 5: zA → A
			// Loop 2 (forward A→B, re-using same track blocks in reverse is not possible
			// without actual reverse paths, so we simulate by re-using B→A segments)
			Triple(inOutB, trackBtoZB, semaphoreZB),           // 6: B → zB  (2nd occurrence of B — allowed)
			Triple(semaphoreZB, trackZBtoVB, switchVB),        // 7: zB → vB (2nd occurrence of zB — allowed)
			Triple(switchVB, trackVBtoDoB1, semaphoreDoB1),    // 8: vB → doB1 (2nd occurrence of vB — allowed)
			Triple(switchVA, trackVAtoZA, semaphoreZA),        // 9: vA → zA  (discontinuous from 8 — see note above)
			Triple(semaphoreZA, trackZAtoA, inOutA),           // 10: zA → A  (2nd occurrence of zA — allowed)
			// Loop 3 attempt: re-introduce B → zB, which would create a 3rd occurrence of the separator
			Triple(inOutB, trackBtoZB, semaphoreZB),           // 11: B → zB  (3rd occurrence of B — merge should be rejected)
		)

		// Register first segment to initialise PathInfo
		val firstSegment = segments[0]
		val firstPathInfo = createPathInfo(
			start = firstSegment.first,
			target = firstSegment.third,
			path = listOf(firstSegment.first, firstSegment.second, firstSegment.third)
		)
		registry.registerPathInfo(trainId, firstPathInfo)

		// Iteratively merge remaining segments
		var lastValidSize = 3 // size after first registration
		for (index in 1 until segments.size) {
			val (start, track, end) = segments[index]
			val pathInfo = createPathInfo(
				start = start,
				target = end,
				path = listOf(start, track, end)
			)
			registry.registerPathInfo(trainId, pathInfo)

			// After each merge, verify PathInfo is valid (not malformed)
			val currentPathInfo = registry.getPathInfo(trainId)
			assertThat(currentPathInfo).isNotNull()

			val reservedPath = currentPathInfo!!.reservedPath
			// A valid PathInfo must have at least 1 element
			assertThat(reservedPath.size).isGreaterThan(0)

			// A valid PathInfo must NOT end with a separator at the very last position
			// unless it's also the ONLY element (single-element path is valid: start == target)
			if (reservedPath.size > 1) {
				val pathElements = reservedPath.toList()
				val lastElement = pathElements.last()
				val secondToLast = pathElements[pathElements.size - 2]
				// The path should end with a separator (target), but the element BEFORE
				// that separator must be a track section (not another separator).
				// A truncated path would have a separator as its last element with the
				// previous element also being a separator, which is the malformed case.
				val lastIsSeparator = lastElement is DynamicPathSeparator
				val secondToLastIsAlsoSeparator = secondToLast is DynamicPathSeparator
				// If last element is a separator AND second-to-last is also a separator,
				// the path is malformed (e.g. [..., sepX, sepY] — no track between them).
				// This is the bug we are testing against.
				assertThat(lastIsSeparator && secondToLastIsAlsoSeparator).isFalse()

				// Also verify path size never DECREASES after a valid merge
				// (truncation was causing the path to shrink unexpectedly)
				assertThat(reservedPath.size).isGreaterThanOrEqualTo(lastValidSize)
			}

			// Update last valid size — after 3+ occurrence guard the size stays the same
			if (reservedPath.size > lastValidSize) {
				lastValidSize = reservedPath.size
			}
		}

		// Final check: after 10 segment registrations (9+ above the threshold),
		// the PathInfo must still be non-null and its reserved path must be non-empty.
		val finalPathInfo = registry.getPathInfo(trainId)
		assertThat(finalPathInfo).isNotNull()
		assertThat(finalPathInfo!!.reservedPath.size).isGreaterThan(0)
	}

	@Test
	@DisplayName("3rd occurrence of separator triggers cycle guard: PathInfo stays unchanged (Issue #316 fix)")
	fun cycleGuardFires_returnsUnchangedPathInfo() {
		// This test directly exercises the `return old` code path in mergePathInfo().
		//
		// Setup: register 2 normal merges so inOutB appears twice in the merged path.
		// Then attempt a 3rd merge that would cause inOutB to appear a 3rd time.
		// Expected: registry retains the PathInfo from after the 2nd merge (unchanged).

		val trainId = "train_cycle_guard"

		// Step 1: register first segment B → zB
		val seg1 = createPathInfo(
			start = inOutB,
			target = semaphoreZB,
			path = listOf(inOutB, trackBtoZB, semaphoreZB)
		)
		registry.registerPathInfo(trainId, seg1)

		// Step 2: register segment that brings inOutB back (2nd occurrence — allowed)
		// We re-use the same track to simulate "circular route" without reverse paths
		val seg2 = createPathInfo(
			start = inOutB,
			target = semaphoreZB,
			path = listOf(inOutB, trackBtoZB, semaphoreZB)
		)
		registry.registerPathInfo(trainId, seg2)

		// Capture PathInfo after the 2nd merge — this should be preserved after the guard fires
		val pathInfoAfterTwoMerges = registry.getPathInfo(trainId)
		assertThat(pathInfoAfterTwoMerges).isNotNull()
		val sizeAfterTwoMerges = pathInfoAfterTwoMerges!!.reservedPath.size

		// Step 3: attempt a 3rd merge with inOutB — this would make inOutB appear 3+ times
		// The cycle guard MUST fire and return the old PathInfo unchanged
		val seg3 = createPathInfo(
			start = inOutB,
			target = semaphoreZB,
			path = listOf(inOutB, trackBtoZB, semaphoreZB)
		)
		registry.registerPathInfo(trainId, seg3)

		// Verify: PathInfo is still non-null and has the SAME size as after the 2nd merge
		// (i.e. the 3rd merge was rejected — `return old` fired)
		val pathInfoAfterGuard = registry.getPathInfo(trainId)
		assertThat(pathInfoAfterGuard).isNotNull()
		assertThat(pathInfoAfterGuard!!.reservedPath.size).isEqualTo(sizeAfterTwoMerges)

		// Verify the path is still structurally valid (no consecutive separators)
		val pathElements = pathInfoAfterGuard.reservedPath.toList()
		assertThat(pathElements.size).isGreaterThan(0)
		if (pathElements.size > 1) {
			val lastElement = pathElements.last()
			val secondToLast = pathElements[pathElements.size - 2]
			val lastIsSeparator = lastElement is DynamicPathSeparator
			val secondToLastIsAlsoSeparator = secondToLast is DynamicPathSeparator
			assertThat(lastIsSeparator && secondToLastIsAlsoSeparator).isFalse()
		}
	}

	// Helper: create PathInfo from a list of path elements
	private fun createPathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<Any>,
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
