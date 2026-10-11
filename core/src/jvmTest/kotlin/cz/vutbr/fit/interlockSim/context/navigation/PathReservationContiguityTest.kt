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
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Contiguity invariant on the route start (Issue #893, task A-R1).
 *
 * A route request whose start separator is not contiguous with the requesting train's
 * current authority reserves track somewhere the train is not, holds it against every
 * other train, and releases nobody. `reservePath` must reject it outright.
 *
 * The train's "authority" (its footprint) is the union of two independent sources:
 * the blocks the registry records for it, and the blocks whose physical `occupant`
 * carries its name. The second arm matters because a train can be admitted and
 * physically present with **no** registry state at all — the t=17 admission flow —
 * and a registry-only predicate would be blind to it.
 *
 * A train with an empty footprint passes vacuously: every production caller that reaches
 * `reservePath` for such a train supplies an InOut start (train entry), so the strict arm
 * would buy no safety and invalidate the entry flow.
 *
 * Topology note (`vyhybna.xml`): block `kB` spans InOut `B` ↔ semaphore `zB`, so `zB` is
 * its legal forward boundary. `doB1` is two hops further on (`k1`'s boundary) and is NOT
 * a boundary of `kB`.
 */
@Tag("integration-test")
class PathReservationContiguityTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath from a boundary of a block the train already holds succeeds`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")

		val initial = service.reservePath("t1", zA, doB1)
		assertThat(initial).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// doB1 bounds the last block reserved above, so extending from it is contiguous.
		val extension = service.reservePath("t1", doB1, simulationContext.getInOuts().single { it.name == "B" })

		assertThat(extension).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	@Test
	fun `reservePath from a separator on no held block boundary is rejected and reserves nothing`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val doA2 = findSemaphoreByName("doA2")
		val doB2 = findSemaphoreByName("doB2")

		assertThat(service.reservePath("t1", zA, doB1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val heldBefore = registry.getBlocks("t1").toSet()
		// Guard: the assertion below is only meaningful if doA2 really is off the held route.
		assertThat(heldBefore.flatMap { it.ends().toList() }.contains(doA2))
			.withMessage("doA2 must not bound any block t1 holds, or this fixture proves nothing")
			.isFalse()

		val result = service.reservePath("t1", doA2, doB2)

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NonContiguousStart>()
		assertThat(registry.getBlocks("t1").toSet())
			.withMessage("a rejected request must not add or remove any block")
			.isEqualTo(heldBefore)
		val k2 = blockBetween("doA2", "doB2")
		assertThat(k2.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(k2.trainName).isNull()
	}

	/**
	 * The t=17 admission flow: a train admitted onto `kB` before any route was granted
	 * has zero registry state, so only the graph-scan occupancy arm can see it.
	 */
	@Test
	fun `a physically occupied block is a footprint even with no registry state`() {
		val kB = blockBetween("B", "zB")
		occupy(kB, "T-17")
		assertThat(registry.getBlocks("T-17"))
			.withMessage("this test only exercises the occupancy arm if the registry is empty")
			.isEmpty()

		val result = service.reservePath("T-17", findSemaphoreByName("zB"), findSemaphoreByName("doA1"))

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	@Test
	fun `a start away from the occupied block is rejected even with no registry state`() {
		val kB = blockBetween("B", "zB")
		occupy(kB, "T-17")
		assertThat(registry.getBlocks("T-17")).isEmpty()

		// doA1 is at the far end of the station — it bounds no block T-17 occupies.
		val result = service.reservePath("T-17", findSemaphoreByName("doA1"), findSemaphoreByName("doB1"))

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NonContiguousStart>()
		assertThat(registry.getBlocks("T-17")).isEmpty()
		val k1 = blockBetween("doA1", "doB1")
		assertThat(k1.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(k1.trainName).isNull()
	}

	/**
	 * Pins ruling P4(ii): a train with no footprint anywhere passes vacuously, whatever
	 * its start. Tightening this arm would break every train-entry caller.
	 *
	 * Also the regression pin for ruling D8 (#972): the kernel cannot see a queued train, so a
	 * mid-station origin for one is not refused here; `RequestRouteTool.queuedOriginError` owns that half.
	 *
	 * Uses doA1 -> A rather than doA1 -> doB1: doA1 faces B->A (see
	 * [PathReservationSignalReleaseTest] / [PathReservationStartDirectionTest]), so a doA1 -> doB1 request
	 * is rejected by the unrelated G4 rear-facing-START guard (Issue #893 task A1) regardless of
	 * contiguity, which would confound this test's own concern. It does not use doA1 -> zA
	 * either: zA faces A->B, so that request is refused by the G8 rear-facing-END guard
	 * (Issue #1064).
	 */
	@Test
	@DisplayName(
		"queued train with empty footprint passes the kernel contiguity check vacuously " +
			"(guarded by RequestRouteTool.queuedOriginError, D8)"
	)
	fun `a train with no footprint at all passes vacuously`() {
		val inOutA = simulationContext.getInOuts().single { it.name == "A" }
		val result = service.reservePath("phantom-train", findSemaphoreByName("doA1"), inOutA)

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}
}
