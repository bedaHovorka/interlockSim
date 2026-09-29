/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.RepeatedTestCapExtension.Companion.DEFAULT_CAP_PROPERTY
import org.junit.jupiter.api.Test

/**
 * Tripwire (Issue #1110): the `@RepeatedTest` caps of this module are enforced by
 * [RepeatedTestCapExtension], which ships through `:core-test`'s classpath and only wakes up
 * when the root build script turns on extension auto-detection. If this module ever drops
 * its `:core-test` test dependency, or the root wiring block disappears, the tests below fail —
 * instead of this module's tests silently running uncapped.
 */
class RepeatedTestCapWiringTest {
	@Test
	fun `cap extension is on the classpath and auto-detection is on`() {
		assertThat(
			javaClass.classLoader.getResource("META-INF/services/org.junit.jupiter.api.extension.Extension")
		).isNotNull()
		assertThat(System.getProperty("junit.jupiter.extensions.autodetection.enabled")).isEqualTo("true")
	}

	@Test
	fun `cap values from Gradle properties reach this test JVM`() {
		assertThat(System.getProperty(DEFAULT_CAP_PROPERTY)).isNotNull()
	}
}
