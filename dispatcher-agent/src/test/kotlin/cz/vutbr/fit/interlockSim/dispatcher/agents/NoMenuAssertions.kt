/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo

/**
 * Shared no-menu (C9) checks, extracted from `ObservationRendererTest.NoMenuConstraint` (~L152-207,
 * SP2c.2, Issue #825) so the exact same regex and forbidden-substring rules can be applied to the
 * **live** prompt surfaces [LivePromptNoMenuTest] covers, not only the test-only renderers that
 * motivated the original constraint.
 *
 * ## Why extracted rather than duplicated
 *
 * Before Issue #893 (phase beta, task B3), the no-menu regex and verb list existed only inside
 * `ObservationRendererTest`'s nested `NoMenuConstraint` class, private to that file. Issue #834's
 * system-prompt rebuild (task B2) needed the identical check applied to
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]'s assembled system prompt,
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer.toPromptText], and
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl]'s per-cycle user prompt — a
 * second, drifting copy of the regex/verb-list pair would be strictly worse than one shared
 * definition both `ObservationRendererTest` and [LivePromptNoMenuTest] delegate to.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers, original constraint); extracted Issue #893
 *   (phase beta, task B3)
 */
object NoMenuAssertions {
	/**
	 * No line may start with a numbered-option marker (`1.`/`1)`), optionally indented — the exact
	 * pattern `ObservationRendererTest.NoMenuConstraint` used before extraction.
	 */
	private val numberedOptionPattern = Regex("""^\s*\d+[.)]\s""", RegexOption.MULTILINE)

	/** Fails if [output] contains a line starting with a numbered-option marker. */
	fun assertNoNumberedOptionLines(output: String) {
		assertThat(numberedOptionPattern.containsMatchIn(output)).isEqualTo(false)
	}

	/**
	 * Fails if [output] contains, case-insensitively, one of the menu verbs `option`, `choose one`,
	 * or `select`. `option` also catches `optional` — a real substring trap the #893 phase-beta
	 * prompt rebuild had to avoid deliberately.
	 */
	fun assertNoMenuVerbs(output: String) {
		val lowered = output.lowercase()
		assertThat(lowered).doesNotContain("option")
		assertThat(lowered).doesNotContain("choose one")
		assertThat(lowered).doesNotContain("select")
	}

	/** Runs both [assertNoNumberedOptionLines] and [assertNoMenuVerbs] on [output]. */
	fun assertNoMenuArtifacts(output: String) {
		assertNoNumberedOptionLines(output)
		assertNoMenuVerbs(output)
	}
}
