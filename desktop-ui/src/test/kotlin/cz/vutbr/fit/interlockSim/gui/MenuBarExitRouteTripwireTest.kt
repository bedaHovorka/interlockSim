/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tripwire for the Exit menu route (PR #1430 review): [MenuBar]'s ExitAction must walk the
 * frame's close path ([Frame.requestClose] — the unsaved-changes confirmation and the window
 * layout save) instead of a bare JVM exit. The behavioral close-path tests live in
 * [WindowLifecycleTest]; the dialog branch stays untestable without a robot, so this fails the
 * build if the wiring goes back to an immediate `System.exit`.
 */
class MenuBarExitRouteTripwireTest {
	@Test
	fun exitActionGoesThroughTheClosePath() {
		assertThat(codeLines().any { "requestClose()" in it }).isTrue()
	}

	@Test
	fun exitActionLeavesTheImmediateJvmExitBehind() {
		assertThat(codeLines().none { "System.exit" in it }).isTrue()
	}

	/**
	 * MenuBar's source without comment lines: a doc note mentioning `System.exit` (like the
	 * teardown comment in other classes) must read the code, not the prose. The same
	 * comment-skip idiom [cz.vutbr.fit.interlockSim.ExampleRegistryDeclareTripwireTest] uses.
	 */
	private fun codeLines(): List<String> =
		File("src/main/kotlin/cz/vutbr/fit/interlockSim/gui/MenuBar.kt")
			.readText()
			.lines()
			.filterNot {
				it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*")
			}
}
