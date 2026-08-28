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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isZero
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.FailureReason
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit tests for [MeasuringPlanAdapter] (Issue #817 — Goal 10 dispatcher metrics).
 *
 * Verifies that:
 * - LLM success cycles increment the success counter.
 * - Non-success cycles increment the fallback counter under the correct [TickOutcome].
 * - [PlannerMetricsSnapshot] calculations are correct (totalCycles, successRate).
 * - [DispatcherPlanner] capabilities are forwarded from the inner [KoogAgentPlanAdapter].
 * - Decisions from both the LLM path and the fallback path are forwarded correctly.
 *
 * @since Issue #817 (Goal 10 dispatcher metrics)
 */
@DisplayName("MeasuringPlanAdapter — fallback metrics and LLM success tracking")
class MeasuringPlanAdapterTest {
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	/**
	 * Build a [MeasuringPlanAdapter] wrapping a [KoogAgentPlanAdapter] whose inner Koog agent
	 * is provided by [koogAgent] and whose fallback dispatcher is [fallback].
	 */
	private fun measuring(
		koogAgent: KoogDispatchAgent,
		fallback: Dispatcher,
		inferenceTimeout: Duration = Duration.ofSeconds(30),
		commandQueue: ActuatorCommandQueue = ActuatorCommandQueue()
	): MeasuringPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		val context = mockk<DefaultSimulationContext>()
		val inner =
			KoogAgentPlanAdapter(
				agentFactory = agentFactory,
				context = context,
				fallbackDispatcher = fallback,
				inferenceTimeout = inferenceTimeout,
				commandQueue = commandQueue,
				sinkHolder = SinkHolder()
			)
		return MeasuringPlanAdapter(inner)
	}

	// ── Initial state ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("initial snapshot is all-zero")
	inner class InitialState {
		@Test
		fun `ollamaSuccessCount starts at zero`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.getMetricsSnapshot().ollamaSuccessCount).isZero()
		}

		@Test
		fun `fallbackCount starts at zero`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.getMetricsSnapshot().fallbackCount).isZero()
		}

		@Test
		fun `totalCycles starts at zero`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.getMetricsSnapshot().totalCycles).isZero()
		}

		@Test
		fun `ollamaSuccessRate is 0 when no cycles have run`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.getMetricsSnapshot().ollamaSuccessRate).isEqualTo(0.0)
		}
	}

	// ── LLM success path ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("LLM success path increments ollamaSuccessCount")
	inner class LlmSuccessPath {
		@Test
		fun `non-empty LLM result increments ollamaSuccessCount`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }

			assertThat(adapter.getMetricsSnapshot().ollamaSuccessCount).isEqualTo(1L)
			assertThat(adapter.getMetricsSnapshot().fallbackCount).isZero()
		}

		@Test
		fun `multiple successful LLM cycles accumulate correctly`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			repeat(3) { runBlocking { adapter.plan(observation) } }

			assertThat(adapter.getMetricsSnapshot().ollamaSuccessCount).isEqualTo(3L)
			assertThat(adapter.getMetricsSnapshot().totalCycles).isEqualTo(3L)
		}

		@Test
		fun `success rate is 1_0 when all cycles succeed`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			repeat(5) { runBlocking { adapter.plan(observation) } }

			assertThat(adapter.getMetricsSnapshot().ollamaSuccessRate).isEqualTo(1.0)
		}
	}

	// ── Fallback reason: EMPTY_NO_TOOLS ───────────────────────────────────────

	@Nested
	@DisplayName("silent LLM cycle: LLM returns empty with no tool side effects")
	inner class FallbackEmptyNoTools {
		/**
		 * The fallback oracle finds nothing actionable either, so the cycle is reported as
		 * [TickOutcome.LLM_SILENT_NONACTIONABLE] — which the partition on [PlannerMetricsSnapshot]
		 * scores as a fallback, exactly as the pre-Issue-#713 `EMPTY_NO_TOOLS` fallback-reason
		 * counter did.
		 */
		@Test
		fun `increments fallbackCount under LLM_SILENT_NONACTIONABLE`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns emptyList()
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbackCount).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_SILENT_NONACTIONABLE]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.RULE_FALLBACK]).isEqualTo(0L)
			assertThat(snapshot.outcomeCounts[TickOutcome.TIMEOUT_NOOP]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Idle station (Issue #834) ─────────────────────────────────────────────

	/**
	 * Issue #834: the project owner reported the exact log line this guards against —
	 * `fallback: reason=EMPTY_NO_TOOLS simTime=314.0s fallbackTotal=27 ollamaSuccessRate=27%` —
	 * produced by an idle-station "nothing to do" cycle. An idle station (no active or queued
	 * trains) with no LLM emissions must count toward [PlannerMetricsSnapshot.ollamaSuccessCount]
	 * as a [TickOutcome.LLM_NO_OP], not toward [PlannerMetricsSnapshot.fallbackCount].
	 */
	@Nested
	@DisplayName("idle station: LLM completes with no decisions and no tool emissions")
	inner class IdleStationNoOp {
		// Deliberately not SimulationSnapshot.EMPTY (the pre-first-capture sentinel, which must
		// keep the old fallback behaviour) — a distinct, structurally-idle snapshot instance,
		// simTime matching the owner's reported log line.
		private val idleObservation =
			observation.copy(snapshot = SimulationSnapshot.EMPTY.copy(simTime = 314.0))

		@Test
		fun `counts toward ollamaSuccessCount as LLM_NO_OP, not toward fallbackCount`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns emptyList()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(idleObservation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.ollamaSuccessCount).isEqualTo(1L)
			assertThat(snapshot.fallbackCount).isZero()
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_NO_OP]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_SILENT_NONACTIONABLE]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessRate).isEqualTo(1.0)
		}
	}

	// ── Fallback reason: TIMEOUT ──────────────────────────────────────────────

	@Nested
	@DisplayName("fallback TIMEOUT: LLM exceeds inference timeout")
	inner class FallbackTimeout {
		@Test
		fun `increments fallbackCount under RULE_FALLBACK`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } coAnswers {
				delay(500)
				emptyList()
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback, inferenceTimeout = Duration.ofMillis(50))

			runBlocking { adapter.plan(observation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbackCount).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.RULE_FALLBACK]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_SILENT_NONACTIONABLE]).isEqualTo(0L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_ACTIONS]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Fallback reason: EXCEPTION ────────────────────────────────────────────

	@Nested
	@DisplayName("fallback EXCEPTION: LLM throws an unexpected exception")
	inner class FallbackException {
		@Test
		fun `increments fallbackCount under RULE_FALLBACK on an LLM exception`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } throws RuntimeException("Ollama unavailable")
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbackCount).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.RULE_FALLBACK]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_EXCEPTION]).isEqualTo(0L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_SILENT_NONACTIONABLE]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Mixed cycles ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("mixed success and fallback cycles")
	inner class MixedCycles {
		@Test
		fun `success rate reflects mix of LLM success and fallback cycles`() {
			// 2 success + 2 silent non-actionable cycles = 50% success rate
			val commandQueue = ActuatorCommandQueue()
			var callCount = 0
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } coAnswers {
				callCount++
				if (callCount % 2 == 0) listOf(DispatchDecision.NoAction) else emptyList()
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback, commandQueue = commandQueue)

			repeat(4) { runBlocking { adapter.plan(observation) } }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isEqualTo(4L)
			assertThat(snapshot.ollamaSuccessCount).isEqualTo(2L)
			assertThat(snapshot.fallbackCount).isEqualTo(2L)
			assertThat(snapshot.ollamaSuccessRate).isEqualTo(0.5)
		}

		@Test
		fun `outcomeCounts tracks multiple different outcomes`() {
			var callCount = 0
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } coAnswers {
				callCount++
				when (callCount) {
					1 -> emptyList() // silent cycle -> LLM_SILENT_NONACTIONABLE
					2 -> throw RuntimeException("boom") // LLM exception -> RULE_FALLBACK
					else -> listOf(DispatchDecision.NoAction)
				}
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			repeat(3) { runBlocking { adapter.plan(observation) } }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_SILENT_NONACTIONABLE]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.RULE_FALLBACK]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.LLM_ACTIONS]).isEqualTo(1L)
			assertThat(snapshot.outcomeCounts[TickOutcome.TIMEOUT_NOOP]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isEqualTo(1L)
		}
	}

	// ── Capabilities forwarding ───────────────────────────────────────────────

	@Nested
	@DisplayName("capabilities are forwarded from inner KoogAgentPlanAdapter")
	inner class CapabilitiesForwarding {
		@Test
		fun `capabilities isAsynchronous is true`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.capabilities.isAsynchronous).isEqualTo(true)
		}

		@Test
		fun `capabilities maxSpeedMultiplier matches AGENT_MAX_SPEED_MULTIPLIER`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.capabilities.maxSpeedMultiplier)
				.isEqualTo(PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER)
		}

		@Test
		fun `capabilities name is KoogAgent+RuleBasedFallback`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			assertThat(adapter.capabilities.name).isEqualTo(KoogAgentPlanAdapter.PLANNER_NAME)
		}
	}

	// ── totalCycles ───────────────────────────────────────────────────────────

	@Nested
	@DisplayName("totalCycles equals ollamaSuccessCount + fallbackCount")
	inner class TotalCycles {
		@Test
		fun `totalCycles is correct after mixed cycles`() {
			val agent = mockk<KoogDispatchAgent>()
			var n = 0
			coEvery { agent.decideAsync(any()) } coAnswers {
				n++
				if (n <= 3) listOf(DispatchDecision.NoAction) else emptyList()
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			repeat(5) { runBlocking { adapter.plan(observation) } }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isEqualTo(snapshot.ollamaSuccessCount + snapshot.fallbackCount)
			assertThat(snapshot.totalCycles).isEqualTo(5L)
		}
	}

	// ── successRate boundary ──────────────────────────────────────────────────

	@Nested
	@DisplayName("ollamaSuccessRate boundary values")
	inner class SuccessRateBoundary {
		@Test
		fun `successRate is between 0 and 1 inclusive`() {
			val agent = mockk<KoogDispatchAgent>()
			var n = 0
			coEvery { agent.decideAsync(any()) } coAnswers {
				n++
				if (n % 3 == 0) emptyList() else listOf(DispatchDecision.NoAction)
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			repeat(9) { runBlocking { adapter.plan(observation) } }

			val rate = adapter.getMetricsSnapshot().ollamaSuccessRate
			// rate must be in [0.0, 1.0]
			assertThat(rate).isGreaterThan(-0.001) // effectively >= 0
			assertThat(1.0 - rate).isGreaterThan(-0.001) // effectively rate <= 1.0
		}
	}

	// ── logFinalSummary ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("logFinalSummary logs unconditionally")
	inner class LogFinalSummary {
		@Test
		fun `does not throw with zero cycles recorded`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			adapter.logFinalSummary() // must not throw

			// Calling it must not mutate the counters.
			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isZero()
		}

		@Test
		fun `does not throw with a cycle count not aligned to REPORT_EVERY_N_CYCLES`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			// 3 cycles — not a multiple of MeasuringPlanAdapter.REPORT_EVERY_N_CYCLES (10).
			repeat(3) { runBlocking { adapter.plan(observation) } }

			adapter.logFinalSummary() // must not throw

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isEqualTo(3L)
		}

		@Test
		fun `does not mutate counters when called multiple times`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }
			adapter.logFinalSummary()
			adapter.logFinalSummary()
			adapter.logFinalSummary()

			assertThat(adapter.getMetricsSnapshot().totalCycles).isEqualTo(1L)
		}
	}

	// ── logFinalSummary log text ─────────────────────────────────────────────

	/**
	 * Verifies the integration point: [MeasuringPlanAdapter.logFinalSummary] actually emits
	 * a log line carrying the `"[MeasuringPlanAdapter] final summary —"` label, not just that
	 * the counters underneath it are correct (the other [LogFinalSummary] tests already cover
	 * that). This is a regression guard against `formatSummaryLine`/label wiring silently
	 * breaking, which snapshot-only assertions cannot catch.
	 *
	 * Follows the same Logback `ListAppender` pattern as
	 * `cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplierSp2b5Test` — a `ListAppender`
	 * attached to the Logback root logger, plus temporarily raising both the root logger and
	 * the `cz.vutbr.fit.interlockSim.dispatcher` package logger to INFO, because
	 * `logback-test.xml` pins the dispatcher package to WARN which would otherwise suppress
	 * `logFinalSummary`'s INFO-level line regardless of the root level.
	 */
	@Nested
	@DisplayName("logFinalSummary logs the expected label text")
	inner class LogFinalSummaryLogText {
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
				.filter { it.contains("[MeasuringPlanAdapter] final summary —") }

		private fun failureBannerEvents(): List<ILoggingEvent> =
			appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("FAILED") }

		@Test
		fun `logFinalSummary emits a line labeled 'final summary'`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			repeat(2) { runBlocking { adapter.plan(observation) } }
			adapter.logFinalSummary()

			val messages = finalSummaryMessages()
			assertThat(messages).isNotEmpty()
			assertThat(messages.first()).contains("[MeasuringPlanAdapter] final summary —")
			assertThat(messages.first()).contains("totalCycles=2")
		}

		@Test
		@DisplayName("logFinalSummary with Failed(LLM_ABANDONED) emits prominent WARN banner (SP2c.8 #831)")
		fun `logFinalSummary with Failed outcome emits WARN banner with tick number`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			adapter.logFinalSummary(
				runOutcome = RunOutcome.Failed(FailureReason.LLM_ABANDONED),
				failedAtTick = 42L
			)

			val banners = failureBannerEvents()
			assertThat(banners).isNotEmpty()
			assertThat(banners.first().formattedMessage).contains("FAILED (LLM_ABANDONED)")
			assertThat(banners.first().formattedMessage).contains("at tick 42")
		}

		@Test
		@DisplayName("logFinalSummary with Running outcome does NOT emit WARN banner")
		fun `logFinalSummary with Running outcome emits no WARN failure banner`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			adapter.logFinalSummary(runOutcome = RunOutcome.Running)

			assertThat(failureBannerEvents()).isEqualTo(emptyList<ILoggingEvent>())
		}
	}

	// ── TickOutcome partition (Issue #713 Task 10) ────────────────────────────

	/**
	 * Pins the success/fallback partition documented on [PlannerMetricsSnapshot].
	 *
	 * [MeasuringPlanAdapter] reports through [PlannerTickListener.onTick] since Issue #713
	 * Task 10, so the tests below drive it directly with [TickRecord]s instead of going through
	 * a mocked LLM cycle — the classification is what is under test, not the path that produced
	 * the outcome.
	 */
	@Nested
	@DisplayName("TickOutcome accounting and the success/fallback partition")
	inner class TickOutcomePartition {
		@Test
		fun `getMetricsSnapshot counts ticks by TickOutcome`() {
			val adapter = measuring(mockk<KoogDispatchAgent>(), mockk<Dispatcher>())

			adapter.onTick(TickRecord(outcome = TickOutcome.LLM_ACTIONS, simTime = 1.0))
			adapter.onTick(TickRecord(outcome = TickOutcome.LLM_NO_OP, simTime = 2.0))
			adapter.onTick(
				TickRecord(
					outcome = TickOutcome.TIMEOUT_NOOP,
					simTime = 3.0,
					timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS
				)
			)

			val counts = adapter.getMetricsSnapshot().outcomeCounts

			assertThat(counts[TickOutcome.LLM_ACTIONS]).isEqualTo(1L)
			assertThat(counts[TickOutcome.LLM_NO_OP]).isEqualTo(1L)
			assertThat(counts[TickOutcome.TIMEOUT_NOOP]).isEqualTo(1L)
			assertThat(counts[TickOutcome.RULE_FALLBACK]).isEqualTo(0L)
		}

		@Test
		fun `ollamaSuccessRate follows the documented outcome partition`() {
			val adapter = measuring(mockk<KoogDispatchAgent>(), mockk<Dispatcher>())

			// 2 successes, 2 fallbacks — LLM_SILENT_NONACTIONABLE is a fallback, not a success.
			adapter.onTick(TickRecord(outcome = TickOutcome.LLM_ACTIONS, simTime = 1.0))
			adapter.onTick(TickRecord(outcome = TickOutcome.LLM_NO_OP, simTime = 2.0))
			adapter.onTick(TickRecord(outcome = TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 3.0))
			adapter.onTick(TickRecord(outcome = TickOutcome.RULE_FALLBACK, simTime = 4.0))

			val snapshot = adapter.getMetricsSnapshot()

			assertThat(snapshot.totalCycles).isEqualTo(4L)
			assertThat(snapshot.ollamaSuccessCount).isEqualTo(2L)
			assertThat(snapshot.fallbackCount).isEqualTo(2L)
			assertThat(snapshot.ollamaSuccessRate).isEqualTo(0.5)
		}

		/**
		 * Pins the "[MeasuringPlanAdapter.onTick] must not throw" invariant its own comment states.
		 *
		 * [CompositeTickListener] deliberately does not swallow delegate exceptions, and
		 * `ExampleRegistry` builds this adapter before [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]
		 * registers its own listener — so a throw here would abort the fan-out and starve the
		 * driver's attribution listener and the run recorder, which is exactly the Issue #843
		 * defect (`totalTicks = 0` in every per-run JSON). Drives all eight outcomes past
		 * [MeasuringPlanAdapter.REPORT_EVERY_N_CYCLES] so the checkpoint branch — the one that
		 * builds a snapshot and so evaluates [PlannerMetricsSnapshot]'s `require` — is exercised
		 * too, not just the early-return hot path.
		 */
		@Test
		fun `onTick never throws for any TickOutcome, including at a periodic checkpoint`() {
			val adapter = measuring(mockk<KoogDispatchAgent>(), mockk<Dispatcher>())

			val oneOfEach =
				TickOutcome.entries.mapIndexed { index, outcome ->
					TickRecord(
						outcome = outcome,
						simTime = index.toDouble(),
						timeoutNoOpCause =
							if (outcome == TickOutcome.TIMEOUT_NOOP) TimeoutNoOpCause.DEADLINE_MISS else null
					)
				}

			// 3 rounds x 8 outcomes = 24 ticks, so cycles 10 and 20 take the checkpoint branch.
			repeat(3) { oneOfEach.forEach { record -> adapter.onTick(record) } }

			// Reaching this line at all is the assertion: no onTick call threw.
			assertThat(adapter.getMetricsSnapshot().totalCycles).isEqualTo(24L)
		}
	}
}
