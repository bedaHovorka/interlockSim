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
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.hovorka.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
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

	/**
	 * Regression test for the code-review finding that the "auto-halt on violation" feature
	 * had zero effect in production: [MultiTrainLoop] never registered a halt callback for
	 * its dynamically-generated trains, so [DefaultCollisionDetectionService.autoHaltTrainOnViolation]
	 * silently did nothing outside hand-written tests that wired `registerHaltCallback` manually.
	 *
	 * Predicts the exact name [MultiTrainLoop]'s `DeterministicGenerator` will assign to the
	 * train it creates by reading [Train.name]'s shared counter via a throwaway probe [Train]
	 * constructed immediately beforehand (no other train is created in between). A local
	 * subclass then emits a [CollisionWarning.BlockEntryViolation] for that exact name as soon
	 * as the real train has been generated, and asserts its velocity became `0.0` — proving
	 * [MultiTrainLoop] registered `train::requestHalt` for it automatically, without any
	 * external/manual wiring.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("MultiTrainLoop auto-registers a halt callback for each dynamically generated train")
	fun `dynamically generated train has its halt callback auto-registered`() {
		val ctx = loadLinearContext()

		val detectionService =
			ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		detectionService.autoHaltTrainOnViolation = true
		detectionService.autoPauseOnCritical = false // prevent pause from blocking the headless run

		// Predict the next train name: Train's counter is a simple shared incrementing field,
		// so a throwaway probe constructed here reveals the number MultiTrainLoop's generator
		// will assign to the very next Train it creates.
		val inOuts = ctx.getInOuts()
		val probeTimetable =
			Timetable(
				inOuts.first { it.name == "A" },
				inOuts.first { it.name == "B" },
				Time(0.0),
				Time(1.0),
				1.0
			)
		val probeNumber = Train(ctx, probeTimetable).name.substringAfter("Train #").toInt()
		val expectedTrainName = "Train #${probeNumber + 1}"

		val block: DynamicTrackBlock = ctx.getGraph().values().first()

		class ProbingMultiTrainLoop :
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs = listOf(spec("A", "B", inTime = 0.0)),
				maxConcurrentTrains = 10
			) {
			var violationEmitted: Boolean = false
				private set

			// Captured synchronously, in the same call, immediately after the halt callback
			// fires — Train's own Motor is a separate Continuous() process that Train.stop()
			// does not stop, so it keeps chasing its target speed on later ticks. Reading the
			// snapshot after ctx.run() returns would observe a re-accelerated, non-zero velocity.
			var velocityImmediatelyAfterHalt: Double? = null
				private set

			override suspend fun iteration() {
				super.iteration()
				// Wait until the train is actually approved (present in approvedTrains, i.e.
				// getTrainSnapshot resolves) rather than just generated — getTrainsEntered()
				// increments as soon as the train is placed into the unapproved queue, one tick
				// before approveTrains() promotes it, which is too early for getTrainSnapshot.
				if (!violationEmitted && getTrainSnapshot(expectedTrainName) != null) {
					violationEmitted = true
					emitCustom(
						CollisionWarning.BlockEntryViolation(
							trainId = expectedTrainName,
							block = block,
							time = time()
						)
					)
					velocityImmediatelyAfterHalt = getTrainSnapshot(expectedTrainName)?.velocity
					env.stop()
					terminate()
				}
			}
		}

		val process = ProbingMultiTrainLoop()
		ctx.setMainProcess(process)
		ctx.run()

		assertThat(process.violationEmitted).isTrue()
		assertThat(process.velocityImmediatelyAfterHalt).isEqualTo(0.0)
	}
}
