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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Switch protection of the partial release (`releaseAllBut`, Issue #1050) across its two
 * directions (PR #1194 review round):
 *
 * - a switch inside the KEPT head stays locked and owned, however the PathInfo trim cuts the
 *   freed tail off behind it;
 * - a switch inside the FREED tail is reclaimed (unlocked, unowned) by the per-block registry
 *   reclaim, however the head keeps running.
 *
 * `vyhybna.xml` cannot pin either direction: its trimmable cut sits next to an InOut, so no
 * keep head spans a switch, and every keep head that does span `vyhybna`'s switches ends at one
 * (the trim refuses, so the release defers wholesale instead of freeing anything). The
 * `two-switch-line.xml` fixture places one switch inside each direction; the straight line and
 * the uniform 24.0 m/s keep the expected lock states manually traceable.
 */
class RouteReleaserPartialReleaseTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var environment: SimulationEnvironment
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService
	private lateinit var inOutA: DynamicInOut
	private lateinit var inOutB: DynamicInOut

	@BeforeEach
	fun setUp() {
		simulationContext = loadTwoSwitchLineContext().tracked()
		environment = simulationContext
		service = simulationContext.getRoutingServices().getPathReservationService()
		registry = simulationContext.scope.get()

		val inOuts = simulationContext.getInOuts().map(simulationContext::toDynamic)
		inOutA = inOuts.filterIsInstance<DynamicInOut>().single { it.name == "A" }
		inOutB = inOuts.filterIsInstance<DynamicInOut>().single { it.name == "B" }
	}

	@Test
	fun `a partial release keeps the head's switch locked and reclaims the tail's one`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOutA, inOutB)).reservedBlocks
		assertThat(blocks).hasSize(6)
		// Span the first three blocks: the kept head ends at the semaphore s3, covers swX on
		// both sides and releases the tail past s3, whose own switch swY goes stale.
		val occupant = FakeTrackOccupant("train1")
		blocks.take(3).forEach { it.enter(occupant) }
		// Cap the approach lock at the head's frontier (s3 shows proceed after the reservation,
		// which would drag k4 in and leave nothing to free): the s3 aspect back to STOP keeps
		// k4 RESERVED but unapproachable.
		semaphoreByName("s3").signal = Signal.STOP
		val headSwitch = switchByName("swX")
		val tailSwitch = switchByName("swY")

		val release = service.releasePathDetailed("train1")

		assertThat(headSwitch.locked, "the switch inside the kept head").isTrue()
		assertThat(registry.getSwitches("train1"), "switches owned after the partial release").contains(headSwitch)
		assertThat(tailSwitch.locked, "the switch inside the freed tail").isFalse()
		assertThat(release.released.toSet(), "the freed tail").isEqualTo(blocks.drop(3).toSet())
		assertThat(release.deferred.toSet(), "the kept head").isEqualTo(blocks.take(3).toSet())
		blocks.take(3).forEach { block ->
			assertThat(block.getState(), "state of kept $block").isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(registry.getOwner(block), "owner of kept $block").isEqualTo("train1")
		}
		blocks.drop(3).forEach { block ->
			assertThat(block.getState(), "state of freed $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of freed $block").isNull()
		}
	}

	@Test
	fun `a partial release survives one block's failed release event and frees the rest of the tail`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOutA, inOutB)).reservedBlocks
		val occupant = FakeTrackOccupant("train1")
		blocks.take(3).forEach { it.enter(occupant) }
		semaphoreByName("s3").signal = Signal.STOP
		// The funnel emits one BlockReleased per freed block; the first listener throw aborts
		// only its own dropFreedBlock (caught per block, after that block's registry cleanup),
		// so the release reports it as not released, yet continues with the rest.
		environment.addBlockOccupancyListener(ThrowingOnceReleaseListener())

		val release = service.releasePathDetailed("train1")

		val failedBlock = blocks[3]
		assertThat(release.released.toSet(), "the blocks freed after the throw")
			.isEqualTo(blocks.drop(4).toSet())
		assertThat(failedBlock.getState(), "state of the block whose event threw")
			.isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.getOwner(failedBlock), "the registry cleanup still ran").isNull()
	}

	/** The dynamic semaphore named [name] in the loaded grid. */
	private fun semaphoreByName(name: String) =
		simulationContext.cellsOfType<DynamicRailSemaphore>().first { it.name == name }

	/** The dynamic rail switch named [name]; switches are not NodeCells, so no generic lookup covers them. */
	private fun switchByName(name: String) =
		simulationContext.cellsOfType<DynamicRailSwitch>().first { it.name == name }

	private fun loadTwoSwitchLineContext(): DefaultSimulationContext {
		val editingContext =
			javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/two-switch-line.xml")!!.use { xmlStream ->
				Util.assertInstanceOf<EditingContext>(editingContextFactory.createContext(xmlStream))
			}
		return editingContext.use { editing ->
			Util.assertInstanceOf<DefaultSimulationContext>(simulationContextFactory.createContext(editing))
		}
	}

	/** A [BlockOccupancyListener] whose first BlockReleased notification throws, the later ones pass. */
	private class ThrowingOnceReleaseListener : BlockOccupancyListener {
		private var emitted = 0

		override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
			emitted++
			if (emitted == 1) throw IllegalStateException("release listener failure (test)")
		}
	}
}