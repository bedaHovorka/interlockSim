/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Individual train lifecycle scenarios via SimpleLinearTrackTestProcess (issue #366).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import cz.hovorka.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
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
 * Issue #366 integration scenarios — individual train lifecycle tests running
 * against a linear-track topology with [SimpleLinearTrackTestProcess] as the
 * coordinator instead of the full [ShuntingLoop].
 *
 * Assertions use the counter getters exposed by the process (mirroring the
 * #365 instrumentation surface) — no log parsing.
 */
@Tag("integration-test")
@DisplayName("SimpleLinearTrackTestProcess — individual train lifecycle (#366)")
class SimpleLinearTrackTestProcessTest : KoinTestBase() {
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
		// Initialise dynamic InOut wrappers before use (mirrors ShuntingLoop tests).
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	private fun specAB(
		inTime: Double = 1.0,
		outTime: Double = 15.0
	): SimpleLinearTrackTestProcess.TrainSpec =
		SimpleLinearTrackTestProcess.TrainSpec(
			inName = "A",
			outName = "B",
			inTime = inTime,
			outTime = outTime,
			length = 20.0
		)

	/** Scenario 1: train follows a pre-reserved path A→B and makes genuine forward progress. */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `train follows reserved path and makes forward progress`() {
		val ctx = loadLinearContext()
		val inOuts = ctx.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val reservationService = ctx.getRoutingServices().getPathReservationService()

		var capturedTrain: Train? = null
		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = 50L,
				trainSpecs = listOf(specAB()),
				onTrainCreated = { train ->
					capturedTrain = train
					val res = reservationService.reservePath(train.name, a, b)
					assertThat(res).isInstanceOf<PathReservationService.ReservationResult.Success>()
				}
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 1 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()} " +
				"blocks=${process.getAllBlockTransitions()}"
		}
		val train = requireNotNull(capturedTrain) { "onTrainCreated was never called" }
		assertThat(process.getTrainsEntered()).isEqualTo(1)
		assertThat(process.getMaxConcurrentTrains()).isEqualTo(1)
		// Strong motion proof: train advanced along the reserved path.
		assertThat(train.totalDistance).isGreaterThan(0.0)
		assertThat(process.getBlockTransitions(train.name)).isGreaterThan(0)
	}

	/** Scenario 2: train halts at semaphore when path is not reserved. */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `train halts at semaphore when path not reserved`() {
		val ctx = loadLinearContext()
		// No reservation — train should enter but not exit.
		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = 20L,
				trainSpecs = listOf(specAB())
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 2 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"blocks=${process.getAllBlockTransitions()}"
		}
		assertThat(process.getTrainsEntered()).isEqualTo(1)
		// No reservation was made — the train cannot complete its journey.
		assertThat(process.getTrainsExited()).isEqualTo(0)
	}

	/** Scenario 3: second train waits while first holds a conflicting reservation. */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `second train waits for conflicting first train`() {
		val ctx = loadLinearContext()
		val inOuts = ctx.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val reservationService = ctx.getRoutingServices().getPathReservationService()

		// Reserve A→B for the FIRST train created; the second cannot claim it.
		var firstReserved = false
		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = 30L,
				trainSpecs =
					listOf(
						specAB(inTime = 1.0, outTime = 15.0),
						specAB(inTime = 2.0, outTime = 20.0)
					),
				onTrainCreated = { train ->
					if (!firstReserved) {
						val res = reservationService.reservePath(train.name, a, b)
						assertThat(res).isInstanceOf<PathReservationService.ReservationResult.Success>()
						firstReserved = true
					} else {
						// Train 1 holds A→B: attempting to reserve for train 2 must fail
						// with AllPathsBlocked, directly exercising the conflict code path.
						val conflict = reservationService.reservePath(train.name, a, b)
						assertThat(conflict).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
					}
				}
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 3 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()} " +
				"blocks=${process.getAllBlockTransitions()}"
		}
		// Both trains are queued and become approved (MAX_TRAINS=2).
		assertThat(process.getTrainsEntered()).isEqualTo(2)
		assertThat(process.getMaxConcurrentTrains()).isGreaterThanOrEqualTo(1)
		assertThat(process.getMaxConcurrentTrains()).isLessThanOrEqualTo(SimpleLinearTrackTestProcess.MAX_TRAINS)
		// Second train cannot exit while blocks are held by Train #1's reservation.
		assertThat(process.getTrainsExited()).isLessThanOrEqualTo(1)
	}

	/** Scenario 4: blocked train resumes after reservation is released. */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `blocked train resumes after path release`() {
		val ctx = loadLinearContext()
		val inOuts = ctx.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }

		val reservationService = ctx.getRoutingServices().getPathReservationService()
		// Reserve for a phantom owner first to block the path.
		val reserved = reservationService.reservePath("Phantom", a, b)
		assertThat(reserved).isInstanceOf<PathReservationService.ReservationResult.Success>()

		var capturedTrain: Train? = null
		var distanceBeforeRelease = 0.0
		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = 60L,
				trainSpecs = listOf(specAB()),
				onTrainCreated = { train ->
					capturedTrain = train
					// Schedule a helper process that waits before releasing the blocked
					// path and reassigning it to the real train.  This ensures there is
					// a genuine "blocked" period: the train is activated and attempts to
					// proceed, but the path is still held by Phantom.  Only after the
					// helper fires does the train receive the reservation and resume.
					val releaseHelper =
						object : Process() {
							override suspend fun actions() {
								hold(10.0) // 10-second blocking window
								// Train has been active for ~10 sim-seconds but Phantom still
								// holds the path — it must not have completed its journey yet.
								assertThat(train.terminated()).isEqualTo(false)
								distanceBeforeRelease = train.totalDistance
								reservationService.releasePath("Phantom")
								val res = reservationService.reservePath(train.name, a, b)
								assertThat(res).isInstanceOf<PathReservationService.ReservationResult.Success>()
							}
						}
					Process.activate(releaseHelper)
				}
			)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"Scenario 4 metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"blocks=${process.getAllBlockTransitions()}"
		}
		val train = requireNotNull(capturedTrain) { "onTrainCreated was never called" }
		assertThat(process.getTrainsEntered()).isEqualTo(1)
		// Train must have moved AFTER the path was released — not merely before blocking.
		assertThat(train.totalDistance).isGreaterThan(distanceBeforeRelease)
		assertThat(process.getBlockTransitions(train.name)).isGreaterThan(0)
	}
}
