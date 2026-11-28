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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
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
		val scenario = entryScenario()
		val run = ReleasedEntryDriver(scenario)

		runShuntingLoop(scenario.context, END_TIME, env = scenario.decorated) { run.install(it) }

		assertRunEndedCleanly()
		val trainId = requireNotNull(run.delayedTrainId) { "a queued train must have been seeded" }
		val refusal = requireNotNull(run.refusalSeen) { "the non-contiguous refusal was never exposed" }
		assertRefusalNamesTheDelayedTrain(refusal, trainId, scenario.entryInOuts)
		val released = requireNotNull(run.releasedAt) { "the wrong route was never released" }
		val entered = requireNotNull(run.entryReservedAt) { "the delayed train never got its entry route" }
		assertThat(entered, "the entry follows the release").isGreaterThanOrEqualTo(released)
		assertThat(run.refusalAtEntry, "the refused-entry state ends with the entry grant").isNull()
		assertThat(requireNotNull(scenario.exitTimes[trainId]) { "the delayed train never exited" })
			.isLessThan(END_TIME.toDouble())
		assertThat(scenario.context.runRecord().refusedEntriesAtRunEnd, "nobody is refused at run end").isEmpty()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a train with no reservation refused as non-contiguous waits with simulated time advancing")
	fun refusalWithoutAnyReservationWaitsWithSimulatedTimeAdvancing() {
		val state = RefusalState()
		val context =
			loadCervenyUjezd { env, inOut, real ->
				InOutWorker(env, inOut, pathReservationService = RefusalWindow(real, inOut.name, state))
			}
		val exits = recordExits(context)
		val reservations = context.getRoutingServices().getPathReservationService()
		var heldDuringRefusal = false

		runShuntingLoop(context, END_TIME) { loop ->
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					wired.onControlStep()
					val trainId = state.refusedTrain ?: return@ControlStepListener
					val first = state.refusalTimes.firstOrNull() ?: return@ControlStepListener
					if (Process.time() < first + REFUSED_FOR && reservations.getReservedBlocks(trainId).isNotEmpty()) {
						heldDuringRefusal = true
					}
				}
		}

		assertRunEndedCleanly()
		assertThat(heldDuringRefusal, "the refused train must hold no reservation").isEqualTo(false)
		assertThat(state.refusalTimes.size, "refusals while the injection lasts").isGreaterThan(2)
		assertThat(state.refusalTimes.zipWithNext { a, b -> b - a }, "simulated time advances between attempts")
			.each { it.isGreaterThanOrEqualTo(InOutWorker.STALE_ORIGIN_RETRY_SECONDS) }
		val trainId = requireNotNull(state.refusedTrain)
		assertThat(requireNotNull(exits[trainId]) { "the refused train never exited" })
			.isGreaterThan(state.refusalTimes.first() + REFUSED_FOR)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a wrong route re-refused through the real service until the kernel expiry releases it stays quiet")
	fun refusalsThroughTheRealReservationServiceStayQuietUntilTheKernelExpiryReleasesTheRoute() {
		val scenario = entryScenario()
		var delayedTrainId: String? = null
		var entryWorker: InOutWorker? = null
		val reservePathCalls = mutableListOf<Pair<String, Double>>()
		scenario.context.onRunAccountingEvent {
			if (it is RunAccountingEvent.ReservePathCalled) reservePathCalls += it.trainId to it.time
		}

		runShuntingLoop(scenario.context, END_TIME, env = scenario.decorated) { loop ->
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					if (delayedTrainId == null) {
						seedWrongOriginRoute(loop, scenario.context, scenario.reservations, scenario.entryInOuts)
							?.let { (id, worker) ->
								delayedTrainId = id
								entryWorker = worker
							}
					}
					wired.onControlStep()
					// Nobody ever cancels the wrong route: the worker keeps waiting until the kernel
					// reservation expiry releases it, so every refusal of the wait is produced by the
					// real reservation service — the two sites the one-WARNING-per-train design must
					// keep quiet.
				}
		}

		assertRunEndedCleanly()
		val trainId = requireNotNull(delayedTrainId) { "a queued train must have been seeded" }
		val worker = requireNotNull(entryWorker) { "the entry worker was never captured" }
		val trainCalls = reservePathCalls.filter { it.first == trainId }.map { it.second }
		assertThat(trainCalls.size, "the entry was re-attempted through the real service repeatedly").isGreaterThan(2)
		assertThat(worker.staleOriginDenials, "one episode for the whole wait").isEqualTo(1)
		val trainNamed = Regex("${Regex.escape(trainId)}(?!\\d)")
		val refusalInfos =
			appender.list
				.filter { it.level == Level.INFO && EntryRefusalKind.NON_CONTIGUOUS.text in it.formattedMessage }
				.filter { trainNamed.containsMatchIn(it.formattedMessage) }
		assertThat(refusalInfos.map { it.formattedMessage }, "the first denial of the wait was logged").hasSize(1)
		assertThat(
			requireNotNull(scenario.exitTimes[trainId]) { "the delayed train's entry route was never granted" }
		).isLessThan(END_TIME.toDouble())
		val record = scenario.context.runRecord()
		assertThat(record.endTimeReached, "the run reaches its end time").isTrue()
		assertThat(record.refusedEntriesAtRunEnd, "the wait was over by the end of the run").isEmpty()
		// Word boundary: "Train #1" must not match "Train #10".
		val warningWordings =
			listOf(
				"rejected non-contiguous start",
				"aborting, start is not contiguous",
				EntryRefusalKind.NON_CONTIGUOUS.text
			)
		val refusalWarnings =
			appender.list
				.filter { it.level == Level.WARN && warningWordings.any { wording -> wording in it.formattedMessage } }
				.map { it.formattedMessage }
		assertThat(
			refusalWarnings,
			"the refusal is one diagnostic line per episode at INFO and no WARNING of any kind at the sites" +
				" the wait passes per retry"
		).isEmpty()
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

		assertRunEndedCleanly()
		val trainId = requireNotNull(refusedTrain) { "no train was refused" }
		assertThat(END_TIME - requireNotNull(firstRefusalAt), "the wait outlasts the WARNING threshold")
			.isGreaterThan(InOutWorker.STALE_ORIGIN_MAX_WAIT_SECONDS)
		// Word boundary: "Train #1" must not match "Train #10".
		val trainNamed = Regex("${Regex.escape(trainId)}(?!\\d)")
		val warnings = appender.list.filter { it.level == Level.WARN && trainNamed.containsMatchIn(it.formattedMessage) }
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

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a regrant inside the retry hold keeps the recovered train out of the run record's refused entries")
	fun regrantInsideTheHoldKeepsTheRecoveredTrainOutOfRunRecordRefusedEntries() {
		val scenario = entryScenario()
		val run = RegrantCutoffDriver(scenario)

		runShuntingLoop(scenario.context, END_TIME, env = scenario.decorated) { run.install(it) }

		assertRunEndedCleanly()
		val trainId = requireNotNull(run.delayedTrainId) { "a queued train must have been seeded" }
		val refusal = requireNotNull(run.refusalSeen) { "the non-contiguous refusal was never exposed" }
		assertThat(refusal.trainId).isEqualTo(trainId)
		val regranted = requireNotNull(run.regrantAt) { "the dispatcher regrant never happened" }
		assertThat(regranted, "the regrant follows the first refusal").isGreaterThanOrEqualTo(refusal.since)
		assertThat(
			regranted,
			"the cutoff lands inside the retry hold, one retry period after the first refusal at the latest"
		).isLessThanOrEqualTo(refusal.since + InOutWorker.STALE_ORIGIN_RETRY_SECONDS)
		assertThat(run.authoritySeen, "the regrant granted the train entry authority").isTrue()
		assertThat(scenario.exitTimes[trainId], "the run ended before the worker resumed on the fresh route").isNull()
		val record = scenario.context.runRecord()
		assertThat(record.endTimeReached, "the cutoff preceded the end time").isFalse()
		assertThat(
			record.refusedEntriesAtRunEnd,
			"a train that got its entry authority back mid-hold is not recorded as refused at run end"
		).isEmpty()
	}

	/**
	 * A tripwire, not a reproduction: `MultiTrainLoop`'s `DeadlockWatchdog` must give no new end cause
	 * through the stale route. In production the loop reserves a train's whole route before the train
	 * reaches its worker, so the worker never sees the refusal; the test injects it. The refused train
	 * was started by the loop (its whole route reserved), so it is no watchdog member; the trains behind
	 * it wait for its blocks. The wait-for edges sampled during the wait must never leave the refused
	 * train, and the run must end at its end time with no stall and no errorStop, the refused train
	 * named in the run record.
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
		// Sampled on every accounting event (the waiting trains' entry retries keep them coming).
		val edgesLeavingRefused = mutableListOf<Set<String>>()
		var edgeSamples = 0
		context.onRunAccountingEvent {
			val refused = refusedTrain ?: return@onRunAccountingEvent
			edgeSamples++
			loop.waitForEdges()[refused]?.let { edgesLeavingRefused += it }
		}
		context.setMainProcess(loop)
		context.run()

		assertRunEndedCleanly()
		assertThat(edgeSamples, "wait-for edges sampled during the wait").isGreaterThan(0)
		assertThat(edgesLeavingRefused.flatten(), "no wait-for edge leaves the refused train").isEmpty()
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
	 * The wiring of the real-worker scenario: the context's InOut workers delegate to their real
	 * reservation services, the environment the loop sees records every `errorStop` into [errorStops],
	 * and the journey recorders the runs' assertions read are attached.
	 */
	private fun entryScenario(): EntryScenario {
		val context = loadCervenyUjezd { env, inOut, real -> InOutWorker(env, inOut, pathReservationService = real) }
		val decorated =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += Process.time() to it
			}
		val reservations = context.getRoutingServices().getPathReservationService()
		return EntryScenario(
			context = context,
			decorated = decorated,
			navigation = context.getRoutingServices().getTrainNavigationService(),
			reservations = reservations,
			entryInOuts = recordEntryInOuts(context),
			exitTimes = recordExits(context)
		)
	}

	/** The wiring [entryScenario] returns; the recorders are the same live maps their runs fill. */
	private class EntryScenario(
		val context: DefaultSimulationContext,
		val decorated: NavigationDecoratingContext,
		val navigation: TrainNavigationService,
		val reservations: PathReservationService,
		val entryInOuts: Map<String, String>,
		val exitTimes: Map<String, Double>
	)

	/** No `errorStop` and no logged FATAL marker or worker `APPROVAL_ERROR` line ended the run early. */
	private fun assertRunEndedCleanly() {
		assertThat(errorStops, "no errorStop may end the run").isEmpty()
		assertThat(fatalLines(), "no FATAL SimulationException may be logged").isEmpty()
	}

	/** [refusal] names [trainId], its entry InOut and the non-contiguous refusal kind. */
	private fun assertRefusalNamesTheDelayedTrain(
		refusal: RefusedEntry,
		trainId: String,
		entryInOuts: Map<String, String>
	) {
		assertThat(refusal.trainId).isEqualTo(trainId)
		assertThat(refusal.inOutName).isEqualTo(entryInOuts.getValue(trainId))
		assertThat(refusal.kind).isEqualTo(EntryRefusalKind.NON_CONTIGUOUS)
	}

	/**
	 * The driver of the wrong-origin release run: it seeds the wrong-origin route for the first queued
	 * train, releases it [RELEASE_AFTER] after the episode's first refusal, and records the refusal,
	 * the release and the entry grant on the way.
	 */
	private class ReleasedEntryDriver(
		private val scenario: EntryScenario
	) {
		var delayedTrainId: String? = null
			private set
		var entryWorker: InOutWorker? = null
			private set
		var refusalSeen: RefusedEntry? = null
			private set
		var releasedAt: Double? = null
			private set
		var entryReservedAt: Double? = null
			private set
		var refusalAtEntry: RefusedEntry? = null
			private set

		/** Installs this driver on [loop]'s control step, wrapping the listener the loop already runs. */
		fun install(loop: ShuntingLoop) {
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					if (delayedTrainId == null) {
						seedWrongOriginRoute(loop, scenario.context, scenario.reservations, scenario.entryInOuts)
							?.let { (id, worker) ->
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
						scenario.reservations.releasePath(trainId)
						releasedAt = now
					}
					val entry = scenario.entryInOuts.getValue(trainId)
					if (
						entryReservedAt == null &&
						scenario.navigation.isPathReservedForTrain(trainId, scenario.context.inOutNamed(entry))
					) {
						entryReservedAt = now
						refusalAtEntry = worker.refusedEntry
					}
				}
		}
	}

	/**
	 * The driver of the regrant-cutoff run: it seeds the wrong-origin route, then — on the first
	 * control step that sees the episode armed — cancels the stale route, grants a valid entry route
	 * from the train's own InOut and stops the run inside the retry hold, before the worker's next
	 * pass.
	 */
	private class RegrantCutoffDriver(
		private val scenario: EntryScenario
	) {
		var delayedTrainId: String? = null
			private set
		var entryWorker: InOutWorker? = null
			private set
		var refusalSeen: RefusedEntry? = null
			private set
		var regrantAt: Double? = null
			private set
		var authoritySeen: Boolean = false
			private set

		/** Installs this driver on [loop]'s control step, wrapping the listener the loop already runs. */
		fun install(loop: ShuntingLoop) {
			val wired = requireNotNull(loop.controlStepListener) { "runShuntingLoop did not install a listener" }
			loop.controlStepListener =
				ControlStepListener {
					if (delayedTrainId == null) {
						seedWrongOriginRoute(loop, scenario.context, scenario.reservations, scenario.entryInOuts)
							?.let { (id, worker) ->
								delayedTrainId = id
								entryWorker = worker
							}
					}
					wired.onControlStep()
					if (delayedTrainId == null) return@ControlStepListener
					val trainId = requireNotNull(delayedTrainId)
					val worker = requireNotNull(entryWorker)
					if (regrantAt != null) return@ControlStepListener
					if (worker.staleOriginDenials == 0) return@ControlStepListener
					// The worker sits in its retry hold at this instant: the episode is armed, the train
					// still heads the queue and holds no entry authority from this InOut yet.
					refusalSeen = worker.refusedEntry
					// Production-shaped dispatcher replace in one control step: cancel the stale route
					// and grant a valid entry route from the train's own InOut, then cut the run off
					// inside the hold, before the worker's next pass.
					scenario.reservations.releasePath(trainId)
					val entry = scenario.context.inOutNamed(scenario.entryInOuts.getValue(trainId))
					assertReservationSuccess(scenario.reservations.reservePathToAnyNextSemaphore(trainId, entry))
					authoritySeen = scenario.navigation.isPathReservedForTrain(trainId, entry)
					regrantAt = Process.time()
					scenario.context.stop()
				}
		}
	}

	/** The state the per-worker [RefusalWindow]s share: the latched train and its refusal instants. */
	private class RefusalState {
		var refusedTrain: String? = null
		val refusalTimes = mutableListOf<Double>()
	}

	/**
	 * The injected refusal window the no-reservation test runs against [real]: the first train to ask
	 * for an entry path is answered `NonContiguousStart` for [REFUSED_FOR] seconds from its first
	 * refusal, then [real] answers. Every worker of the parsed context gets its own window; they
	 * share [state].
	 */
	private class RefusalWindow(
		private val real: PathReservationService,
		private val inOutName: String,
		private val state: RefusalState
	) : PathReservationService by real {
		override fun reservePathToAnyNextSemaphore(
			trainId: String,
			start: DynamicPathSeparator,
			next: TrackSection
		): ReservationResult {
			if (state.refusedTrain == null) state.refusedTrain = trainId
			val first = state.refusalTimes.firstOrNull()
			if (trainId != state.refusedTrain || (first != null && Process.time() >= first + REFUSED_FOR)) {
				return real.reservePathToAnyNextSemaphore(trainId, start, next)
			}
			state.refusalTimes += Process.time()
			return ReservationResult.NonContiguousStart(inOutName, "injected by the test")
		}
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

/** The InOut of [context] named [name]. */
private fun DefaultSimulationContext.inOutNamed(name: String): DynamicInOut = getInOuts().single { it.name == name }

/** The run record of [context]. */
private fun DefaultSimulationContext.runRecord(): RunRecord =
	requireNotNull(getMetricsServices().getRunRecordSource().runRecord()) { "the run record was not built" }
