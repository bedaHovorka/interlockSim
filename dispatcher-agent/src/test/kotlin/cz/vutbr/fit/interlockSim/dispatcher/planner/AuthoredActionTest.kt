/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The one "unattributed" rule every author-counting sink shares (Issue #1477). */
@DisplayName("AuthoredAction.isUnattributed (Issue #1477)")
class AuthoredActionTest {
	private fun authored(
		author: ActionAuthor?,
		tickIndex: Long
	) = AuthoredAction(author = author, reason = "", decisionKind = "ApproveTrain", tickIndex = tickIndex)

	@Test
	fun `an action with an author and a correlated tick index is attributed`() {
		assertThat(authored(ActionAuthor.LLM, 3L).isUnattributed).isFalse()
	}

	@Test
	fun `an action with no author is unattributed`() {
		assertThat(authored(null, 3L).isUnattributed).isTrue()
	}

	@Test
	fun `an action with the missing-correlation tick index is unattributed even with an author`() {
		assertThat(authored(ActionAuthor.RULE_BASED, AuthoredAction.UNATTRIBUTED_TICK_INDEX).isUnattributed).isTrue()
	}
}
