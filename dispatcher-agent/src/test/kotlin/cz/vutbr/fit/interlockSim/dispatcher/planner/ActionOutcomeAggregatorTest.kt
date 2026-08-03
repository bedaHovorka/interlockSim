/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isZero
import ch.qos.logback.classic.Level
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit tests for [ActionOutcomeAggregator] — the production [ActionOutcomeSink] wired into
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] so SP2c.20 attribution data
 * (Issue #843) actually reaches somewhere in a live run, not just in tests.
 */
@DisplayName("ActionOutcomeAggregator — production ActionOutcomeSink (SP2c.20 follow-up)")
class ActionOutcomeAggregatorTest {
	private fun outcome(
		phase: ActionPhase = ActionPhase.APPLIED,
		rejection: RejectionCode? = null,
		applyFailure: ApplyFailureCode? = null,
		author: ActionAuthor = ActionAuthor.LLM
	) = ActionOutcome(
		phase = phase,
		rejection = rejection,
		applyFailure = applyFailure,
		authored = AuthoredAction(author, "", "RequestRoute", 1L)
	)

	@Test
	@DisplayName("a fresh aggregator has zero counts everywhere")
	fun freshAggregatorIsEmpty() {
		val aggregator = ActionOutcomeAggregator()
		val snapshot = aggregator.getSnapshot()
		assertThat(snapshot.totalOutcomes).isZero()
		ActionPhase.entries.forEach { assertThat(snapshot.countsByPhase.getValue(it)).isZero() }
		ApplyFailureCode.entries.forEach { assertThat(snapshot.countsByApplyFailure.getValue(it)).isZero() }
		ActionAuthor.entries.forEach { assertThat(snapshot.countsByAuthor.getValue(it)).isZero() }
	}

	@Test
	@DisplayName("counts by phase increment correctly")
	fun countsByPhase() {
		val aggregator = ActionOutcomeAggregator()
		aggregator.onActionOutcome(outcome(phase = ActionPhase.APPLIED))
		aggregator.onActionOutcome(outcome(phase = ActionPhase.APPLIED))
		aggregator.onActionOutcome(
			outcome(phase = ActionPhase.APPLIED_THEN_FAILED, applyFailure = ApplyFailureCode.ALL_PATHS_BLOCKED)
		)

		val snapshot = aggregator.getSnapshot()
		assertThat(snapshot.totalOutcomes).isEqualTo(3L)
		assertThat(snapshot.countsByPhase.getValue(ActionPhase.APPLIED)).isEqualTo(2L)
		assertThat(snapshot.countsByPhase.getValue(ActionPhase.APPLIED_THEN_FAILED)).isEqualTo(1L)
	}

	@Test
	@DisplayName("counts by applyFailure only increment for non-null codes")
	fun countsByApplyFailure() {
		val aggregator = ActionOutcomeAggregator()
		aggregator.onActionOutcome(outcome(phase = ActionPhase.APPLIED, applyFailure = null))
		aggregator.onActionOutcome(
			outcome(phase = ActionPhase.APPLIED_THEN_FAILED, applyFailure = ApplyFailureCode.ALL_PATHS_BLOCKED)
		)
		aggregator.onActionOutcome(
			outcome(phase = ActionPhase.APPLIED_THEN_FAILED, applyFailure = ApplyFailureCode.CONFLICT)
		)

		val snapshot = aggregator.getSnapshot()
		assertThat(snapshot.countsByApplyFailure.getValue(ApplyFailureCode.ALL_PATHS_BLOCKED)).isEqualTo(1L)
		assertThat(snapshot.countsByApplyFailure.getValue(ApplyFailureCode.CONFLICT)).isEqualTo(1L)
		assertThat(snapshot.countsByApplyFailure.getValue(ApplyFailureCode.NO_ROUTE_EXISTS)).isZero()
	}

	@Test
	@DisplayName("counts by author increment correctly")
	fun countsByAuthor() {
		val aggregator = ActionOutcomeAggregator()
		aggregator.onActionOutcome(outcome(author = ActionAuthor.LLM))
		aggregator.onActionOutcome(outcome(author = ActionAuthor.LLM))
		aggregator.onActionOutcome(outcome(author = ActionAuthor.RULE_BASED))

		val snapshot = aggregator.getSnapshot()
		assertThat(snapshot.countsByAuthor.getValue(ActionAuthor.LLM)).isEqualTo(2L)
		assertThat(snapshot.countsByAuthor.getValue(ActionAuthor.RULE_BASED)).isEqualTo(1L)
		assertThat(snapshot.countsByAuthor.getValue(ActionAuthor.RULE_FALLBACK)).isZero()
	}

	@Test
	@DisplayName("getSnapshot returns an immutable point-in-time view")
	fun snapshotIsPointInTime() {
		val aggregator = ActionOutcomeAggregator()
		aggregator.onActionOutcome(outcome())
		val first = aggregator.getSnapshot()
		aggregator.onActionOutcome(outcome())
		val second = aggregator.getSnapshot()

		assertThat(first.totalOutcomes).isEqualTo(1L)
		assertThat(second.totalOutcomes).isEqualTo(2L)
	}

	@Test
	@DisplayName("onActionOutcome produces no log output (sim-thread no-logging contract)")
	fun onActionOutcomeProducesNoLogOutput() {
		val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger
		val originalLevel = rootLogger.level
		rootLogger.level = Level.TRACE
		val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
		rootLogger.addAppender(appender)
		appender.start()
		try {
			val aggregator = ActionOutcomeAggregator()
			appender.list.clear()
			aggregator.onActionOutcome(outcome())
			assertThat(appender.list.size).isZero()
		} finally {
			rootLogger.detachAppender(appender)
			rootLogger.level = originalLevel
		}
	}
}
