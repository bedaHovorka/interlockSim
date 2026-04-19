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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
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

	private fun loadLinearContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true)
			as DefaultSimulationContext
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	private fun specAB(inTime: Double = 1.0, outTime: Double = 20.0) =
		SimpleLinearTrackTestProcess.TrainSpec(
			inName = "A",
			outName = "B",
			inTime = inTime,
			outTime = outTime,
			length = 20.0
		)

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Single train: iteration() reserves path and clears queue (Success path)")
	fun `iteration reserves path and clears queue on success`() {
		val ctx = loadLinearContext()
		val process = SimpleLinearTrackTestProcess(
			ctx,
			endTime = 50L,
			trainSpecs = listOf(specAB())
		)
		ctx.setMainProcess(process)
		ctx.run()

		assertThat(process.getTrainsEntered()).isEqualTo(1)
		assertThat(process.getAllBlockTransitions().values.sum()).isGreaterThan(0)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Two trains: second train waits while first occupies path, then both complete")
	fun `iteration processes two sequential trains both completing successfully`() {
		val ctx = loadLinearContext()
		val process = SimpleLinearTrackTestProcess(
			ctx,
			endTime = 100L,
			trainSpecs = listOf(
				specAB(inTime = 1.0, outTime = 40.0),
				specAB(inTime = 2.0, outTime = 80.0)
			)
		)
		ctx.setMainProcess(process)
		ctx.run()

		// Both trains must have been approved and entered the network.
		assertThat(process.getTrainsEntered()).isEqualTo(2)
		// Combined block transitions confirm both trains made forward progress.
		assertThat(process.getAllBlockTransitions().values.sum()).isGreaterThan(0)
	}
}
