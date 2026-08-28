/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEmpty
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression lock for Issue #843 acceptance criterion #3: "RejectionCode covers every
 * rejection reason enumerated by SP2c.3's ActionValidator, one-to-one."
 *
 * A grep-verified pass at SP2c.20-follow-up time confirmed every [RejectionCode] entry is
 * referenced by [ActionValidator]. This test mechanizes that check by scanning
 * `ActionValidator.kt`'s source text for `RejectionCode.<entryName>`, so a future
 * [RejectionCode] addition that no validator path actually produces — or a validator rejection
 * added without a matching enum entry — fails CI instead of silently drifting.
 */
@DisplayName("RejectionCode 1:1 exhaustiveness against ActionValidator (SP2c.20 follow-up, Issue #843 AC #3)")
class RejectionCodeExhaustivenessTest {
	@Test
	@DisplayName("every RejectionCode entry is referenced by name in ActionValidator.kt")
	fun everyRejectionCodeIsReferencedInActionValidator() {
		val validatorFile = File("src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/ActionValidator.kt")
		check(validatorFile.exists()) {
			"ActionValidator.kt not found at ${validatorFile.absolutePath} — " +
				"working directory assumption (module root) is wrong; adjust the relative path"
		}
		val source = validatorFile.readText()

		val missing =
			RejectionCode.entries.filterNot { entry ->
				source.contains("RejectionCode.${entry.name}")
			}
		assertThat(missing.map { it.name }).isEmpty()
	}
}
