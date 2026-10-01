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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM-only tripwire for Issue #969: every [DispatchDecision] subtype has its own
 * [DispatchDecisionKind]. Reflection over sealed subclasses is not available on
 * Kotlin/Native, so this sibling of the commonTest `DispatchDecisionKindTest` lives in jvmTest.
 */
@DisplayName("DispatchDecisionKind covers every DispatchDecision subtype (#969)")
class DispatchDecisionKindCoverageTest {
	@Test
	@DisplayName("one DispatchDecisionKind entry per sealed DispatchDecision subtype")
	fun oneKindPerSealedSubtype() {
		assertThat(DispatchDecision::class.sealedSubclasses.size).isEqualTo(DispatchDecisionKind.entries.size)
	}
}
