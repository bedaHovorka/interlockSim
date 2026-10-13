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
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for Issue #1188: a reservation request whose `start` is a
 * [DynamicRailSwitch] used to pass Step 2c/2d (blocks reserved AND registered to the train)
 * and only then throw `SimulationException[FATAL]` ("PathSeparator must be
 * OrientedPathSeparator") out of `configureSwitchesInPath`, past every rollback path. The
 * blocks stayed registered to a route that was never granted, so a later request from a
 * separator that does not bound them was refused as `NonContiguousStart`.
 *
 * Fix: a Step 0 guard (`DefaultPathReservationService.rejectNonOrientedStart`, mirrored at the
 * top of [PathReservationService.reservePathToAny]) refuses a non-oriented origin with the typed
 * [PathReservationService.ReservationResult.GeometricallyImpossible] before any block, switch
 * or signal is touched. Routes run signal-to-signal; a switch is interior track, never an
 * endpoint (the same invariant Issue #938 pinned for `PathInfo.start`).
 */
@Tag("integration-test")
class PathReservationNonOrientedOriginTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePathToAny from a switch start is refused and reserves nothing`() {
		// Arrange - vA is a switch, i.e. a non-oriented separator
		val switchVA = findSwitchByName("vA")

		// Act - must return a typed result, never throw
		val result = service.reservePathToAny("train1", switchVA)

		// Assert
		assertRefusedAndNothingTouched(result)
	}

	@Test
	fun `reservePath from a switch start is refused and reserves nothing`() {
		// Arrange
		val switchVA = findSwitchByName("vA")

		// Act
		val result = service.reservePath("train1", switchVA, inOut2)

		// Assert
		assertRefusedAndNothingTouched(result)
	}

	@Test
	fun `reservePathToAnyNextSemaphore from a switch start is refused and reserves nothing`() {
		// Arrange - the outer semaphore scan delegates to reservePath once per semaphore it finds,
		// so the Step 0 guard refuses each attempt and the scan reports the permanent verdict.
		val switchVA = findSwitchByName("vA")
		val next = blockBetween("vA", "doA1")

		// Act
		val result = service.reservePathToAnyNextSemaphore("train1", switchVA, next)

		// Assert
		assertRefusedAndNothingTouched(result)
	}

	@Test
	fun `a refused switch start leaves a later request from an unrelated separator unaffected`() {
		// Arrange - the refused request used to leak two RESERVED blocks onto train1,
		// which made every later request from a separator that does not bound them fail
		// as NonContiguousStart (the train's footprint was no longer empty).
		val switchVA = findSwitchByName("vA")
		assertThat(service.reservePathToAny("train1", switchVA))
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()

		// Act - the same train now asks for an ordinary InOut-to-InOut route
		val result = service.reservePath("train1", inOut1, inOut2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	/** [result] must be the request-level refusal, touching no block, switch or PathInfo. */
	private fun assertRefusedAndNothingTouched(result: PathReservationService.ReservationResult) {
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(service.getReservedBlocks("train1")).isEmpty()
		assertThat(registry.getSwitches("train1")).isEmpty()
		assertThat(registry.getPathInfo("train1")).isNull()
	}
}
