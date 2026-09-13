/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Safety tests for [RegistryPartialRouteReleaser] against a **real** reserved path on
 * `vyhybna.xml` (Issue #847 round 4, finding R4-3).
 *
 * ## Why against real track objects
 *
 * The whole risk of a partial release is interlocking state, and interlocking state lives in the
 * `core/` domain objects — block `state`/`reservedFrom`/`trainName`, semaphore aspects, switch
 * locks, registry ownership. A mock-based test would assert that the class calls the methods this
 * class happens to call, which proves nothing about whether the resulting state is safe.
 *
 * The invariants asserted here are the ones round 3 identified as load-bearing when it declined to
 * implement this: the occupied block keeps everything, the released blocks are genuinely free and
 * unowned, and nothing is left reachable behind a permissive signal.
 */
@DisplayName("RegistryPartialRouteReleaser — releasing a tail must leave a safe interlocking state")
class RegistryPartialRouteReleaserTest : DispatcherKoinTestBase() {
	private lateinit var context: DefaultSimulationContext
	private lateinit var zA: DynamicRailSemaphore
	private lateinit var doB1: DynamicRailSemaphore

	private val trainId = "Train #1"

	@BeforeEach
	fun setUp() {
		context = TestFixtures.newShuntingSimulationContext().tracked()
		zA = elementAt(14, 8)
		doB1 = elementAt(25, 8)
	}

	private fun registry(): PathReservationRegistry = context.scope.get<PathReservationRegistry>()

	private inline fun <reified T : Cell> elementAt(
		x: Int,
		y: Int
	): T {
		val cell = context.getRailWayNetGrid()[Point(x, y)] ?: error("No cell at ($x, $y)")
		return Util.assertInstanceOf(cell)
	}

	private fun releaser(): RegistryPartialRouteReleaser =
		RegistryPartialRouteReleaser(
			registry = registry(),
			pathReservationService = context.getRoutingServices().getPathReservationService()
		)

	private fun heldBlocks(): List<DynamicTrackBlock> = registry().getBlocks(trainId)

	/**
	 * Reserves a real path and then marks its first block occupied, reproducing the stranded shape
	 * round 3 measured: the train stands on one block of its route and holds the rest un-travelled.
	 *
	 * @return the occupied block and the un-travelled tail.
	 */
	private fun reserveAndOccupyHead(): Pair<DynamicTrackBlock, List<DynamicTrackBlock>> {
		val result = context.getRoutingServices().getPathReservationService().reservePath(trainId, zA, doB1)
		assertThat(result, "reservePath result").isNotNull()
		val blocks = heldBlocks()
		assertThat(blocks, "blocks reserved").isNotEmpty()
		val head = blocks.first()
		// Drive the head through the real entry transition (RESERVED -> OCCUPIED, occupant set), so
		// the state the releaser inspects is the state a real train would leave behind rather than a
		// hand-forced field.
		head.enter(mockk<TrackOccupant>(relaxed = true) { every { name } returns trainId })
		return head to blocks.drop(1)
	}

	private fun inOutNamed(name: String): DynamicInOut = context.getInOuts().single { it.name == name }

	/**
	 * Reserves a LONGER real route (InOut A -> InOut B, spanning at least one intermediate
	 * semaphore) and marks its first block occupied, mirroring [reserveAndOccupyHead] but long
	 * enough to exercise the intermediate-semaphore and InOut-inSemaphore reset paths a single
	 * zA->doB1 route cannot reach: that route lights no intermediate signal, and
	 * never starts at an InOut.
	 *
	 * @return the occupied block and the un-travelled tail.
	 */
	private fun reserveAndOccupyLongRoute(): Pair<DynamicTrackBlock, List<DynamicTrackBlock>> {
		val pathReservationService = context.getRoutingServices().getPathReservationService()
		val result = pathReservationService.reservePath(trainId, inOutNamed("A"), inOutNamed("B"))
		assertThat(result, "reservePath result").isNotNull()
		val blocks = heldBlocks()
		assertThat(blocks, "blocks reserved").isNotEmpty()
		val head = blocks.first()
		head.enter(mockk<TrackOccupant>(relaxed = true) { every { name } returns trainId })
		return head to blocks.drop(1)
	}

	@Test
	@DisplayName("the block the train stands on keeps its state and its registry ownership")
	fun occupiedBlockIsUntouched() {
		val (head, tail) = reserveAndOccupyHead()
		val registry = registry()

		releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) })

		assertThat(head.getState(), "state of the block the train stands on")
			.isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(head.getTrackOccupant(), "occupant of the block the train stands on").isNotNull()
		assertThat(registry.getOwner(head), "occupied block owner").isEqualTo(trainId)
		assertThat(heldBlocks().map { BlockIdentity.stableBlockId(it) }, "blocks still held")
			.contains(BlockIdentity.stableBlockId(head))
	}

	@Test
	@DisplayName("every released block ends up FREE, unowned, and out of the registry")
	fun releasedBlocksAreFreeAndUnowned() {
		// The long route: its head meets the tail at the signal zA. The short zA -> doB1 route meets it
		// at the switch vA, where a release is refused (Issue #1063).
		val (_, tail) = reserveAndOccupyLongRoute()
		val registry = registry()

		val released = releaseLongRouteTailFully(tail).released

		assertThat(released, "released ids").isNotEmpty()
		val stillHeld = heldBlocks().map { BlockIdentity.stableBlockId(it) }.toSet()
		released.forEach { id ->
			val block = tail.first { BlockIdentity.stableBlockId(it) == id }
			assertThat(block.getState(), "state of released block $id").isEqualTo(TrackFacility.State.FREE)
			assertThat(block.trainName, "trainName of released block $id").isEqualTo(null)
			assertThat(registry.getOwner(block), "registry owner of released block $id").isEqualTo(null)
			assertThat(stillHeld.contains(id), "released block $id still registered").isEqualTo(false)
		}
	}

	/**
	 * The invariant that makes the whole operation defensible. `cancelPathSetup` on a *block* frees
	 * the block but does not touch the semaphore that authorised entry to it, so a released block
	 * could otherwise sit behind a still-permissive signal — and the next `request_route` could put
	 * a second train into a block the first is signalled into.
	 */
	@Test
	@DisplayName("no released block is left reachable through a permissive signal")
	fun releasedBlocksAreNotSignalledInto() {
		// The long route: its head meets the tail at the signal zA, so the tail can be released.
		val (_, tail) = reserveAndOccupyLongRoute()
		val entrySignals =
			tail
				.flatMap { it.ends().toList() }
				.filterIsInstance<DynamicRailSemaphore>()
				.distinct()
				.filter { it.signal.isAllowing() }
		// Guard: if no signal guarded the tail, the loop below would pass vacuously.
		assertThat(entrySignals, "signals guarding the tail before release").isNotEmpty()

		val released = releaseLongRouteTailFully(tail).released

		assertThat(released, "released ids").isNotEmpty()
		entrySignals.forEach { semaphore ->
			assertThat(semaphore.signal.name, "aspect of a signal guarding a released block")
				.isEqualTo("STOP")
		}
	}

	/**
	 * `tryAtomicReservation` sets EVERY block's `reservedFrom` to the route's START separator, not
	 * to the locally adjacent one — so the old `reservedFrom as? DynamicRailSemaphore` cast only
	 * ever found the START itself, never an INTERMEDIATE semaphore between two blocks the same
	 * reservation owns (e.g. `doB1`/`doB2` on an InOut A -> InOut B route). Those stayed lit with
	 * no route behind them.
	 *
	 * @since Issue #893 (phase alpha, task A3)
	 */
	@Test
	@DisplayName("tail release returns every semaphore inside the tail to STOP")
	fun tailReleaseResetsIntermediateSemaphores() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val litInsideTail =
			tail
				.flatMap { it.ends().toList() }
				.filterIsInstance<DynamicRailSemaphore>()
				.distinct()
				.filter { it.signal.isAllowing() }
		// Guard: if nothing was lit inside the tail, the loop below would pass vacuously.
		assertThat(litInsideTail, "semaphores lit inside the tail before release").isNotEmpty()

		val tailIds = tail.map { BlockIdentity.stableBlockId(it) }

		// Approach lock (Issue #1025): the head's exit signal shows proceed toward the tail, so the
		// first call drops every governing signal and defers the physical release.
		val first = releaser().releaseUntravelledTail(trainId, tailIds)
		assertThat(first.deferred, "first call deferred by the standing proceed aspect").isTrue()
		assertThat(first.released, "released ids on the deferred call").isEmpty()
		litInsideTail.forEach { semaphore ->
			assertThat(semaphore.signal.name, "aspect of intermediate semaphore ${semaphore.name} after release")
				.isEqualTo("STOP")
		}

		// With the signals at STOP the next call releases the tail, and nothing is lit again.
		val second = releaser().releaseUntravelledTail(trainId, tailIds)
		assertThat(second.released, "released ids on the second call").isNotEmpty()
		litInsideTail.forEach { semaphore ->
			assertThat(semaphore.signal.name, "aspect of intermediate semaphore ${semaphore.name} after the release")
				.isEqualTo("STOP")
		}
	}

	/**
	 * When the route started at a `DynamicInOut`, `reservedFrom as? DynamicRailSemaphore` casts to
	 * `null` for every block — the InOut is not a semaphore — so the old code reset nothing at all,
	 * leaving the InOut's `inSemaphore` lit with no route behind it.
	 *
	 * @since Issue #893 (phase alpha, task A3)
	 */
	@Test
	@DisplayName("an InOut-started route's tail release resets its cleared inSemaphore")
	fun tailReleaseResetsInOutInSemaphore() {
		val inOutA = inOutNamed("A")
		val (_, tail) = reserveAndOccupyLongRoute()
		assertThat(inOutA.inSemaphore.signal.isAllowing(), "InOut A's inSemaphore lit before release").isTrue()

		val tailIds = tail.map { BlockIdentity.stableBlockId(it) }

		// Approach lock (Issue #1025): the head's exit signal zA shows proceed toward the tail, so
		// the first call only drops the signals and defers the physical release.
		val first = releaser().releaseUntravelledTail(trainId, tailIds)
		assertThat(first.deferred, "first call deferred by the standing proceed aspect").isTrue()
		assertThat(first.released, "released ids on the deferred call").isEmpty()
		assertThat(inOutA.inSemaphore.signal.name, "aspect of InOut A's inSemaphore after tail release")
			.isEqualTo("STOP")
		tail.forEach { block ->
			assertThat(block.getState(), "state of a deferred tail block").isEqualTo(TrackFacility.State.RESERVED)
		}

		// With every boundary signal at STOP the next call releases the tail.
		val second = releaser().releaseUntravelledTail(trainId, tailIds)
		assertThat(second.deferred, "second call deferred").isFalse()
		assertThat(second.released, "released ids on the second call").isNotEmpty()
	}

	/**
	 * A train that occupies nothing is an ordinary abandoned route. The whole-route path handles
	 * that correctly and keeps its own counters; doing it here would bypass them silently.
	 */
	@Test
	@DisplayName("a train occupying none of its route is refused — that is the whole-route case")
	fun refusesWhenNothingIsOccupied() {
		context.getRoutingServices().getPathReservationService().reservePath(trainId, zA, doB1)
		val tailIds = heldBlocks().map { BlockIdentity.stableBlockId(it) }

		val released = releaser().releaseUntravelledTail(trainId, tailIds).released

		assertThat(released, "released ids").isEmpty()
		assertThat(heldBlocks(), "blocks still held").isNotEmpty()
	}

	/**
	 * Releases the whole un-travelled tail of the long `A -> B` route. The first call is deferred by
	 * the approach lock (Issue #1025, the head's exit signal `zA` shows proceed), the second one
	 * physically releases the tail.
	 */
	private fun releaseLongRouteTailFully(tail: List<DynamicTrackBlock>): TailRelease {
		val tailIds = tail.map { BlockIdentity.stableBlockId(it) }
		val first = releaser().releaseUntravelledTail(trainId, tailIds)
		assertThat(first.deferred, "first call deferred by the standing proceed aspect").isTrue()
		return releaser().releaseUntravelledTail(trainId, tailIds)
	}

	/** The loop track the `A -> B` route did NOT take, named by its B-side facing signal. */
	private fun otherTrackEnd(tail: List<DynamicTrackBlock>): DynamicRailSemaphore {
		val usesTrack1 = tail.any { BlockIdentity.stableBlockId(it) == "k1" }
		return if (usesTrack1) elementAt(24, 9) else elementAt(25, 8)
	}

	/**
	 * Issue #1063 (the permanent stall of #1031): after the tail is reclaimed the stored PathInfo
	 * must end where the occupied head meets the released tail. An untrimmed PathInfo keeps
	 * `isPathExtendedBeyond` true for that signal, so the rule engine skips the train and the LLM
	 * is told "route already set" for the rest of the run.
	 */
	@Test
	@DisplayName("a fully released tail trims the stored PathInfo back to the head's exit signal")
	fun fullTailReleaseTrimsPathInfoToTheBoundary() {
		val (_, tail) = reserveAndOccupyLongRoute()

		val released = releaseLongRouteTailFully(tail).released

		assertThat(released.size, "released block count").isEqualTo(tail.size)
		val pathInfo = registry().getPathInfo(trainId)
		assertThat(pathInfo, "PathInfo after the tail release").isNotNull()
		assertThat(pathInfo!!.target, "PathInfo target after the tail release").isEqualTo(zA)
		assertThat(pathInfo.start, "PathInfo start after the tail release").isEqualTo(inOutNamed("A"))
		assertThat(registry().isPathExtendedBeyond(trainId, zA), "path still extends beyond zA").isFalse()
	}

	/**
	 * The #1031 shape: the train stands at the boundary and the dispatcher asks for a route onto the
	 * other loop track. Before the trim this aborted in `mergePathInfo` Step 0a (the new path starts
	 * at zA, the stored one still ends at B) and was denied as `AllPathsBlocked` forever.
	 */
	@Test
	@DisplayName("after the tail release a new route from the head's exit signal is granted")
	fun routeFromTheBoundaryIsGrantedAfterTheTailRelease() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val otherEnd = otherTrackEnd(tail)
		releaseLongRouteTailFully(tail)

		val result = context.getRoutingServices().getPathReservationService().reservePath(trainId, zA, otherEnd)

		assertThat(result, "re-request from zA to ${otherEnd.name}")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry().getPathInfo(trainId)!!.target, "PathInfo target after the new grant")
			.isEqualTo(otherEnd)
	}

	@Test
	@DisplayName("a release deferred by the approach lock leaves the PathInfo untouched")
	fun deferredReleaseDoesNotTrimPathInfo() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val before = registry().getPathInfo(trainId)

		val first = releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) })

		assertThat(first.deferred, "first call deferred").isTrue()
		assertThat(registry().getPathInfo(trainId), "PathInfo after a deferred release").isEqualTo(before)
	}

	/**
	 * Releasing only the block next to the head would leave still-held blocks beyond the boundary;
	 * trimming there would drop blocks the train still owns out of its PathInfo.
	 */
	@Test
	@DisplayName("a release that leaves held blocks beyond the boundary does not trim the PathInfo")
	fun partialTailReleaseDoesNotTrimPathInfo() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val nearest = tail.first { block -> zA in block.ends() }
		val nearestId = listOf(BlockIdentity.stableBlockId(nearest))
		releaser().releaseUntravelledTail(trainId, nearestId)
		val before = registry().getPathInfo(trainId)

		val second = releaser().releaseUntravelledTail(trainId, nearestId)

		assertThat(second.released, "released ids").isEqualTo(nearestId)
		assertThat(registry().getPathInfo(trainId), "PathInfo after a partial release").isEqualTo(before)
	}

	/**
	 * Issue #1063 (PR #1067 review): a train standing on `zA-vA` meets its tail at the switch `vA`.
	 * A PathInfo cannot end at a switch, so a release there would leave a PathInfo describing free
	 * track — the #1031 stall. The release must be refused before any block or signal changes.
	 */
	@Test
	@DisplayName("a tail that meets the occupied head at a switch is not released")
	fun tailBeyondASwitchBoundaryIsNotReleased() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val secondHead = tail.first { block -> zA in block.ends() }
		secondHead.enter(mockk<TrackOccupant>(relaxed = true) { every { name } returns trainId })
		val beyondSwitch = tail - secondHead
		val heldBefore = heldBlocks()
		val pathInfoBefore = registry().getPathInfo(trainId)
		val zAAspectBefore = zA.signal.name

		val result = releaser().releaseUntravelledTail(trainId, beyondSwitch.map { BlockIdentity.stableBlockId(it) })

		assertThat(result.released, "released ids").isEmpty()
		assertThat(result.deferred, "deferred").isFalse()
		assertThat(heldBlocks(), "blocks still held").isEqualTo(heldBefore)
		assertThat(beyondSwitch.map { it.getState() }.toSet(), "tail states")
			.isEqualTo(setOf(TrackFacility.State.RESERVED))
		assertThat(registry().getPathInfo(trainId), "PathInfo").isEqualTo(pathInfoBefore)
		assertThat(zA.signal.name, "zA aspect").isEqualTo(zAAspectBefore)
	}

	@Test
	@DisplayName("a train holding nothing releases nothing")
	fun unknownTrainReleasesNothing() {
		assertThat(releaser().releaseUntravelledTail("Ghost", listOf("kA")).released, "released ids").isEmpty()
	}

	/**
	 * Issue #1067 gap 1: the sweeper does not always offer the whole tail in one call, and a block
	 * next to the head may release before one further out does. The final block to come free is not
	 * itself adjacent to the occupied head — it is adjacent to a block already released earlier —
	 * so a boundary computed only from "what this call released" finds nothing there and used to
	 * skip the trim. It must still happen once no held block remains beyond the head, no matter
	 * which sweep frees the last one.
	 */
	@Test
	@DisplayName("a tail released over two sweeps still trims the PathInfo once the last block frees")
	fun tailReleasedOverTwoSweepsStillTrimsPathInfo() {
		val (_, tail) = reserveAndOccupyLongRoute()
		val nearest = tail.first { block -> zA in block.ends() }
		val rest = tail.filter { it != nearest }

		// Sweep 1 (deferred by the approach lock) + sweep 2 release only the block next to the head.
		releaseLongRouteTailFully(listOf(nearest))
		val afterNearestOnly = registry().getPathInfo(trainId)
		assertThat(afterNearestOnly!!.target, "PathInfo target with the far tail still held").isNotEqualTo(zA)

		// Sweep 3 releases the remainder, none of which touches the occupied head directly.
		val third = releaser().releaseUntravelledTail(trainId, rest.map { BlockIdentity.stableBlockId(it) })

		assertThat(third.released, "released ids on the final sweep")
			.isEqualTo(rest.map { BlockIdentity.stableBlockId(it) })
		val pathInfo = registry().getPathInfo(trainId)
		assertThat(pathInfo!!.target, "PathInfo target once the whole tail is gone").isEqualTo(zA)
		assertThat(registry().isPathExtendedBeyond(trainId, zA), "path still extends beyond zA").isFalse()
	}

	/**
	 * Issue #1067 gap 2: on `vyhybna.xml`'s `A -> B` direction, the intermediate signal `doA1` faces
	 * WEST — away from the train — so [DefaultPathReservationService] leaves it at STOP even while
	 * the granted route runs past it (the rear-facing rule behind Issue #566). If the occupied head
	 * ever ends up being exactly the short `vA`-`doA1` block, trimming the PathInfo to `doA1` would
	 * store an end no future route can start from: G4 (`startFacesTravelDirection`) refuses to
	 * reserve out of a rear-facing signal, reproducing the very stall this trim exists to fix.
	 */
	@Test
	@DisplayName("the trim refuses a boundary signal that faces away from the train")
	fun trimRefusesARearFacingBoundary() {
		val pathReservationService = context.getRoutingServices().getPathReservationService()
		val result = pathReservationService.reservePath(trainId, inOutNamed("A"), inOutNamed("B"))
		assertThat(result, "reservePath result").isInstanceOf<PathReservationService.ReservationResult.Success>()
		val vA = elementAt<DynamicRailSwitch>(15, 8)
		val doA1 = elementAt<DynamicRailSemaphore>(16, 8)
		val blocks = heldBlocks()
		val head = blocks.first { block -> vA in block.ends() && doA1 in block.ends() }
		// Simulate the train having already passed the blocks behind the head (A -> zA -> vA),
		// mirroring how Train.Tail frees a block once the train has left it.
		blocks.takeWhile { it != head }.forEach { behind ->
			behind.cancelPathSetup(requireNotNull(behind.reservedFrom) { "a reserved block has a reservedFrom" })
			assertThat(registry().unregisterBlock(trainId, behind), "unregister a passed block").isTrue()
		}
		head.enter(mockk<TrackOccupant>(relaxed = true) { every { name } returns trainId })
		val tail = heldBlocks().filter { it != head }
		assertThat(doA1.signal.name, "doA1's aspect on the granted route it faces away from").isEqualTo("STOP")
		val before = registry().getPathInfo(trainId)

		val released = releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) }).released

		assertThat(released.size, "released block count").isEqualTo(tail.size)
		assertThat(registry().getPathInfo(trainId), "PathInfo after the release").isEqualTo(before)
	}

	/**
	 * Only blocks the sweeper offered may go. The sweeper's reading is one control step old, so the
	 * releaser must not widen the set on its own initiative.
	 */
	@Test
	@DisplayName("blocks outside the offered set are left alone")
	fun onlyOfferedBlocksAreReleased() {
		val (_, tail) = reserveAndOccupyHead()

		val released = releaser().releaseUntravelledTail(trainId, emptyList()).released

		assertThat(released, "released ids").isEmpty()
		tail.forEach { block ->
			assertThat(block.getState(), "state of an un-offered block")
				.isEqualTo(TrackFacility.State.RESERVED)
		}
	}
}
