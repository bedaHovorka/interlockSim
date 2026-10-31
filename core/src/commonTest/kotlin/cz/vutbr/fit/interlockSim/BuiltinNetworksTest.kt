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
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import kotlin.test.Test

class BuiltinNetworksTest {
	@Test
	fun `VYHYBNA_XML starts with XML declaration and contains expected elements`() {
		val xml = BuiltinNetworks.VYHYBNA_XML
		assertThat(xml).startsWith("<?xml")
		assertThat(xml).contains("<net X=\"100\" Y=\"100\">")
		assertThat(xml).contains("name=\"vA\"")
		assertThat(xml).contains("name=\"vB\"")
	}

	/**
	 * The built-in Cerveny Ujezd network (Goal 1B SP3.2, Issue #1164) is a copy of the `:core-test`
	 * fixture, because `:fast-sim` and `:desktop-ui` read it from `:core` only. The two must not drift.
	 */
	@Test
	fun `CERVENY_UJEZD_XML is the same file as the test fixture`() {
		assertThat(BuiltinNetworks.CERVENY_UJEZD_XML).isEqualTo(NetworkResources.CERVENY_UJEZD_XML)
	}
}
