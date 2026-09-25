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
import assertk.assertions.startsWith
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
}
