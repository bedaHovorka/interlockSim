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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for Issue #938: a PathInfo whose start is a switch (vA/vB) makes every
 * subsequent merge non-contiguous, freezing the train's PathInfo for the rest of the run.
 *
 * Root cause: [DefaultPathReservationService.forwardOnlyPathInfo] walked the already-owned
 * prefix section-by-section and used whatever separator it landed on as the new PathInfo
 * start — even when that separator was a [DynamicRailSwitch]. Routes run signal-to-signal;
 * a switch is interior, never an endpoint. Using a switch as PathInfo.start meant
 * `new.start != old.target` on every merge attempt (old path ended at a semaphore),
 * triggering Step 0a's non-contiguous abort every time.
 *
 * Fix: when the trim-point separator is a [DynamicRailSwitch], [forwardOnlyPathInfo]
 * falls back to the original `start` (which must be a semaphore or InOut) and includes
 * the full path, so PathInfo is always semaphore/InOut-bounded.
 */
@Tag("integration-test")
class PathReservationSwitchStartPathInfoRegressionTest : PathReservationServiceTestBase() {
	private fun findSemaphoreByName(name: String): DynamicRailSemaphore {
		val grid = simulationContext.getRailWayNetGrid()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)]
				if (cell is DynamicRailSemaphore && cell.name == name) return cell
			}
		}
		throw IllegalStateException("Semaphore '$name' not found in grid")
	}

	/**
	 * Block whose two endpoint separators (by name) are [first] and [second].
	 * vyhybna.xml blocks carry no XML name of their own; they are addressed by endpoints.
	 */
	private fun blockBetween(
		first: String,
		second: String
	): DynamicTrackBlock {
		fun separatorName(sep: PathSeparator): String? =
			when (sep) {
				is DynamicRailSemaphore -> sep.name
				is DynamicRailSwitch -> sep.name
				is DynamicInOut -> sep.name
				else -> null
			}
		return simulationContext
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull { block ->
				block.ends().mapNotNull { separatorName(it) }.toSet() == setOf(first, second)
			} ?: throw IllegalStateException("No block found between '$first' and '$second'")
	}

	/**
	 * Issue #938: when the already-owned prefix of a forward reservation ends at a switch
	 * (e.g. vB on the k2 siding branch) and the first new block is right after that switch,
	 * [DefaultPathReservationService.forwardOnlyPathInfo] used to record the switch as
	 * PathInfo.start. The resulting PathInfo was malformed (a route starts at a semaphore,
	 * never at a switch), and every subsequent merge aborted with
	 * "non-contiguous merge" because `new.start` (switch) ≠ `old.target` (semaphore).
	 *
	 * After the fix, a switch trim-point falls back to the original semaphore start, so the
	 * registered PathInfo is always semaphore/InOut-bounded and the merge succeeds.
	 *
	 * Topology exercised (vyhybna.xml, off the far end of the k2 siding branch):
	 * ```
	 * doB2 → [junction_block] → vB → [vB_zB_block] → zB
	 * ```
	 * After the first hop (both blocks owned), the vB_zB_block is surgically released so that
	 * the second reservation has the junction block as already-owned and vB_zB as new.
	 * forwardOnlyPathInfo then lands on vB (a switch) as the trim-point separator, which is
	 * exactly the shape this test pins.
	 *
	 * ## Why this route and not `doA2 → doB1`
	 *
	 * `doA2 → doB1` looks like the same shape but is doubly impossible, and must not be
	 * reintroduced here:
	 *
	 * - **No switch position joins its segments at vB.** [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch]
	 *   only wires `merging↔branch` (BRANCH) and `merging↔mainDir` (MAIN) — never
	 *   `branch↔mainDir`. At vB, `merging` faces zB, `mainDir` faces doB1 and `branch` faces
	 *   doB2, so entering from doB2 and leaving toward doB1 is the physically impossible
	 *   sibling-branch diversion that
	 *   [DefaultPathReservationService.configureSwitchesInPath] rejects (Issue #742). The only
	 *   other candidate, `doA2 → vA → doA1 → doB1`, needs vA as `branch↔mainDir` and fails
	 *   for the same reason — so the request is `GeometricallyImpossible`, never Success.
	 * - **doA2 is a rear-facing START going east.** `orientation="true"` means it faces
	 *   B→A, so the G4 guard in [DefaultPathReservationService.configureStartSignal] rejects
	 *   it regardless of contiguity.
	 *
	 * The chosen route avoids both: doB2 faces A→B (forward-facing START), and vB is
	 * traversed `branch↔merging` = BRANCH, a position that exists.
	 */
	@Test
	fun `PathInfo start is not a switch when trim-point separator is a switch (issue 938)`() {
		// Given: the branch exit past vB: doB2 → junction → vB → vB_zB → zB → kB → B
		val doB2 = findSemaphoreByName("doB2")
		val inOutB = simulationContext.getInOuts().single { it.name == "B" }
		// The block that comes AFTER the switch vB — its reservedFrom will equal doB2
		// (tryAtomicReservation calls block.setUpPath(start=doB2, …) for all new blocks).
		val vBzBBlock = blockBetween("vB", "zB")
		val kBBlock = blockBetween("zB", "B")

		// Reserve from doB2 to B — all blocks (junction, vB_zB, kB) are new. (Not doB2 to zB: zB faces
		// B->A, so G8 refuses a route ending there, Issue #1064.)
		val result1 = service.reservePath("train1", doB2, inOutB)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Surgically free vB_zB_block and kB so that the SECOND reservation finds the junction
		// block already owned but vB_zB and kB new (the trim-point will be vB, a switch).
		// Both blocks are reserved from doB2 (tryAtomicReservation uses start as the from-sep).
		vBzBBlock.cancelPathSetup(doB2)
		registry.unregisterBlock("train1", vBzBBlock)
		kBBlock.cancelPathSetup(doB2)
		registry.unregisterBlock("train1", kBBlock)

		// Clear the stored PathInfo so registering the second hop does not attempt a merge
		// (the first PathInfo ended at B; we want to see forwardOnlyPathInfo's result
		// in isolation, stored fresh as the only entry).
		registry.restorePathInfo("train1", null)

		// When: reserve from doB2 to zB again.
		// forwardOnlyPathInfo walks junction (owned) → vB (switch);
		// finds vB_zB (new) at trim-point vB.
		// Before fix: returned PathInfo(start=vB) — switch-start defect.
		// After fix:  returns PathInfo(start=doB2) — semaphore-bounded, correct.
		val result2 = service.reservePath("train1", doB2, inOutB)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Then: PathInfo.start must be the bounding semaphore doB2, never the switch vB.
		val pathInfo = registry.getPathInfo("train1")
		assertThat(pathInfo).isNotNull()
		assertThat(pathInfo!!.start)
			.isNotInstanceOf<DynamicRailSwitch>()
		assertThat(pathInfo.start)
			.isEqualTo(doB2)
	}
}
