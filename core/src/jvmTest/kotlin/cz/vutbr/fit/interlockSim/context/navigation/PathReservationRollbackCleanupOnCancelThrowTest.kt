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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The rollback finally-guard around a throwing `cancelPathSetup` (Issue #1165 split). */
@Tag("integration-test")
class PathReservationRollbackCleanupOnCancelThrowTest : PathReservationServiceTestBase() {
	@Test
	fun `rollbackUnconfigurableCandidate still unregisters a block from the registry when cancelPathSetup throws`() {
		// Given: a mock block registered to train1, whose cancelPathSetup throws -- the
		// degenerate state the finally-guard defends against (a cancel that fails mid-rollback).
		val mockBlock = mockk<DynamicTrackBlock>(relaxed = true)
		every { mockBlock.reservedFrom } returns inOut1
		every { mockBlock.cancelPathSetup(any()) } throws IllegalStateException("forced rollback failure")
		// registry.registerAtomic's conflict guard reads trainName/occupant; both must read
		// as unowned (null). registry.unregisterBlock's vacancy guard requires FREE + no occupant.
		every { mockBlock.trainName } returns null
		every { mockBlock.getState() } returns TrackFacility.State.FREE
		every { mockBlock.occupant } returns null

		val regResult = registry.registerAtomic("train1", listOf(mockBlock))
		assertThat(regResult).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		assertThat(registry.isRegistered(mockBlock)).isTrue()

		// When: rollback runs and cancelPathSetup throws.
		(service as DefaultPathReservationService).rollbackUnconfigurableCandidate(
			trainId = "train1",
			forwardBlocks = listOf(mockBlock),
			switches = emptyList(),
			priorSwitches = emptySet()
		)

		// Then: the block MUST have been unregistered from the registry (finally ran). Before
		// the fix, registry.unregisterBlock was inside the same try as cancelPathSetup, so the
		// throw skipped registry cleanup and leaked a block still registered to the train.
		assertThat(registry.isRegistered(mockBlock)).isFalse()
		assertThat(registry.getOwner(mockBlock)).isNull()
	}
}
