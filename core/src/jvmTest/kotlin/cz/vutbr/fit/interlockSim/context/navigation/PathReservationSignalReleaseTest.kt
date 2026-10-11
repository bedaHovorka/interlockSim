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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Signal clearing and route release must be symmetric: a proceed aspect may not outlive
 * the reservation that produced it.
 *
 * ## The defect these tests pin down
 *
 * `reservePath` clears the START separator ([DefaultPathReservationService.configureStartSignal])
 * and every semaphore between two consecutive reserved blocks
 * (`configureIntermediateSemaphores`).  Until this suite was added, **no** release path
 * undid that: `releasePath` and `unregister` cancelled blocks and unlocked switches but
 * never touched a semaphore, so every aspect they cleared stayed lit forever.
 *
 * Observed live on `exampleGui shuntingLoopAI 333`: an `A → B` route granted at t=26.0
 * cleared `zA`, `doA1` and `doB1`; the `OrphanReservationSweeper` cancelled the stale
 * route at t=88.0; all three were still showing S80 at the end of the run. A standing
 * proceed aspect with no route behind it authorises an opposing train onto track that
 * the interlocking believes is free — the failure mode a signal returning to danger
 * exists to prevent.
 *
 * `Signal.STOP` is always the fail-safe direction: it authorises nothing, so resetting
 * too eagerly can only ever be over-restrictive.
 */
@Tag("integration-test")
class PathReservationSignalReleaseTest : PathReservationServiceTestBase() {
	@Test
	fun `releasePath returns every semaphore it cleared to STOP`() {
		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)

		val semaphoresOnPath = semaphoresBounding(success.reservedBlocks)
		// Guard: if nothing was cleared the assertion below would pass vacuously.
		assertThat(semaphoresOnPath.filter { it.signal.isAllowing() }).isNotEmpty()

		service.releasePath("train1")

		assertAllBackAtStop(semaphoresOnPath)
	}

	@Test
	fun `unregister returns every semaphore it cleared to STOP`() {
		// unregister() is the production train-completion path (Train -> releaseTrainReservations).
		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)

		val semaphoresOnPath = semaphoresBounding(success.reservedBlocks)
		assertThat(semaphoresOnPath.filter { it.signal.isAllowing() }).isNotEmpty()

		service.unregister("train1")

		assertAllBackAtStop(semaphoresOnPath)
	}

	@Test
	fun `releasing one train's route leaves another train's cleared signals lit`() {
		// The reset must be scoped to the releasing train: a shared semaphore still
		// protecting a live reservation may not be dropped to STOP under the other train.
		val first = service.reservePath("train1", inOut1, inOut2)
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// train2 gets no route (the network is a single loop, train1 holds it), so the
		// release below must not disturb anything train1 still owns.
		service.releasePath("train2")

		val stillHeld = semaphoresBounding(service.getReservedBlocks("train1"))
		assertThat(stillHeld.filter { it.signal.isAllowing() })
			.withMessage("train1 still holds its route; its signals must stay cleared")
			.isNotEmpty()
	}

	/**
	 * [PathReservationService.resetSemaphoresForReleasedBlocks] (Issue #893, task A3) is the
	 * ownership-aware block-scoped reset a partial (tail) release uses. It must respect the
	 * same last-writer-wins ownership as [releasePath]/[unregister]: once a semaphore it would
	 * otherwise reset has been re-cleared for a DIFFERENT train, it must be left alone -- both
	 * by the API itself and by a later full [releasePath] of the original train.
	 */
	@Test
	fun `resetSemaphoresForReleasedBlocks leaves a since re-cleared semaphore alone, and a later releasePath does too`() {
		// t1 reserves the direct path from zA to B: zA (start) and doB1 (an internal boundary
		// between two blocks t1 owns) both end up cleared; zB, passed from behind, stays at STOP.
		// (Not zA -> zB: zB faces B->A, so G8 refuses a route ending there, Issue #1064.)
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val inOutB = simulationContext.getInOuts().single { it.name == "B" }
		val result = service.reservePath("t1", zA, inOutB)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val blocks = service.getReservedBlocks("t1")

		// Guard: doB1 must actually be lit for t1 before testing that resetting it works at all.
		assertThat(doB1.signal.isAllowing())
			.withMessage("doB1 must be an internal boundary of the zA->B route, lit for t1")
			.isTrue()

		// The un-travelled tail: every block from doB1 onward. The block just before doB1 stays
		// "retained" together with zA -- exactly the shape a partial (occupied-head) release
		// works on, without needing an actual occupied block for this service-level test.
		val doB1Index = blocks.indexOfFirst { doB1 in it.ends() }
		assertThat(doB1Index).isGreaterThanOrEqualTo(0)
		val tail = blocks.subList(doB1Index + 1, blocks.size)
		assertThat(tail, "tail blocks beyond doB1").isNotEmpty()

		// Genuinely free the tail (cancelPathSetup + unregisterBlock), then reset the semaphores
		// it governed -- exactly what a partial release does.
		tail.forEach { block ->
			block.reservedFrom?.let { block.cancelPathSetup(it) }
			service.unregisterBlock("t1", block)
		}
		service.resetSemaphoresForReleasedBlocks("t1", tail)

		assertThat(doB1.signal, "doB1 after the tail release").isEqualTo(Signal.STOP)
		assertThat(zA.signal.isAllowing())
			.withMessage("zA governs the retained head, outside the released tail; it must stay lit")
			.isTrue()

		// A second train reserves the freed track from doB1 onward, re-clearing doB1 for itself.
		val secondResult = service.reservePath("t2", doB1, inOutB)
		assertThat(secondResult).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(doB1.signal.isAllowing())
			.withMessage("doB1 must be lit for t2 after its own reservation")
			.isTrue()

		// Ownership hygiene #1: re-invoking the API for t1 over the SAME (now-foreign) blocks
		// must leave t2's live signal alone.
		service.resetSemaphoresForReleasedBlocks("t1", tail)
		assertThat(doB1.signal.isAllowing())
			.withMessage("doB1 belongs to t2 now; a stale reset for t1 must not touch it")
			.isTrue()

		// Ownership hygiene #2: releasing t1's remaining (retained) route must reset t1's own
		// signal (zA) but must not disturb t2's doB1.
		service.releasePath("t1")
		assertThat(zA.signal, "zA after releasePath(t1)").isEqualTo(Signal.STOP)
		assertThat(doB1.signal.isAllowing())
			.withMessage("releasePath(t1) must not reset doB1, which now belongs to t2")
			.isTrue()
	}

	/**
	 * [PathReservationService.unregisterBlock] (Issue #893, task A4) is the production
	 * tail-clearance path: `Train.Tail.separatorAction` calls it once per block as a train's
	 * tail leaves it. Before this task it only updated the registry -- it never returned the
	 * released block's semaphores to [Signal.STOP], leaving them lit forever exactly like the
	 * pre-A3 `releasePath`/`unregister` defect this file's header documents, except on the
	 * per-block tail path instead of a full route release.
	 */
	@Test
	fun `unregisterBlock returns the semaphores guarding the released block to STOP`() {
		val result = service.reservePath("t1", inOutNamed("A"), inOutNamed("B"))
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val blocks = assertReservationSuccess(result).reservedBlocks
		val firstBlock = blocks.first()

		val releasedBoundary = firstBlock.ends().filterIsInstance<DynamicRailSemaphore>()
		val litReleasedBoundary = releasedBoundary.filter { it.signal.isAllowing() }
		// Guard: if nothing bounding the released block was actually cleared, the STOP
		// assertion below would pass vacuously.
		assertThat(litReleasedBoundary).isNotEmpty()

		val stillHeldBoundary =
			blocks
				.drop(1)
				.flatMap { it.ends().toList() }
				.filterIsInstance<DynamicRailSemaphore>()
				.distinctBy { it.name }
				.filter { it !in releasedBoundary }
		val litStillHeld = stillHeldBoundary.filter { it.signal.isAllowing() }
		// Guard: the "stays lit" half of the assertion needs at least one genuinely lit
		// semaphore bounding only still-held blocks, or it would pass vacuously too.
		assertThat(litStillHeld).isNotEmpty()

		// Genuinely free the first block (occupant == null && FREE), the same precondition
		// PathReservationRegistry.unregisterBlock enforces and the existing
		// resetSemaphoresForReleasedBlocks test above satisfies the same way.
		firstBlock.reservedFrom?.let { firstBlock.cancelPathSetup(it) }
		assertThat(service.unregisterBlock("t1", firstBlock)).isTrue()

		litReleasedBoundary.forEach { semaphore ->
			assertThat(semaphore.signal, "semaphore ${semaphore.name} bounding the released block")
				.isEqualTo(Signal.STOP)
		}
		litStillHeld.forEach { semaphore ->
			assertThat(semaphore.signal.isAllowing())
				.withMessage(
					"semaphore ${semaphore.name} bounds only still-held blocks; unregisterBlock " +
						"of the first block must not touch it"
				).isTrue()
		}
	}

	/**
	 * Gemma4-mandated boundary-transition test. Replays the full choreography this task's
	 * fix participates in: head passage ([Train.semaphoreAction], `Train.kt`, drops a facing
	 * semaphore to STOP as the front passes it) followed later by tail clearance
	 * (`Train.Tail.separatorAction` -> [PathReservationService.unregisterBlock]).
	 *
	 * ## What this test is, and is not
	 *
	 * Assertions (i)-(ii) below are a **forward-invariant safety guard**, not a test that
	 * discriminates this task's change: reverting the single `resetSemaphoresForReleasedBlocks`
	 * call this task adds to `unregisterBlock` leaves (i)-(ii) passing, because `zA` was already
	 * forced to STOP by the manual head-passage simulation in step (a), and `doB1` is never
	 * touched by releasing the first block either way. This was confirmed by running this test
	 * against the pre-fix code during RED -- it already passed. The historical G2 defect (a
	 * released block's governing semaphore staying lit forever) is discriminated by
	 * `unregisterBlock returns the semaphores guarding the released block to STOP` above; that
	 * is the test that actually fails without the fix.
	 *
	 * Assertion (iii) is the genuine discriminator this test adds on top: with no second train
	 * ever reclaiming `zA`, last-writer-wins ownership cannot rescue it the way it does in the
	 * `resetSemaphoresForReleasedBlocks leaves a since re-cleared semaphore alone...` test above
	 * -- only the bookkeeping purge protects it. [Signal.STOP] is idempotent so a stale re-touch
	 * is invisible while `zA` sits at STOP; (iii) forces `zA` to a distinguishable aspect first
	 * so a later stale reset becomes observable.
	 *
	 * Every boundary of a tail-cleared block is behind the head by definition (the
	 * traffic-simulation-expert R3 ruling this task implements): the block the tail just left
	 * cannot bound anything the train still needs in front of it.
	 */
	@Test
	fun `unregisterBlock never drops the signal ahead of the train (forward-invariant guard, purge check)`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val result = service.reservePath("t1", zA, simulationContext.getInOuts().single { it.name == "B" })
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val blocks = assertReservationSuccess(result).reservedBlocks
		val firstBlock = blocks.first()
		assertThat(firstBlock.ends().toList()).contains(zA)

		// Guards: both semaphores must start lit, or the assertions below are vacuous.
		assertThat(zA.signal.isAllowing()).isTrue()
		assertThat(doB1.signal.isAllowing()).isTrue()

		// (a) Head passes zA, the route's first facing semaphore -- simulate exactly what
		// Train.semaphoreAction does at the end of its hold(1.0): drop the aspect to STOP.
		zA.signal = Signal.STOP

		// (b) Tail clears the first block -- free it and unregister it, as in the previous test.
		firstBlock.reservedFrom?.let { firstBlock.cancelPathSetup(it) }
		assertThat(service.unregisterBlock("t1", firstBlock)).isTrue()

		// (i) zA is STOP -- head passage already guaranteed that; this assertion alone cannot
		// discriminate the fix (see the class KDoc above). It documents the choreography's
		// end state before the genuine discriminator in (iii).
		assertThat(zA.signal, "zA immediately after the tail clears its block").isEqualTo(Signal.STOP)

		// (ii) The signal AHEAD of the train -- doB1, bounding blocks t1 still holds -- must
		// stay lit. This is the core safety claim: releasing the REARMOST block can never drop
		// authority ahead of the head, because every boundary of a tail-cleared block is behind
		// it. unregisterBlock never touches doB1 either way, so this also doesn't discriminate
		// the fix -- it is the invariant this whole task exists to protect, checked directly
		// rather than only asserted in prose.
		assertThat(doB1.signal.isAllowing())
			.withMessage("doB1 governs a still-held block ahead of the train; it must stay lit")
			.isTrue()

		// (iii) Genuine discriminator. Nobody reclaims zA for another train here, so
		// semaphoreClearedFor[zA] is still "t1" the whole time -- the ownership guard that
		// protects a re-claimed semaphore in the sibling resetSemaphoresForReleasedBlocks test
		// does not apply. Force zA to a distinguishable allowing aspect (standing in for any
		// later, unrelated signal change; STOP is idempotent and would otherwise mask a stale
		// re-touch), then run a later, independent releasePath(t1) over the rest of the route.
		// Without this task's purge, t1's bookkeeping still lists zA as its own and
		// resetClearedSemaphores (which releasePath calls unconditionally, before it even looks
		// at block state) stomps it back to STOP; with the purge, zA is no longer in that set
		// and is left alone.
		zA.signal = Signal.S80
		service.releasePath("t1")
		assertThat(zA.signal, "zA after a later releasePath(t1), with nobody having reclaimed it")
			.isEqualTo(Signal.S80)
	}

	@Test
	fun `reservePath never clears a semaphore the route passes from behind`() {
		// A semaphore facing against the direction of travel governs the OPPOSING movement.
		// The train does not consult it -- Train.separatorAction only invokes semaphoreAction
		// when isSeparatorInDirection() holds -- so clearing it authorises nobody useful while
		// inviting a train coming the other way onto the route. It is also the aspect that
		// then never returns to danger, because the reset at the end of semaphoreAction is on
		// exactly the path that was skipped.
		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)

		val lit = semaphoresBounding(success.reservedBlocks).filter { it.signal.isAllowing() }

		// The route must still be traversable -- F2 must not resurrect the Issue #566 stall
		// where a granted route could never be driven because a semaphore stayed at STOP.
		assertThat(lit)
			.withMessage("a granted route must clear the semaphores that govern it")
			.isNotEmpty()

		lit.forEach { semaphore ->
			val (_, authorizedTo) = semaphore.authorizedDirection()
			assertThat(authorizedTo)
				.withMessage(
					"Semaphore ${semaphore.name} was cleared for travel towards $authorizedTo, " +
						"but it faces ${semaphore.direction()} - a proceed aspect must only ever be " +
						"shown in the direction the semaphore faces"
				).isEqualTo(semaphore.direction())
		}
	}

	@Test
	fun `opposite routes over the same track clear disjoint sets of semaphores`() {
		// The sharpest statement of the rule, and one that needs no hard-coded knowledge of
		// which semaphore faces which way: a signal governs ONE direction. Run the loop both
		// ways and the two cleared sets must not overlap.
		//
		// Without the rear-traversal guard both routes clear every semaphore bounding the
		// same seven blocks, so the two sets come out identical instead of disjoint - which
		// is precisely the defect: an A→B route lighting the signals that authorise B→A.
		val aToB = service.reservePath("eastbound", inOutNamed("A"), inOutNamed("B"))
		assertThat(aToB).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val litEastbound = litSemaphoreNames(assertReservationSuccess(aToB))

		service.releasePath("eastbound")

		val bToA = service.reservePath("westbound", inOutNamed("B"), inOutNamed("A"))
		assertThat(bToA).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val litWestbound = litSemaphoreNames(assertReservationSuccess(bToA))

		assertThat(litEastbound).isNotEmpty()
		assertThat(litWestbound).isNotEmpty()
		assertThat(litEastbound.intersect(litWestbound))
			.withMessage(
				"$litEastbound (A→B) and $litWestbound (B→A) share a semaphore - a proceed " +
					"aspect cleared for one direction must never also stand for the opposite one"
			).isEmpty()
	}

	@Test
	fun `extending a route never re-lights semaphores between blocks the train already owns`() {
		// Step 1: reserve a partial westbound route from B to doA1 (B -> zB -> vB -> doB1 -> doA1).
		// It owns zB as an INTERNAL boundary -- both its neighbouring blocks, zB-B and zB-vB, are
		// part of this same reservation -- rather than as the route's start or target. zB faces the
		// direction of travel (B -> A), so it gets lit here. The route ends at doA1, which faces the
		// train too: a route ending at a signal facing the other way (the earlier zA -> zB fixture)
		// is refused by G8 (Issue #1064).
		val zB = findSemaphoreByName("zB")
		val partial = service.reservePath("t1", inOutNamed("B"), findSemaphoreByName("doA1"))
		val partialSuccess = assertReservationSuccess(partial)

		val clearedByPartial =
			semaphoresBounding(partialSuccess.reservedBlocks).filter { it.signal.isAllowing() }
		// Guard: if nothing was cleared, the "stays at STOP" assertion below would pass vacuously.
		assertThat(clearedByPartial).isNotEmpty()
		assertThat(clearedByPartial.map { it.name }).contains(zB.name)

		// Step 2: simulate head passage -- exactly what Train.semaphoreAction does when a
		// train passes a facing semaphore: hold(1.0); semaphore.signal = Signal.STOP.
		clearedByPartial.forEach { it.signal = Signal.STOP }

		// Step 3: extend the SAME route all the way to A, reusing the original start. The
		// recomputed candidate spans the blocks t1 already owns (B..doA1) plus the new blocks
		// (doA1..A). The service is expected to filter the already-owned blocks into
		// forwardBlocks internally and only configure signals for the new portion.
		val extended = service.reservePath("t1", inOutNamed("B"), inOutNamed("A"))
		assertThat(extended).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Assert: every semaphore strictly between two blocks the train already owned --
		// zB in particular, sitting between the zB-B block and the zB-vB block, both already
		// owned before the extension -- must still be at STOP. The route extension must not
		// re-light a semaphore behind the train's head.
		clearedByPartial.forEach { semaphore ->
			assertThat(semaphore.signal)
				.withMessage(
					"Semaphore ${semaphore.name} was re-lit to ${semaphore.signal} by the route " +
						"extension even though the train already passed it and returned it to STOP"
				).isEqualTo(Signal.STOP)
		}
	}

	@Test
	fun `extending a route over owned blocks emits BlockReserved only for the new blocks`() {
		// Issue #1081 review (on the Issue #1060 extension): reservePath used to emit
		// BlockReserved for EVERY block of a successful candidate -- including the
		// already-owned prefix filtered out at Step 2a.5 -- while each physical block
		// is later released exactly once. Per-event reservation counters
		// (DefaultMetricsCollectionService.activeReservationCount,
		// DefaultCollisionDetectionService, TemporalConflictDetector) therefore never
		// returned to zero for a train whose route was extended over its own blocks,
		// so the train was never counted as completed.
		val reservedEvents = mutableListOf<BlockOccupancyEvent>()
		registry.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RESERVED) {
				reservedEvents.add(event)
			}
		}

		// Step 1: reserve a partial westbound route from B to doA1, so the train owns
		// every block of B..doA1 (the same fixture as the re-lighting test above).
		val partial = service.reservePath("t1", inOutNamed("B"), findSemaphoreByName("doA1"))
		val partialSuccess = assertReservationSuccess(partial)
		val ownedBeforeExtension = partialSuccess.reservedBlocks.toSet()
		val eventsAfterPartial = reservedEvents.toList()
		assertThat(eventsAfterPartial.map { it.block }.toSet())
			.withMessage("the partial reservation must emit BlockReserved for exactly its own blocks")
			.isEqualTo(ownedBeforeExtension)

		// Step 2: extend the SAME route all the way to A. The recomputed candidate
		// spans the blocks t1 already owns (B..doA1) plus the new blocks (doA1..A) --
		// the overlapping-extension shape of Issue #1060.
		val extended = service.reservePath("t1", inOutNamed("B"), inOutNamed("A"))
		val extendedSuccess = assertReservationSuccess(extended)
		val newBlocks = extendedSuccess.reservedBlocks.toSet() - ownedBeforeExtension

		// Guard: the extension must genuinely overlap -- it re-traverses the owned
		// prefix AND acquires new blocks. Otherwise the assertions below would pass
		// vacuously for a topology change.
		assertThat(newBlocks)
			.withMessage("the extension must acquire at least one NEW block")
			.isNotEmpty()
		assertThat(extendedSuccess.reservedBlocks.toSet().containsAll(ownedBeforeExtension))
			.withMessage("the extension's candidate must re-traverse the blocks the train already owns")
			.isTrue()

		// Assert: the extension emits BlockReserved ONLY for the newly acquired blocks.
		// Before the fix it re-emitted the already-owned prefix, which no later release
		// ever paired off again.
		val extensionEvents = reservedEvents.drop(eventsAfterPartial.size)
		assertThat(extensionEvents)
			.withMessage("the extension must emit at least one BlockReserved (for its new blocks)")
			.isNotEmpty()
		assertThat(extensionEvents.map { it.block }.toSet())
			.withMessage("every BlockReserved of the extension must be for a NEW block")
			.isEqualTo(newBlocks)

		// And: across both reservations, no block is reserved twice.
		assertThat(reservedEvents.groupBy { it.block }.any { it.value.size > 1 })
			.withMessage(
				"some block emitted more than one BlockReserved across reserve + extension; " +
					"a block is released exactly once, so per-event reservation counters would drift"
			).isFalse()
	}

	@Test
	fun `extending a route lights the boundary from the last owned block into the first new block`() {
		// Step 1: reserve a partial route from zA to doB1 -- ending EXACTLY at doB1, not past
		// it. A destination separator is never configured as an intermediate boundary (there
		// is no "next block" beyond it in this partial's block list), so doB1 starts at STOP.
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val partial = service.reservePath("t1", zA, doB1)
		assertThat(partial).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Guard: doB1 must start unlit, otherwise the "becomes lit" assertion below would be
		// meaningless (it might already have been lit for an unrelated reason).
		assertThat(doB1.signal.isAllowing())
			.withMessage("Semaphore doB1 is the partial route's destination; it must start at STOP")
			.isFalse()

		// Step 2: extend the SAME route all the way to B, reusing the original start. The
		// recomputed candidate spans the blocks t1 already owns (zA..doB1) plus new blocks
		// beyond doB1 (doB1..B). doB1 is now a genuine owned -> new transition: the last
		// block the train already owns (doA1-doB1) leads into the first NEW block
		// (doB1-vB), so the train still needs doB1 lit to proceed into the extension.
		val extended = service.reservePath("t1", zA, inOutNamed("B"))
		assertThat(extended).isInstanceOf<PathReservationService.ReservationResult.Success>()

		assertThat(doB1.signal.isAllowing())
			.withMessage(
				"Semaphore doB1 governs the boundary between the block the train already owns " +
					"(doA1-doB1) and the first newly reserved block (doB1-vB); the extension must " +
					"light it so the train can proceed"
			).isTrue()
	}

	/**
	 * Direct unit coverage of [PathReservationService.hasClearedSignals] (Issue #893, task
	 * A7): [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.releaseRoute] reads
	 * this before calling [PathReservationService.releasePath] (which purges the same
	 * bookkeeping as a side effect of its own reset), so this method's truthfulness matters
	 * on its own, independent of that caller.
	 */
	@Test
	fun `hasClearedSignals reports true only while the train still owns a cleared semaphore`() {
		assertThat(service.hasClearedSignals("t1"))
			.withMessage("t1 has not reserved anything yet")
			.isFalse()

		val result = service.reservePath("t1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		assertThat(service.hasClearedSignals("t1"))
			.withMessage("reservePath must have cleared at least the START signal")
			.isTrue()

		service.releasePath("t1")

		assertThat(service.hasClearedSignals("t1"))
			.withMessage("releasePath resets and forgets every cleared signal for the train")
			.isFalse()
	}

	/**
	 * Regression test for the F2 final-review finding on Issue #893: [resetSemaphoreSet]'s
	 * ownership-skip branch (`semaphoreClearedFor[semaphore] != trainId`) returned before
	 * removing the semaphore from the CALLER's own [clearedSemaphores] entry, so once a
	 * semaphore's ownership moved on to another train, the original train's ledger kept
	 * listing it forever. Because [hasClearedSignals] is a pure key-presence check (see its
	 * KDoc: "clearedSemaphores never holds an empty set for a key"), the leak made it report
	 * `true` for a train holding nothing at all -- and
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.releaseRoute] reads exactly
	 * this flag BEFORE calling [PathReservationService.releasePath] to decide whether a
	 * release genuinely happened, so a caller could see a false "released" verdict.
	 *
	 * [resetClearedSemaphores] (the whole-route release path) does not share this defect: it
	 * removes the entire per-train map entry up front (`clearedSemaphores.remove(trainId)`),
	 * so the key is already gone before the ownership check runs, whichever branch it takes.
	 * The leak is specific to [resetSemaphoreSet], the block-scoped reset used by
	 * [resetSemaphoresForReleasedBlocks] (tail/partial releases).
	 */
	@Test
	fun `resetSemaphoresForReleasedBlocks purges a semaphore from the ledger even when its ownership already moved on`() {
		val zA = findSemaphoreByName("zA")
		val result = service.reservePath("t1", zA, simulationContext.getInOuts().single { it.name == "B" })
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val reservedBlocks = service.getReservedBlocks("t1")

		// Guards: zA must genuinely be t1's cleared START signal, or the rest is vacuous.
		assertThat(zA.signal.isAllowing()).isTrue()
		assertThat(service.hasClearedSignals("t1"))
			.withMessage("reservePath must have cleared at least the START signal")
			.isTrue()

		// t2 re-clears the SAME semaphore t1 holds -- last-writer-wins ownership transfer,
		// exactly what reservePath's own internal recording does when a route is re-granted
		// through a previously-cleared boundary. clearedSemaphores["t1"] is untouched by
		// this call -- only the new owner's side of the ledger changes, which is the root of
		// the leak this test targets.
		service.recordExternalClearedSemaphore("t2", zA)

		// t2 releases -- this purges t2's own ledger entry AND removes semaphoreClearedFor[zA]
		// entirely, so zA is now owned by nobody.
		service.releasePath("t2")

		// t1's own block-scoped reset runs over its full reserved set, which still lists zA
		// (never removed by the ownership transfer above). Ownership no longer matches t1
		// (it matches nobody), so the physical aspect write is correctly skipped -- but the
		// stale ledger ENTRY for zA under t1 must still be purged.
		service.resetSemaphoresForReleasedBlocks("t1", reservedBlocks)

		assertThat(service.hasClearedSignals("t1"))
			.withMessage(
				"zA's ownership moved on and was released by its new owner; t1's ledger must " +
					"not still claim it"
			).isFalse()
	}

	private fun litSemaphoreNames(success: PathReservationService.ReservationResult.Success): Set<String> =
		semaphoresBounding(success.reservedBlocks)
			.filter { it.signal.isAllowing() }
			.mapNotNull { it.name }
			.toSet()

	private fun semaphoresBounding(blocks: Collection<DynamicTrackBlock>): List<DynamicRailSemaphore> =
		blocks
			.flatMap { it.ends().toList() }
			.filterIsInstance<DynamicRailSemaphore>()
			.distinctBy { it.name }

	private fun assertAllBackAtStop(semaphores: List<DynamicRailSemaphore>) {
		semaphores.forEach { semaphore ->
			assertThat(semaphore.signal)
				.withMessage(
					"Semaphore ${semaphore.name} still shows ${semaphore.signal} after its route was " +
						"released - a proceed aspect must not outlive the reservation that cleared it"
				).isEqualTo(Signal.STOP)
		}
	}
}
