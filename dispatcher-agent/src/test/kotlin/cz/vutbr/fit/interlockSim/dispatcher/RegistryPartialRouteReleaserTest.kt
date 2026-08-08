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
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

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
class RegistryPartialRouteReleaserTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	private lateinit var context: DefaultSimulationContext
	private lateinit var zA: DynamicRailSemaphore
	private lateinit var doA1: DynamicRailSemaphore

	private val trainId = "Train #1"

	@BeforeEach
	fun setUp() {
		startKoin { modules(dispatcherAgentTestModule) }
		context =
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = xmlContextFactory.createContext(stream) as EditingContext
				DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
			}
		zA = elementAt(14, 8)
		doA1 = elementAt(16, 8)
	}

	@AfterEach
	fun tearDown() {
		stopKoin()
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
		val result = context.getRoutingServices().getPathReservationService().reservePath(trainId, zA, doA1)
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
	 * zA->doA1 hop cannot reach: that route never passes through a shared boundary separator, and
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
		val (_, tail) = reserveAndOccupyHead()
		val registry = registry()
		val tailIds = tail.map { BlockIdentity.stableBlockId(it) }

		val released = releaser().releaseUntravelledTail(trainId, tailIds)

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
		val (_, tail) = reserveAndOccupyHead()
		val entrySignals = tail.mapNotNull { it.reservedFrom as? DynamicRailSemaphore }

		val released = releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) })

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

		val released = releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) })

		assertThat(released, "released ids").isNotEmpty()
		litInsideTail.forEach { semaphore ->
			assertThat(semaphore.signal.name, "aspect of intermediate semaphore ${semaphore.name} after release")
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

		val released = releaser().releaseUntravelledTail(trainId, tail.map { BlockIdentity.stableBlockId(it) })

		assertThat(released, "released ids").isNotEmpty()
		assertThat(inOutA.inSemaphore.signal.name, "aspect of InOut A's inSemaphore after tail release")
			.isEqualTo("STOP")
	}

	/**
	 * A train that occupies nothing is an ordinary abandoned route. The whole-route path handles
	 * that correctly and keeps its own counters; doing it here would bypass them silently.
	 */
	@Test
	@DisplayName("a train occupying none of its route is refused — that is the whole-route case")
	fun refusesWhenNothingIsOccupied() {
		context.getRoutingServices().getPathReservationService().reservePath(trainId, zA, doA1)
		val tailIds = heldBlocks().map { BlockIdentity.stableBlockId(it) }

		val released = releaser().releaseUntravelledTail(trainId, tailIds)

		assertThat(released, "released ids").isEmpty()
		assertThat(heldBlocks(), "blocks still held").isNotEmpty()
	}

	@Test
	@DisplayName("a train holding nothing releases nothing")
	fun unknownTrainReleasesNothing() {
		assertThat(releaser().releaseUntravelledTail("Ghost", listOf("kA")), "released ids").isEmpty()
	}

	/**
	 * Only blocks the sweeper offered may go. The sweeper's reading is one control step old, so the
	 * releaser must not widen the set on its own initiative.
	 */
	@Test
	@DisplayName("blocks outside the offered set are left alone")
	fun onlyOfferedBlocksAreReleased() {
		val (_, tail) = reserveAndOccupyHead()

		val released = releaser().releaseUntravelledTail(trainId, emptyList())

		assertThat(released, "released ids").isEmpty()
		tail.forEach { block ->
			assertThat(block.getState(), "state of an un-offered block")
				.isEqualTo(TrackFacility.State.RESERVED)
		}
	}
}
