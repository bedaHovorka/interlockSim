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
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/**
 * Tests for [formatRationale], shared by the "Why this route?" dialog in [Frame] and by
 * [SemiAutoApprovalDialog] (Issue #1123).
 */
class RationaleFormattingTest {
	@Test
	fun `empty rationale yields the shared no-rationale text`() {
		assertThat(formatRationale(emptyList())).isEqualTo("No rationale recorded.")
	}

	@Test
	fun `each rationale entry becomes one bullet line`() {
		assertThat(formatRationale(listOf("block k1 is free", "shortest route")))
			.isEqualTo("• block k1 is free\n• shortest route")
	}
}
