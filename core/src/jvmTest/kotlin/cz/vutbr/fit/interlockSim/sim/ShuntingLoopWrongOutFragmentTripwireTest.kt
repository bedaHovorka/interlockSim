/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.testutil.SourceScan
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_BLIND_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_EXIT_INSTEAD_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_FALLBACK_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_OFFERED_CLAUSE_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_PREFIX_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_TRAIN_REPORT_FRAGMENT
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tripwire binding the wrong-out wordings to the shared fragments in
 * `cz.vutbr.fit.interlockSim.testutil.ShuntingLoopWrongOutMessages.kt` (Issue #1387), in the
 * `TrainErrorStopFragmentTripwireTest` style (issues #1375/#1380/#1391): when the wording changes,
 * the constants and their consumers must be updated together with it. Two sides are pinned:
 * `ShuntingLoop`'s `WRONG_OUT:` INFO line (which the owner reads the #1387 acceptance from — class
 * A blind vs fallback, the #1299 class B) and `Train`'s report the simulation tests tally, so a
 * reword cannot zero the wrong-out counts without anyone noticing (`CervenyUjezdSteeredPickFlatTest`
 * counts to a hard zero).
 *
 * A needle is a fragment extended to its message shape where the shape carries meaning
 * ([WRONG_OUT_LINE_NEEDLE], [WRONG_OUT_EXIT_NEEDLE]); where the fragment alone is the shape, it is
 * asserted directly. Each must occur exactly once in the code of its source file
 * ([SourceScan.codeLinesKeepingStrings]: comment spans removed, string literals kept), so KDoc and
 * comment text cannot trip the pins, and consumer-file wording echoes are outside the surface.
 *
 * @since Issue #1387 (review round)
 */
@DisplayName("The wrong-out wordings stay pinned by the ShuntingLoopWrongOutMessages fragments")
class ShuntingLoopWrongOutFragmentTripwireTest {
	private companion object {
		/** The line pin: the prefix as its message renders it, before ` left by`. */
		const val WRONG_OUT_LINE_NEEDLE = WRONG_OUT_PREFIX_FRAGMENT + "\${train.name} left by"

		/** The exit sentence pin: the fact the `ends in wrong out` report states, as this line spells it. */
		const val WRONG_OUT_EXIT_NEEDLE = "left by \$exit " + WRONG_OUT_EXIT_INSTEAD_FRAGMENT

		/** `ShuntingLoop.kt`'s code, read once ([SourceScan.codeText]) and shared by all its pins. */
		val SHUNTING_LOOP_CODE: String =
			SourceScan.codeText(
				"cz/vutbr/fit/interlockSim/sim/ShuntingLoop.kt",
				"core/src/commonMain/kotlin",
				"src/commonMain/kotlin"
			)

		/** `Train.kt`'s code, read once ([SourceScan.codeText]) and shared by its pins. */
		val TRAIN_CODE: String =
			SourceScan.codeText(
				"cz/vutbr/fit/interlockSim/sim/Train.kt",
				"core/src/commonMain/kotlin",
				"src/commonMain/kotlin"
			)
	}

	/** Asserts one message-shaped needle occurs exactly once in [code], named after its source file. */
	private fun assertPinned(
		code: String,
		sourceName: String,
		needle: String
	) {
		assertThat(
			SourceScan.countOccurrences(code, needle),
			name =
				"$sourceName code occurrences of '$needle' — the wording changed or gained a second " +
					"site: update the ShuntingLoopWrongOutMessages fragments and their consumers together"
		).isEqualTo(1)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the wrong-out INFO line still names its shared prefix before ' left by'")
	fun wrongOutLineStillNamesItsFragment() {
		assertPinned(SHUNTING_LOOP_CODE, "ShuntingLoop.kt", WRONG_OUT_LINE_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the wrong-out exit sentence still names its shared fragment (quoted exit)")
	fun wrongOutExitSentenceStillNamesItsFragment() {
		assertPinned(SHUNTING_LOOP_CODE, "ShuntingLoop.kt", WRONG_OUT_EXIT_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the class B verdict still names its shared clause")
	fun wrongOutOfferedClauseStillNamesItsFragment() {
		assertPinned(SHUNTING_LOOP_CODE, "ShuntingLoop.kt", WRONG_OUT_OFFERED_CLAUSE_FRAGMENT)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the class A fallback verdict still names its shared clause")
	fun wrongOutFallbackClauseStillNamesItsFragment() {
		assertPinned(SHUNTING_LOOP_CODE, "ShuntingLoop.kt", WRONG_OUT_FALLBACK_FRAGMENT)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the class A blind verdict still names its shared clause")
	fun wrongOutBlindClauseStillNamesItsFragment() {
		assertPinned(SHUNTING_LOOP_CODE, "ShuntingLoop.kt", WRONG_OUT_BLIND_FRAGMENT)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Train's wrong-out report suffix stays spelled where the classifier counts it")
	fun trainWrongOutReportStillNamesItsFragment() {
		assertPinned(TRAIN_CODE, "Train.kt", WRONG_OUT_TRAIN_REPORT_FRAGMENT)
	}
}
