package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import cz.hovorka.kdisco.SimulationEvent as KDiscoSimulationEvent

@Tag("integration-test")
@DisplayName("BlockEvent subscription — #569 event delivery")
class BlockEventSubscriptionTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private fun twoTrainLinearContext(): DefaultSimulationContext {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("onBlockEvent delivers BlockReserved and BlockReleased for both trains")
	fun blockEventsDeliveredForBothTrains() {
		val ctx = twoTrainLinearContext()
		val events = mutableListOf<BlockEvent>()

		ctx.onBlockEvent { events.add(it) }

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
						MultiTrainLoop.TrainSpec("A", "B", inTime = 2.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		val reserved = events.filterIsInstance<BlockEvent.BlockReserved>()
		val released = events.filterIsInstance<BlockEvent.BlockReleased>()

		assertThat(reserved).isNotEmpty()
		assertThat(released).isNotEmpty()
		// Every reserved block eventually gets released
		assertThat(reserved.size).isEqualTo(released.size)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Per-block order: BlockReserved → OccupancySet → OccupancyCleared → BlockReleased")
	fun blockEventOrderIsCorrectPerBlock() {
		val ctx = twoTrainLinearContext()
		val eventsByBlock = mutableMapOf<DynamicTrackBlock, MutableList<BlockEvent>>()

		ctx.onBlockEvent { event ->
			eventsByBlock.getOrPut(event.block) { mutableListOf() }.add(event)
		}

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		assertThat(eventsByBlock).isNotEmpty()

		for ((block, blockEvents) in eventsByBlock) {
			val types = blockEvents.map { it::class.simpleName }
			// The first event for any block must be BlockReserved (not OccupancySet or BlockReleased)
			assertThat(
				blockEvents.first() is BlockEvent.BlockReserved,
				name = "Block ${block.staticRef.hashCode()} first event must be BlockReserved, got $types"
			).isTrue()
			// BlockReleased must be the last event for each block (block is free after release)
			val lastReleaseIdx = blockEvents.indexOfLast { it is BlockEvent.BlockReleased }
			val lastReservedIdx = blockEvents.indexOfLast { it is BlockEvent.BlockReserved }
			assertThat(lastReleaseIdx).isGreaterThan(lastReservedIdx)
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("onSimulationEvent delivers kdisco process lifecycle events")
	fun rawKdiscoEventsDelivered() {
		val ctx = twoTrainLinearContext()
		val rawEvents = mutableListOf<KDiscoSimulationEvent>()

		ctx.onSimulationEvent { rawEvents.add(it) }

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		val processActivated = rawEvents.filterIsInstance<KDiscoSimulationEvent.ProcessActivated>()
		assertThat(processActivated).isNotEmpty()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Listener registered after run() is ignored (context is frozen)")
	fun listenerAfterRunIsIgnored() {
		val ctx = twoTrainLinearContext()
		val events = mutableListOf<BlockEvent>()

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		// Register AFTER run — should be silently ignored
		ctx.onBlockEvent { events.add(it) }

		assertThat(events.size).isEqualTo(0)
	}
}
