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
import assertk.assertions.isNotEmpty
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
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
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
	 * Issue #1295 review fix: after the first denial the entry path stays unavailable (another train
	 * blocks it), so the worker can no longer re-attempt the reservation — and with the bare
	 * `waitUntil(pathFree)` the denial-driven deadline check could never run again, parking the
	 * worker past the episode's 120 s bound. The deadline must stay armed and stop the run at the
	 * bound exactly as when further denials keep arriving (the test above). Ordinary contention
	 * without a stale-origin episode stays on the plain `waitUntil` and must remain untimed-out —
	 * that branch is what the healthy scenarios in this file already cover, since every run here
	 * starts with no episode running.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("an entry path that stays unavailable keeps the stale-origin deadline armed")
	fun staleOriginDeadlineSurvivesAnUnavailableEntryPath() {
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

							// Available only until (and matching) the first NonContiguousStart denial: after it
							// another train occupies the entry, so the worker's own waitUntil(pathFree) can no
							// longer pass and the reservation is never re-attempted.
							override fun isPathToAnyNextSemaphoreAvailable(
								start: PathSeparator,
								next: TrackSection?
							): Boolean = firstRefusalAt == null
						}
				)
			}

		runShuntingLoop(context, END_TIME)

		assertThat(errorStops, "the run must still stop on the stale-origin deadline despite the unavailable path")
			.isNotEmpty()
		val (stoppedAt, error) = errorStops.single()
		assertThat(error).isInstanceOf(SimulationException::class)
		assertThat(error.message.orEmpty()).contains("stale reservation not expired")
		assertThat(stoppedAt - requireNotNull(firstRefusalAt))
			.isBetween(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS, InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS + 2.0)
	}

	/**
	 * Issue #1295 review fix (round 2): the stale holding expires mid-episode while another train keeps
	 * the entry path blocked past the episode's 120 s bound. Once the holding is gone the episode is
	 * over — what is left is ordinary contention, which must never be timed out — so the bounded poll
	 * must fall back to the plain availability wait instead of stopping the run with "stale
	 * reservation not expired". The entry route then succeeds when the path frees.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a stale holding that expires while the entry stays blocked ends the episode, not the run")
	fun staleHoldingThatExpiresWhileTheEntryStaysBlockedEndsTheEpisode() {
		var firstRefusalAt: Double? = null
		var entryReservedAt: Double? = null
		val heldBlock = mockk<DynamicTrackBlock>()
		val context =
			loadContext { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						object : PathReservationService by real {
							private fun holdingExpired(): Boolean =
								firstRefusalAt?.let { Process.time() >= it + HOLDING_EXPIRES_AFTER } ?: false

							override fun reservePathToAnyNextSemaphore(
								trainId: String,
								start: DynamicPathSeparator,
								next: TrackSection
							): ReservationResult {
								if (!holdingExpired()) {
									if (firstRefusalAt == null) firstRefusalAt = Process.time()
									return ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
								}
								val result = real.reservePathToAnyNextSemaphore(trainId, start, next)
								if (result is ReservationResult.Success && entryReservedAt == null) {
									entryReservedAt = Process.time()
								}
								return result
							}

							override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> =
								if (firstRefusalAt == null) {
									real.getReservedBlocks(trainId)
								} else if (holdingExpired()) {
									emptyList()
								} else {
									listOf(heldBlock)
								}

							// After the first denial another train blocks the entry until well past the
							// 120 s episode bound; the stale holding expires long before that. Outside that
							// window the real availability answers, so it agrees with the real reservation
							// (a fake "free" with a real AllPathsBlocked would spin the worker in place).
							override fun isPathToAnyNextSemaphoreAvailable(
								start: PathSeparator,
								next: TrackSection?
							): Boolean {
								val blockedUntil = firstRefusalAt?.let { it + ENTRY_BLOCKED_FOR }
								return (blockedUntil == null || Process.time() >= blockedUntil) &&
									real.isPathToAnyNextSemaphoreAvailable(start, next)
							}
						}
				)
			}

		runShuntingLoop(context, END_TIME)

		assertThat(errorStops, "ordinary contention after the holding expired must not stop the run").isEmpty()
		val deniedAt = requireNotNull(firstRefusalAt) { "the stale-origin denial never happened" }
		val reservedAt = requireNotNull(entryReservedAt) { "the train never got its entry route" }
		assertThat(reservedAt - deniedAt).isGreaterThanOrEqualTo(ENTRY_BLOCKED_FOR)
		assertThat(ENTRY_BLOCKED_FOR).isGreaterThan(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS)
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

		/** Seconds after the first denial at which the injected stale holding expires. */
		const val HOLDING_EXPIRES_AFTER = 60.0

		/** Seconds after the first denial during which the entry path stays blocked (past the 120 s bound). */
		const val ENTRY_BLOCKED_FOR = 150.0
	}
}
