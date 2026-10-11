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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * ReservePathToAny Tests
 *
 * Tests for reservePathToAny() method which should try BOTH InOuts AND semaphores as targets.
 * Based on vyhybna.xml topology.
 */
@Tag("integration-test")
class PathReservationToAnyTest : PathReservationServiceTestBase() {
	private fun assertIsDirectedToOutSide(
		blocks: List<DynamicTrackBlock>,
		nameOfOut: String
	) {
		val sem1 = "do${nameOfOut}1"
		val sem2 = "do${nameOfOut}2"
		assertThat(
			blocks.any { block ->
				val (sep1, sep2) = block.ends()
				(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
					(sep2 is DynamicInOut && sep2.name == nameOfOut) ||
					(sep1 is DynamicRailSemaphore && (sep1.name == sem1 || sep1.name == sem2)) ||
					(sep2 is DynamicRailSemaphore && (sep2.name == sem1 || sep2.name == sem2))
			}
		).isTrue()
	}

	private fun assertIsReachedOutSide(
		blocks: List<DynamicTrackBlock>,
		nameOfOut: String
	) {
		assertThat(
			blocks.any { block ->
				val (sep1, sep2) = block.ends()
				(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
					(sep2 is DynamicInOut && sep2.name == nameOfOut)
			}
		).isTrue()
	}

	@Test
	fun `test scenario 1 - from zA to B side semaphores`() {
		// Arrange - Find zA semaphore (14,8) and target semaphores
		val zA = findSemaphoreByName("zA")

		// Act - reserve path from zA to any available target
		val result = service.reservePathToAny("train1", zA)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block).isInstanceOf(DynamicTrackBlock::class)
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(zA)
		}

		// Verify START semaphore signal allows entry (not STOP)
		assertThat(zA.signal).isNotEqualTo(Signal.STOP)

		// Assert path reaches one of: doB1, doB2, or B
		// Path should go through: zA → vA → (doA1 or doA2) → (k1 or k2) → (doB1 or doB2)
		assertPathContainsSeparators(blocks, "zA", "vA")
		// Path must reach B side (at least one of: doB1, doB2, or InOut B)
		assertIsDirectedToOutSide(blocks, "B")
	}

	@Test
	fun `test scenario 2 - from zB to A side semaphores`() {
		// Arrange - Find zB semaphore (27,8)
		val zB = findSemaphoreByName("zB")

		// Act - reserve path from zB to any available target
		val result = service.reservePathToAny("train2", zB)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train2
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train2")
			assertThat(block.reservedFrom).isEqualTo(zB)
		}

		// Assert path starts from zB (reservePathToAny finds ANY valid target)
		// In vyhybna.xml, from zB there are multiple possible targets
		// The algorithm tries InOuts first, so may go to B (shortest path)
		assertPathContainsSeparators(blocks, "zB")
		assertThat(blocks).isNotNull()
		assertThat(blocks.isEmpty()).isFalse()

		// Path must reach A side (at least one of: doA1, doA2, or InOut A)
		assertIsDirectedToOutSide(blocks, "A")
	}

	@Test
	fun `test scenario 3 - from doA1 to InOut A`() {
		// Arrange - Find doA1 semaphore (16,8)
		val doA1 = findSemaphoreByName("doA1")

		// Act - reserve path from doA1
		val result = service.reservePathToAny("train1", doA1)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(doA1)
		}

		// Assert path starts from doA1 (reservePathToAny finds ANY valid target)
		// From doA1, paths exist to both InOut A and InOut B
		// The algorithm tries InOuts first, and may find B before A
		assertPathContainsSeparators(blocks, "doA1")
		assertThat(blocks).isNotNull()
		assertThat(blocks.isEmpty()).isFalse()

		assertIsReachedOutSide(blocks, "A")
	}

	@Test
	fun `test scenario 4 - from doA2 to InOut A`() {
		// Arrange - Find doA2 semaphore (17,9)
		val doA2 = findSemaphoreByName("doA2")

		// Act - reserve path from doA2
		val result = service.reservePathToAny("train1", doA2)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(doA2)
		}

		// Assert path starts from doA2 (reservePathToAny finds ANY valid target)
		// From doA2, paths exist to both InOut A and InOut B
		// The algorithm tries InOuts first, and may find B before A
		assertPathContainsSeparators(blocks, "doA2")
		assertThat(blocks).isNotNull()
		assertThat(blocks.isEmpty()).isFalse()

		assertIsReachedOutSide(blocks, "A")
	}

	@Test
	fun `test scenario 5 - from doB1 to InOut B`() {
		// Arrange - Find doB1 semaphore (25,8)
		val doB1 = findSemaphoreByName("doB1")

		// Act - reserve path from doB1
		val result = service.reservePathToAny("train1", doB1)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(doB1)
		}

		// Assert path goes to InOut B: doB1 → vB → zB → kB → B
		assertPathContainsSeparators(blocks, "doB1", "vB", "zB")

		assertIsReachedOutSide(blocks, "B")
	}

	@Test
	fun `test scenario 6 - from doB2 to InOut B`() {
		// Arrange - Find doB2 semaphore (24,9)
		val doB2 = findSemaphoreByName("doB2")

		// Act - reserve path from doB2
		val result = service.reservePathToAny("train1", doB2)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Get reserved blocks
		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(doB2)
		}

		// Assert path goes to InOut B: doB2 → vB → zB → kB → B
		assertPathContainsSeparators(blocks, "doB2", "vB", "zB")

		assertIsReachedOutSide(blocks, "B")
	}
}
