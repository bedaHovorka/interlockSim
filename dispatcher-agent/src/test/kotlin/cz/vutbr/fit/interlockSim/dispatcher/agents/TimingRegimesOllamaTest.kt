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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaPrewarmExtension
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-Ollama coverage for the F1 paused-clock and F2 wall-clock-deadline timing regimes
 * (SP2c.10, Issue #833), `@Tag("ollama-test")`.
 *
 * ## Why this class exists
 *
 * Every existing [TickBudgetTest] and [DispatchTickLoopTest][cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoopTest]
 * case for [PausedClockTickBudget] and [DeadlineTickBudget] exercises the wrapper against a fake
 * emission (`delay(...)`), never a real Ollama inference. Issue #833's own motivation is that a
 * local 7B-class model with tools is ~1-5 s per decision — the entire reason these two timing
 * regimes exist — yet nothing in the module proved either regime against that genuine latency.
 * This class closes that hole, following the live-Ollama convention already established by
 * [KoogRealOllamaToolCallingTest] (real [DefaultAgentService]/[OllamaSimpleExecutor] wiring) and
 * `OllamaRuntimeContractOllamaTest` (asserting real backend behaviour, not client-library
 * interpretation of it).
 *
 * Assertions here deliberately avoid pinning exact timings — a 7B-class model's latency varies
 * with machine load — and instead assert the structural/behavioural facts SP2c.10 actually
 * promises:
 * - **F1**: [SimulationController.requestPause] happens before the real inference call and
 *   [SimulationController.requestResume] happens after — the pause window genuinely brackets a
 *   multi-second real emission, not just a fake `delay(...)`.
 * - **F2**: a real-time ratio is computed from genuine (not simulated) wall-clock latency, is a
 *   positive finite number, and the tick outcome is not treated as a failure purely because that
 *   ratio is below 1x — matching the "ungated for the LLM arm" acceptance criterion.
 *
 * [OllamaPrewarmExtension] preloads the model before the test class runs (Issue #815) so the
 * generous per-test timeout below is not consumed by cold model-load latency.
 *
 * @since Issue #833 (SP2c.10 — Goal 10 timing regimes F1+F2)
 */
@DisplayName("SP2c.10 — F1/F2 timing regimes against real local Ollama inference (#833)")
@ExtendWith(OllamaPrewarmExtension::class)
@Timeout(3, unit = TimeUnit.MINUTES)
class TimingRegimesOllamaTest {
	/** Recording [SimulationController] fake — mirrors [TickBudgetTest]'s, kept local to this class. */
	private class RecordingController : SimulationController {
		val calls = mutableListOf<String>()

		@Volatile
		var paused: Boolean = false

		override fun isPaused(): Boolean = paused

		override fun requestPause() {
			calls += "pause"
			paused = true
		}

		override fun requestResume() {
			calls += "resume"
			paused = false
		}

		override suspend fun awaitIfPaused() = Unit

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun throttle(simDeltaSeconds: Double) = Unit

		override fun currentSpeedMultiplier(): Double = 1.0
	}

	/** Trivial no-argument perception tool — kept minimal, same shape as the sibling ollama-tests. */
	private class FakePerceptionTool(
		private val callCount: AtomicInteger
	) : DomainTool {
		override val name: String = "queued_trains"
		override val description: String = "Query trains waiting for admission."
		override val parameters: List<DomainToolParameter> = emptyList()

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount.incrementAndGet()
			return ToolResult.Success("[]")
		}
	}

	private class FakeRequestRouteTool(
		val callCount: AtomicInteger = AtomicInteger(0)
	) : DomainTool {
		override val name: String = "request_route"
		override val description: String =
			"Reserve a route for a named train from one point to another. Call this to admit a " +
				"queued train onto the network."
		override val parameters: List<DomainToolParameter> =
			listOf(
				DomainToolParameter("train_name", "Name of the train to reserve a route for", DomainToolParameterType.String),
				DomainToolParameter("from_point", "Name of the point the train departs from", DomainToolParameterType.String),
				DomainToolParameter("to_point", "Name of the point the train travels to", DomainToolParameterType.String)
			)

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount.incrementAndGet()
			return ToolResult.Success("queued")
		}
	}

	private val systemPrompt =
		"You are a railway dispatcher test harness. Exactly one queued train, named \"T1\", " +
			"must travel from point \"A\" to point \"B\". Call the request_route tool with " +
			"train_name=\"T1\", from_point=\"A\", to_point=\"B\" to reserve its route, then " +
			"reply with one short confirmation sentence."

	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = listOf(QueuedTrainObservation(trainId = "T1", destinationInOutName = "B")),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	/** Generous per-call ceiling for the outer [withTimeout] guarding a real inference call. */
	private val callTimeoutMillis = 120_000L

	/**
	 * F1 live check: a real (multi-second) Ollama-backed decision is wrapped in
	 * [PausedClockTickBudget]. Would fail if [PausedClockTickBudget] stopped calling
	 * [SimulationController.requestPause]/[requestResume] around the block, or called them in the
	 * wrong order, or left the simulation paused after a real (slow) inference completed.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("F1: PausedClockTickBudget brackets a real Ollama inference with pause before and resume after")
	fun pausedClockBracketsRealInference() {
		val controller = RecordingController()
		val budget = PausedClockTickBudget(controller)
		val requestRouteTool = FakeRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		runBlocking {
			withTimeout(callTimeoutMillis) {
				budget.withBudget {
					val agent =
						service.createDispatchAgent(
							modelName = config.modelName,
							tools = listOf(FakePerceptionTool(AtomicInteger(0)), requestRouteTool),
							systemPrompt = systemPrompt
						)
					agent.decideAsync(observation)
				}
			}
		}

		// The pause/resume pair genuinely bracketed the real inference call, in order, and the
		// simulation is not left paused afterwards.
		assertThat(controller.calls).isEqualTo(listOf("pause", "resume"))
		assertThat(controller.isPaused()).isFalse()
		// The real model genuinely ran (not a short-circuited/degenerate response): the fake
		// actuator tool was invoked at least once.
		assertThat(requestRouteTool.callCount.get()).isGreaterThan(0)
	}

	/**
	 * F2 live check: a real inference is run through [DeadlineTickBudget] configured with the
	 * documented 3 s-class production deadline window (30 s here, generous enough that a healthy
	 * local model normally completes well inside it — the point is to measure genuine latency, not
	 * to provoke a timeout), and a real-time ratio is computed from genuine wall-clock elapsed
	 * time. Asserts the ratio is reported (a positive finite number) and that a sub-1x ratio (which
	 * a multi-second local inference will typically produce against this fixture's near-zero
	 * simDelta) does not by itself fail or reject the tick — matching #833's "ungated for the LLM
	 * arm" acceptance criterion.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("F2: real-time ratio is computed from genuine Ollama latency and does not gate the tick")
	fun realTimeRatioComputedFromGenuineLatencyIsUngated() {
		val budget = DeadlineTickBudget(timeoutMillis = 30_000L)
		val requestRouteTool = FakeRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		// A deliberately small simDelta: a real multi-second inference against it is certain to
		// yield a real-time ratio well below 1x, which is exactly the "honest, ungated" case F2
		// is meant to report rather than reject.
		val simDeltaSeconds = 0.1

		val startNanos = System.nanoTime()
		val decisions =
			runBlocking {
				withTimeout(callTimeoutMillis) {
					budget.withBudget {
						val agent =
							service.createDispatchAgent(
								modelName = config.modelName,
								tools = listOf(FakePerceptionTool(AtomicInteger(0)), requestRouteTool),
								systemPrompt = systemPrompt
							)
						agent.decideAsync(observation)
					}
				}
			}
		val emissionWallClockSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

		// The deadline was not exceeded: a genuine (non-null) result came back.
		assertThat(decisions).isNotNull()
		assertThat(emissionWallClockSeconds).isGreaterThan(0.0)

		val realTimeRatio = simDeltaSeconds / emissionWallClockSeconds

		// The ratio reflects genuine latency: positive, finite, and — because a real multi-second
		// inference vastly exceeds this fixture's 0.1 s simDelta — below 1x. Reporting a sub-1x
		// ratio here does not itself fail the test / gate the run, matching #833's LLM-arm rule.
		assertThat(realTimeRatio).isGreaterThan(0.0)
		assertThat(realTimeRatio.isFinite()).isTrue()
		assertThat(requestRouteTool.callCount.get()).isGreaterThan(0)
	}
}
