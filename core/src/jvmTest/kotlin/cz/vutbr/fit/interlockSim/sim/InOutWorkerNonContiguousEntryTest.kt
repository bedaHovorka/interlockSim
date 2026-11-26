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
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.runShuntingLoop
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Issue #1457 (Goal 9B SP3.2): a non-contiguous entry is a refused route the train waits out at its
 * InOut, never a FATAL `SimulationException`, on the Červený Újezd station (`cerveny_ujezd.xml`),
 * where all six early ends of the 1B-SP10.1 LLM baseline had this one cause.
 *
 * The refusal is the [ReservationResult.NonContiguousStart] answer to the entry route an [InOutWorker]
 * sets up for the train at the head of its queue. Whatever the train holds — a stale route from the
 * wrong InOut, or nothing at all — the worker waits [InOutWorker.STALE_ORIGIN_RETRY_SECONDS] of
 * simulated time between attempts, exposes the wait as [InOutWorker.refusedEntry], logs one WARNING
 * per train once the wait passes [InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS], and the run record names
 * the train still refused at run end ([RunRecord.refusedEntriesAtRunEnd]).
 */
@Tag("integration-test")
@DisplayName("InOutWorker waits out a non-contiguous entry on Cerveny Ujezd instead of a FATAL (Issue #1457)")
class InOutWorkerNonContiguousEntryTest : KoinTestBase() {
	private val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var originalLevel: Level? = null

	/** Every `errorStop` that reached a worker's or a train's environment, with its simulation time. */
	private val errorStops = mutableListOf<Pair<Double, Throwable>>()

	@BeforeEach
	fun attachAppender() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		originalLevel = rootLogger.level
		rootLogger.level = Level.INFO
		rootLogger.addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		rootLogger.detachAppender(appender)
		rootLogger.level = originalLevel
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("an approved train holding a route from the wrong InOut waits, then enters and exits once it is released")
	fun wrongOriginRouteDelaysTheEntryUntilItIsReleased() {
		val context = loadCervenyUjezd { env, inOut, real -> InOutWorker(env, inOut, pathReservationService = real) }
		val decorated =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += Process.time() to it
			}
		val navigation = context.getRoutingServices().getTrainNavigationService()
		val reservations = context.getRoutingServices().getPathReservationService()
		val entries = recordEntryInOuts(context)
		val exits = recordExits(context)

		var delayedTrainId: String? = null
		var entryWorker: InOutWorker? = null
		var refusalSeen: RefusedEntry? = null
		var releasedAt: Double? = null
		var entryReservedAt: Double? = null
		var refusalAtEntry: RefusedEntry? = null

		runShuntingLoop(context, END_TIME, env = decorated) { loop ->
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					if (delayedTrainId == null) {
						seedWrongOriginRoute(loop, context, reservations, entries)?.let { (id, worker) ->
							delayedTrainId = id
							entryWorker = worker
						}
					}
					wired.onControlStep()
					val trainId = delayedTrainId ?: return@ControlStepListener
					val worker = requireNotNull(entryWorker)
					val now = Process.time()
					if (refusalSeen == null) refusalSeen = worker.refusedEntry
					val refusal = refusalSeen
					if (refusal != null && releasedAt == null && now >= refusal.since + RELEASE_AFTER) {
						// The dispatcher cancels the wrong route; the train then enters from its own InOut.
						reservations.releasePath(trainId)
						releasedAt = now
					}
					val entry = entries.getValue(trainId)
					if (entryReservedAt == null && navigation.isPathReservedForTrain(trainId, context.inOutNamed(entry))) {
						entryReservedAt = now
						refusalAtEntry = worker.refusedEntry
					}
				}
		}

		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(fatalLines(), "no FATAL SimulationException may be logged").isEmpty()
		val trainId = requireNotNull(delayedTrainId) { "a queued train must have been seeded" }
		val refusal = requireNotNull(refusalSeen) { "the non-contiguous refusal was never exposed" }
		assertThat(refusal.trainId).isEqualTo(trainId)
		assertThat(refusal.inOutName).isEqualTo(entries.getValue(trainId))
		assertThat(refusal.kind).isEqualTo(EntryRefusalKind.NON_CONTIGUOUS)
		val released = requireNotNull(releasedAt) { "the wrong route was never released" }
		val entered = requireNotNull(entryReservedAt) { "the delayed train never got its entry route" }
		assertThat(entered, "the entry follows the release").isGreaterThanOrEqualTo(released)
		assertThat(refusalAtEntry, "the refused-entry state ends with the entry grant").isNull()
		assertThat(requireNotNull(exits[trainId]) { "the delayed train never exited" }).isLessThan(END_TIME.toDouble())
		assertThat(context.runRecord().refusedEntriesAtRunEnd, "nobody is refused at run end").isEmpty()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a train with no reservation refused as non-contiguous waits with simulated time advancing")
	fun refusalWithoutAnyReservationWaitsWithSimulatedTimeAdvancing() {
		var refusedTrain: String? = null
		val refusalTimes = mutableListOf<Double>()
		val context =
			loadCervenyUjezd { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						object : PathReservationService by real {
							override fun reservePathToAnyNextSemaphore(
								trainId: String,
								start: DynamicPathSeparator,
								next: TrackSection
							): ReservationResult {
								if (refusedTrain == null) refusedTrain = trainId
								val first = refusalTimes.firstOrNull()
								if (trainId != refusedTrain || (first != null && Process.time() >= first + REFUSED_FOR)) {
									return real.reservePathToAnyNextSemaphore(trainId, start, next)
								}
								refusalTimes += Process.time()
								return ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
							}
						}
				)
			}
		val exits = recordExits(context)
		val reservations = context.getRoutingServices().getPathReservationService()
		var heldDuringRefusal = false

		runShuntingLoop(context, END_TIME) { loop ->
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					wired.onControlStep()
					val trainId = refusedTrain ?: return@ControlStepListener
					val first = refusalTimes.firstOrNull() ?: return@ControlStepListener
					if (Process.time() < first + REFUSED_FOR && reservations.getReservedBlocks(trainId).isNotEmpty()) {
						heldDuringRefusal = true
					}
				}
		}

		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(fatalLines(), "no FATAL SimulationException may be logged").isEmpty()
		assertThat(heldDuringRefusal, "the refused train must hold no reservation").isEqualTo(false)
		assertThat(refusalTimes.size, "refusals while the injection lasts").isGreaterThan(2)
		assertThat(refusalTimes.zipWithNext { a, b -> b - a }, "simulated time advances between attempts")
			.each { it.isGreaterThanOrEqualTo(InOutWorker.STALE_ORIGIN_RETRY_SECONDS) }
		val trainId = requireNotNull(refusedTrain)
		assertThat(requireNotNull(exits[trainId]) { "the refused train never exited" })
			.isGreaterThan(refusalTimes.first() + REFUSED_FOR)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a wrong route that is never released keeps the train waiting to the end time with one WARNING")
	fun wrongRouteNeverReleasedWaitsToTheEndTimeWithOneWarning() {
		var refusedTrain: String? = null
		var firstRefusalAt: Double? = null
		val heldBlock = mockk<DynamicTrackBlock>()
		val context =
			loadCervenyUjezd { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						object : PathReservationService by real {
							override fun reservePathToAnyNextSemaphore(
								trainId: String,
								start: DynamicPathSeparator,
								next: TrackSection
							): ReservationResult {
								if (refusedTrain == null) refusedTrain = trainId
								if (trainId != refusedTrain) return real.reservePathToAnyNextSemaphore(trainId, start, next)
								if (firstRefusalAt == null) firstRefusalAt = Process.time()
								return ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
							}

							// The wrong route the refused train holds, and that nobody ever releases.
							override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> =
								if (trainId == refusedTrain) listOf(heldBlock) else real.getReservedBlocks(trainId)
						}
				)
			}
		val entries = recordEntryInOuts(context)

		runShuntingLoop(context, END_TIME)

		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(fatalLines(), "no FATAL SimulationException may be logged").isEmpty()
		val trainId = requireNotNull(refusedTrain) { "no train was refused" }
		assertThat(END_TIME - requireNotNull(firstRefusalAt), "the wait outlasts the WARNING threshold")
			.isGreaterThan(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS)
		val warnings = appender.list.filter { it.level == Level.WARN && trainId in it.formattedMessage }
		assertThat(warnings.map { it.formattedMessage }, "exactly one WARNING for the refused train").hasSize(1)
		assertThat(warnings.single().formattedMessage).contains(EntryRefusalKind.NON_CONTIGUOUS.text)
		val record = context.runRecord()
		assertThat(record.endTimeReached, "the run reaches its end time").isTrue()
		assertThat(record.stall, "no stall").isNull()
		assertThat(record.classification).isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
		assertThat(record.refusedEntriesAtRunEnd.map { it.trainId to it.inOutName })
			.containsExactly(trainId to entries.getValue(trainId))
		assertThat(record.refusedEntriesAtRunEnd.single().kind).isEqualTo(EntryRefusalKind.NON_CONTIGUOUS)
		assertThat(record.refusedEntriesAtRunEnd.single().since).isEqualTo(requireNotNull(firstRefusalAt))
		assertThat(record.summaryLine()).contains("$trainId at ${entries.getValue(trainId)}")
		assertThat(record.summaryLine()).contains(EntryRefusalKind.NON_CONTIGUOUS.text)
	}

	/**
	 * The brief's watchdog guard: `MultiTrainLoop`'s `DeadlockWatchdog` must give no new end cause
	 * through the stale route. The refused train was started by the loop (its whole route reserved),
	 * so it is no watchdog member; the trains behind it wait for its blocks. The run must end at its
	 * end time with no stall and no errorStop, the refused train named in the run record.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("MultiTrainLoop's watchdog gives no new end cause through a refused non-contiguous entry")
	fun multiTrainLoopWatchdogGivesNoNewEndCauseThroughTheRefusedEntry() {
		// The first admitted train is the refused one. The loop holds its whole route from this InOut,
		// which leaves the worker's own availability wait false; the stand-in for the stale route
		// therefore also answers that wait, so the worker attempts the entry and is refused.
		var refusedTrain: String? = null
		val context =
			loadCervenyUjezd { env, inOut, real ->
				InOutWorker(
					env,
					inOut,
					pathReservationService =
						object : PathReservationService by real {
							override fun reservePathToAnyNextSemaphore(
								trainId: String,
								start: DynamicPathSeparator,
								next: TrackSection
							): ReservationResult {
								if (trainId != refusedTrain) return real.reservePathToAnyNextSemaphore(trainId, start, next)
								return ReservationResult.NonContiguousStart(inOut.name, "injected by the test")
							}

							override fun isPathToAnyNextSemaphoreAvailable(
								start: PathSeparator,
								next: TrackSection?
							): Boolean = refusedTrain != null || real.isPathToAnyNextSemaphoreAvailable(start, next)
						}
				)
			}
		context.onTrainJourneyEvent {
			if (it is TrainJourneyEvent.Admitted && refusedTrain == null) refusedTrain = it.trainId
		}
		context.getInOuts()
		val realNav = context.getRoutingServices().getTrainNavigationService()
		// The loop reserved the refused train's whole route; the train must still wait for its worker.
		val nav =
			object : TrainNavigationService by realNav {
				override fun isPathReservedForTrain(
					trainId: String,
					separator: PathSeparator
				): Boolean = trainId != refusedTrain && realNav.isPathReservedForTrain(trainId, separator)
			}
		val decorated = NavigationDecoratingContext(context, nav) { errorStops += Process.time() to it }
		val loop =
			MultiTrainLoop(
				decorated,
				endTime = MULTI_END_TIME,
				trainSpecs =
					listOf(0.0, 5.0, 10.0).map {
						MultiTrainLoop.TrainSpec(inName = WEST_ENTRY_1, outName = EAST_EXIT_1, inTime = it, length = 20.0)
					}
			)
		context.setMainProcess(loop)
		context.run()

		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(fatalLines(), "no FATAL SimulationException may be logged").isEmpty()
		val trainId =
			requireNotNull(refusedTrain) {
				"no train was refused: entered ${loop.getTrainsEntered()} exited ${loop.getTrainsExited()} " +
					"failures ${loop.getEntryFailures()} record ${context.runRecord().summaryLine()}"
			}
		val record = context.runRecord()
		assertThat(record.stall, "the watchdog reports no stall").isNull()
		assertThat(record.endTimeReached, "the run reaches its end time").isTrue()
		assertThat(record.classification).isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
		assertThat(record.refusedEntriesAtRunEnd.map { it.trainId to it.inOutName })
			.containsExactly(trainId to WEST_ENTRY_1)
	}

	/**
	 * Loads `cerveny_ujezd.xml` with a process factory whose InOut workers come from [buildWorker]; every
	 * worker sees an environment that records `errorStop` into [errorStops].
	 */
	private fun loadCervenyUjezd(
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
		return CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, processFactory).tracked()
	}

	/** Entry InOut per train, from its queue step (`TrainJourneyEvent.Queued`). */
	private fun recordEntryInOuts(context: DefaultSimulationContext): Map<String, String> {
		val entries = mutableMapOf<String, String>()
		context.onTrainJourneyEvent { if (it is TrainJourneyEvent.Queued) entries[it.trainId] = it.entryInOut }
		return entries
	}

	/** Exit time per train (`TrainJourneyEvent.Exited`). */
	private fun recordExits(context: DefaultSimulationContext): Map<String, Double> {
		val exits = mutableMapOf<String, Double>()
		context.onTrainJourneyEvent { if (it is TrainJourneyEvent.Exited) exits[it.trainId] = it.time }
		return exits
	}

	/**
	 * Seeds the state under test — a route from the wrong InOut — for the first queued train, through
	 * the real reservation service, bypassing the SP8 wrong-origin refusal on purpose (the stale route is
	 * the state under test, however it came about). The wrong InOut is the train's planned exit.
	 *
	 * @return the seeded train's id and its entry InOut's worker, or `null` when no train is queued yet
	 */
	private fun seedWrongOriginRoute(
		loop: ShuntingLoop,
		context: DefaultSimulationContext,
		reservations: PathReservationService,
		entries: Map<String, String>
	): Pair<String, InOutWorker>? {
		val queued = loop.getQueuedTrains().firstOrNull() ?: return null
		val entry = entries[queued.trainId] ?: return null
		val wrong = context.inOutNamed(queued.destinationInOutName)
		assertThat(wrong.name == entry, "the wrong InOut differs from the entry").isEqualTo(false)
		assertReservationSuccess(reservations.reservePathToAnyNextSemaphore(queued.trainId, wrong))
		return queued.trainId to context.getWorkerFor(context.inOutNamed(entry))
	}

	private fun DefaultSimulationContext.inOutNamed(name: String): DynamicInOut = getInOuts().single { it.name == name }

	private fun DefaultSimulationContext.runRecord(): RunRecord =
		requireNotNull(getMetricsServices().getRunRecordSource().runRecord()) { "the run record was not built" }

	/** Logged FATAL markers and worker APPROVAL_ERROR lines: what the old FATAL path wrote. */
	private fun fatalLines(): List<String> =
		appender.list
			.map { it.formattedMessage }
			.filter { "[FATAL]" in it || "APPROVAL_ERROR" in it }

	private companion object {
		const val END_TIME = 300L
		const val MULTI_END_TIME = 300L

		/** Seconds after the first refusal at which the test releases the wrong route. */
		const val RELEASE_AFTER = 10.0

		/** Seconds the injected refusal of the no-reservation test lasts. */
		const val REFUSED_FOR = 20.0

		/** The fixture's InOut names (`cerveny_ujezd.xml`). */
		const val WEST_ENTRY_1: String = "west_entry_1"
		const val EAST_EXIT_1: String = "east_exit_1"
	}
}
