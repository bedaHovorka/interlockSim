/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import org.koin.core.component.get
import kotlin.test.Test

class BuiltinNetworksTest : CommonKoinTestBase() {
	@Test
	fun `VYHYBNA_XML starts with XML declaration and contains expected elements`() {
		val xml = BuiltinNetworks.VYHYBNA_XML
		assertThat(xml).startsWith("<?xml")
		assertThat(xml).contains("<net X=\"100\" Y=\"100\">")
		assertThat(xml).contains("name=\"vA\"")
		assertThat(xml).contains("name=\"vB\"")
	}

	/**
	 * Since the duplicate-file relink of PR #1287, `NetworkResources.CERVENY_UJEZD_XML` delegates to
	 * the built-in network itself, so there is no second copy left to compare against. The single
	 * copy must still parse and name every switch: the seeded-run label pin of `CervenyUjezdBlockLabelTest`
	 * and the dispatcher topology pin both derive from it (#1279). Parsing needs a started Koin
	 * container, so the class extends [CommonKoinTestBase].
	 *
	 * Since Issue #1473 the loader names an unnamed switch `SW_<x>_<y>`, so a switch name is never
	 * blank: a name the XML did not give is detected as the generated name of the switch's own
	 * position ([GeneratedElementName.switchAt]).
	 */
	@Test
	fun `CERVENY_UJEZD_XML parses and names every RailSwitch`() {
		assertEverySwitchNamedInXml(BuiltinNetworks.CERVENY_UJEZD_XML)
	}

	/** Same check for the other built-in networks the dispatcher addresses by switch name (#1473 review). */
	@Test
	fun `VYHYBNA_XML and PRAHA_HLAVNI_NADRAZI_STATION_XML name every RailSwitch`() {
		assertEverySwitchNamedInXml(BuiltinNetworks.VYHYBNA_XML)
		assertEverySwitchNamedInXml(BuiltinNetworks.PRAHA_HLAVNI_NADRAZI_STATION_XML)
	}

	private fun assertEverySwitchNamedInXml(xml: String) {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		val grid: RailwayNetGrid<Cell> = ctx.getRailWayNetGrid()
		val switches =
			grid.mapNotNull { (position, cell) -> (cell as? DynamicRailSwitch)?.let { position to it.name } }
		assertThat(switches, name = "switches of the built-in network").isNotEmpty()
		assertThat(
			switches.filter { (position, name) -> name.isBlank() || name == GeneratedElementName.switchAt(position) },
			name = "switches the built-in network XML leaves unnamed"
		).isEmpty()
		val names = switches.map { it.second }
		assertThat(
			names,
			name = "switch names of the built-in network"
		).isEqualTo(names.distinct())
	}
}
