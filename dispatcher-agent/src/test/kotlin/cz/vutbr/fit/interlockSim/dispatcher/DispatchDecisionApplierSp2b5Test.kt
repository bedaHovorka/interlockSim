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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.toRationaleLogSuffix
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit tests for the SP2b.5 rationale-logging feature (Issue #560 — Goal 10).
 *
 * Covers two layers:
 * - The shared [toRationaleLogSuffix] formatter (now a top-level extension in `:core`,
 *   used by both the synchronous `applyToolDrivenToActuator` path and the asynchronous
 *   [DispatchDecisionApplier] path).
 * - The integration point: [DispatchDecisionApplier.applyDecision] actually appends the
 *   formatted rationale suffix to the emitted DEBUG log line — verified via a Logback
 *   `ListAppender` so the test fails if the `+ rationale.toRationaleLogSuffix()` call is
 *   removed (the isolation tests alone would not catch that).
 *
 * @since Issue #560 (SP2b.5 — Goal 10)
 */
@DisplayName("DispatchDecisionApplier — SP2b.5 rationale logging (Issue #560)")
@Timeout(10, unit = TimeUnit.SECONDS)
class DispatchDecisionApplierSp2b5Test {
	@Nested
	@DisplayName("toRationaleLogSuffix")
	inner class ToRationaleLogSuffix {
		@Test
		@DisplayName("empty list produces empty string (no clutter for decisions without rationale)")
		fun emptyListProducesEmptyString() {
			assertThat(emptyList<String>().toRationaleLogSuffix()).isEmpty()
		}

		@Test
		@DisplayName("single-entry list produces a suffix containing 'rationale' and the entry text")
		fun singleEntryProducesSuffix() {
			val suffix = listOf("Slot available").toRationaleLogSuffix()
			assertThat(suffix).contains("rationale")
			assertThat(suffix).contains("Slot available")
		}

		@Test
		@DisplayName("multi-entry list joins entries with '; ' separator")
		fun multiEntryJoinsWithSemicolon() {
			val suffix = listOf("Rule 1", "Rule 2", "Rule 3").toRationaleLogSuffix()
			assertThat(suffix).contains("Rule 1; Rule 2; Rule 3")
		}

		@Test
		@DisplayName("suffix includes opening and closing bracket delimiters")
		fun suffixHasBracketDelimiters() {
			val suffix = listOf("entry").toRationaleLogSuffix()
			assertThat(suffix).contains("[")
			assertThat(suffix).contains("]")
		}
	}

	// ── applyDecision appends rationale to the actual log line (Issue #560) ──────

	/**
	 * Verifies the integration point: a queued [DispatchDecision.ApproveTrain] with a
	 * non-empty rationale produces a DEBUG log event whose formatted message carries
	 * the `" | rationale: […]"` suffix, and one with an empty rationale produces an
	 * ApproveTrain log line with no `rationale:` substring.
	 *
	 * The test attaches a `ListAppender` to the Logback **root** logger so it does not
	 * depend on the exact logger name kotlin-logging infers for the applier's companion
	 * logger.  It also temporarily raises both the root logger and the
	 * `cz.vutbr.fit.interlockSim.dispatcher` package logger to DEBUG — `logback-test.xml`
	 * pins the dispatcher package to WARN, which would otherwise suppress the applier's
	 * DEBUG line regardless of the root level.  Both levels are restored in `@AfterEach`.
	 */
	@Nested
	@DisplayName("applyDecision appends rationale to the log line")
	inner class ApplyDecisionLogSuffix {
		private lateinit var appender: ListAppender<ILoggingEvent>
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
		private var originalRootLevel: Level = Level.WARN
		private var originalDispatcherLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
			dispatcherLogger =
				LoggerFactory.getLogger("cz.vutbr.fit.interlockSim.dispatcher") as LogbackLogger
			originalRootLevel = rootLogger.level
			originalDispatcherLevel = dispatcherLogger.level
			rootLogger.level = Level.DEBUG
			dispatcherLogger.level = Level.DEBUG
			appender = ListAppender()
			rootLogger.addAppender(appender)
			appender.start()
		}

		@AfterEach
		fun detachAppender() {
			rootLogger.detachAppender(appender)
			rootLogger.level = originalRootLevel
			dispatcherLogger.level = originalDispatcherLevel
		}

		private fun makeApplier(): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
			val queue = ActuatorCommandQueue()
			val networkActuator: NetworkActuatorPort = mockk(relaxed = true)
			val applier = DispatchDecisionApplier(queue, networkActuator, { })
			return queue to applier
		}

		private fun approveTrainMessages(): List<String> =
			appender.list.map { it.formattedMessage }.filter { it.contains("Applying ApproveTrain") }

		@Test
		@DisplayName("ApproveTrain with non-empty rationale logs the suffix on the DEBUG line")
		fun approveTrainWithRationale_logsSuffix() {
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1", rationale = listOf("Slot available"))))

			applier.onControlStep()

			val messages = approveTrainMessages()
			assertThat(messages).isNotEmpty()
			assertThat(messages.any { it.contains("Applying ApproveTrain: trainId=T1") }).isTrue()
			assertThat(messages.any { it.contains(" | rationale: [Slot available]") }).isTrue()
		}

		@Test
		@DisplayName("ApproveTrain with empty rationale logs no 'rationale:' substring")
		fun approveTrainWithoutRationale_logsNoSuffix() {
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			val messages = approveTrainMessages()
			assertThat(messages).isNotEmpty()
			assertThat(messages.any { it.contains("Applying ApproveTrain: trainId=T1") }).isTrue()
			assertThat(messages.none { it.contains("rationale:") }).isTrue()
		}
	}
}
