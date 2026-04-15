/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ResourcesTest {
	@Test
	fun `reads a known text resource`() {
		val content = Resources.read("cz/vutbr/fit/interlockSim/util/hello-resource.txt")
		assertThat(content).contains("hello from resources")
	}

	@Test
	fun `throws IllegalArgumentException when resource is missing`() {
		assertFailsWith<IllegalArgumentException> {
			Resources.read("cz/vutbr/fit/interlockSim/util/definitely-not-present.txt")
		}
	}
}
