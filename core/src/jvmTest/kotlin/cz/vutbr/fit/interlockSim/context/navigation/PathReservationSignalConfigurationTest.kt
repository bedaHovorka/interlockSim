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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Signal Configuration Tests (Issue #296 Phase 4)
 *
 * Tests for automatic semaphore signal configuration during path reservation.
 *
 * NOTE: These tests verify that signal configuration is CALLED, but due to the
 * race condition at simulation time t=0.0, the signal may not be visible to
 * trains in actual simulation. See Issue #296 for details.
 */
@Tag("integration-test")
class PathReservationSignalConfigurationTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath configures START semaphore signal when START is DynamicRailSemaphore`() {
		// Arrange - Find semaphore doA1 by iterating grid (simulation grid uses dynamic cells)
		val grid = simulationContext.getRailWayNetGrid()
		var doA1Semaphore: DynamicRailSemaphore? = null

		// Iterate through grid to find doA1 semaphore
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell =
					grid[
						cz.vutbr.fit.interlockSim.util
							.Point(x, y)
					]
				if (cell is DynamicRailSemaphore && cell.name == "doA1") {
					doA1Semaphore = cell
					break
				}
			}
			if (doA1Semaphore != null) break
		}

		assertThat(doA1Semaphore).isNotNull()

		// Assert BEFORE - initial signal is STOP
		assertThat(doA1Semaphore!!.signal).isEqualTo(Signal.STOP)
		assertThat(doA1Semaphore.signal.isAllowing()).isFalse()

		// Act - reserve path from semaphore to inOut2
		val result = service.reservePath("train1", doA1Semaphore, inOut2)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Assert AFTER - signal was configured to allow movement
		assertThat(doA1Semaphore.signal).isNotEqualTo(Signal.STOP)
		assertThat(doA1Semaphore.signal.isAllowing()).isTrue()
	}

	@Test
	fun `reservePath configures every governing semaphore along a full InOut-to-InOut path, not just START`() {
		// Act - reserve a full InOut-to-InOut route, spanning the intermediate semaphores
		// at the vA/vB switch junctions (doA1/doA2, doB1/doB2) in addition to the
		// START/target boundary semaphores.
		val result = service.reservePath("train1", inOut1, inOut2)

		val success = assertReservationSuccess(result)

		// Every semaphore that GOVERNS this movement must be configured to allow it -- one
		// left at STOP means a train halts there forever (Issue #296 Phase 4 / Step 2h
		// configureIntermediateSemaphores, and configureStartSignal for the START boundary).
		//
		// "Governs" is the load-bearing word. This assertion originally covered every
		// semaphore bounding a reserved block, including the ones the route passes from
		// behind. vyhybna.xml is bidirectional: an A→B route is governed by zA and doB1,
		// and runs past doA1 and zB, which govern the OPPOSING B→A movement. Clearing those
		// two authorised a train coming the other way and produced an aspect that never
		// returned to danger -- Train.separatorAction skips a rear-passed separator, so the
		// reset at the end of semaphoreAction never ran for it. See
		// DefaultPathReservationService.facesDirectionOfTravel.
		//
		// The stall this test guards against is unaffected: a train never waits on a
		// semaphore it passes from behind, so leaving that one at STOP cannot halt it.
		val semaphoresOnPath =
			success.reservedBlocks
				.flatMap { it.ends().toList() }
				.filterIsInstance<DynamicRailSemaphore>()
				.distinctBy { it.name }

		assertThat(semaphoresOnPath).isNotEmpty()

		// The original point of this test: clearing reaches PAST the START boundary. START
		// here is inOut1 (an InOut, absent from semaphoresOnPath), so more than one cleared
		// named semaphore means Step 2h ran, not just Step 2g.
		val governing = semaphoresOnPath.filter { it.signal.isAllowing() }
		assertThat(governing.map { it.name })
			.withMessage("intermediate semaphores must be cleared, not only the START boundary")
			.hasSize(2)
	}

	@Test
	fun `reservePathToAnyNextSemaphore identifies InOut output semaphore`() {
		// Arrange - Access InOut's output semaphore directly
		// Cast inOut1 (DynamicPathSeparator) to DynamicInOut for type-safe access
		val dynamicInOut = inOut1 as DynamicInOut
		val outSemaphore = dynamicInOut.outSemaphore

		// Assert BEFORE - InOut output semaphore is constant FREE
		// Note: InOut output semaphores are created as ConstantSemaphore with Signal.FREE
		// and never change (no-op setter). This is by design - exit points always allow trains out.
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(outSemaphore.signal.isAllowing()).isTrue()

		// Act - reserve path from InOut
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Assert AFTER - InOut output semaphore remains constant FREE (ConstantSemaphore)
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(outSemaphore.signal.isAllowing()).isTrue()

		// Verify blocks were reserved
		val blocks = service.getReservedBlocks("train1")
		assertThat(blocks).isNotNull()
		assertThat(blocks.isEmpty()).isFalse()
	}

	@Test
	fun `releasePath allows subsequent signal reconfiguration`() {
		// Arrange - Access InOut's output semaphore
		val dynamicInOut = inOut1 as DynamicInOut
		val outSemaphore = dynamicInOut.outSemaphore

		// Assert BEFORE - InOut output semaphore is constant FREE
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)

		// Reserve path for train1
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

		// Assert AFTER FIRST RESERVATION - semaphore remains constant FREE
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(outSemaphore.signal.isAllowing()).isTrue()

		// Act - release path
		service.releasePath("train1")

		// Assert AFTER RELEASE - semaphore still constant FREE (ConstantSemaphore)
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(outSemaphore.signal.isAllowing()).isTrue()

		// Reserve again with different train
		val result = service.reservePathToAnyNextSemaphore("train2", inOut1, next)

		// Assert - second reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Assert AFTER SECOND RESERVATION - semaphore still constant FREE
		assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(outSemaphore.signal.isAllowing()).isTrue()

		// Verify blocks were reserved for train2
		val blocks = service.getReservedBlocks("train2")
		assertThat(blocks).isNotNull()
		assertThat(blocks.isEmpty()).isFalse()
	}
}
