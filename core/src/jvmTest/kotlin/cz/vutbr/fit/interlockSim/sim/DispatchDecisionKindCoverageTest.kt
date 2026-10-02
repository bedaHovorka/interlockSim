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
import cz.vutbr.fit.interlockSim.testutil.coversEverySealedSubclassOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM-only tripwire for Issue #969: the sample table of the commonTest `DispatchDecisionKindTest`
 * names every [DispatchDecision] subtype, so a new subtype fails the build until it has a row (and
 * with it a [DispatchDecisionKind]). Reflection over sealed subclasses is not available on
 * Kotlin/Native, so this sibling lives in jvmTest.
 */
@DisplayName("DispatchDecisionKind covers every DispatchDecision subtype (#969)")
class DispatchDecisionKindCoverageTest {
	@Test
	@DisplayName("the DispatchDecisionKindTest table has one row per sealed DispatchDecision subtype")
	fun tableCoversEverySealedSubtype() {
		assertThat(DispatchDecisionKindTest().samples()).coversEverySealedSubclassOf(DispatchDecision::class)
	}
}
