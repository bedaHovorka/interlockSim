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
import assertk.assertions.isGreaterThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [MeasuringPlanAdapter] (Issue #817 — Goal 10 dispatcher metrics).
 *
 * Verifies that:
 * - LLM success cycles increment the success counter.
 * - Fallback cycles increment the fallback counter with the correct [FallbackReason].
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
				commandQueue = commandQueue
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
		fun `empty LLM result with actuator-tool side effect increments ollamaSuccessCount`() {
			val commandQueue = ActuatorCommandQueue()
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } coAnswers {
				commandQueue.postAll(listOf(DispatchDecision.NoAction))
				emptyList()
			}
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback, commandQueue = commandQueue)

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
	@DisplayName("fallback EMPTY_NO_TOOLS: LLM returns empty with no tool side effects")
	inner class FallbackEmptyNoTools {
		@Test
		fun `increments fallbackCount with EMPTY_NO_TOOLS reason`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns emptyList()
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbackCount).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EMPTY_NO_TOOLS]).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.TIMEOUT]).isEqualTo(0L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EXCEPTION]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Fallback reason: TIMEOUT ──────────────────────────────────────────────

	@Nested
	@DisplayName("fallback TIMEOUT: LLM exceeds inference timeout")
	inner class FallbackTimeout {
		@Test
		fun `increments fallbackCount with TIMEOUT reason`() {
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
			assertThat(snapshot.fallbacksByReason[FallbackReason.TIMEOUT]).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EMPTY_NO_TOOLS]).isEqualTo(0L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EXCEPTION]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Fallback reason: EXCEPTION ────────────────────────────────────────────

	@Nested
	@DisplayName("fallback EXCEPTION: LLM throws an unexpected exception")
	inner class FallbackException {
		@Test
		fun `increments fallbackCount with EXCEPTION reason`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } throws RuntimeException("Ollama unavailable")
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbackCount).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EXCEPTION]).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.TIMEOUT]).isEqualTo(0L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EMPTY_NO_TOOLS]).isEqualTo(0L)
			assertThat(snapshot.ollamaSuccessCount).isZero()
		}
	}

	// ── Mixed cycles ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("mixed success and fallback cycles")
	inner class MixedCycles {
		@Test
		fun `success rate reflects mix of LLM success and fallback cycles`() {
			// 2 success + 2 EMPTY_NO_TOOLS fallbacks = 50% success rate
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
		fun `fallbacksByReason tracks multiple different fallback reasons`() {
			var callCount = 0
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } coAnswers {
				callCount++
				when (callCount) {
					1 -> emptyList() // EMPTY_NO_TOOLS
					2 -> throw RuntimeException("boom") // EXCEPTION
					else -> listOf(DispatchDecision.NoAction)
				}
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			repeat(3) { runBlocking { adapter.plan(observation) } }

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.fallbacksByReason[FallbackReason.EMPTY_NO_TOOLS]).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.EXCEPTION]).isEqualTo(1L)
			assertThat(snapshot.fallbacksByReason[FallbackReason.TIMEOUT]).isEqualTo(0L)
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
}
