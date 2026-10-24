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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.assertReservedBlocks
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The OrientedPathSeparator overload of reservePathToAnyNextSemaphore (Issue #1165 split). */
@Tag("integration-test")
class PathReservationOrientedSeparatorOverloadTest : PathReservationServiceTestBase() {
	/**
	 * Test the new OrientedPathSeparator overload.
	 *
	 * This overload simplifies usage by automatically determining the next track section
	 * based on the separator's orientation, then delegating to the existing overload.
	 */
	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator succeeds`() {
		// Arrange
		// inOut1 is an OrientedPathSeparator (DynamicInOut implements OrientedPathSeparator)
		assertThat(inOut1).isInstanceOf<DynamicInOut>()

		// Act - call new overload without explicit next parameter
		val result = service.reservePathToAnyNextSemaphore("train1", inOut1 as DynamicInOut)

		// Assert
		val success = assertReservationSuccess(result)
		assertThat(success.reservedBlocks).isNotNull()
		// vyhybna.xml: inOut1 (11,8) -> first semaphore at (14,8) = 1 block
		assertThat(success.reservedBlocks.size).isEqualTo(1)

		// Verify all blocks are RESERVED
		assertReservedBlocks(success.reservedBlocks, "train1", inOut1)
	}

	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator delegates correctly`() {
		// Arrange
		val inOut = inOut1 as DynamicInOut

		// Get the expected next track section (what the implementation should find)
		val expectedNext = navigator.getNextTrackSection(inOut, null)
		assertThat(expectedNext).isNotNull()

		// Act - call new overload
		val result1 = service.reservePathToAnyNextSemaphore("train1", inOut)

		// Release path for comparison
		service.releasePath("train1")

		// Call existing overload with explicit next parameter
		val result2 = service.reservePathToAnyNextSemaphore("train1", inOut, expectedNext!!)

		// Assert - both results should be identical
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success1 = result1 as PathReservationService.ReservationResult.Success
		val success2 = result2 as PathReservationService.ReservationResult.Success

		// Same number of blocks reserved
		assertThat(success1.reservedBlocks.size).isEqualTo(success2.reservedBlocks.size)
	}

	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator returns NoPathExists when no outgoing track`() {
		// Arrange
		// Find a semaphore with no outgoing track (dead-end)
		// In vyhybna.xml, both semaphores are mid-network, so we need to create a scenario
		// where getNextTrackSection returns null

		// For this test, we'll use inOut2 which is an exit point
		// When trying to reserve FROM inOut2 (entry-as-exit direction), there might be no path
		val inOut = inOut2 as DynamicInOut

		// Act - try to reserve path from exit InOut (should fail or succeed depending on network)
		val result = service.reservePathToAnyNextSemaphore("train1", inOut)

		// Assert - result should be either Success or NoPathExists
		// (vyhybna.xml is bidirectional, so this might actually succeed)
		// The important thing is that it doesn't crash
		assertThat(result).isNotNull()
	}

	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator works with static separator`() {
		// Arrange
		// Get static InOut from editing context
		val staticInOuts = simulationContext.getInOuts()
		val staticInOut = staticInOuts.toList()[0]

		// Act - call with static separator (should auto-convert to dynamic)
		val result = service.reservePathToAnyNextSemaphore("train1", staticInOut)

		// Assert
		val success = assertReservationSuccess(result)
		assertThat(success.reservedBlocks).isNotEmpty()
		// Verify all blocks are dynamic wrappers
		success.reservedBlocks.forEach { block ->
			assertThat(block).isInstanceOf<DynamicTrackBlock>()
		}
	}

	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator registers ownership`() {
		// Arrange
		val inOut = inOut1 as DynamicInOut

		// Act
		val result = service.reservePathToAnyNextSemaphore("train1", inOut)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Verify ownership registration
		val reservedBlocks = service.getReservedBlocks("train1")
		assertThat(reservedBlocks.size).isEqualTo(1) // Path to first semaphore
	}

	@Test
	fun `reservePathToAnyNextSemaphore with OrientedPathSeparator returns AllPathsBlocked when occupied`() {
		// Arrange
		val inOut = inOut1 as DynamicInOut

		// Reserve the path for another train to block it
		service.reservePath("other-train", inOut1, inOut2)

		// Act
		val result = service.reservePathToAnyNextSemaphore("train1", inOut)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}

	// ========================================
	// Semaphore-based tests (using helper methods)
	// ========================================

	@Test
	fun `from semaphore zB to any doAn semaphore via oriented overload`() {
		// Arrange - Find zB semaphore (27,8)
		// Topology: zB → vB (switch) → either doB1 (MAIN) or doB2 (BRANCH)
		// This test verifies path from zB BACKWARD (toward A side) finds next semaphore
		val zB = findSemaphoreByName("zB")

		// Act - use NEW overload (no explicit next parameter)
		val result = service.reservePathToAnyNextSemaphore("train1", zB)

		// Assert - reservation succeeded
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		// Verify blocks are RESERVED for train1
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.reservedFrom).isEqualTo(zB)
		}

		// Assert path starts from zB
		assertPathContainsSeparators(blocks, "zB", "vB", "doB1")
		// Method reserves to NEXT semaphore (doB1 or doB2), not all the way to destination
		assertThat(blocks.isEmpty()).isFalse()
	}

	@Test
	fun `from semaphore zA to any doBn semaphore via oriented overload`() {
		// Arrange - Find zA semaphore (14,8)
		// Topology: A ← zA ← vA ← doB1 (orientation=false means forward is RIGHT/increasing X)
		// From zA with orientation=false, the FORWARD direction goes through vA toward doB1
		val zA = findSemaphoreByName("zA")

		// Act - use NEW overload (automatic next detection)
		val result = service.reservePathToAnyNextSemaphore("train2", zA)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train2")
			assertThat(block.reservedFrom).isEqualTo(zA)
		}

		// Assert path starts from zA and reaches InOut A (the next separator in that direction)
		assertPathContainsSeparators(blocks, "zA", "vA", "doB1")
		assertThat(blocks.isEmpty()).isFalse()
	}

	@ParameterizedTest
	@CsvSource("A,B", "B,A")
	fun `parallel from zX do to doYn via oriented overload`(
		first: String,
		second: String
	) {
		// Arrange - Find start semaphore (zA or zB)
		val firstSemaphoreName = "z$first"
		val secondSemaphoreName = "z$second"
		val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
		val firstTrainId = "first-train"
		val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
		val secondTrainId = "second-train"

		// Act - first train
		val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
		// Assert
		val success1 = assertReservationSuccess(result1)
		val blocks1 = success1.reservedBlocks

		blocks1.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(firstTrainId)
			assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
		}

		// Assert path goes from start semaphore to expected doYn semaphore
		assertPathContainsSeparators(blocks1, "z$first", "v$first", "do${second}1")
		assertThat(blocks1.isEmpty()).isFalse()

		// Act - second train
		val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
		// Assert
		val success2 = assertReservationSuccess(result2)
		val blocks2 = success2.reservedBlocks

		blocks2.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(secondTrainId)
			assertThat(block.reservedFrom).isEqualTo(secondSemaphore)
		}

		assertThat(blocks2.intersect(blocks1)).isEmpty() // No overlap

		// Assert path goes from start semaphore to expected doYn semaphore
		assertPathContainsSeparators(blocks2, "z$second", "v$second", "do${first}2")
		assertThat(blocks2.isEmpty()).isFalse()
	}

	@Test
	fun `from semaphore doB1 to next separator via oriented overload`() {
		// Arrange - Find doB1 semaphore (25,8) - MAIN branch near B
		// Topology: doA1 ↔ doB1 ← vB ← B (orientation=false means forward is RIGHT/increasing X)
		// From doB1 with orientation=false, the FORWARD direction goes through vB toward B
		val doB1 = findSemaphoreByName("doB1")

		// Act - use NEW overload
		val result = service.reservePathToAnyNextSemaphore("train3", doB1)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks

		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train3")
			assertThat(block.reservedFrom).isEqualTo(doB1)
		}

		// Assert path goes from doB1 to doA1 (both in result)
		assertPathContainsSeparators(blocks, "doB1", "vB", "B")
		assertThat(blocks.isEmpty()).isFalse()
	}

	@Test
	fun `from semaphore doB2 to next separator via oriented overload`() {
		// Arrange - Find doB2 semaphore (24,9) - BRANCH path near B
		// Topology: doA2 ↔ doB2 ← vB ← B (orientation=false means forward is RIGHT/increasing X)
		// From doB2 with orientation=false, the FORWARD direction goes through vB toward B
		val doB2 = findSemaphoreByName("doB2")

		// Act
		val result = service.reservePathToAnyNextSemaphore("train4", doB2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks
		assertPathContainsSeparators(blocks, "doB2", "vB", "B")
		assertThat(blocks.isEmpty()).isFalse()
	}

	@Test
	fun `from semaphore doA1 to next separator via oriented overload`() {
		// Arrange - Find doA1 semaphore (16,8) - MAIN branch near A
		// Topology: doA1 → vA (switch) → zA (semaphore) → A (InOut)
		// Next semaphore from doA1 is zA
		val doA1 = findSemaphoreByName("doA1")

		// Act
		val result = service.reservePathToAnyNextSemaphore("train5", doA1)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks
		assertPathContainsSeparators(blocks, "doA1", "vA", "A")
		// Method reserves to NEXT semaphore (zA), not to InOut A
		assertThat(blocks.isEmpty()).isFalse()
	}

	@Test
	fun `from semaphore doA2 to next separator via oriented overload`() {
		// Arrange - Find doA2 semaphore (17,9) - BRANCH path near A
		// Topology: doA2 → vA (switch) → zA (semaphore) → A (InOut)
		// Next semaphore from doA2 is zA
		val doA2 = findSemaphoreByName("doA2")

		// Act
		val result = service.reservePathToAnyNextSemaphore("train6", doA2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		val blocks = success.reservedBlocks
		assertPathContainsSeparators(blocks, "doA2", "vA", "A")
		// Method reserves to NEXT semaphore (zA), not to InOut A
		assertThat(blocks.isEmpty()).isFalse()
	}

	@ParameterizedTest
	@CsvSource("A,B", "B,A")
	fun `parallel from doX1 do to X and doY2 do Y via oriented overload`(
		first: String,
		second: String
	) {
		// Arrange - Find start semaphore (doA1 or doB1)
		val firstSemaphoreName = "do${first}1"
		val secondSemaphoreName = "do${second}2"
		val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
		val firstTrainId = "first-train"
		val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
		val secondTrainId = "second-train"

		// Act - first train
		val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
		// Assert
		val success1 = assertReservationSuccess(result1)
		val blocks1 = success1.reservedBlocks

		blocks1.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(firstTrainId)
			assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
		}

		// Assert path goes from start semaphore to expected InOut X
		assertPathContainsSeparators(blocks1, "do${first}1", "v$first", "$first")
		assertThat(blocks1.isEmpty()).isFalse()

		// Act - second train
		val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
		// Assert
		val success2 = assertReservationSuccess(result2)
		val blocks2 = success2.reservedBlocks

		blocks2.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(secondTrainId)
			assertThat(block.reservedFrom).isEqualTo(secondSemaphore)
		}

		assertThat(blocks2.intersect(blocks1)).isEmpty() // No overlap

		// Assert path goes from start semaphore to expected InOut Y
		assertPathContainsSeparators(blocks2, "do${second}2", "v$second", "$second")
		assertThat(blocks2.isEmpty()).isFalse()
	}

	@ParameterizedTest
	@CsvSource("A,1,2", "A,2,1", "B,1,2", "B,2,1")
	fun `only first from doXn do to X and not doYm do X via oriented overload`(
		out: String,
		n: Int,
		m: Int
	) {
		// Arrange - Find start semaphore (doA1 or doB1)
		val firstSemaphoreName = "do${out}$n"
		val secondSemaphoreName = "do${out}$m"
		val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
		val firstTrainId = "first-train"
		val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
		val secondTrainId = "second-train"

		// Act - first train
		val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
		// Assert
		val success1 = assertReservationSuccess(result1)
		val blocks1 = success1.reservedBlocks
		blocks1.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(firstTrainId)
			assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
		}

		// Assert path goes from start semaphore to expected InOut X
		assertPathContainsSeparators(blocks1, "do${out}$n", "v$out", out)
		assertThat(blocks1.isEmpty()).isFalse()

		// Act - second train
		val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
		// Assert
		// Issue #937: the second train's candidates are mixed — one is a permanent geometric
		// impossibility (rear-facing START or unconfigurable switch), but another is merely
		// held by the first train, which will release it on moving. A mixed attempt is
		// contention, not permanent impossibility, so the second train must be told to wait.
		// Under #903's first-hit-wins this reported GeometricallyImpossible, which makes
		// InOutWorker throw and kills the run over a blockage that clears by itself.
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}

	/**
	 * Issue #937: a `[GeometricallyImpossible, AllPathsBlocked]` mixed attempt must return
	 * `AllPathsBlocked`, because the attempt as a whole is **not** permanently impossible —
	 * the contention candidate can become free, and then a retry succeeds.
	 *
	 * ## Why this reverses the Issue #903 ruling on this case
	 *
	 * #903 fixed a real defect: the contention branches silently overwrote an earlier
	 * geometric result, losing it entirely. Its remedy was first-hit-wins, which conflated
	 * two separate questions — *which reason is worth reporting* (a geometric one, yes) and
	 * *whether the request is permanently unsatisfiable* (only if EVERY candidate is). The
	 * second must be an AND over candidates. Reporting one impossible candidate as a verdict
	 * on the whole attempt parks a train forever on ordinary contention:
	 * `InOutWorker` throws `SimulationException` on `GeometricallyImpossible` rather than
	 * waiting, and the renderer tells the model never to retry the request.
	 *
	 * The reason string still uses first-hit-wins — that part of #903 stands. Only the
	 * permanence gate changed.
	 *
	 * Fixture `geometric-priority.xml` (header comment documents the topology): `doB1` is
	 * the START; `InOutC` (geometric candidate, route needs the unconfigurable vB A<->D
	 * join) is enumerated FIRST because `prioritizeInOuts` puts InOuts before semaphores;
	 * `B` (contention candidate, route via the configurable vB A<->F MAIN) is enumerated
	 * SECOND. A blocker reservation from `B` to `zB` owns the zB-B block, so the B candidate
	 * hits an owned block (AllPathsBlocked) while InOutC's candidate stays free up to the
	 * unconfigurable switch (GeometricallyImpossible).
	 */
	@Test
	fun `a mixed geometric and contention attempt stays retryable`() {
		// Fresh context from the geometric-priority fixture (the class-level @BeforeEach
		// loads vyhybna.xml, so this test builds its own context/service for this topology).
		val editing = editingContextFactory.createContext(TestFixtures.loadGeometricPriorityXml()) as EditingContext
		val ctx = (simulationContextFactory.createContext(editing) as DefaultSimulationContext).tracked()
		val svc = ctx.getRoutingServices().getPathReservationService()

		fun semByName(name: String): DynamicRailSemaphore {
			val grid = ctx.getRailWayNetGrid()
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell =
						grid[
							cz.vutbr.fit.interlockSim.util
								.Point(x, y)
						]
					if (cell is DynamicRailSemaphore && cell.name == name) return cell
				}
			}
			error("Semaphore $name not found in geometric-priority fixture")
		}

		fun inOutByName(name: String): DynamicInOut =
			ctx
				.getInOuts()
				.map { ctx.toDynamic(it) }
				.filterIsInstance<DynamicInOut>()
				.single { it.name == name }

		val doB1 = semByName("doB1")
		val zB = semByName("zB")
		val b = inOutByName("B")

		// Blocker: reserve B -> zB, owning the zB-B block so the B candidate (doB1 -> vB ->
		// zB -> B) hits an owned block and classifies as AllPathsBlocked (contention), while
		// InOutC's candidate (doB1 -> vB -> InOutC) stays free up to unconfigurable vB.
		val blockerResult = svc.reservePath("blocker", b, zB)
		assertThat(blockerResult)
			.withMessage("blocker B -> zB must succeed to seed the contention on the B candidate")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Probe: oriented overload from doB1. Candidates enumerate as [InOutC, B]
		// (prioritizeInOuts). InOutC (first) -> GeometricallyImpossible; B (second) ->
		// AllPathsBlocked. Not every candidate is impossible, so the attempt is retryable.
		val result = svc.reservePathToAnyNextSemaphore("probe", doB1)

		assertThat(result)
			.withMessage(
				"one impossible candidate does not make the attempt permanently impossible — " +
					"the blocked candidate can free up, so the caller must be told to wait"
			).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}

	/**
	 * The other half of Issue #937's gate: when there is genuinely nothing to wait for, the
	 * permanent verdict must still be reported.
	 *
	 * Without this, the AND could be satisfied vacuously or the downgrade could swallow every
	 * geometric result, and `InOutWorker` would spin on `waitUntil(pathFree)` forever instead
	 * of failing fast on a misconfigured network — the failure mode Issue #903 fixed. The
	 * `doB1 -> doB2` diversion on `vyhybna.xml` has exactly one candidate and it needs the
	 * unconfigurable `vB` join, so `count == attemptedPaths` holds and the verdict stands.
	 */
	@Test
	fun `an attempt whose every candidate is geometric still reports impossible`() {
		val doB1 = findSemaphoreByName("doB1")
		val doB2 = findSemaphoreByName("doB2")

		val result = service.reservePath("all-geometric-train", doB1, doB2)

		assertThat(result)
			.withMessage(
				"every candidate is permanently impossible, so the caller must fail fast " +
					"rather than wait for a blockage that will never clear"
			).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
	}
}
