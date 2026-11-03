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
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
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

/**
 * Issue #1341: a dispatcher cycle timeout must close the HTTP request it abandons, so Ollama (one
 * slot, `-np 1`) stops generating and the next cycle's request does not queue behind a runaway.
 *
 * The 2026-11-01 run showed the failure: the cycle timed out at 21:01:37.743, but Ollama's request
 * for task 3913 stayed open until the run shut down (`500 | 2m40s` at 21:03:53), and three later
 * requests each timed out queued behind it. The cause is Ktor's Apache5 engine, which Ktor picked
 * by default: cancelling a request sent on a **reused** keep-alive connection leaves the socket
 * open (a fresh connection is torn down). The production chain reuses its pooled connection on
 * every cycle after the first, so this test runs two cycles through the real
 * [KoogAgentPlanAdapter] → [DefaultAgentService] → [OllamaSimpleExecutor] stack against
 * [HoldingOllamaServer]: the first is answered, the second is held for 30 s.
 *
 * TSE ruling for the bound: assert a generous ceiling ([CLOSE_CEILING_MILLIS] after the timeout,
 * with the hold far longer) and print the measured close latency rather than asserting the
 * ~1 s acceptance figure, which would be load-flaky.
 */
@DisplayName("A cycle timeout closes the abandoned Ollama request (Issue #1341)")
class OllamaTimeoutAbortTest {
	private companion object {
		val CYCLE_TIMEOUT: Duration = Duration.ofMillis(1_500)
		const val CLOSE_CEILING_MILLIS = 5_000L
	}

	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@Test
	fun `a timed-out cycle closes its held Ollama connection within the ceiling`() {
		HoldingOllamaServer().use { server ->
			val config = OllamaExecutorConfig(ollamaEndpoint = server.baseUrl)
			val executor = OllamaSimpleExecutor(config)
			try {
				val adapter = adapterOver(executor, config)

				// Cycle 1 is answered and leaves its keep-alive connection in the client's pool.
				runBlocking { adapter.plan(observation) }

				// Cycle 2 is held: it must time out, fall back, and close the held request. The agent is
				// cached by now, so the cycle's deadline starts (to within scheduling noise) here.
				val timeoutFiresAt = System.nanoTime() + CYCLE_TIMEOUT.toNanos()
				val decisions = runBlocking { adapter.plan(observation) }

				assertThat(decisions, "cycle 2 falls back to the rule-based dispatcher")
					.containsExactly(DispatchDecision.NoAction)
				assertThat(server.awaitHeldRequest(0), "cycle 2 reached the held request").isTrue()

				val latencyMillis = server.awaitCloseLatencyAfter(timeoutFiresAt, CLOSE_CEILING_MILLIS)
				println(
					"Issue #1341: held Ollama connection close latency after the cycle timeout = " +
						(latencyMillis?.let { "$it ms" } ?: "not closed within $CLOSE_CEILING_MILLIS ms")
				)
				assertThat(latencyMillis, "the held connection was closed").isNotNull()
				assertThat(latencyMillis!!, "close latency after the timeout (ms)")
					.isLessThanOrEqualTo(CLOSE_CEILING_MILLIS)
			} finally {
				executor.close()
			}
		}
	}

	/**
	 * The production planner wired to a real Koog agent built by [DefaultAgentService] over
	 * [executor]; only the agent factory (which would also pre-warm a real Ollama) is mocked.
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
