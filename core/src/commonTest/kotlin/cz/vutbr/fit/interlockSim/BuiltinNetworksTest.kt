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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.util.cellsOfType
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
	 */
	@Test
	fun `CERVENY_UJEZD_XML parses and names every RailSwitch`() {
		val xml = BuiltinNetworks.CERVENY_UJEZD_XML
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		val switches = ctx.getRailWayNetGrid().cellsOfType<DynamicRailSwitch>()
		assertThat(switches, name = "switches of the built-in network").isNotEmpty()
		assertThat(
			switches.filter { it.name.isBlank() },
			name = "unnamed switches of the built-in network"
		).isEmpty()
		val names = switches.map { it.name }
		assertThat(
			names,
			name = "switch names of the built-in network"
		).isEqualTo(names.distinct())
	}
}
