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
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression test for the fast-sim hang (Issue #685).
 *
 * kDisco's `waitUntil` returns immediately without suspending when the condition is
 * already true. [InOutWorker]'s `pathFree` condition
 * ([PathReservationService.isPathToAnyNextSemaphoreAvailable]) only checks block
 * FREE-ness, while the actual reservation
 * ([PathReservationService.reservePathToAnyNextSemaphore]) can additionally fail
 * (switch locks held by another train, next-block validation). When the two disagree,
 * `AllPathsBlocked` followed by a bare `continue` back to `waitUntil(pathFree)` spun
 * forever at a fixed simulation time — a wall-clock livelock that hung the simulation.
 *
 * The fix holds [InOutWorker.RETRY_HOLD_SECONDS] of simulation time before retrying,
 * guaranteeing forward progress. This test forces the pathological disagreement
 * (available == true, reservation == AllPathsBlocked, permanently) and verifies the
 * simulation still terminates at its end time instead of hanging.
 */
@Tag("integration-test")
@DisplayName("InOutWorker livelock guard — Issue #685")
class InOutWorkerLivelockGuardTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	/**
	 * Stub reproducing the Issue #685 disagreement: the availability check claims a
	 * path is free while every reservation attempt is rejected as blocked.
	 * All other operations delegate to the real service.
	 */
	private class AlwaysBlockedReservationService(
		delegate: PathReservationService
	) : PathReservationService by delegate {
		var reservationAttempts: Int = 0
			private set

		override fun isPathToAnyNextSemaphoreAvailable(
			start: PathSeparator,
			next: TrackSection?
		): Boolean = true

		override fun reservePathToAnyNextSemaphore(
			trainId: String,
			start: DynamicPathSeparator,
			next: TrackSection
		): PathReservationService.ReservationResult {
			reservationAttempts++
			return PathReservationService.ReservationResult.AllPathsBlocked(1)
		}
	}

	/**
	 * Process factory that wires [AlwaysBlockedReservationService] into every
	 * [InOutWorker] it creates.
	 */
	private class BlockedWorkerFactory : SimulationProcessFactory {
		private val delegate = DefaultSimulationProcessFactory()
		val stubServices = mutableListOf<AlwaysBlockedReservationService>()

		override fun createMainProcess(env: SimulationEnvironment): LoopProcess = delegate.createMainProcess(env)

		override fun createInOutWorker(
			env: SimulationEnvironment,
			inOut: DynamicInOut
		): InOutWorker {
			val stub = AlwaysBlockedReservationService(env.getPathReservationService())
			stubServices.add(stub)
			return InOutWorker(env, inOut, pathReservationService = stub)
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Persistent available/blocked disagreement terminates at endTime instead of hanging")
	fun `worker retries with bounded polling when reservation stays blocked`() {
		val endTime = 15L
		val factory = BlockedWorkerFactory()
		val editingContext =
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withSemaphore(3, 3, true)
				.withInOut("B", 5, 5, false)
				.withConnection(1, 1, 3, 3, 100.0, 80.0)
				.withConnection(3, 3, 5, 5, 100.0, 80.0)
				.buildEditingContext()
		val ctx = DefaultSimulationContext.fromEditingContext(editingContext, factory)
		context = ctx
		ctx.getInOuts()

		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = endTime,
				trainSpecs =
					listOf(
						SimpleLinearTrackTestProcess.TrainSpec(
							inName = "A",
							outName = "B",
							inTime = 1.0,
							outTime = 10.0,
							length = 20.0
						)
					)
			)
		ctx.setMainProcess(process)

		// Without the livelock guard this call never returns: the worker spins at a
		// fixed simulation time (waitUntil returns immediately, reservation is
		// always AllPathsBlocked) and the @Timeout above trips.
		ctx.run()

		// The train was generated but could never be granted a path.
		assertThat(process.getTrainsEntered()).isEqualTo(1)

		// The worker retried at a bounded rate (~1 attempt per RETRY_HOLD_SECONDS of
		// simulation time), not in an unbounded spin (which would be millions).
		val attempts = factory.stubServices.sumOf { it.reservationAttempts }
		assertThat(attempts).isBetween(1, 50)
	}
}
