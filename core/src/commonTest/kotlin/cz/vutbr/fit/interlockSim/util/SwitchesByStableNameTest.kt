/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import kotlin.test.Test

/**
 * [switchesByStableName] (Issue #1468 review): the one grid scan both ports key their switches by —
 * the XML name, or for a blank name the generated `SW_<x>_<y>` of the grid position.
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
}
