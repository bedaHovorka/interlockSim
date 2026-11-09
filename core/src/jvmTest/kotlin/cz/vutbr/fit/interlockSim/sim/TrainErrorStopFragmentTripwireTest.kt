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
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.testutil.ORIGIN_ROUTE_EXTENSION_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.ROUTE_EXTENSION_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.SourceScan
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tripwire binding `Train`'s stall wordings to the shared fragments in
 * `cz.vutbr.fit.interlockSim.testutil.TrainErrorStopMessages.kt` (issues #1375/#1380): when
 * [Train.waitForPathOrReportStall] rewords its messages, the constants and their consumers must be
 * updated together with it. Both consumers of the origin fragment are `integration-test`-tagged, so
 * without this tripwire a reword surfaced only there — and in the plain `:core:jvmTest` gate the
 * mid-journey one is witnessed only by [DtMaxTenthStallReproducerTest]'s boolean check; this pin
 * fails in the plain gate instead, with the constants file named in the failure.
 *
 * Each needle is a constant extended to its message shape ([MID_JOURNEY_NEEDLE], [ORIGIN_NEEDLE]),
 * built from the constant itself: a reword cannot sneak past by keeping the bare fragment somewhere
 * else, and the constant cannot drift while this test passes. Only code lines count
 * ([SourceScan.isCode]), so the scan reads only `sim/Train.kt`: KDoc and comment text cannot trip
 * the pins by design, and consumer-file wording echoes are outside its surface altogether. A wording
 * surviving only in a trailing `//` comment on an otherwise-code line of `Train.kt` can still pass —
 * both blind spots close when issue #1382's comment-aware scanner lands in [SourceScan]. A reword
 * may also push the worded part across a line-concatenation boundary (the origin stall-WARN arm
 * already is one, which keeps the WARN fragments private): the pin then fails as a false alarm until
 * that same scanner handles it, and issue #1391 extends the pins to the stall-WARN and no-path
 * wordings there.
 *
 * @since Issue #1380 (SP13 — Goal 1B)
 */
@DisplayName("Train's errorStop wordings stay pinned by the TrainErrorStopMessages fragments")
class TrainErrorStopFragmentTripwireTest {
	private companion object {
		/** The mid-journey pin: the fragment as its message renders it, before ` at '<where>'`. */
		const val MID_JOURNEY_NEEDLE = ROUTE_EXTENSION_FRAGMENT + " at"

		/** The origin pin: the fragment as its message renders it, before ` at InOut '<where>'`. */
		const val ORIGIN_NEEDLE = ORIGIN_ROUTE_EXTENSION_FRAGMENT + " at InOut"

		/** `Train.kt`'s code lines, read once from the first source-root spelling that resolves. */
		val TRAIN_CODE_LINES: List<String> =
			SourceScan
				.directory("core/src/commonMain/kotlin", "src/commonMain/kotlin")
				.resolve("cz/vutbr/fit/interlockSim/sim/Train.kt")
				.takeIf { it.isFile }
				?.let { it.readLines().filter(SourceScan::isCode) }
				?: error("sim/Train.kt not found under the scanned commonMain sources")
	}

	/** Asserts one message-shaped needle appears on a code line of `Train.kt`. */
	private fun assertPinned(needle: String) {
		assertThat(
			TRAIN_CODE_LINES.count { needle in it },
			name =
				"code lines of Train.kt carrying '$needle' — the wording changed: update the " +
					"TrainErrorStopMessages fragments and their consumers together"
		).isGreaterThan(0)
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
}
