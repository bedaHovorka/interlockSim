/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import org.junit.jupiter.api.Test

/**
 * Issue #1405: [RecordingNavigator] stores the switch-constrained searches once, in
 * `switchConstrainedAnswers`; the probe count and the per-pair counts are views computed from
 * that record, and one `reset()` clears the record and the topological count together, so the
 * views can never disagree after a reset.
 */
class RecordingNavigatorTest : PathReservationServiceTestBase() {
	private fun twoInOuts(): Pair<DynamicPathSeparator, DynamicPathSeparator> {
		val inOuts = simulationContext.getInOuts().toList().map { simulationContext.toDynamic(it) }
		return inOuts[0] to inOuts[1]
	}

	@Test
	fun `the counters are derived from the single record`() {
		val (a, b) = twoInOuts()
		val recording = RecordingNavigator(navigator, noRouteTo = setOf(b))

		recording.findAllSwitchConstrainedPaths(a, b)
		recording.findAllSwitchConstrainedPaths(a, b)
		recording.findAllSwitchConstrainedPaths(b, a)

		assertThat(recording.switchConstrainedAnswers.size, name = "records").isEqualTo(3)
		assertThat(recording.evaluations, name = "evaluations").isEqualTo(3)
		assertThat(recording.calls, name = "calls").isEqualTo(mapOf((a to b) to 2, (b to a) to 1))
	}

	@Test
	fun `reset clears the record, the views and the topological count together`() {
		val (a, b) = twoInOuts()
		val recording = RecordingNavigator(navigator, noRouteTo = setOf(b))
		recording.findAllSwitchConstrainedPaths(a, b)
		recording.findAllTopologicalPaths(a, b, 5)

		recording.reset()

		assertThat(recording.switchConstrainedAnswers, name = "record").isEmpty()
		assertThat(recording.evaluations, name = "evaluations").isEqualTo(0)
		assertThat(recording.calls, name = "calls").isEqualTo(emptyMap())
		assertThat(recording.topologicalCalls, name = "topologicalCalls").isEqualTo(0)

		recording.findAllSwitchConstrainedPaths(a, b)
		assertThat(recording.evaluations, name = "evaluations after recount").isEqualTo(1)
		assertThat(recording.switchConstrainedAnswers.map { it.start to it.target }).containsExactly(a to b)
	}
}
