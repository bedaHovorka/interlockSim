/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.runSimpleLinearTrackScenario
import cz.vutbr.fit.interlockSim.testutil.trainSpecAB
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration tests for [InOutWorker.iteration] — verifies the path reservation
 * branches that cannot be reached from unit tests alone.
 *
 * Uses [SimpleLinearTrackTestProcess] as the coordinator so InOutWorker handles
 * all path reservation itself (no onTrainCreated pre-reservation), exercising
 * the Success branch of `iteration()` end-to-end.
 */
@Tag("integration-test")
@DisplayName("InOutWorker.iteration — path reservation branches")
class InOutWorkerIterationTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	/**
	 * One `occupied` event with the entry queue state at the moment it fired. A train leaves the queue
	 * only after its front has moved, so [trainsLeft] counts trains with `totalDistance > 0`.
	 */
	private data class OccupiedEvent(
		val occupied: Boolean,
		val queueNonEmpty: Boolean,
		val trainsLeft: Int
	)

	private fun loadLinearContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true)
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("Single train: iteration() reserves path and clears queue (Success path)")
	fun `iteration reserves path and clears queue on success`() {
		val ctx = loadLinearContext()
		val process =
			runSimpleLinearTrackScenario(ctx, endTime = 50L, trainSpecs = listOf(trainSpecAB(outTime = 20.0)))
				.process

		assertThat(process.getTrainsEntered()).isEqualTo(1)
		assertThat(process.getAllBlockTransitions().values.sum()).isGreaterThan(0)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("Two trains: second train waits while first occupies path, then both complete")
	fun `iteration processes two sequential trains both completing successfully`() {
		val ctx = loadLinearContext()
		val entry = ctx.getInOuts().first { it.name == "A" }
		// The renderer reads `occupied` from the grid cell, so the InOut listened on here must be that
		// very object, not an equal wrapper (equals compares the wrapped static InOut).
		val gridCell = ctx.getRailWayNetGrid().single { entry == it.value }.value
		assertThat(gridCell).isSameInstanceAs(entry)
		val trains = mutableListOf<Train>()
		// The entry queue is read while the event fires (listeners run synchronously).
		val occupiedEvents = mutableListOf<OccupiedEvent>()
		entry.addPropertyChangeListener { event ->
			occupiedEvents +=
				OccupiedEvent(
					occupied = event.newValue as Boolean,
					queueNonEmpty = !ctx.getWorkerFor(entry).getQueqe().empty(),
					trainsLeft = trains.count { it.totalDistance > 0.0 }
				)
		}
		val process =
			runSimpleLinearTrackScenario(
				ctx,
				endTime = 100L,
				trainSpecs =
					listOf(
						trainSpecAB(inTime = 1.0, outTime = 40.0),
						trainSpecAB(inTime = 2.0, outTime = 80.0)
					),
				onTrainCreated = { trains += it }
			).process

		// Both trains must have been approved and entered the network.
		assertThat(process.getTrainsEntered()).isEqualTo(2)
		// Combined block transitions confirm both trains made forward progress.
		assertThat(process.getAllBlockTransitions().values.sum()).isGreaterThan(0)
		// Issue #1008: the entry InOut turns occupied once, when the first train arrives, stays
		// occupied when the first train leaves (the second one still waits in the queue), and
		// turns free only when the last train has left. Every event matches the queue at the moment
		// it fires: listeners never see `occupied == true` with an empty queue.
		assertThat(occupiedEvents).containsExactly(
			OccupiedEvent(occupied = true, queueNonEmpty = true, trainsLeft = 0),
			OccupiedEvent(occupied = false, queueNonEmpty = false, trainsLeft = 2)
		)
		assertThat(entry.occupied).isFalse()
	}
}
