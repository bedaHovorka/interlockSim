/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * First slice of Goal 1: Multi-Train Simulation scenarios for [MultiTrainLoop].
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Goal 1 first-slice scenarios: deterministic multi-train dispatcher using
 * kDisco [cz.hovorka.kdisco.Resource] setup-time gating and
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService] ownership.
 */
@Tag("integration-test")
@DisplayName("MultiTrainLoop — deterministic multi-train dispatcher (Goal 1 slice)")
class MultiTrainLoopTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private fun loadLinearContext(): DefaultSimulationContext {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	private fun loadYJunctionContext(): DefaultSimulationContext {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation() as DefaultSimulationContext
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	private fun spec(
		inName: String,
		outName: String,
		inTime: Double,
		length: Double = 20.0
	): MultiTrainLoop.TrainSpec =
		MultiTrainLoop.TrainSpec(
			inName = inName,
			outName = outName,
			inTime = inTime,
			length = length
		)

	/** Scenario 1: three trains injected sequentially all complete their journey. */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `three trains on linear track all enter and exit`() {
		val ctx = loadLinearContext()
		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						spec("A", "B", inTime = 0.0),
						spec("A", "B", inTime = 2.0),
						spec("A", "B", inTime = 4.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 1 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()} " +
				"occupiedResources=${process.getOccupiedResourceCount()}"
		}

		assertThat(process.getTrainsEntered()).isEqualTo(3)
		assertThat(process.getTrainsExited()).isEqualTo(3)
		assertThat(process.getMaxConcurrentTrains()).isGreaterThanOrEqualTo(2)
	}

	/** Scenario 2: trains with fully overlapping paths queue safely and complete. */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `conflicting paths queue without collision`() {
		val ctx = loadLinearContext()
		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						spec("A", "B", inTime = 0.0),
						spec("A", "B", inTime = 1.0),
						spec("A", "B", inTime = 2.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 2 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()}"
		}

		assertThat(process.getTrainsEntered()).isEqualTo(3)
		assertThat(process.getTrainsExited()).isEqualTo(3)
		// All three are approved concurrently even though full-path reservation
		// serializes their movement through the shared blocks.
		assertThat(process.getMaxConcurrentTrains()).isGreaterThanOrEqualTo(2)
	}

	/** Scenario 3: Y-junction with two trains heading to different exits. */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `two trains on Y junction reach different exits`() {
		val ctx = loadYJunctionContext()

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						spec("Entry", "ExitMain", inTime = 0.0),
						spec("Entry", "ExitBranch", inTime = 1.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 3 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()}"
		}

		assertThat(process.getTrainsEntered()).isEqualTo(2)
		assertThat(process.getTrainsExited()).isEqualTo(2)
		assertThat(process.getMaxConcurrentTrains()).isGreaterThanOrEqualTo(2)
		assertThat(process.getOccupiedResourceCount()).isZero()
	}
}
