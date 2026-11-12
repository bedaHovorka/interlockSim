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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * G4 (Issue #893, task A1): reject a route whose START semaphore faces away from the
 * requested direction of travel.
 *
 * ## Domain ruling (traffic-simulation-expert R2; binding)
 *
 * A rear-facing START is the same malformation class as a non-contiguous request (A-R1,
 * see [PathReservationContiguityTest]) and must be rejected outright, not silently left dark: granting
 * a route with no proceed authority at its origin would be a #566-class stall for a
 * train standing at that signal. The intermediate-semaphore rear-facing SKIP (PR #892,
 * see [PathReservationSignalReleaseTest]) stays exactly as-is -- a train never waits on a semaphore it
 * passes from behind; only the START is authority-defining.
 *
 * Topology facts (`vyhybna.xml`): `zA`, `doB1`, `doB2` face A->B; `doA1`, `doA2`, `zB`
 * face B->A (see [RouteSignalConfigurator.facesDirectionOfTravel]).
 */
@Tag("integration-test")
class PathReservationStartDirectionTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath rejects a route whose START semaphore faces away from it`() {
		// doA1 faces B->A (it governs entry into the vA-doA1 block). Requesting doA1 as
		// the START of a route towards doB1 asks it to authorise the OPPOSITE
		// direction -- the block it would need to clear (doA1-doB1) lies behind its
		// facing, not ahead of it.
		val doA1 = findSemaphoreByName("doA1")
		val doB1 = findSemaphoreByName("doB1")

		// Give the train a footprint so the A-R1 contiguity predicate (Step 0 of
		// reservePath) passes and the request reaches signal configuration: physically
		// place it on the block on doA1's LEGITIMATE side (vA-doA1) -- a train standing
		// behind the signal, exactly the scenario the domain ruling describes.
		val vaDoA1 = blockBetween("vA", "doA1")
		occupy(vaDoA1, "rearTrain")

		assertThat(doA1.signal).isEqualTo(Signal.STOP)

		// maxDepth=2 restricts topological search to the single direct doA1-doB1 block
		// (depth 1). Without the cap, BFS also finds a second, much longer candidate
		// around vyhybna's sibling branch (doA1 -> vA -> doA2 -> doB2 -> vB -> doB1)
		// whose first forward block is not even adjacent to doA1 -- an unrelated edge
		// case this test does not intend to exercise.
		val result = service.reservePath("rearTrain", doA1, doB1, maxDepth = 3)

		// Issue #903: a rear-facing START is a permanent geometric impossibility, not
		// ordinary contention -- it must never be reported as AllPathsBlocked (which the
		// dispatcher's invalid-output rate excludes and which invites a pointless retry).
		assertThat(result)
			.withMessage("a rear-facing START must not be granted a route")
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()

		assertThat(registry.getBlocks("rearTrain"))
			.withMessage("a rejected start must reserve nothing")
			.isEmpty()
		val k1 = blockBetween("doA1", "doB1")
		assertThat(k1.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(k1.trainName).isNull()

		assertThat(doA1.signal)
			.withMessage("a rejected START must not be left showing proceed")
			.isEqualTo(Signal.STOP)
	}

	/**
	 * Issue #961: a rear-facing START candidate must publish neither a reserve nor a release event, or
	 * the event counters the detectors keep go out of balance (#1081). Before Issue #1378 the candidate
	 * was rolled back by `rollbackUnconfigurableCandidate` after its blocks were reserved and
	 * registered; since then the Step 1.4 screen refuses it before anything is reserved, and this pin
	 * keeps both event counts at zero either way.
	 */
	@Test
	fun `a candidate rolled back inside reservePath publishes no reserve and no release event`() {
		val doA1 = findSemaphoreByName("doA1")
		val doB1 = findSemaphoreByName("doB1")
		occupy(blockBetween("vA", "doA1"), "rearTrain")
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val result = service.reservePath("rearTrain", doA1, doB1, maxDepth = 3)

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(registry.getBlocks("rearTrain").filter { it.getState() != TrackFacility.State.OCCUPIED })
			.withMessage("the rolled-back candidate must leave no registry entry")
			.isEmpty()
		assertThat(listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RESERVED }, "reserve events")
			.isEqualTo(0)
		assertThat(listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RELEASED }, "release events")
			.isEqualTo(0)
	}

	@Test
	fun `reservePath still succeeds and lights the START when it faces the travel direction`() {
		// Liveness twin (anti-#566): the SAME semaphore, used in the direction it
		// actually faces (B->A, towards zA/A), must still succeed and light up.
		val doA1 = findSemaphoreByName("doA1")

		// Ends at InOut A, not at zA: zA faces A->B, so G8 refuses a route ending there (Issue #1064).
		val result = service.reservePath("liveTrain", doA1, inOutNamed("A"))

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(doA1.signal.isAllowing())
			.withMessage("a legitimately-facing START must still be cleared")
			.isTrue()
		val (_, authorizedTo) = doA1.authorizedDirection()
		assertThat(authorizedTo)
			.withMessage("a proceed aspect must only ever be shown in the direction the semaphore faces")
			.isEqualTo(doA1.direction())
	}

	@Test
	fun `re-requesting an already-owned sub-route from a rear-facing START is geometrically impossible`() {
		// Reserve the full A->B route first (governed by zA/doB1; doA1 is intermediate and
		// rear-facing for this direction, so PR #892's guard already leaves it at STOP --
		// see PathReservationSignalReleaseTest."reservePath never clears a semaphore the route passes
		// from behind"). Then re-request the doA1->doB1 sub-route, which the train
		// already fully owns: this is the SAME rear-facing doA1/doB1 pairing as the
		// rejection test above, but one that used to reach the already-owned early return.
		//
		// Issue #1378 (traffic-simulation-expert ruling): Step 1.4 screens a candidate whose
		// first block lies behind its semaphore START before the candidate loop, so it also
		// covers the already-owned early return. The re-request was granted as Success before;
		// it is now refused, and the held route stays exactly as it was.
		val doA1 = findSemaphoreByName("doA1")
		val doB1 = findSemaphoreByName("doB1")

		// Named explicitly (rather than the class-level inOut1/inOut2, whose A/B identity
		// is an implementation detail of InOut declaration order) to pin down the A->B
		// direction this test's reasoning depends on.
		val full = service.reservePath("t1", inOutNamed("A"), inOutNamed("B"))
		assertThat(full).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(doA1.signal)
			.withMessage("doA1 is rear-facing on this A->B route; it must start at STOP")
			.isEqualTo(Signal.STOP)
		val heldBlocks = registry.getBlocks("t1").toSet()
		val heldPathInfo = registry.getPathInfo("t1")

		val reRequest = service.reservePath("t1", doA1, doB1)

		assertThat(reRequest).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(registry.getBlocks("t1").toSet())
			.withMessage("a refused re-request must leave the held blocks unchanged")
			.isEqualTo(heldBlocks)
		assertThat(registry.getPathInfo("t1"))
			.withMessage("a refused re-request must leave the held PathInfo unchanged")
			.isEqualTo(heldPathInfo)
		assertThat(doA1.signal)
			.withMessage("a semaphore the route passes from behind must stay at STOP")
			.isEqualTo(Signal.STOP)
	}

	/**
	 * Issue #1378: `doA1 -> zB` has two candidates. Candidate 0 leaves doA1 eastwards, from behind
	 * (Step 1.4); candidate 1 loops round the sibling track and reaches zB from behind (Step 1.5, G8).
	 * Both are permanent, and the reported reason is candidate 0's, the first in priority order.
	 */
	@Test
	fun `a start refusal on candidate 0 and an end refusal on candidate 1 report candidate 0`() {
		var summary = ""
		lateinit var result: PathReservationService.ReservationResult
		withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
			result = service.reservePath("t1", findSemaphoreByName("doA1"), findSemaphoreByName("zB"))
			summary =
				serviceLog.list
					.single { it.formattedMessage.startsWith("reservePath: refused 't1'") }
					.formattedMessage
		}

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		val reason = (result as PathReservationService.ReservationResult.GeometricallyImpossible).reason
		assertThat(reason).startsWith("Route origin 'doA1' faces away from the direction of travel on candidate 0;")
		assertThat(summary).contains(": 2 geometric (first: Route origin 'doA1'")
		assertThat(registry.getBlocks("t1")).isEmpty()
	}
}
