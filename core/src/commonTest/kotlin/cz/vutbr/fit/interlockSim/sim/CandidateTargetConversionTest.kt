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
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
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
	 * A separator that is neither a station exit nor a signal — a switch-shaped element, which
	 * the interlocking must never report as a route endpoint.
	 */
	private class NotAnEndpointSeparator : DynamicPathSeparator {
		override fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment> = emptySet()

		override fun getSpatialType(): Cell.SpatialType? = null

		override fun joins(): Set<Cell.Segment> = emptySet()

		override fun cancelPathSetup(
			from: Cell.Segment?,
			to: Cell.Segment?
		) = Unit

		override fun setUpPath(
			from: Cell.Segment?,
			to: Cell.Segment?,
			allowedSpeed: Double,
			trackOccupant: TrackOccupant
		) = Unit

		override fun allowedSpeed(): Double = 0.0

		override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = null
	}

	@Test
	fun `an unknown separator kind fails loudly instead of being dropped`() {
		val candidate = ReservationTargetCandidate(NotAnEndpointSeparator(), available = true)

		val failure = assertFailsWith<IllegalStateException> { candidate.toCandidateTarget() }

		assertThat(failure.message).isNotNull().contains("NotAnEndpointSeparator")
		assertThat(failure.message).isNotNull().contains("Unknown reservation target separator type")
	}
}
