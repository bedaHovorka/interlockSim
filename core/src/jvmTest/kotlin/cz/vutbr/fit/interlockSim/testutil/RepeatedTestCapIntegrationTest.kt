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

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Proves the `:core` `integrationTest` task fails a `@RepeatedTest(51)` (Issue #1110).
 */
@Tag("integration-test")
class RepeatedTestCapIntegrationTest {
	@Test
	@DisplayName("integrationTest task: @RepeatedTest above the cap fails, at the cap passes")
	fun capIsEnforced() {
		RepeatedTestCapProbe.assertCapEnforced()
	}
}
