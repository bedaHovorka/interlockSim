/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for the SP3 [CollisionWarning.BlockEntryViolation] reservation-mismatch
 * detection rule in [DefaultCollisionDetectionService] (#613).
 *
 * Drives [DefaultCollisionDetectionService.handleBlockEvent] directly with synthetic
 * [BlockEvent.OccupancySet] events against a real [DynamicTrackBlock] taken from a
 * transformed test network, so the rule is exercised without a full simulation run.
 * Runs in `commonTest` under both JVM and native targets.
 *
 * Mandated coverage (#613 review):
 * - Fire: block reserved for A, `OccupancySet` for B → [CollisionWarning.BlockEntryViolation].
 * - No-fire: block reserved for A, `OccupancySet` for A → no warning.
 * - No-fire: block unreserved, `OccupancySet` for A → no warning (SP1 semantics confirmed).
 * - Multi-subscriber delivery with listener isolation.
 *
 * @since Issue #613 (Goal 3 SP3)
 */
class BlockEntryViolationDetectionTest : KoinComponent {
	/** A [PauseController] that counts [requestPause] calls. */
	private class CountingPauseController : PauseController {
		var calls: Int = 0
			private set

		override fun requestPause() {
			calls++
		}
	}

	/** Minimal [TrackOccupant] carrying only a name, for synthetic [BlockEvent]s. */
	private class NamedOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	/** A real [DynamicTrackBlock] from a transformed test network. */
	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	/** Reserve [block] for [trainId] through the real reservation API (FREE → RESERVED). */
	private fun reserveFor(
		block: DynamicTrackBlock,
		trainId: String
	) {
		block.setUpPath(block.ends().first() as DynamicPathSeparator, trainId)
	}

	@Test
	fun `occupancy by a different train than the reservation emits BlockEntryViolation`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		val block = realBlock()
		reserveFor(block, "train-A")

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("train-B"), time = 3.5))

		assertThat(received).hasSize(1)
		val warning = received.single()
		assertThat(warning).isInstanceOf(CollisionWarning.BlockEntryViolation::class)
		warning as CollisionWarning.BlockEntryViolation
		assertThat(warning.trainId).isEqualTo("train-B")
		assertThat(warning.reservedForAtDetection).isEqualTo("train-A")
		assertThat(warning.block).isEqualTo(block)
		assertThat(warning.time).isEqualTo(3.5)
		assertThat(pauseController.calls).isEqualTo(1)
	}

	@Test
	fun `occupancy by the reserving train itself emits no warning`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		val block = realBlock()
		reserveFor(block, "train-A")

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("train-A"), time = 1.0))

		assertThat(received).isEmpty()
		assertThat(pauseController.calls).isEqualTo(0)
	}

	@Test
	fun `occupancy of an unreserved block emits no warning`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		val block = realBlock()

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("train-A"), time = 1.0))

		assertThat(received).isEmpty()
		assertThat(pauseController.calls).isEqualTo(0)
	}

	@Test
	fun `all subscribers receive the BlockEntryViolation even when one listener throws`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<String>()

		service.onCollisionWarning { received.add("first") }
		service.onCollisionWarning { throw IllegalStateException("boom") }
		service.onCollisionWarning { received.add("third") }

		val block = realBlock()
		reserveFor(block, "train-A")

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("train-B"), time = 2.0))

		// Delivery is in registration order and isolated from the throwing listener...
		assertThat(received).containsExactly("first", "third")
		// ...and the pause is still requested exactly once.
		assertThat(pauseController.calls).isEqualTo(1)
	}
}
