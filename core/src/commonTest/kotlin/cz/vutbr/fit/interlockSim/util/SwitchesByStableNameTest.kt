/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import org.koin.core.component.get
import kotlin.test.Test

/**
 * [switchesByStableName] (Issue #1468 review): the scan the perception port keys its switches by —
 * the XML name, or for a blank name the generated `SW_<x>_<y>` of the grid position. The actuator
 * port still scans its own rejecting cache (`DefaultNetworkActuatorPort.buildSwitchCache`); folding
 * that scan onto this helper is a recorded follow-up of the #1516 review round.
 *
 * A FALLBACK-involved name collision fails the scan (#1516 review round, Copilot thread
 * PRRT_kwDOQyJ9Cc68uzeu): a blank switch whose generated name meets a name indexed earlier throws
 * at list construction, and so does an explicit name that arrives after a generated one — the same
 * diagnostic the actuator's scan throws, so one message fragment pins both rejections.
 */
class SwitchesByStableNameTest : CommonKoinTestBase() {
	@Test
	fun aNamedSwitchKeepsItsName() {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation().tracked()
		val grid = ctx.getRailWayNetGrid()

		val switches = grid.switchesByStableName()

		assertThat(switches.map { it.first }).containsExactly(TestTopologies.Y_JUNCTION_SWITCH)
		assertThat(switches.single().second).isSameInstanceAs(grid[TestTopologies.Y_JUNCTION_SWITCH_AT] as DynamicRailSwitch)
	}

	@Test
	fun anUnnamedSwitchGetsTheGeneratedNameOfItsPosition() {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation(switchName = "").tracked()

		assertThat(ctx.getRailWayNetGrid().switchesByStableName().map { it.first })
			.containsExactly(GeneratedElementName.switchAt(TestTopologies.Y_JUNCTION_SWITCH_AT))
	}

	@Test
	fun aGeneratedSwitchNameCollidingWithAnEarlierExplicitNameFailsAtListConstruction() {
		// Copilot thread PRRT_kwDOQyJ9Cc68uzeu: the blank switch at (30,30) generates SW_30_30,
		// which is already the explicit name of the switch at (10,30) -- the one the x-outer scan
		// reaches first. The actuator's scan rejects this shape at construction (Issue #1473), so
		// the perception-side scan must reject it at list construction instead of listing two
		// switches under one name.
		val ctx =
			laneJunction(EXPLICIT_AT to COLLISION_NAME, BLANK_AT to null)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()

		assertFailure { ctx.getRailWayNetGrid().switchesByStableName() }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains(COLLISION_NAME, "RailSwitch at ${label(BLANK_AT)}", label(EXPLICIT_AT), FIX_HINT)
	}

	@Test
	fun aGeneratedSwitchNameCollidingWithALaterExplicitNameAlsoFailsAtListConstruction() {
		// The mirror scan order (all collision outcomes must be order-independent, as the actuator's
		// mirror pin of Issue #1473 is): the blank switch at (20,30) claims the generated SW_20_30
		// first, and the switch explicitly named SW_20_30 at (40,30) reaches the scan second.
		// Last-wins would silently re-point the shared name at the explicit switch, so the mirror
		// must fail too.
		val ctx =
			laneJunction(MIRROR_BLANK_AT to null, MIRROR_EXPLICIT_AT to MIRROR_NAME)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()

		assertFailure { ctx.getRailWayNetGrid().switchesByStableName() }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains(MIRROR_NAME, "RailSwitch at ${label(MIRROR_BLANK_AT)}", label(MIRROR_EXPLICIT_AT), FIX_HINT)
	}

	/**
	 * A straight `entry → switch(es) → exit` lane on one shared row whose switches carry the
	 * [placements] names at their grid positions, `null` for blank (the fallback case under
	 * test). The lane mirrors the shape of `StableElementNamesTest.laneJunction`, with the same
	 * one-row wiring so every collision party sits on one joined track.
	 */
	private fun laneJunction(vararg placements: Pair<Point, String?>): DefaultEditingContext {
		val row = placements.first().first.y
		check(placements.all { (at, _) -> at.y == row }) { "a lane needs a single row" }
		val switchNodes: List<Pair<Point, NodeCell>> =
			placements.map { (at, name) ->
				at to RailSwitch(name ?: "", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			}
		val laneNodes: List<Pair<Point, NodeCell>> =
			listOf(Point(switchNodes.first().first.x - LANE_MARGIN, row) to InOut("J_IN", false, Cell.SpatialType.HORIZONTAL)) +
				switchNodes +
				listOf(Point(switchNodes.last().first.x + LANE_MARGIN, row) to InOut("J_OUT", true, Cell.SpatialType.HORIZONTAL))
		val context = DefaultEditingContext(60, 50)
		for ((at, cell) in laneNodes) context.putCell(at, cell)
		for (index in 0 until laneNodes.size - 1) {
			val (fromAt, fromCell) = laneNodes[index]
			val (toAt, toCell) = laneNodes[index + 1]
			context.joinCells(fromAt, toAt, SimpleTrackBlock(fromCell, toCell, 250.0, 90.0))
		}
		return context
	}

	private companion object {
		/** The gap between a lane's InOut and its nearest switch, in columns. */
		const val LANE_MARGIN = 4

		/** The blank switch of the first pin: its position form meets the explicit holder scanned before it. */
		val BLANK_AT = Point(30, 30)

		/** The explicit holder of [BLANK_AT]'s generated form, reached by the scan first. */
		val EXPLICIT_AT = Point(10, 30)

		/** The generated form of [BLANK_AT], held explicitly by the switch at [EXPLICIT_AT]. */
		val COLLISION_NAME = GeneratedElementName.switchAt(BLANK_AT)

		/** The mirror pin's blank switch: it indexes its position form before [MIRROR_EXPLICIT_AT]. */
		val MIRROR_BLANK_AT = Point(20, 30)

		/** The mirror pin's explicit holder of that form, reached by the scan second. */
		val MIRROR_EXPLICIT_AT = Point(40, 30)

		/** The generated form of [MIRROR_BLANK_AT], held explicitly by the switch at [MIRROR_EXPLICIT_AT]. */
		val MIRROR_NAME = GeneratedElementName.switchAt(MIRROR_BLANK_AT)

		/** The rejection's tail, shared with the actuator's construction diagnostic (Issue #1473). */
		const val FIX_HINT = "explicit name in code or move it to another position"

		/** The position label of the generated-name diagnostics, `(<x>,<y>)`. */
		private fun label(at: Point): String = "(${at.x},${at.y})"
	}
}
