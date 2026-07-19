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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for the [DispatchDecisionApplier.Companion.toRationaleLogSuffix] helper
 * introduced in SP2b.5 (Issue #560 — Goal 10).
 *
 * This helper formats a `List<String>` rationale into a loggable suffix appended to
 * each applied-decision log line in [DispatchDecisionApplier].
 *
 * @since Issue #560 (SP2b.5 — Goal 10)
 */
@DisplayName("DispatchDecisionApplier — SP2b.5 toRationaleLogSuffix (Issue #560)")
@Timeout(10, unit = TimeUnit.SECONDS)
class DispatchDecisionApplierSp2b5Test {
	@Nested
	@DisplayName("toRationaleLogSuffix")
	inner class ToRationaleLogSuffix {
		@Test
		@DisplayName("empty list produces empty string (no clutter for decisions without rationale)")
		fun emptyListProducesEmptyString() {
			val suffix = with(DispatchDecisionApplier) { emptyList<String>().toRationaleLogSuffix() }
			assertThat(suffix).isEmpty()
		}

		@Test
		@DisplayName("single-entry list produces a suffix containing 'rationale' and the entry text")
		fun singleEntryProducesSuffix() {
			val suffix =
				with(DispatchDecisionApplier) {
					listOf("Slot available").toRationaleLogSuffix()
				}
			assertThat(suffix).contains("rationale")
			assertThat(suffix).contains("Slot available")
		}

		@Test
		@DisplayName("multi-entry list joins entries with '; ' separator")
		fun multiEntryJoinsWithSemicolon() {
			val suffix =
				with(DispatchDecisionApplier) {
					listOf("Rule 1", "Rule 2", "Rule 3").toRationaleLogSuffix()
				}
			assertThat(suffix).contains("Rule 1; Rule 2; Rule 3")
		}

		@Test
		@DisplayName("suffix includes opening and closing bracket delimiters")
		fun suffixHasBracketDelimiters() {
			val suffix =
				with(DispatchDecisionApplier) {
					listOf("entry").toRationaleLogSuffix()
				}
			assertThat(suffix).contains("[")
			assertThat(suffix).contains("]")
		}
	}
}
