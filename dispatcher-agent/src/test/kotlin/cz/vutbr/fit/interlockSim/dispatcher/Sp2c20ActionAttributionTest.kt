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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isZero
import ch.qos.logback.classic.Level
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Acceptance-criteria tests for SP2c.20 — action-level attribution, rejection codes, C7
 * violation gate (Issue #843).
 *
 * ## Acceptance criteria covered
 *
 * - `c7Clean = false` when [ActionAuthor.RULE_FALLBACK] or [ActionAuthor.SAFETY_NET] action
 *   is observed.
 * - `C7_VIOLATION` WARN fires **exactly once** per run (fire-once per log-capture test).
 * - `!! C7_VIOLATION !!` prefix appears in [DispatcherPreferenceStore.logFinalSummary] when
 *   `!c7Clean`.
 * - No prefix when `c7Clean = true`.
 * - [ActionOutcomeSink.onActionOutcome] path produces no log output (sim-thread contract).
 * - [ActionPhase.APPLIED_THEN_FAILED] / [ApplyFailureCode.ALL_PATHS_BLOCKED] is distinct
 *   from [ActionPhase.REJECTED_BY_VALIDATOR] — [ALL_PATHS_BLOCKED] must not count toward the
 *   invalid-output rate.
 * - New types ([ActionPhase], [AuthoredAction], [ActionOutcome], [ActionOutcomeSink]) exist in
 *   the `dispatcher/planner/` package.
 * - [ApplyFailureCode] has all new entries; [ApplyFailureCode.CAP_EXCEEDED_APPLY] replaces the
 *   old `CAP_EXCEEDED` name.
 * - [ActionAuthor.TIMEOUT_NOOP] dispatching action count is always zero (P3-style check).
 *
 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
 */
@DisplayName("SP2c.20 — action attribution, rejection codes, C7 violation gate (#843)")
@Timeout(10, unit = TimeUnit.SECONDS)
class Sp2c20ActionAttributionTest {
	// ── Test utilities ──────────────────────────────────────────────────────

	private fun attributed(
		author: ActionAuthor,
		action: DispatchAction = DispatchAction.NoOp,
		tick: Long = 1L,
		reason: String = ""
	) = AttributedAction(
		commandId = CommandId(0L),
		tick = tick,
		action = action,
		author = author,
		reason = reason
	)

	private fun record(
		tick: Long = 1L,
		simTime: Double = 10.0,
		actions: List<AttributedAction> = emptyList()
	) = TickRecord(
		tick = tick,
		simTime = simTime,
		stateDigest = "digest",
		actions = actions,
		verdicts = actions.map { ValidationVerdict.Valid },
		outcomes = emptyList()
	)

	// ── C7 gate: c7Clean latched flag ────────────────────────────────────────

	@Nested
	@DisplayName("c7Clean latched flag (SP2c.20)")
	inner class C7Clean {
		@Test
		@DisplayName("c7Clean is true when no actions have been observed")
		fun freshStoreIsC7Clean() {
			val store = DispatcherPreferenceStore()
			assertThat(store.c7Clean).isTrue()
		}

		@Test
		@DisplayName("c7Clean is true with LLM-only actions")
		fun llmOnlyActionsAreC7Clean() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					actions =
						listOf(
							attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1")),
							attributed(ActionAuthor.LLM, DispatchAction.NoOp)
						)
				)
			)
			assertThat(store.c7Clean).isTrue()
		}

		@Test
		@DisplayName("c7Clean is true with RULE_BASED actions (intentional rule-based run, not a violation)")
		fun ruleBasedActionsAreC7Clean() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(actions = listOf(attributed(ActionAuthor.RULE_BASED, DispatchAction.ApproveTrain("T-1"))))
			)
			assertThat(store.c7Clean).isTrue()
		}

		@Test
		@DisplayName("c7Clean is true with TIMEOUT_NOOP actions (safe no-op, not a violation)")
		fun timeoutNoopIsC7Clean() {
			val store = DispatcherPreferenceStore()
			store.observe(record(actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))))
			assertThat(store.c7Clean).isTrue()
		}

		@Test
		@DisplayName("c7Clean becomes false when a RULE_FALLBACK action is observed")
		fun ruleFallbackSetsC7CleanFalse() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(actions = listOf(attributed(ActionAuthor.RULE_FALLBACK, DispatchAction.ApproveTrain("T-1"))))
			)
			assertThat(store.c7Clean).isFalse()
		}

		@Test
		@DisplayName("c7Clean becomes false when a SAFETY_NET action is observed")
		fun safetyNetSetsC7CleanFalse() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(actions = listOf(attributed(ActionAuthor.SAFETY_NET, DispatchAction.ApproveTrain("T-1"))))
			)
			assertThat(store.c7Clean).isFalse()
		}

		@Test
		@DisplayName("c7Clean is false even if only one of many ticks has a RULE_FALLBACK action")
		fun singleRuleFallbackTickSetsC7CleanFalseForRun() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))
			store.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))
			store.observe(record(tick = 3L, actions = listOf(attributed(ActionAuthor.LLM))))
			assertThat(store.c7Clean).isFalse()
		}
	}

	// ── C7 gate: one-time WARN ───────────────────────────────────────────────

	@Nested
	@DisplayName("C7_VIOLATION one-time WARN (SP2c.20)")
	inner class C7ViolationWarn {
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
		private lateinit var appender: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
		private var originalRootLevel: Level = Level.WARN
		private var originalDispatcherLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger
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

		private fun c7ViolationWarns(): List<ch.qos.logback.classic.spi.ILoggingEvent> =
			appender.list.filter {
				it.level == Level.WARN && it.formattedMessage.contains("C7_VIOLATION")
			}

		@Test
		@DisplayName("C7_VIOLATION WARN fires once when first RULE_FALLBACK action is observed")
		fun c7ViolationWarnFiresOnceForRuleFallback() {
			val store = DispatcherPreferenceStore()

			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))

			val warns = c7ViolationWarns()
			assertThat(warns).hasSize(1)
			assertThat(warns.first().formattedMessage).contains("C7_VIOLATION")
		}

		@Test
		@DisplayName("C7_VIOLATION WARN fires once when first SAFETY_NET action is observed")
		fun c7ViolationWarnFiresOnceForSafetyNet() {
			val store = DispatcherPreferenceStore()

			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.SAFETY_NET))))

			val warns = c7ViolationWarns()
			assertThat(warns).hasSize(1)
		}

		@Test
		@DisplayName("C7_VIOLATION WARN fires exactly once even across multiple violating ticks")
		fun c7ViolationWarnFiresExactlyOnceAcrossMultipleViolations() {
			val store = DispatcherPreferenceStore()

			// Three consecutive ticks each with a RULE_FALLBACK action — WARN must fire only on tick 1
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))
			store.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))
			store.observe(record(tick = 3L, actions = listOf(attributed(ActionAuthor.SAFETY_NET))))

			// Exactly one WARN, not three
			val warns = c7ViolationWarns()
			assertThat(warns).hasSize(1)
		}

		@Test
		@DisplayName("No C7_VIOLATION WARN fires when all actions are LLM-authored")
		fun noC7WarnForLlmOnlyRun() {
			val store = DispatcherPreferenceStore()

			store.observe(
				record(
					actions =
						listOf(
							attributed(ActionAuthor.LLM),
							attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp)
						)
				)
			)
			store.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.LLM))))

			val warns = c7ViolationWarns()
			assertThat(warns).hasSize(0)
		}
	}

	// ── C7 gate: summary prefix ──────────────────────────────────────────────

	@Nested
	@DisplayName("logFinalSummary C7_VIOLATION prefix (SP2c.20)")
	inner class SummaryPrefix {
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
		private lateinit var appender: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
		private var originalRootLevel: Level = Level.WARN
		private var originalDispatcherLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger
			dispatcherLogger =
				LoggerFactory.getLogger("cz.vutbr.fit.interlockSim.dispatcher") as LogbackLogger
			originalRootLevel = rootLogger.level
			originalDispatcherLevel = dispatcherLogger.level
			rootLogger.level = Level.INFO
			dispatcherLogger.level = Level.INFO
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

		private fun finalSummaryMessages(): List<String> =
			appender.list
				.map { it.formattedMessage }
				.filter { it.contains("[DispatcherPreferenceStore] final summary —") }

		@Test
		@DisplayName("summary has no prefix when c7Clean is true (LLM-only run)")
		fun noC7PrefixWhenC7Clean() {
			val store = DispatcherPreferenceStore()
			store.observe(record(actions = listOf(attributed(ActionAuthor.LLM))))

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			assertThat(line).doesNotContain("!! C7_VIOLATION !!")
			assertThat(line).contains("[DispatcherPreferenceStore] final summary —")
		}

		@Test
		@DisplayName("summary is prefixed '!! C7_VIOLATION !!' when c7Clean is false (RULE_FALLBACK)")
		fun c7ViolationPrefixWhenRuleFallback() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(actions = listOf(attributed(ActionAuthor.RULE_FALLBACK, DispatchAction.ApproveTrain("T-1"))))
			)

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			assertThat(line).contains("!! C7_VIOLATION !!")
			assertThat(line).contains("[DispatcherPreferenceStore] final summary —")
		}

		@Test
		@DisplayName("summary is prefixed '!! C7_VIOLATION !!' when c7Clean is false (SAFETY_NET)")
		fun c7ViolationPrefixWhenSafetyNet() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(actions = listOf(attributed(ActionAuthor.SAFETY_NET, DispatchAction.ApproveTrain("T-1"))))
			)

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			assertThat(line).contains("!! C7_VIOLATION !!")
		}
	}

	// ── ActionOutcomeSink: no-logging contract ───────────────────────────────

	@Nested
	@DisplayName("ActionOutcomeSink no-logging contract (SP2c.20)")
	inner class ActionOutcomeSinkNoLogging {
		private lateinit var rootLogger: LogbackLogger
		private lateinit var appender: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
		private var originalRootLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger
			originalRootLevel = rootLogger.level
			rootLogger.level = Level.TRACE
			appender = ListAppender()
			rootLogger.addAppender(appender)
			appender.start()
		}

		@AfterEach
		fun detachAppender() {
			rootLogger.detachAppender(appender)
			rootLogger.level = originalRootLevel
		}

		@Test
		@DisplayName("ActionOutcomeSink.onActionOutcome is called by the applier without producing log output")
		fun actionOutcomeSinkProducesNoLogOutput() {
			// Record all outcomes, then check no logs were produced DURING onActionOutcome
			val outcomes = mutableListOf<ActionOutcome>()
			val logCountsAtCallTime = mutableListOf<Int>()
			val sink =
				ActionOutcomeSink { outcome ->
					// Capture log count at the moment of the call — a logging sink would increase it
					logCountsAtCallTime.add(appender.list.size)
					outcomes.add(outcome)
					// Deliberately no logging here — this is the correct contract
				}

			val queue = ActuatorCommandQueue()
			val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = networkActuator,
					onApproveTrain = {},
					actionOutcomeSink = sink
				)

			// Clear any log events from setup
			appender.list.clear()

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-1")))
			applier.onControlStep()

			// The sink was called exactly once (one ApproveTrain decision)
			assertThat(outcomes).hasSize(1)
			assertThat(outcomes.first().authored).isNotNull()

			// No new log entries were produced by the applier DURING the onActionOutcome callback
			// (the count at call time was 0 since we cleared before posting)
			assertThat(logCountsAtCallTime).hasSize(1)
			assertThat(logCountsAtCallTime.first()).isZero()
		}

		@Test
		@DisplayName("onActionOutcome is NOT called for NoAction decisions")
		fun noActionDecisionsDoNotTriggerSink() {
			val outcomes = mutableListOf<ActionOutcome>()
			val sink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }

			val queue = ActuatorCommandQueue()
			val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = networkActuator,
					onApproveTrain = {},
					actionOutcomeSink = sink
				)

			queue.postAll(listOf(DispatchDecision.NoAction))
			applier.onControlStep()

			assertThat(outcomes).hasSize(0)
		}

		@Test
		@DisplayName("ActionOutcomeSink is not called when null (backward-compat: no-op when not wired)")
		fun nullSinkIsNoOp() {
			// No assertion needed — just verify no NPE is thrown when sink is null (default)
			val queue = ActuatorCommandQueue()
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = mockk(relaxed = true),
					onApproveTrain = {}
					// actionOutcomeSink = null (default)
				)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-1")))
			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName(
			"tickIndex is -1 when the correlation map has no entry for the drained decision " +
				"(SP2c.20 follow-up, AC #4 end-to-end)"
		)
		fun tickIndexIsMinusOneWhenUnattributed() {
			val outcomes = mutableListOf<ActionOutcome>()
			val sink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			val correlationMap = CommandCorrelationMap()
			// The queue is NOT wired with correlationMap, so postAll below never registers this
			// decision — simulating a decision that reaches the applier without a correlation
			// entry (e.g. posted without going through the registration path).
			val queue = ActuatorCommandQueue()
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = mockk(relaxed = true),
					onApproveTrain = {},
					correlationMap = correlationMap,
					actionOutcomeSink = sink
				)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-1")))
			applier.onControlStep()

			assertThat(outcomes).hasSize(1)
			assertThat(outcomes.first().authored.tickIndex).isEqualTo(-1L)
			assertThat(correlationMap.unattributedApplies).isGreaterThanOrEqualTo(1L)
		}

		@Test
		@DisplayName(
			"DROPPED_INVALID branch: the onActionOutcome sink call itself produces no log output " +
				"(the preceding logger.warn is expected and allowed)"
		)
		fun droppedInvalidSinkCallProducesNoLogOutput() {
			val outcomes = mutableListOf<ActionOutcome>()
			val logCountsAtCallTime = mutableListOf<Int>()
			val sink =
				ActionOutcomeSink { outcome ->
					logCountsAtCallTime.add(appender.list.size)
					outcomes.add(outcome)
				}
			val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
			every { networkActuator.requestRoute(any(), any(), any()) } throws
				IllegalArgumentException("Unknown endpoint")
			val queue = ActuatorCommandQueue()
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = networkActuator,
					onApproveTrain = {},
					actionOutcomeSink = sink
				)

			queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
			applier.onControlStep()

			assertThat(outcomes).hasSize(1)
			assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.DROPPED_INVALID)
			// The applier's own logger.warn(e) fires BEFORE the sink call, so appender.list is
			// non-empty by then — the invariant under test is that the sink call itself adds
			// nothing on top: the log count at call time must equal the final log count.
			assertThat(logCountsAtCallTime).hasSize(1)
			assertThat(logCountsAtCallTime.first()).isEqualTo(appender.list.size)
		}
	}

	// ── ALL_PATHS_BLOCKED vs invalid-output rate ─────────────────────────────

	@Nested
	@DisplayName("ALL_PATHS_BLOCKED excluded from invalid-output rate (SP2c.20)")
	inner class AllPathsBlockedNotInvalidOutput {
		@Test
		@DisplayName("APPLIED_THEN_FAILED / ALL_PATHS_BLOCKED is distinct from REJECTED_BY_VALIDATOR")
		fun appliedThenFailedIsDistinctFromRejectedByValidator() {
			val blockedOutcome =
				ActionOutcome(
					phase = ActionPhase.APPLIED_THEN_FAILED,
					rejection = null,
					applyFailure = ApplyFailureCode.ALL_PATHS_BLOCKED,
					authored = AuthoredAction(ActionAuthor.LLM, "route attempt", "RequestRoute", 1L)
				)
			val rejectedOutcome =
				ActionOutcome(
					phase = ActionPhase.REJECTED_BY_VALIDATOR,
					rejection = RejectionCode.UNKNOWN_ENDPOINT,
					applyFailure = null,
					authored = AuthoredAction(ActionAuthor.LLM, "", "RequestRoute", 1L)
				)

			// APPLIED_THEN_FAILED is not REJECTED_BY_VALIDATOR
			assertThat(blockedOutcome.phase).isNotEqualTo(rejectedOutcome.phase)
			// ALL_PATHS_BLOCKED has no rejection code
			assertThat(blockedOutcome.rejection).isNull()
			// REJECTED_BY_VALIDATOR has no apply-failure code
			assertThat(rejectedOutcome.applyFailure).isNull()
		}

		@Test
		@DisplayName("ALL_PATHS_BLOCKED ActionOutcome is valid (invariants hold)")
		fun allPathsBlockedOutcomeIsValid() {
			val outcome =
				ActionOutcome(
					phase = ActionPhase.APPLIED_THEN_FAILED,
					rejection = null,
					applyFailure = ApplyFailureCode.ALL_PATHS_BLOCKED,
					authored = AuthoredAction(ActionAuthor.LLM, "no free path", "RequestRoute", 1L)
				)
			assertThat(outcome.phase).isNotEqualTo(ActionPhase.REJECTED_BY_VALIDATOR)
			assertThat(outcome.applyFailure).isNotNull()
		}
	}

	// ── New ApplyFailureCode entries ─────────────────────────────────────────

	@Nested
	@DisplayName("ApplyFailureCode expanded entries (SP2c.20)")
	inner class ApplyFailureCodeEntries {
		@Test
		@DisplayName("ApplyFailureCode has all required SP2c.20 entries")
		fun applyFailureCodeHasAllRequiredEntries() {
			val names = ApplyFailureCode.entries.map { it.name }.toSet()
			assertThat(names.contains("ALL_PATHS_BLOCKED")).isTrue()
			assertThat(names.contains("CONFLICT")).isTrue()
			assertThat(names.contains("NO_ROUTE_EXISTS")).isTrue()
			assertThat(names.contains("APPROVE_REJECTED")).isTrue()
			assertThat(names.contains("CAP_EXCEEDED_APPLY")).isTrue()
			assertThat(names.contains("DROPPED_INVALID")).isTrue()
		}

		@Test
		@DisplayName("ApplyFailureCode does NOT have the old CAP_EXCEEDED name (renamed to CAP_EXCEEDED_APPLY)")
		fun capExceededWasRenamed() {
			val names = ApplyFailureCode.entries.map { it.name }.toSet()
			assertThat(names.contains("CAP_EXCEEDED")).isFalse()
			assertThat(names.contains("CAP_EXCEEDED_APPLY")).isTrue()
		}

		/**
		 * `ORIGIN_NOT_CONTIGUOUS` is the one deliberate addition since SP2c.20 (Issue #893,
		 * task A-R1): a route origin that is not contiguous with the train's own footprint is
		 * an LLM-output error and must be counted apart from `ALL_PATHS_BLOCKED` contention.
		 * The guard itself stands — every further entry still has to be argued for here.
		 */
		@Test
		@DisplayName("ApplyFailureCode has exactly the expected entries — no stray additions")
		fun applyFailureCodeIsExactlyTheExpectedSet() {
			val names = ApplyFailureCode.entries.map { it.name }.toSet()
			assertThat(names).isEqualTo(
				setOf(
					"ALL_PATHS_BLOCKED",
					"CONFLICT",
					"NO_ROUTE_EXISTS",
					"APPROVE_REJECTED",
					"CAP_EXCEEDED_APPLY",
					"ORIGIN_NOT_CONTIGUOUS",
					// Issue #834 review finding #2: four-condition interlocking refusal code.
					"CONDITION_FAILED",
					"DROPPED_INVALID",
					// Issue #903: permanent geometric impossibility (rear-facing START or
					// unconfigurable switch), excluded from ALL_PATHS_BLOCKED's contention bucket.
					"GEOMETRICALLY_IMPOSSIBLE"
				)
			)
		}
	}

	// ── New planner types in correct package ─────────────────────────────────

	@Nested
	@DisplayName("New SP2c.20 types exist in dispatcher/planner/ package")
	inner class PlannerPackageTypes {
		@Test
		@DisplayName("ActionPhase is in the planner package with the required enum values")
		fun actionPhaseIsInPlannerPackage() {
			val pkg = cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase::class.java.packageName
			assertThat(pkg).contains("dispatcher.planner")
			val names =
				cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase.entries
					.map { it.name }
					.toSet()
			assertThat(names.contains("EMITTED")).isTrue()
			assertThat(names.contains("REJECTED_BY_VALIDATOR")).isTrue()
			assertThat(names.contains("APPLIED")).isTrue()
			assertThat(names.contains("APPLIED_THEN_FAILED")).isTrue()
		}

		@Test
		@DisplayName("AuthoredAction is in the planner package")
		fun authoredActionIsInPlannerPackage() {
			val pkg = cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction::class.java.packageName
			assertThat(pkg).contains("dispatcher.planner")
		}

		@Test
		@DisplayName("ActionOutcome is in the planner package")
		fun actionOutcomeIsInPlannerPackage() {
			val pkg = cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome::class.java.packageName
			assertThat(pkg).contains("dispatcher.planner")
		}

		@Test
		@DisplayName("ActionOutcomeSink is in the planner package")
		fun actionOutcomeSinkIsInPlannerPackage() {
			val pkg = cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink::class.java.packageName
			assertThat(pkg).contains("dispatcher.planner")
		}
	}

	// ── TIMEOUT_NOOP dispatching-action count is always zero ────────────────

	@Nested
	@DisplayName("TIMEOUT_NOOP dispatching actions count (P3-style check)")
	inner class TimeoutNoopDispatchingCount {
		@Test
		@DisplayName("TIMEOUT_NOOP always has zero dispatching (non-no_op) actions")
		fun timeoutNoopHasZeroDispatchingActions() {
			val store = DispatcherPreferenceStore()
			// Simulate a run where TIMEOUT_NOOP fires several times (always with NoOp)
			repeat(5) { i ->
				store.observe(
					record(
						tick = i.toLong(),
						actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))
					)
				)
			}
			assertThat(store.getDispatchingActionCount(ActionAuthor.TIMEOUT_NOOP)).isZero()
		}

		@Test
		@DisplayName(
			"P3 (SP2c.20 follow-up): an all-fallback run dispatches zero actions for every author, " +
				"not just TIMEOUT_NOOP — the admission safety net is deleted (SP2c.8), so there is no " +
				"author left that could originate a dispatching action outside LLM/RULE_BASED intent"
		)
		fun allFallbackRunHasZeroDispatchingActionsForEveryAuthor() {
			val store = DispatcherPreferenceStore()
			repeat(5) { i ->
				store.observe(
					record(
						tick = i.toLong(),
						actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))
					)
				)
			}
			ActionAuthor.entries.forEach { author ->
				assertThat(store.getDispatchingActionCount(author)).isZero()
			}
		}
	}

	// ── unattributedApplies counter ──────────────────────────────────────────

	@Nested
	@DisplayName("CommandCorrelationMap.unattributedApplies counter (SP2c.20)")
	inner class UnattributedApplies {
		@Test
		@DisplayName("unattributedApplies is zero when all decisions are registered")
		fun unattributedIsZeroWhenAllRegistered() {
			val map = CommandCorrelationMap()
			map.newCycle()
			val decisions = listOf(DispatchDecision.ApproveTrain("T-1"))
			map.register(decisions)
			map.correlate(decisions[0]) // evict
			assertThat(map.unattributedApplies).isZero()
		}

		@Test
		@DisplayName("unattributedApplies increments when a decision is correlated without a registered entry")
		fun unattributedIncrementsOnMissingEntry() {
			val map = CommandCorrelationMap()
			// Correlate a decision that was never registered
			val decision = DispatchDecision.ApproveTrain("T-orphan")
			map.correlate(decision)
			assertThat(map.unattributedApplies).isGreaterThanOrEqualTo(1L)
		}

		@Test
		@DisplayName("CommandAndTick carries author and reason from register call")
		fun commandAndTickCarriesAuthorAndReason() {
			val map = CommandCorrelationMap()
			map.newCycle()
			val decision = DispatchDecision.ApproveTrain("T-1")
			map.register(listOf(decision), ActionAuthor.RULE_FALLBACK, "test reason")
			val entry = map.correlate(decision)
			assertThat(entry).isNotNull()
			assertThat(entry!!.author).isEqualTo(ActionAuthor.RULE_FALLBACK)
			assertThat(entry.reason).isEqualTo("test reason")
		}
	}
}
