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
import assertk.assertions.isNull
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import cz.vutbr.fit.interlockSim.context.navigation.PathRelease as ReleaseOutcome

/** Approach-locked (deferred) release behaviour, Issue #1050 (Issue #1165 split). */
@Tag("integration-test")
class PathReservationApproachLockedReleaseTest : PathReservationServiceTestBase() {
	@Test
	fun `a kept head whose trim boundary is a switch defers the whole route and frees nothing (Issue 1050)`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		// A train standing across the first two blocks: its kept head ends at the switch vA,
		// and a PathInfo never ends at a switch (isValidPathInfoEnd). Freeing the tail now
		// would leave a stale PathInfo the registry cannot trim, which later stalls the train
		// with OwnershipConflict.
		val occupant = FakeTrackOccupant("train1")
		blocks[0].enter(occupant)
		blocks[1].enter(occupant)

		val first = service.releasePathDetailed("train1")

		// The preflight refuses the trim, so the release frees NOTHING and reports the whole
		// route as deferred (Issue #1050 review round). The sweeper retries with a fresh
		// clock; the topology becomes trimmable once the train moves on.
		assertThat(first.deferred.toSet()).isEqualTo(blocks.toSet())
		assertThat(first.released).isEmpty()
		blocks.take(2).forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(registry.getOwner(block)).isEqualTo("train1")
		}
		blocks.drop(2).forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block)).isEqualTo("train1")
		}

		val immediateRetry = service.releasePathDetailed("train1")

		// An immediate repeat changes nothing: the switch boundary is still untrimmable and
		// the occupied blocks are kept on every call.
		assertThat(immediateRetry.deferred.toSet()).isEqualTo(blocks.toSet())
		assertThat(immediateRetry.released).isEmpty()
		blocks.forEach { block -> assertThat(registry.getOwner(block)).isEqualTo("train1") }
	}

	@Test
	fun `a repeat after the booking window frees what approach locking kept (Issue 1050)`() =
		runBlocking {
			val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
			val start = blocks.first().reservedFrom
			val entry = (start as DynamicInOut).inSemaphore
			entry.signal = Signal.FREE
			val outcomes = mutableListOf<ReleaseOutcome>()

			Simulation
				.create {
					Process.activate(
						object : Process() {
							override suspend fun actions() {
								outcomes += service.releasePathDetailed("train1")
								hold(0.5)
								outcomes += service.releasePathDetailed("train1")
								hold(1.0)
								outcomes += service.releasePathDetailed("train1")
							}
						}
					)
				}.run(10.0)

			// t=0.0: the proceed aspect at the entry defers the head; the tail is trimmed at
			// the semaphore zA (a valid PathInfo end) and freed.
			assertThat(outcomes[0].deferred.toSet()).isEqualTo(setOf(blocks.first()))
			assertThat(outcomes[0].released.toSet()).isEqualTo(blocks.drop(1).toSet())
			// t=0.5: still inside the booking window (1.0 s) armed at t=0.0 -- the head stays
			// deferred even though its signal now reads STOP.
			assertThat(outcomes[1].deferred.toSet()).isEqualTo(setOf(blocks.first()))
			assertThat(outcomes[1].released).isEmpty()
			// t=1.5: the window closed at t=1.0 and no train booked anything -- the deferral
			// expired, the signals read STOP, and the wholesale release takes the head.
			assertThat(outcomes[2].deferred).isEmpty()
			assertThat(outcomes[2].released.toSet()).isEqualTo(setOf(blocks.first()))
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
				assertThat(registry.getOwner(block)).isNull()
			}
			assertThat(service.getReservedBlocks("train1")).isEmpty()
		}

	@Test
	fun `releasePath frees everything when no proceed aspect stands at a reserved block`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		((blocks.first().reservedFrom) as DynamicInOut).inSemaphore.signal = Signal.STOP

		val release = service.releasePathDetailed("train1")

		assertThat(release.deferred).isEmpty()
		assertThat(release.released.toSet()).isEqualTo(blocks.toSet())
	}

	@Test
	fun `a retry at exactly the booking window's expiry still keeps the block deferred (Issue 1050)`() =
		runBlocking {
			val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
			val start = blocks.first().reservedFrom
			val entry = (start as DynamicInOut).inSemaphore
			entry.signal = Signal.FREE
			val outcomes = mutableListOf<ReleaseOutcome>()
			var ownerAtExpiry: String? = null

			Simulation
				.create {
					Process.activate(
						object : Process() {
							override suspend fun actions() {
								outcomes += service.releasePathDetailed("train1")
								hold(1.0)
								outcomes += service.releasePathDetailed("train1")
								// Read the registry HERE, mid-run: once .run() returns below, the whole
								// process (including the t=1.5 phase) has already completed.
								ownerAtExpiry = registry.getOwner(blocks.first())
								hold(0.5)
								outcomes += service.releasePathDetailed("train1")
							}
						}
					)
				}.run(10.0)

			// t=0.0: the proceed aspect defers the head, arming the window to close at t=1.0.
			assertThat(outcomes[0].deferred.toSet()).isEqualTo(setOf(blocks.first()))
			// t=1.0: exactly the booking window's expiry. Train.Front resumes from its own
			// hold(1.0) and books the block AT this instant, before next.enter() runs, so a
			// retry scheduled first at the same simulation time must still see it as deferred
			// (Copilot review round, PR #1080) -- only a strictly later `now` proves the
			// window has fully closed.
			assertThat(outcomes[1].deferred.toSet()).isEqualTo(setOf(blocks.first()))
			assertThat(outcomes[1].released).isEmpty()
			assertThat(ownerAtExpiry).isEqualTo("train1")
			// t=1.5: strictly past the expiry -- no train booked, so the wholesale release
			// finally takes the head.
			assertThat(outcomes[2].deferred).isEmpty()
			assertThat(outcomes[2].released.toSet()).isEqualTo(setOf(blocks.first()))
			assertThat(registry.getOwner(blocks.first())).isNull()
		}

	@Test
	fun `unregister does not leave a stale deferral entry for a reused block (Issue 1050)`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		val entry = (blocks.first().reservedFrom as DynamicInOut).inSemaphore
		entry.signal = Signal.FREE

		// Defer the head, then unregister train1 wholesale -- as journey-completion cleanup
		// does -- while the head's booking window is still open.
		service.releasePathDetailed("train1")
		service.unregister("train1")

		// Reserve the freed head for a different train, then drop its own proceed aspect so
		// train2 has no approach-lock reason of its own: no occupant, and no signal allowing
		// entry. The head can then be reported as deferred ONLY through a leaked
		// approachLockDeferredUntil entry left over from train1's now-gone reservation
		// (Copilot review round, PR #1080).
		val reserved = assertReservationSuccess(service.reservePath("train2", inOut1, inOut2)).reservedBlocks
		val head = reserved.first()
		assertThat(head).isEqualTo(blocks.first())
		(head.reservedFrom as DynamicInOut).inSemaphore.signal = Signal.STOP

		val release = service.releasePathDetailed("train2")

		assertThat(release.deferred).isEmpty()
		assertThat(release.released.toSet()).isEqualTo(reserved.toSet())
	}
}
