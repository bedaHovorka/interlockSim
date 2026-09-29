/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The four semaphoreAction logic-error guards fail loudly with their own formatter text.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.single
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.UncaughtSimulationExceptions
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.runHoldAtSeparatorScenario
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.test.inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * The four `semaphoreAction` "logic error" guards (Issue #1006), driven end to end: the unit
 * test in `TrainSemaphoreGuardMessageTest` pins the formatter texts, this class pins that each
 * guard actually fires with its own text.
 *
 * The guards sit in branches a correct interlocking cannot reach by construction: a signal
 * only turns allowing when the path is reserved, so a re-fetch that answers anything but
 * [PathResult.Available] is a logic error and must fail loudly. The rungs therefore force the
 * answers through the navigation-injection seam (the same one
 * [OwnershipConflictStandRestartTest] drives) and pin:
 *
 * - [resumingFromStopOntoAFailedReFetchFailsLoudly] — the STOP branch's resume re-fetch
 *   answers `NoTopologicalPath` or `OwnershipConflict`: the guard logs that variant's own
 *   error line and the resume-path guard throws;
 * - [startingFromAStandWithAllowingSignalAndNullPathFailsLoudly] — an allowing signal at a
 *   stand whose first fetch answered `OwnershipConflict`: the pre-existing-path guard throws.
 *
 * Every thrown message is asserted to equal the matching `Train.format*Message(...)` output,
 * so the call sites route through the formatters rather than inline literals.
 *
 * ## How the throw is observed
 *
 * The guards throw from inside the train's kDisco process, which kDisco runs under a
 * `SupervisorJob`: the throw neither stops the run nor leaves `context.run()`, it reaches the
 * thread's `UncaughtExceptionHandler` and the train process is gone. That is the known gap
 * [UncaughtSimulationExceptions] exists for (Issue #1025), not a contract this class endorses;
 * the rungs record the escape with it and also pin that no train exits.
 */
@Tag("integration-test")
@DisplayName("The semaphoreAction logic-error guards fail loudly (Issue #1006)")
class SemaphoreActionGuardRegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>

	private companion object {
		/** `B —100 m— zB`: the stand lands at the separator itself, the aspect still allowing. */
		const val DISTANCE_TO_ZB = 100.0

		/** Train length; short enough to stand at the held separator with its tail clear of B. */
		const val TRAIN_LENGTH = 20.0

		/** How long the train is held before the lift; every wake-up has settled by then. */
		const val STAND_HOLD_SECONDS = 2.0

		/** The stand is at about 11 s and the guard fires one sample after the lift at about 13 s. */
		const val END_TIME = 30L

		/** The separator whose queries the scenario decorates. */
		const val HOLD_SIGNAL = "zB"

		/**
		 * How many queries at the lifted separator get the real result in the stand rung, before
		 * `semaphoreAction`'s own fetch gets the failure: kDisco's wake of the conflict wait (the
		 * notice's test plus `waitUntil`'s own re-test on resume), `waitForPathOrReportStall`'s
		 * post-wake retest, and the actions-loop re-fetch. No report or other observable event
		 * separates the loop re-fetch from `semaphoreAction`'s fetch, so this rung counts; a
		 * shifted count fails loudly, because the guard then never fires.
		 */
		const val REAL_ANSWERS_AT_A_STAND = 4

		/** The two re-fetch answers the STOP rung injects, each with its own error-line formatter. */
		@JvmStatic
		fun failedReFetches(): List<Arguments> =
			listOf(
				Arguments.of(
					Named.of("NoTopologicalPath", PathResult.NoTopologicalPath),
					Train.Companion::formatNoTopologicalPathWhileResumingMessage
				),
				Arguments.of(
					Named.of("OwnershipConflict", PathResult.OwnershipConflict),
					Train.Companion::formatOwnershipConflictWhileResumingMessage
				)
			)
	}

	private class GuardOutcome(
		val uncaught: List<Throwable>,
		val trainsExited: Int,
		val trainNumber: Int
	)

	@BeforeEach
	fun setUp() {
		// `sim.Train` is pinned to WARN in logback-test.xml and the resuming-rung error lines are
		// ERROR, so no level change is needed: the appender on the root logger receives them.
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		rootLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		rootLogger.detachAppender(appender)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("failedReFetches")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("resuming from a STOP signal onto a failed re-fetch fails loudly")
	fun resumingFromStopOntoAFailedReFetchFailsLoudly(
		injectedFailure: PathResult,
		errorLine: (Int, String) -> String
	) {
		val outcome = runGuardScenario(stopBranch = true, injectedFailure = injectedFailure)

		assertGuardFired(outcome, Train.formatResumePathNullMessage(outcome.trainNumber, HOLD_SIGNAL))
		assertThat(appender.list.map { it.formattedMessage })
			.contains(errorLine(outcome.trainNumber, HOLD_SIGNAL))
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("starting from a stand with an allowing signal and a null path fails loudly")
	fun startingFromAStandWithAllowingSignalAndNullPathFailsLoudly() {
		val outcome = runGuardScenario(stopBranch = false, injectedFailure = PathResult.OwnershipConflict)

		assertGuardFired(outcome, Train.formatPreExistingPathNullMessage(outcome.trainNumber, HOLD_SIGNAL))
	}

	// ── Shared ────────────────────────────────────────────────────────────────────

	/** Exactly one exception escaped, it is the guard's with [expectedMessage], and the train died. */
	private fun assertGuardFired(
		outcome: GuardOutcome,
		expectedMessage: String
	) {
		assertThat(outcome.uncaught).single().transform { it.message }.isEqualTo(expectedMessage)
		assertThat(outcome.uncaught.single()).isInstanceOf<SimulationException>()
		assertThat(outcome.trainsExited).isEqualTo(0)
	}

	/**
	 * One run of the guard scenario: the train stands at `zB` held by an ownership conflict,
	 * then the hold lifts. [stopBranch] picks the branch each rung drives:
	 *
	 * - `true` — `zB` turns STOP at the stand, so the lift delivers the train into
	 *   `semaphoreAction`'s STOP branch; the next sample turns `zB` allowing. The train reports
	 *   `OK <aspect>` as `TRAIN_EVENTS` the moment its wait for the allowing signal ends, and the
	 *   resume re-fetch is the next call after that report — so every query gets the real result
	 *   until the report arrives, and the one after it gets [injectedFailure].
	 * - `false` — `zB` stays allowing, so the lift delivers the train into the stand branch at
	 *   near-zero velocity; [REAL_ANSWERS_AT_A_STAND] queries get the real result and
	 *   `semaphoreAction`'s own fetch gets [injectedFailure], leaving its `path` null.
	 *
	 * Every callback and every answer runs on the one simulation thread, so plain `var`s are
	 * enough.
	 */
	private fun runGuardScenario(
		stopBranch: Boolean,
		injectedFailure: PathResult
	): GuardOutcome {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val zB = context.cellsOfType<DynamicRailSemaphore>().single { it.name == HOLD_SIGNAL }

		var holding = true
		var resumedFromStop = false
		var queriesAfterLift = 0
		var trainNumber = -1

		context.addReportTypes(ReportType.TRAIN_EVENTS)
		context.addPropertyChangeListener(
			ContextPropertyChangeListener { event ->
				// Only after the lift: an earlier `OK` belongs to a signal the train passed before zB.
				val isTrainEvent = event.propertyName == ReportType.TRAIN_EVENTS.name
				if (!holding && isTrainEvent && (event.newValue as String).contains(" OK ")) {
					resumedFromStop = true
				}
			}
		)

		val run =
			UncaughtSimulationExceptions.record {
				runHoldAtSeparatorScenario(
					context,
					holdSignal = HOLD_SIGNAL,
					standThreshold = DISTANCE_TO_ZB / 2,
					endTime = END_TIME,
					trainLength = TRAIN_LENGTH,
					standHoldSeconds = STAND_HOLD_SECONDS,
					holding = { holding },
					answerAfterLift = { realNav, trainId, separator ->
						val failNow =
							if (stopBranch) resumedFromStop else ++queriesAfterLift > REAL_ANSWERS_AT_A_STAND
						// The real Available carries the reserved path the loop and semaphoreAction
						// act on, not only the verdict.
						if (failNow) injectedFailure else realNav.findReservedPathForTrain(trainId, separator)
					},
					onStand = { observation ->
						trainNumber = observation.train.trainNumber
						if (stopBranch) {
							zB.signal = Signal.STOP
						}
					},
					onHoldElapsed = { holding = false },
					onSample = {
						// Only once the train is parked in the STOP branch's wait may zB turn
						// allowing — an earlier flip would take the stand branch instead.
						// onHoldElapsed runs after onSample of its own sample, so the first sample
						// seen here after the lift is one period later.
						if (stopBranch && !holding && zB.signal == Signal.STOP) {
							zB.signal = Signal.FREE
						}
					}
				)
			}
		return GuardOutcome(
			uncaught = run.uncaught,
			trainsExited = run.value.process.getTrainsExited(),
			trainNumber = trainNumber
		)
	}
}
