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
import org.junit.jupiter.api.Test

/**
 * Proves the `:core` `jvmTest`/`test` task fails an untagged `@RepeatedTest(51)` (Issue #1110).
 */
class RepeatedTestCapTest {
	@Test
	@DisplayName("test task: @RepeatedTest above the cap fails, at the cap passes")
	fun capIsEnforced() {
		RepeatedTestCapProbe.assertCapEnforced()
	}
}
