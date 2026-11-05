/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Simulation Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runShuntingLoop
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Issue #1295: a refused entry-path setup in [InOutWorker] must not kill the run when the refusal
 * is recoverable, and must still stop it when it is a real invariant violation.
 *
 * Recoverable means exactly one case: the reservation from the real InOut is refused as
 * [ReservationResult.NonContiguousStart] while the queued train still holds a stale reservation of
 * its own (non-empty [PathReservationService.getReservedBlocks]). The worker records the denial once,
 * re-tries every [InOutWorker.STALE_ORIGIN_RETRY_SECONDS] until the reservation expiry releases the
 * stale holding, and stops the run only after [InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS].
 */
@Tag("integration-test")
@DisplayName("InOutWorker recovers from a stale non-contiguous origin and keeps FATAL otherwise (Issue #1295)")
class InOutWorkerStaleOriginTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

	/** Every `errorStop` that reached a worker's or a train's environment, with its simulation time. */
	private val errorStops = mutableListOf<Pair<Double, Throwable>>()

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a queued train holding a stale wrong-origin route enters once the reservation expires")
	fun staleWrongOriginReservationDelaysTheEntryInsteadOfEndingTheRun() {
		val context = loadContext { env, inOut, real -> InOutWorker(env, inOut, pathReservationService = real) }
		val decorated =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += Process.time() to it
			}
		val navigation = context.getRoutingServices().getTrainNavigationService()
		val reservations = context.getRoutingServices().getPathReservationService()

		var delayedTrainId: String? = null
		var entryInOut: DynamicInOut? = null
		var entryWorker: InOutWorker? = null
		var denialSeenAt: Double? = null
		var entryReservedAt: Double? = null
		var approvedSeen = false
		var exitedAt: Double? = null

		val loop =
			runShuntingLoop(context, END_TIME, env = decorated) { loop ->
				val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
				loop.controlStepListener =
					ControlStepListener {
						if (delayedTrainId == null) {
							loop.getQueuedTrains().firstOrNull()?.let { queued ->
								val inOuts = context.getInOuts()
								val wrong = inOuts.single { it.name == queued.destinationInOutName }
								val entry = inOuts.single { it.name != queued.destinationInOutName }
								assertThat(wrong).isNotEqualTo(entry)
								// Bypasses the SP8 wrong-origin refusal on purpose: the stale holding is the
								// state under test, however it came about.
								assertThat(reservations.reservePathToAnyNextSemaphore(queued.trainId, wrong))
									.isInstanceOf(ReservationResult.Success::class)
								delayedTrainId = queued.trainId
								entryInOut = entry
								entryWorker = context.getWorkerFor(entry)
							}
						}
						wired.onControlStep()
						val trainId = delayedTrainId ?: return@ControlStepListener
						val now = Process.time()
						if (denialSeenAt == null && requireNotNull(entryWorker).staleOriginDenials == 1) {
							denialSeenAt = now
						}
						if (entryReservedAt == null && navigation.isPathReservedForTrain(trainId, requireNotNull(entryInOut))) {
							entryReservedAt = now
						}
						val approved = loop.getApprovedTrains().any { it.name == trainId }
						if (approved) approvedSeen = true
						if (approvedSeen && !approved && exitedAt == null) exitedAt = now
					}
			}

		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(delayedTrainId, "a queued train must have been seeded").isNotNull()
		val deniedAt = requireNotNull(denialSeenAt) { "the stale-origin denial was never recorded" }
		val reservedAt = requireNotNull(entryReservedAt) { "the delayed train never got its entry route" }
		assertThat(reservedAt).isGreaterThan(deniedAt)
		assertThat(requireNotNull(entryWorker).staleOriginDenials, "one denial per episode").isEqualTo(1)
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)
		assertThat(requireNotNull(exitedAt) { "the delayed train never exited" }).isLessThan(END_TIME.toDouble())
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("GeometricallyImpossible still stops the run at the first refusal")
	fun geometricallyImpossibleStaysFatal() {
		var firstRefusalAt: Double? = null
		val context =
			loadContext { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						refusing(real) {
							if (firstRefusalAt == null) firstRefusalAt = Process.time()
							ReservationResult.GeometricallyImpossible("injected by the test")
						}
				)
			}

		runShuntingLoop(context, END_TIME)

		val (stoppedAt, error) = errorStops.single()
		assertThat(error).isInstanceOf(SimulationException::class)
		assertThat(stoppedAt).isEqualTo(requireNotNull(firstRefusalAt))
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("NonContiguousStart with no reservation held still stops the run at the first refusal")
	fun nonContiguousStartWithoutHeldBlocksStaysFatal() {
		var firstRefusalAt: Double? = null
		val context =
			loadContext { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						refusing(real) {
							if (firstRefusalAt == null) firstRefusalAt = Process.time()
							ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
						}
				)
			}

		runShuntingLoop(context, END_TIME)

		val (stoppedAt, error) = errorStops.single()
		assertThat(error).isInstanceOf(SimulationException::class)
		assertThat(error.message.orEmpty()).contains("non-contiguous")
		assertThat(stoppedAt).isEqualTo(requireNotNull(firstRefusalAt))
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a stale reservation that never expires stops the run at the 120 s bound")
	fun staleReservationThatNeverExpiresStopsTheRunAtTheBound() {
		var firstRefusalAt: Double? = null
		val heldBlock = mockk<DynamicTrackBlock>()
		val context =
			loadContext { env, inOut, real ->
				val refusingService =
					refusing(real) {
						if (firstRefusalAt == null) firstRefusalAt = Process.time()
						ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
					}
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						object : PathReservationService by refusingService {
							override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> = listOf(heldBlock)
						}
				)
			}

		runShuntingLoop(context, END_TIME)

		val (stoppedAt, error) = errorStops.single()
		assertThat(error).isInstanceOf(SimulationException::class)
		assertThat(error.message.orEmpty()).contains("stale reservation not expired")
		assertThat(stoppedAt - requireNotNull(firstRefusalAt))
			.isBetween(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS, InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS + 2.0)
		assertThat(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS).isEqualTo(120.0)
	}

	/**
	 * Loads `vyhybna.xml` with a process factory whose InOut workers come from [buildWorker]; every
	 * worker sees an environment that records `errorStop` into [errorStops].
	 */
	private fun loadContext(
		buildWorker: (env: SimulationEnvironment, inOut: DynamicInOut, real: PathReservationService) -> InOutWorker
	): DefaultSimulationContext {
		val defaults = DefaultSimulationProcessFactory()
		val processFactory =
			object : SimulationProcessFactory by defaults {
				override fun createInOutWorker(
					env: SimulationEnvironment,
					inOut: DynamicInOut
				): InOutWorker {
					val recording =
						object : SimulationEnvironment by env {
							override fun errorStop(error: Throwable) {
								errorStops += Process.time() to error
								env.errorStop(error)
							}
						}
					return buildWorker(recording, inOut, env.getRoutingServices().getPathReservationService())
				}
			}
		return TestFixtures
			.loadShuntingSimulationContext(
				DefaultSimulationContextFactory(editingContextFactory, processFactory),
				editingContextFactory
			).tracked()
	}

	/** [real] with the entry-path setup from an InOut answered by [refusal]. */
	private fun refusing(
		real: PathReservationService,
		refusal: () -> ReservationResult
	): PathReservationService =
		object : PathReservationService by real {
			override fun reservePathToAnyNextSemaphore(
				trainId: String,
				start: DynamicPathSeparator,
				next: TrackSection
			): ReservationResult = refusal()
		}

	private companion object {
		const val END_TIME = 300L
	}
}
