/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.runShuntingLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A train the dispatcher never extends gets a WARN that names it, before the run is stopped
 * (Issue #943).
 *
 * ## The defect this pins
 *
 * PR #940 moved "train waiting for its route to be extended" onto the event-driven
 * `waitUntil(env.createPathAvailableCondition(...))` branch of `Train.Site.actions()`. That branch
 * had no horizon, no counter and no log line. A train whose route terminus is rear-facing — one
 * the dispatcher never extends — therefore waited there to the run's end time while holding its
 * reserved block, and nothing in the logs or metrics told the difference between that livelock and
 * a healthy train waiting a moment for its next route.
 *
 * `Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS` gives that wait a diagnostic surface: one WARN
 * naming the train and the separator it is stuck at. [stallWarningNamesTheTrainOnce] pins it, and
 * [cleanBaselineRunLogsNoStallWarning] is the calibration gate — a healthy `vyhybna` run must
 * never trip it, otherwise the horizon is set too tight.
 *
 * The `errorStop` half of the bound is pinned in `:core`'s `Issue943OwnershipConflictStallBoundTest`,
 * where the new `Train.kt` lines also land in `:core`'s coverage report. This test lives here
 * because the WARN itself can only be asserted where logback is on the test compile classpath:
 * `:core`'s test source set has it as `runtimeOnly` only. Same reason as [MergeAbortSimSurvivalTest].
 *
 * ## How the scenario is produced
 *
 * The decorator stalls exactly one train — the first to query a path from a non-`DynamicInOut`
 * separator, i.e. the first train under way and holding track — by returning
 * [PathResult.OwnershipConflict] for it forever. Every other train keeps the real service, so the
 * layout goes on working and simulated time keeps advancing around the stalled train. It is
 * injected through [NavigationDecoratingContext], which also overrides `createPathAvailableCondition` so
 * the stalled train's wait condition consults the decorated service and never becomes true.
 */
@DisplayName("Issue #943: a never-extended train is named in a WARN before the run stops")
@Tag("integration-test")
class OwnershipConflictStallWarningTest : DispatcherKoinTestBase() {
	private val fixture = LiftedStackFixture()

	private lateinit var context: DefaultSimulationContext
	private lateinit var appender: ListAppender<ILoggingEvent>
	private lateinit var rootLogger: Logger

	@BeforeEach
	fun setUp() {
		context = TestFixtures.newShuntingSimulationContext()
		// Attached to the ROOT logger rather than a named one: `Train`'s logger is declared in a
		// companion object, so its name depends on how kotlin-logging derives it, and this
		// assertion must not.
		rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		appender = ListAppender<ILoggingEvent>().apply { start() }
		rootLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		rootLogger.detachAppender(appender)
		appender.stop()
		context.close()
	}

	/** Stall WARNs that name [trainId]. */
	private fun warningsNaming(trainId: String): List<String> =
		warningsContaining(STALL_WARN_FRAGMENT).filter { it.contains(trainId) }

	/** Origin stall WARNs that name [trainId]. */
	private fun warningsNamingOrigin(trainId: String): List<String> =
		warningsContaining(ORIGIN_STALL_WARN_FRAGMENT).filter { it.contains(trainId) }

	/** WARN messages containing [fragment]. */
	private fun warningsContaining(fragment: String): List<String> =
		appender.list
			.filter { it.level == Level.WARN }
			.map { it.formattedMessage }
			.filter { it.contains(fragment) }

	/**
	 * The stalled train is named in exactly one WARN — one per stall episode, not one per wake-up —
	 * and that WARN is already in the log when the run is stopped, so it is an early warning rather
	 * than a duplicate of the fatal message.
	 *
	 * Other trains may warn too, and that is correct: the stalled train holds a block, so a train
	 * behind it can genuinely wait past the horizon as well. The one-shot property is therefore
	 * asserted per train, not per run.
	 */
	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun stallWarningNamesTheTrainOnce() {
		val realNav = context.getRoutingServices().getTrainNavigationService()
		val stalledTrainId = AtomicReference<String?>(null)
		val stallingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->

				if (separator !is DynamicInOut) {
					stalledTrainId.compareAndSet(null, trainId)
				}
				return@decoratingTrainNavigationService if (trainId == stalledTrainId.get() && separator !is DynamicInOut) {
					PathResult.OwnershipConflict
				} else {
					realNav.findReservedPathForTrain(trainId, separator)
				}
			}

		val capturedErrors = CopyOnWriteArrayList<Throwable>()
		// How many WARNs naming the stalled train were already logged when the run was stopped
		// because of that same train: the ordering check.
		val warningsAtErrorStop = AtomicInteger(-1)
		val stallingContext =
			NavigationDecoratingContext(context, stallingNav) { error ->
				val trainId = stalledTrainId.get()
				val message = error.message.orEmpty()
				if (trainId != null && message.contains(ERROR_STOP_FRAGMENT) && message.contains(trainId)) {
					warningsAtErrorStop.compareAndSet(-1, warningsNaming(trainId).size)
				}
				capturedErrors.add(error)
			}

		val loop = runShuntingLoop(context, SIM_END_TIME, env = stallingContext)

		// The scenario engaged: a train really was stalled mid-journey.
		assertThat(stalledTrainId.get(), name = "stalled train id").isNotNull()

		// Exactly one WARN names the stalled train — one per stall episode, not one per wake-up.
		val trainId = stalledTrainId.get()!!
		assertThat(warningsNaming(trainId), name = "stall WARNs naming $trainId").hasSize(1)

		// The run was then stopped for that train, and its WARN was already in the log by then.
		assertThat(
			capturedErrors.firstOrNull {
				it.message?.contains(ERROR_STOP_FRAGMENT) == true && it.message?.contains(trainId) == true
			},
			name = "captured stall errorStop for $trainId"
		).isNotNull()
		assertThat(warningsAtErrorStop.get(), name = "WARNs naming $trainId logged before its errorStop")
			.isEqualTo(1)
	}

	/**
	 * Calibration gate: a healthy `vyhybna` run must never trip the stall WARN. If this fails, the
	 * WARN horizon is too tight for legitimate waits — raise the horizon rather than relax the
	 * assertion.
	 */
	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun cleanBaselineRunLogsNoStallWarning() {
		val loop = runShuntingLoop(context, SIM_END_TIME)

		// Sanity: the run really did dispatch, so "no WARN" is not "no work".
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)

		assertThat(warningsContaining(STALL_WARN_FRAGMENT), name = "stall WARNs").isEmpty()
		assertThat(warningsContaining(ORIGIN_STALL_WARN_FRAGMENT), name = "origin stall WARNs").isEmpty()
	}

	/**
	 * A train the dispatcher never admits gets an **origin-worded** WARN that names it, before the
	 * run is stopped. The mid-journey test stalls a train that has already left its entry `InOut`
	 * (`current != null`); this one stalls a train **at** its entry `InOut` (`current == null`), so
	 * it drives the `atOrigin == true` branch of the stall message (Train.kt:290–297 / 303–308) the
	 * other test never reaches. The origin wording ("reserve its entry route" / "no entry route
	 * reserved") is thus pinned, not left untested — the Issue #943 origin-vs-under-way distinction.
	 */
	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	fun originStallWarningNamesTheTrainOnce() {
		val realNav = context.getRoutingServices().getTrainNavigationService()
		val stalledTrainId = AtomicReference<String?>(null)
		// Stall the first train that queries from its entry InOut — `separator is DynamicInOut` —
		// forever, so it never enters a block and `current` stays null (the `atOrigin` branch).
		val stallingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->

				if (separator is DynamicInOut) {
					stalledTrainId.compareAndSet(null, trainId)
				}
				return@decoratingTrainNavigationService if (trainId == stalledTrainId.get() && separator is DynamicInOut) {
					PathResult.OwnershipConflict
				} else {
					realNav.findReservedPathForTrain(trainId, separator)
				}
			}

		val capturedErrors = CopyOnWriteArrayList<Throwable>()
		val warningsAtErrorStop = AtomicInteger(-1)
		val stallingContext =
			NavigationDecoratingContext(context, stallingNav) { error ->
				val trainId = stalledTrainId.get()
				val message = error.message.orEmpty()
				if (trainId != null && message.contains(ORIGIN_ERROR_STOP_FRAGMENT) && message.contains(trainId)) {
					warningsAtErrorStop.compareAndSet(-1, warningsNamingOrigin(trainId).size)
				}
				capturedErrors.add(error)
			}

		val loop = runShuntingLoop(context, SIM_END_TIME, env = stallingContext)

		// The scenario engaged: a train really was stalled at its entry InOut.
		val trainId = stalledTrainId.get()
		assertThat(trainId, name = "stalled train id").isNotNull()

		// Exactly one origin-worded WARN names it — one per stall episode, not one per wake-up.
		assertThat(warningsNamingOrigin(trainId!!), name = "origin stall WARNs naming $trainId").hasSize(1)

		// The run was stopped for that train with the origin errorStop, and its WARN preceded the stop.
		val originStop =
			capturedErrors.firstOrNull {
				it.message?.contains(ORIGIN_ERROR_STOP_FRAGMENT) == true && it.message?.contains(trainId) == true
			}
		assertThat(originStop, name = "captured origin errorStop for $trainId").isNotNull()
		assertThat(warningsAtErrorStop.get(), name = "origin WARNs naming $trainId logged before its errorStop")
			.isEqualTo(1)
	}

	/**
	 * A train that stalls, is then given its path (resolving the stall), and stalls again is named
	 * in **two** WARNs — one per stall episode — because a successful navigation resets the wait
	 * bookkeeping (Train.kt:517 `resetOwnershipConflictWait()`). This pins Issue #943 requirement 3:
	 * any navigation outcome other than a further OwnershipConflict restarts the horizon clock, so a
	 * re-stall measures from its own beginning and can WARN again. Without the reset the WARN latch
	 * stays set and the second stall goes straight to `errorStop` with no second WARN.
	 *
	 * Mechanism: the decorator forces `OwnershipConflict` at one mid-journey separator until the
	 * first WARN lands, then stops forcing it *only while the train is still at that separator* — so
	 * the dispatcher can reserve the next section, the event-driven wait wakes on its own
	 * path-available condition (Train.kt:281/517 resets the clock), and the train moves on. The
	 * instant the train reaches a different separator, the decorator forces `OwnershipConflict`
	 * again, so the train re-stalls and must WARN a second time from its own new clock.
	 */
	@Test
	@Timeout(value = 360, unit = TimeUnit.SECONDS)
	fun stallWarnsTwiceAfterResolveAndRestall() {
		val realNav = context.getRoutingServices().getTrainNavigationService()
		val stalledTrainId = AtomicReference<String?>(null)
		val stalledSeparator = AtomicReference<PathSeparator?>(null) // where the first stall happens
		val warnedForStalled = AtomicInteger(0) // flips to 1 once the first mid-journey WARN lands

		// Tail-scan the appender for the first WARN naming the stalled train, so the scan stays O(n)
		// in total events rather than O(n^2) in nav calls (the wait re-queries after every event).
		var scanFrom = 0

		fun firstWarnSeen(trainId: String): Boolean {
			if (warnedForStalled.get() == 1) return true
			val events = appender.list
			while (scanFrom < events.size) {
				val event = events[scanFrom++]
				if (event.level == Level.WARN &&
					event.formattedMessage.contains(trainId) &&
					event.formattedMessage.contains(STALL_WARN_FRAGMENT)
				) {
					warnedForStalled.set(1)
					return true
				}
			}
			return false
		}

		val stallingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->

				if (separator !is DynamicInOut && stalledTrainId.compareAndSet(null, trainId)) {
					stalledSeparator.compareAndSet(null, separator)
				}
				val stalled = trainId == stalledTrainId.get() && separator !is DynamicInOut
				if (!stalled) return@decoratingTrainNavigationService realNav.findReservedPathForTrain(trainId, separator)

				// Phase 1: force OwnershipConflict until the first WARN lands.
				if (!firstWarnSeen(trainId)) return@decoratingTrainNavigationService PathResult.OwnershipConflict

				// Phase 2: still at the stall separator — stop forcing, so the dispatcher can
				// reserve the next section and the wait wakes on its own path-available condition
				// (Train.kt:281/517 resets the clock and the train moves on).
				// Phase 3: the train has reached a different separator — force a fresh stall that
				// must WARN anew from its own new clock.
				val stallSep = stalledSeparator.get()
				return@decoratingTrainNavigationService if (stallSep != null && separator === stallSep) {
					realNav.findReservedPathForTrain(trainId, separator)
				} else {
					PathResult.OwnershipConflict
				}
			}

		val capturedErrors = CopyOnWriteArrayList<Throwable>()
		val warningsAtErrorStop = AtomicInteger(-1)
		val stallingContext =
			NavigationDecoratingContext(context, stallingNav) { error ->
				val trainId = stalledTrainId.get()
				val message = error.message.orEmpty()
				if (trainId != null && message.contains(ERROR_STOP_FRAGMENT) && message.contains(trainId)) {
					warningsAtErrorStop.compareAndSet(-1, warningsNaming(trainId).size)
				}
				capturedErrors.add(error)
			}

		val loop = runShuntingLoop(context, SIM_END_TIME, env = stallingContext)

		val trainId = stalledTrainId.get()
		assertThat(trainId, name = "stalled train id").isNotNull()

		// Two WARNs naming the stalled train — one per stall episode, proving the latch reset.
		val stallWarns = warningsNaming(trainId!!)
		assertThat(stallWarns, name = "stall WARNs naming $trainId").hasSize(2)

		// Each WARN fired after ~60 s of its OWN stall (the WARN horizon), so the second measured
		// from the reset, not from the original start — proving the clock restarted. A re-stall
		// measured from the original start would read ~120 s here.
		//
		// `toInt()` cannot lose anything here. The waited time is Double simulated seconds, but
		// `Train.waitForPathOrReportStall` truncates it with its own `.toInt()` before formatting, so
		// the `after N s` field this regex captures is already the whole-second integer the production
		// code emitted. The band below is asserted against that emitted integer, not against the
		// Double horizon constant.
		val waitedSeconds =
			stallWarns.map {
				WARN_WAITED_REGEX
					.find(it)
					?.groupValues
					?.get(1)
					?.toInt()
			}
		assertThat(
			waitedSeconds.all { it != null && it in 50..90 },
			name = "each WARN measured ~60 s of its own stall"
		).isEqualTo(true)

		// The run was stopped for that train, and both WARNs were already logged by then.
		val stop =
			capturedErrors.firstOrNull {
				it.message?.contains(ERROR_STOP_FRAGMENT) == true && it.message?.contains(trainId) == true
			}
		assertThat(stop, name = "captured stall errorStop for $trainId").isNotNull()
		assertThat(warningsAtErrorStop.get(), name = "stall WARNs naming $trainId logged before its errorStop")
			.isEqualTo(2)
	}

	/**
	 * Calibration gate via the lifted dispatcher-agent stack: a healthy `vyhybna` run through
	 * [AgentLoopDriver] + [DispatchDecisionApplier] + [RuleBasedDispatcher] must emit no stall
	 * WARNs. If this fails, the WARN horizon is too tight for the lifted-stack path — raise
	 * [Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS] rather than relax the assertion.
	 *
	 * This test closes the calibration gap noted in Issue #946: [cleanBaselineRunLogsNoStallWarning]
	 * exercises the synchronous wiring path ([wireSynchronousDispatcher]), where the dispatcher is
	 * called synchronously on the kDisco sim thread once per control step, so it always acts within
	 * the same tick as the observation. The lifted stack — which the AI/LLM dispatcher uses in
	 * production — runs the driver on a separate thread and applies decisions asynchronously: it can
	 * skip ticks or be delayed relative to the sim thread. With the lock-step handshake (one driver
	 * cycle per sim tick), this test holds the same freshness guarantee as the synchronous path
	 * while running the full production component stack, so any gap between the two wiring paths
	 * that could let the WARN horizon fire spuriously is caught here.
	 *
	 * The lock-step handshake is the crucial detail: without it the driver thread and the kDisco
	 * sim thread race freely, and the sim can advance many control steps — many simulated seconds —
	 * before the driver posts decisions. That is the large-tickPeriodMs risk the issue names.
	 * Lock-step eliminates the race inside this test; the corresponding guard for production is the
	 * `SnapshotSignal` pacing that ensures the driver wakes on each control step even when
	 * `tickPeriodMs` imposes a wall-clock floor above the control-step period.
	 */
	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun cleanBaselineRunLogsNoStallWarningLiftedStack() {
		val liftedContext = fixture.loadShuntingLoopContext()
		try {
			// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
			liftedContext.getInOuts()
			val loop = ShuntingLoop(liftedContext, SIM_END_TIME)
			val run = fixture.run(loop, liftedContext)

			// Sanity: the run really did dispatch, so "no WARN" is not "no work".
			assertThat(run.trainsExited()).isGreaterThanOrEqualTo(1)

			assertThat(warningsContaining(STALL_WARN_FRAGMENT), name = "stall WARNs (lifted stack)").isEmpty()
			assertThat(warningsContaining(ORIGIN_STALL_WARN_FRAGMENT), name = "origin stall WARNs (lifted stack)").isEmpty()
		} finally {
			liftedContext.close()
		}
	}

	private companion object {
		/** Distinctive fragment of the mid-journey stall WARN. */
		const val STALL_WARN_FRAGMENT = "for the dispatcher to extend its route"

		/** Distinctive fragment of the origin stall WARN. */
		const val ORIGIN_STALL_WARN_FRAGMENT = "for the dispatcher to reserve its entry route"

		/** Distinctive fragment of the mid-journey stall `errorStop` message. */
		const val ERROR_STOP_FRAGMENT = "no route extension"

		/** Distinctive fragment of the origin stall `errorStop` message (distinct from mid-journey). */
		const val ORIGIN_ERROR_STOP_FRAGMENT = "no entry route reserved"

		/**
		 * Extracts the `after N s` horizon value from a stall WARN, to check each episode's own clock.
		 * `\d+` and not a decimal pattern on purpose: the WARN reports whole seconds, rounded down.
		 */
		val WARN_WAITED_REGEX = Regex("after (\\d+) s")

		/**
		 * Long enough for admission, the train to get under way, and both horizons (60 s WARN,
		 * 180 s errorStop) to elapse; the run ends as soon as the second one fires.
		 */
		const val SIM_END_TIME = 400L
	}
}
