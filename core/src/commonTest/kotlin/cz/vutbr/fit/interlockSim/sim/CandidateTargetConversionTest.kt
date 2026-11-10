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
import assertk.assertions.contains
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetCandidate
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit coverage for the single conversion that turns the interlocking's
 * [ReservationTargetCandidate] list into the observation's [CandidateTarget] list.
 *
 * The acceptance point of Issue #1152: an unknown separator kind must fail loudly with a
 * descriptive message instead of being dropped, because a silently shorter candidate list is
 * indistinguishable from "no route is available" to every dispatcher.
 *
 * @since Issue #1152 (SP5 — Goal 1B)
 */
class CandidateTargetConversionTest {
	/**
	 * A separator that is neither a station exit nor a signal. Nothing here is stubbed by hand:
	 * the fake delegates member-for-member to a real [createDynamicInstance] product, and what
	 * makes it unknown to the conversion's `when` arms is its own class name — it is neither a
	 * `DynamicInOut` nor a `DynamicRailSemaphore` runtime type.
	 */
	private class NotAnEndpointSeparator(
		delegate: DynamicPathSeparator =
			createDynamicInstance(RailSemaphore("NotAnEndpointSeparator", true, Cell.SpatialType.HORIZONTAL))
	) : DynamicPathSeparator by delegate

	@Test
	fun `an unknown separator kind fails loudly instead of being dropped`() {
		val candidate = ReservationTargetCandidate(NotAnEndpointSeparator(), available = true)

		val failure = assertFailsWith<IllegalStateException> { candidate.toCandidateTarget() }

		assertThat(failure.message).isNotNull().contains("NotAnEndpointSeparator")
		assertThat(failure.message).isNotNull().contains("Unknown reservation target separator type")
	}
}
