/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.executor.HoldingOllamaServer
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #1340, acceptance 1: one runaway Ollama generation costs at most one dispatcher tick.
 *
 * The forced runaway: [HoldingOllamaServer] in its one-slot mode (like `llama-server -np 1`) answers
 * request 1, never ends request 2, and answers every later request only after the client has closed
 * the held connection and the slot-release delay has passed — the way Ollama queues requests behind
 * a generation that holds its only slot. Cycle 1 is therefore answered, cycle 2 runs into the cycle
 * timeout, and cycle 3 onwards can only be answered if the cycle timeout really freed the slot
 * (Issue #1341). The chain is the production one: [KoogAgentPlanAdapter] → [DefaultAgentService] →
 * [OllamaSimpleExecutor] over the CIO client. Only the agent factory, the simulation context it
 * would receive, and the fallback dispatcher (always `NoAction`) are mocked; the agent, the executor
 * and the HTTP client are real. Mocking the factory also skips the pre-warm request, which would
 * shift the held-request index.
 *
 * Measured, per run:
 * - **fallback cycles**: [TickRecord]s with outcome [TickOutcome.RULE_FALLBACK], read through
 *   [KoogAgentPlanAdapter.addTickListener] (not `fallback.decide` calls: a silent cycle consults the
 *   fallback too);
 * - **breaker openings**: [LlmCircuitBreaker.openCount] (default threshold 3);
 * - **first answered cycle after the runaway**: the first cycle after cycle 2 whose tick is not a
 *   fallback.
 *
 * Expected and pinned: 1 fallback cycle, 0 breaker openings, cycle 3 answered. The pins are cycle
 * counts, never wall-clock bounds (the ruling in [OllamaTimeoutAbortTest]'s KDoc). The cycle
 * timeout and the slot-release delay keep at least the production ratio (30 s / 1 s, the 1 s being
 * the #1341 live check of 2026-11-03): [CYCLE_TIMEOUT] / [SLOT_RELEASE_DELAY_MILLIS] is about 30
 * (5,000 ms / 167 ms).
 */
@DisplayName("A forced runaway Ollama generation costs one dispatcher tick (Issue #1340)")
class ForcedRunawayCostTest {
	private companion object {
		val CYCLE_TIMEOUT: Duration = Duration.ofSeconds(5)
		const val SLOT_RELEASE_DELAY_MILLIS = 167L

		/** The request that never ends: cycle 2's. */
		const val HELD_REQUEST = 2
		const val CYCLES = 5
		const val RUNAWAY_CYCLE = 2

		/**
		 * The tick of an answered cycle here: the reply has no tool call, and the observation is the
		 * [SimulationSnapshot.EMPTY] sentinel, which is never an idle station, so the adapter consults the
		 * fallback as an oracle; it finds only [DispatchDecision.NoAction], so the cycle is not a fallback.
		 */
		val ANSWERED = TickOutcome.LLM_SILENT_NONACTIONABLE
	}

	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@Test
	fun `one runaway generation costs one fallback cycle and no breaker opening`() {
		HoldingOllamaServer(
			heldRequest = HELD_REQUEST,
			slotReleaseDelayMillis = SLOT_RELEASE_DELAY_MILLIS
		).use { server ->
			val config = OllamaExecutorConfig(ollamaEndpoint = server.baseUrl)
			val executor = OllamaSimpleExecutor(config)
			try {
				val adapter = adapterOver(executor, config)
				val ticks = CopyOnWriteArrayList<TickRecord>()
				adapter.addTickListener { ticks.add(it) }

				repeat(CYCLES) { runBlocking { adapter.plan(observation) } }

				val outcomes = ticks.map { it.outcome }
				val fallbackCycles = outcomes.count { it == TickOutcome.RULE_FALLBACK }
				val breakerOpenings = adapter.circuitBreaker.openCount
				val firstAnsweredAfterRunaway =
					outcomes
						.withIndex()
						.firstOrNull { (i, outcome) -> i + 1 > RUNAWAY_CYCLE && outcome != TickOutcome.RULE_FALLBACK }
						?.let { it.index + 1 }
				println(
					"Issue #1340: forced runaway on cycle $RUNAWAY_CYCLE of $CYCLES " +
						"(cycle timeout ${CYCLE_TIMEOUT.toMillis()} ms, slot release ${SLOT_RELEASE_DELAY_MILLIS} ms): " +
						"fallbackCycles=$fallbackCycles, breakerOpenings=$breakerOpenings, " +
						"firstAnsweredCycleAfterRunaway=$firstAnsweredAfterRunaway, outcomes=$outcomes"
				)

				assertThat(outcomes, "tick outcome per cycle").containsExactly(
					ANSWERED,
					TickOutcome.RULE_FALLBACK,
					ANSWERED,
					ANSWERED,
					ANSWERED
				)
				assertThat(fallbackCycles, "fallback cycles").isEqualTo(1)
				assertThat(breakerOpenings, "circuit-breaker openings").isEqualTo(0L)
				assertThat(firstAnsweredAfterRunaway, "first answered cycle after the runaway").isEqualTo(3)
			} finally {
				executor.close()
			}
		}
	}

	/**
	 * The production planner wired to a real Koog agent built by [DefaultAgentService] over
	 * [executor]; only the agent factory (which would also pre-warm a real Ollama) is mocked. The
	 * fallback returns [DispatchDecision.NoAction], so a fallback cycle is visible only in its tick.
	 */
	private fun adapterOver(
		executor: OllamaSimpleExecutor,
		config: OllamaExecutorConfig
	): KoogAgentPlanAdapter {
		val agent =
			runBlocking {
				DefaultAgentService(executor, config).createDispatchAgent(
					modelName = config.modelName,
					tools = emptyList(),
					systemPrompt = "You are a test dispatcher."
				)
			}
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns agent
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		return KoogAgentPlanAdapter(
			agentFactory,
			mockk<DefaultSimulationContext>(),
			fallback,
			CYCLE_TIMEOUT,
			ActuatorCommandQueue(),
			SinkHolder()
		)
	}
}
