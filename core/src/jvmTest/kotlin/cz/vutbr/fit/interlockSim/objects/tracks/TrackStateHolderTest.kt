/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import org.junit.jupiter.api.Test

/**
 * Tests for [TrackStateHolder], the guarded state shared by [DynamicTrack] and [DynamicTrackBlock]
 * (Issue #1123).
 */
class TrackStateHolderTest {
	@Test
	fun `starts FREE`() {
		assertThat(TrackStateHolder().current).isEqualTo(TrackFacility.State.FREE)
	}

	@Test
	fun `matching transition moves to the target state`() {
		val holder = TrackStateHolder()

		assertThat(holder.transition(TrackFacility.State.FREE, TrackFacility.State.RESERVED)).isTrue()
		assertThat(holder.current).isEqualTo(TrackFacility.State.RESERVED)
	}

	@Test
	fun `mismatched transition leaves the state unchanged`() {
		val holder = TrackStateHolder()

		assertThat(holder.transition(TrackFacility.State.RESERVED, TrackFacility.State.OCCUPIED)).isFalse()
		assertThat(holder.current).isEqualTo(TrackFacility.State.FREE)
	}

	@Test
	fun `wrong-state message names the current and the expected state`() {
		val holder = TrackStateHolder()
		holder.transition(TrackFacility.State.FREE, TrackFacility.State.RESERVED)

		assertThat(holder.wrongStateMessage(TrackFacility.State.OCCUPIED))
			.isEqualTo("Wrong state: RESERVED , expected : OCCUPIED")
	}
}
