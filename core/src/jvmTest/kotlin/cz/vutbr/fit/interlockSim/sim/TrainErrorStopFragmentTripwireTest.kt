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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.MID_JOURNEY_NO_PATH_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.ORIGIN_NO_PATH_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.ORIGIN_ROUTE_EXTENSION_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.ORIGIN_STALL_WARN_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.ROUTE_EXTENSION_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.STALL_WARN_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.SourceScan
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tripwire binding `Train`'s stall diagnostics — its `errorStop` messages and stall WARNs — to the
 * shared fragments in `cz.vutbr.fit.interlockSim.testutil.TrainErrorStopMessages.kt` (issues
 * #1375/#1380/#1391): when [Train] rewords one, the constants and their consumers must be updated
 * together with it. Several consumers are `integration-test`-tagged (both origin no-path tests, the
 * stall-WARN test), so without this tripwire a reword surfaced only there; this pin fails in the
 * plain `:core:jvmTest` gate instead, with the constants file named in the failure.
 *
 * Wording role: this tripwire carries the source-text pin of each wording; [DtMaxTenthStallReproducerTest]
 * stays the untagged runtime witness that the rendered mid-journey `errorStop` carries
 * [ROUTE_EXTENSION_FRAGMENT].
 *
 * Each needle is a constant extended to its message shape (for example [MID_JOURNEY_NEEDLE]), built
 * from the constant itself: a reword cannot sneak past by keeping the bare fragment somewhere else,
 * and the constant cannot drift while this test passes. Only code counts
 * ([SourceScan.codeLinesKeepingStrings]: comment spans removed, string literals kept), so the scan
 * reads only `sim/Train.kt`: KDoc, block-comment and trailing `//` comment text cannot trip the pins,
 * and consumer-file wording echoes are outside its surface altogether. A wording split over a
 * `"…" +` / `"…"` line-concatenation boundary (the origin stall WARN is one) is read joined:
 * [joinConcatenations] removes the boundary before the needles are searched.
 *
 * @since Issue #1380 (SP13 — Goal 1B)
 */
@DisplayName("Train's stall-diagnostic wordings stay pinned by the TrainErrorStopMessages fragments")
class TrainErrorStopFragmentTripwireTest {
	private companion object {
		/** The mid-journey pin: the fragment as its message renders it, before ` at '<where>'`. */
		const val MID_JOURNEY_NEEDLE = ROUTE_EXTENSION_FRAGMENT + " at"

		/** The origin pin: the fragment as its message renders it, before ` at InOut '<where>'`. */
		const val ORIGIN_NEEDLE = ORIGIN_ROUTE_EXTENSION_FRAGMENT + " at InOut"

		/** The origin no-path pin: the terminal message's continuation, distinct from its retry WARN. */
		const val ORIGIN_NO_PATH_NEEDLE = ORIGIN_NO_PATH_FRAGMENT + " '\${where.name}' after"

		/** The mid-journey no-path pin: the fragment before ` from '<where>'`. */
		const val MID_JOURNEY_NO_PATH_NEEDLE = MID_JOURNEY_NO_PATH_FRAGMENT + " from '"

		/** The mid-journey stall-WARN pin: the fragment before ` after <N> s`. */
		const val STALL_WARN_NEEDLE = STALL_WARN_FRAGMENT + " after"

		/** The origin stall-WARN pin: the fragment before ` after <N> s`; split over two literals in `Train.kt`. */
		const val ORIGIN_STALL_WARN_NEEDLE = ORIGIN_STALL_WARN_FRAGMENT + " after"

		/** A `"…" +` line end followed by the next line's opening quote: one concatenation boundary. */
		val CONCATENATION_BOUNDARY = Regex("\"\\s*\\+\\s*\n\\s*\"")

		/**
		 * `Train.kt`'s comment-free code, read once from the first source-root spelling that resolves,
		 * with its line-concatenation boundaries joined ([joinConcatenations]).
		 */
		val TRAIN_CODE: String =
			SourceScan
				.directory("core/src/commonMain/kotlin", "src/commonMain/kotlin")
				.resolve("cz/vutbr/fit/interlockSim/sim/Train.kt")
				.takeIf { it.isFile }
				?.let { joinConcatenations(SourceScan.codeLinesKeepingStrings(it.readLines())) }
				?: error("sim/Train.kt not found under the scanned commonMain sources")

		/**
		 * The code [lines] as one text with each `"…" +` / `"…"` boundary across a line end removed,
		 * so a literal split over two lines reads whole. A comment line between the two halves breaks
		 * the join, which can only make a pin fail, never pass.
		 */
		fun joinConcatenations(lines: List<String>): String = lines.joinToString("\n").replace(CONCATENATION_BOUNDARY, "")
	}

	/** Asserts one message-shaped needle appears in the code of `Train.kt`. */
	private fun assertPinned(needle: String) {
		assertThat(
			needle in TRAIN_CODE,
			name =
				"Train.kt code carrying '$needle' — the wording changed: update the " +
					"TrainErrorStopMessages fragments and their consumers together"
		).isTrue()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the mid-journey errorStop message still names its shared fragment before ' at'")
	fun midJourneyErrorStopMessageStillNamesItsFragment() {
		assertPinned(MID_JOURNEY_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the origin errorStop message still names its shared fragment before ' at InOut'")
	fun originErrorStopMessageStillNamesItsFragment() {
		assertPinned(ORIGIN_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the origin no-path errorStop message still names its shared fragment")
	fun originNoPathErrorStopMessageStillNamesItsFragment() {
		assertPinned(ORIGIN_NO_PATH_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the mid-journey no-path errorStop message still names its shared fragment")
	fun midJourneyNoPathErrorStopMessageStillNamesItsFragment() {
		assertPinned(MID_JOURNEY_NO_PATH_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the mid-journey stall WARN still names its shared fragment before ' after'")
	fun midJourneyStallWarnStillNamesItsFragment() {
		assertPinned(STALL_WARN_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the origin stall WARN, split over two literals, still names its shared fragment")
	fun originStallWarnStillNamesItsFragment() {
		assertPinned(ORIGIN_STALL_WARN_NEEDLE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a wording split over a line-concatenation boundary reads whole once joined")
	fun concatenationBoundaryIsJoined() {
		val split =
			listOf(
				"\t\t\"\$name: still waiting for the dispatcher to reserve its \" +",
				"\t\t\t\"entry route after \$waited s\""
			)

		assertThat(ORIGIN_STALL_WARN_NEEDLE in joinConcatenations(SourceScan.codeLinesKeepingStrings(split))).isTrue()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a wording kept only in a comment does not count as pinned")
	fun wordingOnlyInACommentDoesNotCount() {
		val commentOnly =
			listOf(
				"\terrorStop(reworded) // \"$MID_JOURNEY_NEEDLE\"",
				"\t/* $ORIGIN_NEEDLE */ errorStop(reworded)",
				"\t/**",
				"\t * $MID_JOURNEY_NEEDLE",
				"\t */"
			)

		assertThat(
			SourceScan.codeLinesKeepingStrings(commentOnly).count {
				MID_JOURNEY_NEEDLE in it || ORIGIN_NEEDLE in it
			}
		).isEqualTo(0)
	}
}
