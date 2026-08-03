/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isZero
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
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
 * Unit coverage for [DispatcherPreferenceStore] (SP2c.9, Issue #832).
 *
 * The store accumulates per-tick attribution records and exposes:
 * - [DispatcherPreferenceStore.getRecords] — defensive-copy snapshot of every recorded action.
 * - [DispatcherPreferenceStore.getAuthorCounts] — author → action count map (only non-zero authors).
 * - [DispatcherPreferenceStore.getDispatchingActionCount] — non-`no_op` action count per author.
 * - [DispatcherPreferenceStore.logFinalSummary] — structured INFO log of the per-run summary.
 *
 * The log-line assertion follows the same Logback `ListAppender` pattern as
 * `cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapterTest`: a `ListAppender`
 * attached to the Logback root logger, plus temporarily raising both the root logger and the
 * `cz.vutbr.fit.interlockSim.dispatcher` package logger to INFO — `logback-test.xml` pins the
 * dispatcher package to WARN, which would otherwise suppress `logFinalSummary`'s INFO-level
 * line regardless of the root level.
 *
 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
 */
@DisplayName("SP2c.9 — DispatcherPreferenceStore (#832)")
@Timeout(10, unit = TimeUnit.SECONDS)
class DispatcherPreferenceStoreTest {
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

	// ── getRecords / defensive copy semantics ───────────────────────────────

	@Nested
	@DisplayName("getRecords returns a defensive copy")
	inner class GetRecordsDefensiveCopy {
		@Test
		@DisplayName("a fresh store returns an empty record list")
		fun freshStoreReturnsEmpty() {
			val store = DispatcherPreferenceStore()
			assertThat(store.getRecords()).isEqualTo(emptyList<DispatcherPreferenceStore.ActionAttributionRecord>())
		}

		@Test
		@DisplayName("observe appends one record per AttributedAction in the tick")
		fun observeAppendsPerAction() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					tick = 7L,
					simTime = 42.0,
					actions =
						listOf(
							attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1")),
							attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp)
						)
				)
			)

			val records = store.getRecords()
			assertThat(records).hasSize(2)
			assertThat(records[0].tick).isEqualTo(7L)
			assertThat(records[0].simTime).isEqualTo(42.0)
			assertThat(records[0].actionKind).isEqualTo("approve_train")
			assertThat(records[0].author).isEqualTo(ActionAuthor.LLM)
			assertThat(records[1].actionKind).isEqualTo("no_op")
			assertThat(records[1].author).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
		}

		@Test
		@DisplayName("getRecords returns a defensive copy — subsequent observe does not mutate the returned list")
		fun getRecordsIsDefensiveCopy() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))

			val snapshot = store.getRecords()
			assertThat(snapshot).hasSize(1)

			// Further observations must not appear in the previously-returned snapshot.
			store.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.RULE_BASED))))

			assertThat(snapshot).hasSize(1)
			assertThat(store.getRecords()).hasSize(2)
			// Different list instances — defensive copy, not a live view.
			assertThat(store.getRecords()).isNotSameInstanceAs(store.getRecords())
		}

		@Test
		@DisplayName("an empty tick record appends nothing but is tolerated")
		fun emptyTickAppendsNothing() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = emptyList()))
			assertThat(store.getRecords()).hasSize(0)
		}

		@Test
		@DisplayName("reason is persisted into the record")
		fun reasonPersisted() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					tick = 1L,
					actions = listOf(attributed(ActionAuthor.LLM, DispatchAction.NoOp, reason = "T-1 waiting at A"))
				)
			)
			assertThat(store.getRecords()[0].reason).isEqualTo("T-1 waiting at A")
		}
	}

	// ── getAuthorCounts ────────────────────────────────────────────────────

	@Nested
	@DisplayName("getAuthorCounts returns per-author action counts")
	inner class GetAuthorCounts {
		@Test
		@DisplayName("a fresh store returns an empty counts map")
		fun freshStoreReturnsEmptyMap() {
			assertThat(DispatcherPreferenceStore().getAuthorCounts()).isEqualTo(emptyMap<ActionAuthor, Long>())
		}

		@Test
		@DisplayName("counts group by author across ticks and actions")
		fun countsGroupByAuthor() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM), attributed(ActionAuthor.LLM))))
			store.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.RULE_BASED))))
			store.observe(record(tick = 3L, actions = listOf(attributed(ActionAuthor.LLM))))

			val counts = store.getAuthorCounts()
			assertThat(counts[ActionAuthor.LLM] ?: 0L).isEqualTo(3L)
			assertThat(counts[ActionAuthor.RULE_BASED] ?: 0L).isEqualTo(1L)
		}

		@Test
		@DisplayName("the map only contains authors with at least one recorded action")
		fun mapOnlyContainsSeenAuthors() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.SAFETY_NET))))

			val counts = store.getAuthorCounts()
			assertThat(counts.keys).isEqualTo(setOf(ActionAuthor.SAFETY_NET))
		}
	}

	// ── getDispatchingActionCount ──────────────────────────────────────────

	@Nested
	@DisplayName("getDispatchingActionCount counts non-no_op actions per author")
	inner class GetDispatchingActionCount {
		@Test
		@DisplayName("TIMEOUT_NOOP always has zero dispatching actions (always paired with NoOp)")
		fun timeoutNoopIsZeroDispatching() {
			val store = DispatcherPreferenceStore()
			repeat(5) { i ->
				store.observe(
					record(
						tick = i.toLong() + 1L,
						actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))
					)
				)
			}
			assertThat(store.getDispatchingActionCount(ActionAuthor.TIMEOUT_NOOP)).isZero()
		}

		@Test
		@DisplayName("LLM approve_train actions count as dispatching; LLM no_op actions do not")
		fun llmDispatchingCountSplitsByActionKind() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					tick = 1L,
					actions =
						listOf(
							attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1")),
							attributed(ActionAuthor.LLM, DispatchAction.NoOp),
							attributed(ActionAuthor.LLM, DispatchAction.RequestRoute("T-2", "A", "B"))
						)
				)
			)
			// 2 dispatching (approve_train + request_route), 1 no_op.
			assertThat(store.getDispatchingActionCount(ActionAuthor.LLM)).isEqualTo(2L)
		}

		@Test
		@DisplayName("getDispatchingActionCount is zero for an author with no recorded actions")
		fun zeroForUnseenAuthor() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))
			assertThat(store.getDispatchingActionCount(ActionAuthor.OPERATOR)).isZero()
		}
	}

	// ── logFinalSummary log text ─────────────────────────────────────────────

	/**
	 * Verifies the integration point: [DispatcherPreferenceStore.logFinalSummary] actually emits
	 * the structured `[DispatcherPreferenceStore] final summary —` INFO log line with the expected
	 * fields, not just that the counters underneath are correct (the other nested classes cover that).
	 *
	 * Uses the Logback `ListAppender` pattern from `MeasuringPlanAdapterTest`: `logback-test.xml`
	 * pins the `cz.vutbr.fit.interlockSim.dispatcher` package to WARN, so both the root logger
	 * and the package logger are temporarily raised to INFO for the duration of each test.
	 */
	@Nested
	@DisplayName("logFinalSummary emits the structured INFO summary line")
	inner class LogFinalSummaryLogText {
		private lateinit var appender: ListAppender<ILoggingEvent>
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
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
		@DisplayName("logFinalSummary emits exactly one INFO line labelled 'final summary'")
		fun emitsOneSummaryLine() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))

			store.logFinalSummary()

			val messages = finalSummaryMessages()
			assertThat(messages).hasSize(1)
		}

		@Test
		@DisplayName("logFinalSummary line contains totalActions and per-author counts")
		fun lineContainsTotalAndPerAuthorCounts() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					tick = 1L,
					actions =
						listOf(
							attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1")),
							attributed(ActionAuthor.LLM, DispatchAction.NoOp)
						)
				)
			)
			store.observe(
				record(
					tick = 2L,
					actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))
				)
			)

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			assertThat(line).contains("[DispatcherPreferenceStore] final summary —")
			assertThat(line).contains("totalActions=3")
			assertThat(line).contains("LLM=2")
			assertThat(line).contains("TIMEOUT_NOOP=1")
			assertThat(line).contains("RULE_BASED=0")
			assertThat(line).contains("RULE_FALLBACK=0")
			assertThat(line).contains("SAFETY_NET=0")
			assertThat(line).contains("OPERATOR=0")
		}

		@Test
		@DisplayName("logFinalSummary with zero recorded actions reports totalActions=0 and all authors zero")
		fun zeroActionsSummaryIsWellFormed() {
			val store = DispatcherPreferenceStore()

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			assertThat(line).contains("totalActions=0")
			assertThat(line).contains("LLM=0")
			assertThat(line).contains("SAFETY_NET=0")
		}

		@Test
		@DisplayName("logFinalSummary is idempotent — calling twice does not mutate state and re-emits the same line")
		fun logFinalSummaryIsIdempotent() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))

			store.logFinalSummary()
			store.logFinalSummary()

			val messages = finalSummaryMessages()
			assertThat(messages).hasSize(2)
			// State unchanged — getRecords still reports the single recorded action.
			assertThat(store.getRecords()).hasSize(1)
		}

		@Test
		@DisplayName("logFinalSummary line lists all six ActionAuthor entries (zero-filled for unseen)")
		fun lineListsAllAuthorsInOrder() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))

			store.logFinalSummary()

			val line = finalSummaryMessages().first()
			// Every author name token must appear at least once (zero- or non-zero-filled).
			// RULE_FALLBACK was observed once; every other author is zero-filled.
			for (author in ActionAuthor.entries) {
				assertThat(line).contains("${author.name}=")
			}
			assertThat(line).contains("RULE_FALLBACK=1")
		}

		@Test
		@DisplayName("logFinalSummary does not emit a WARN/ERROR failure banner (only the INFO summary)")
		fun noFailureBannerForStoreSummary() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.SAFETY_NET))))

			store.logFinalSummary()

			val failureBanners =
				appender.list.filter {
					(it.level == Level.WARN || it.level == Level.ERROR) &&
						it.formattedMessage.contains("FAILED")
				}
			assertThat(failureBanners).isEqualTo(emptyList<ILoggingEvent>())
		}
	}

	// ── single-thread-ownership edge cases ──────────────────────────────────

	@Nested
	@DisplayName("single-thread-ownership contract edge cases")
	inner class SingleThreadOwnership {
		@Test
		@DisplayName("repeatedly calling getRecords is safe and returns distinct defensive-copy snapshots")
		fun repeatedGetRecordsIsSafe() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))
			val r1 = store.getRecords()
			val r2 = store.getRecords()
			assertThat(r1).isEqualTo(r2)
			// Defensive copies are different instances (non-empty lists are not the singleton).
			assertThat(r1).isNotSameInstanceAs(r2)
			assertThat(r1).hasSize(1)
		}

		@Test
		@DisplayName("getAuthorCounts and getDispatchingActionCount agree on totals for a single-author run")
		fun countsAgreeForSingleAuthorRun() {
			val store = DispatcherPreferenceStore()
			store.observe(
				record(
					tick = 1L,
					actions =
						listOf(
							attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1")),
							attributed(ActionAuthor.LLM, DispatchAction.NoOp),
							attributed(ActionAuthor.LLM, DispatchAction.RequestRoute("T-1", "A", "B"))
						)
				)
			)
			val total = store.getAuthorCounts()[ActionAuthor.LLM] ?: 0L
			val dispatching = store.getDispatchingActionCount(ActionAuthor.LLM)
			// total = 3, dispatching = 2 (no_op excluded)
			assertThat(total).isEqualTo(3L)
			assertThat(dispatching).isEqualTo(2L)
			assertThat(total - dispatching).isEqualTo(1L) // the single no_op
		}
	}
}
