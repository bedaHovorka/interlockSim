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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [semaphoreFacesNextBlock], the one facing rule shared by the reservation service and the registry's
 * PathInfo trim (Issue #1067). The real-topology cases (`zA` facing, `doA1` rear-facing) are covered
 * in `PathReservationRegistryTest`; this pins the comparison and the fail-open side.
 */
class SemaphoreFacingTest {
	private val semaphore = mockk<DynamicRailSemaphore> { every { direction() } returns Cell.Segment.F }
	private val nextBlock = mockk<DynamicTrackBlock>()

	private fun contextResolving(segment: Cell.Segment?): SimulationContext =
		mockk { every { getSegment(semaphore, nextBlock, null) } returns segment }

	@Test
	fun `a signal whose direction is the segment towards the next block faces the train`() {
		assertThat(semaphoreFacesNextBlock(contextResolving(Cell.Segment.F), semaphore, nextBlock)).isTrue()
	}

	@Test
	fun `a signal whose direction is another segment faces away from the train`() {
		assertThat(semaphoreFacesNextBlock(contextResolving(Cell.Segment.A), semaphore, nextBlock)).isFalse()
	}

	@Test
	fun `an unresolvable segment fails open`() {
		assertThat(semaphoreFacesNextBlock(contextResolving(null), semaphore, nextBlock)).isTrue()
	}
}
