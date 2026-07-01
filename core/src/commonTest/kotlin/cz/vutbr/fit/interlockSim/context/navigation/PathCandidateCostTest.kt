/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    PathCandidate cost breakdown tests
    Issue #567 — Goal 2 → Goal 10 prereq

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    2026
*/

package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.Test

/**
 * Tests for [TopologyNavigator.findCandidatePaths] and the [PathCandidate] cost breakdown.
 *
 * ## Coverage
 *
 * - ✅ No path → empty list
 * - ✅ Linear path (no switches) → switchMovementCount == 0
 * - ✅ Path through one switch → switchMovementCount == 1
 * - ✅ Path through two switches → switchMovementCount == 2
 * - ✅ Branch path (switch with two exits) → both candidates returned with count == 1
 * - ✅ conflictRiskWeight defaults to 0.0
 * - ✅ sections match findAllTopologicalPaths output
 */
class PathCandidateCostTest : CommonKoinTestBase() {
	// ========================================================================
	// No path
	// ========================================================================

	/**
	 * Topology: A    B (disconnected)
	 *
	 * Expected: empty list
	 */
	@Test
	fun `findCandidatePaths - disconnected - returns empty list`() {
		val context = DefaultEditingContext(10, 10)
		val inOutA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
		val inOutB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(1, 1), inOutA)
		context.putCell(Point(5, 5), inOutB)

		context.use {
			val navigator: TopologyNavigator = context.scope.get()
			val candidates = navigator.findCandidatePaths(inOutA, inOutB)
			assertThat(candidates).isEmpty()
		}
	}

	// ========================================================================
	// Linear path (no switches)
	// ========================================================================

	/**
	 * Topology: A ---100m--- B
	 *
	 * Expected: 1 candidate, switchMovementCount == 0, conflictRiskWeight == 0.0
	 */
	@Test
	fun `findCandidatePaths - linear path no switch - switchMovementCount is zero`() {
		TestTopologies.simpleLinearPath().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as InOut
			val inOutB = grid.getCellAt(5, 5) as InOut

			val candidates = navigator.findCandidatePaths(inOutA, inOutB)

			assertThat(candidates).hasSize(1)
			assertThat(candidates[0].switchMovementCount).isEqualTo(0)
			assertThat(candidates[0].conflictRiskWeight).isEqualTo(0.0)
		}
	}

	/**
	 * Topology: A ---100m--- [Sem] ---100m--- B  (semaphore, not a switch)
	 *
	 * Expected: switchMovementCount == 0 (semaphore is not a RailSwitch)
	 */
	@Test
	fun `findCandidatePaths - path through semaphore - switchMovementCount is zero`() {
		TestTopologies.linearPathWithSemaphore().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as InOut
			val inOutB = grid.getCellAt(5, 5) as InOut

			val candidates = navigator.findCandidatePaths(inOutA, inOutB)

			assertThat(candidates).hasSize(1)
			assertThat(candidates[0].switchMovementCount).isEqualTo(0)
		}
	}

	// ========================================================================
	// Single switch in path
	// ========================================================================

	/**
	 * Topology: Entry ---250m--- [Switch] ---250m--- ExitMain
	 *                                    \--180m--- ExitBranch
	 *
	 * Path Entry → ExitMain traverses exactly one switch.
	 *
	 * Expected: switchMovementCount == 1
	 */
	@Test
	fun `findCandidatePaths - path through one switch - switchMovementCount is one`() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val entry = grid.getCellAt(5, 30) as InOut
			val exitMain = grid.getCellAt(55, 30) as InOut

			val candidates = navigator.findCandidatePaths(entry, exitMain)

			assertThat(candidates.size).isGreaterThan(0)
			// Every path from entry to exitMain must cross the switch once
			candidates.forEach { candidate ->
				assertThat(candidate.switchMovementCount).isEqualTo(1)
			}
		}
	}

	/**
	 * Topology: Entry ---250m--- [Switch] ---180m--- ExitBranch
	 *
	 * Path Entry → ExitBranch also traverses exactly one switch.
	 *
	 * Expected: switchMovementCount == 1
	 */
	@Test
	fun `findCandidatePaths - branch path through switch - switchMovementCount is one`() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val entry = grid.getCellAt(5, 30) as InOut
			val exitBranch = grid.getCellAt(50, 40) as InOut

			val candidates = navigator.findCandidatePaths(entry, exitBranch)

			assertThat(candidates.size).isGreaterThan(0)
			candidates.forEach { candidate ->
				assertThat(candidate.switchMovementCount).isEqualTo(1)
			}
		}
	}

	// ========================================================================
	// Two switches in path
	// ========================================================================

	/**
	 * Topology: IN ---[Switch1]--- middle ---[Switch2]--- OUT
	 *
	 * Expected: switchMovementCount == 2
	 */
	@Test
	fun `findCandidatePaths - path through two switches - switchMovementCount is two`() {
		val context = DefaultEditingContext(30, 20)
		val inOut = InOut("IN", false, Cell.SpatialType.HORIZONTAL)
		val switch1 = RailSwitch("SW1", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val switch2 = RailSwitch("SW2", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val outMain = InOut("OUT", true, Cell.SpatialType.HORIZONTAL)
		val outBranch1 = InOut("BRANCH1", true, Cell.SpatialType.HORIZONTAL)
		val outBranch2 = InOut("BRANCH2", true, Cell.SpatialType.HORIZONTAL)

		context.putCell(Point(1, 10), inOut)
		context.putCell(Point(8, 10), switch1)
		context.putCell(Point(15, 10), switch2)
		context.putCell(Point(22, 10), outMain)
		context.putCell(Point(22, 5), outBranch1)
		context.putCell(Point(22, 15), outBranch2)

		val trackInToSw1 = SimpleTrackBlock(inOut, switch1, 100.0, 80.0)
		val trackSw1ToSw2 = SimpleTrackBlock(switch1, switch2, 100.0, 80.0)
		val trackSw1Branch = SimpleTrackBlock(switch1, outBranch1, 80.0, 60.0)
		val trackSw2ToOut = SimpleTrackBlock(switch2, outMain, 100.0, 80.0)
		val trackSw2Branch = SimpleTrackBlock(switch2, outBranch2, 80.0, 60.0)

		context.joinCells(Point(1, 10), Point(8, 10), trackInToSw1)
		context.joinCells(Point(8, 10), Point(15, 10), trackSw1ToSw2)
		context.joinCells(Point(8, 10), Point(22, 5), trackSw1Branch)
		context.joinCells(Point(15, 10), Point(22, 10), trackSw2ToOut)
		context.joinCells(Point(15, 10), Point(22, 15), trackSw2Branch)

		context.use {
			val navigator: TopologyNavigator = context.scope.get()

			// Find path through both switches (IN → OUT via SW1 → SW2)
			val candidates = navigator.findCandidatePaths(inOut, outMain)

			assertThat(candidates.size).isGreaterThan(0)
			// The path IN→SW1→SW2→OUT crosses exactly 2 switches
			candidates.forEach { candidate ->
				assertThat(candidate.switchMovementCount).isEqualTo(2)
			}
		}
	}

	// ========================================================================
	// Multiple candidates (branching)
	// ========================================================================

	/**
	 * Both ExitMain and ExitBranch are reachable from Entry through the same switch.
	 * The union of candidates for both targets should contain at least 2 candidates
	 * each with switchMovementCount == 1.
	 */
	@Test
	fun `findCandidatePaths - two exit candidates - both have switchMovementCount one`() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val entry = grid.getCellAt(5, 30) as InOut
			val exitMain = grid.getCellAt(55, 30) as InOut
			val exitBranch = grid.getCellAt(50, 40) as InOut

			val toMain = navigator.findCandidatePaths(entry, exitMain)
			val toBranch = navigator.findCandidatePaths(entry, exitBranch)

			assertThat(toMain.size).isGreaterThan(0)
			assertThat(toBranch.size).isGreaterThan(0)
			(toMain + toBranch).forEach { candidate ->
				assertThat(candidate.switchMovementCount).isEqualTo(1)
			}
		}
	}

	// ========================================================================
	// sections mirror findAllTopologicalPaths
	// ========================================================================

	/**
	 * The sections in each PathCandidate must be identical to the corresponding
	 * list returned by findAllTopologicalPaths.
	 */
	@Test
	fun `findCandidatePaths - sections match findAllTopologicalPaths output`() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val entry = grid.getCellAt(5, 30) as InOut
			val exitMain = grid.getCellAt(55, 30) as InOut

			val rawPaths = navigator.findAllTopologicalPaths(entry, exitMain)
			val candidates = navigator.findCandidatePaths(entry, exitMain)

			assertThat(candidates).hasSize(rawPaths.size)
			candidates.zip(rawPaths).forEach { (candidate, raw) ->
				assertThat(candidate.sections).isEqualTo(raw)
			}
		}
	}

	// ========================================================================
	// conflictRiskWeight hook
	// ========================================================================

	/**
	 * The static layer always produces conflictRiskWeight == 0.0.
	 * A dispatcher can attach its own value using PathCandidate.copy().
	 */
	@Test
	fun `findCandidatePaths - conflictRiskWeight defaults to zero and is updatable via copy`() {
		TestTopologies.simpleLinearPath().use { context ->
			val navigator: TopologyNavigator = context.scope.get()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as InOut
			val inOutB = grid.getCellAt(5, 5) as InOut

			val candidates = navigator.findCandidatePaths(inOutA, inOutB)
			assertThat(candidates).hasSize(1)

			// Static layer always provides 0.0
			assertThat(candidates[0].conflictRiskWeight).isEqualTo(0.0)

			// Dispatcher can enrich with dynamic risk value via copy()
			val enriched = candidates[0].copy(conflictRiskWeight = 0.75)
			assertThat(enriched.conflictRiskWeight).isEqualTo(0.75)
			// Original candidate is unchanged (data class immutability)
			assertThat(candidates[0].conflictRiskWeight).isEqualTo(0.0)
		}
	}
}
