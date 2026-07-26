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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * [DispatcherPlanner] backed by the Koog LLM agent with a deterministic [Dispatcher] fallback.
 *
 * ## SP2b.9 (Issue #566)
 *
 * Runs the DISPATCHER agent against a local Ollama model (via [KoogAgentFactory]) and falls back
 * to the deterministic [fallbackDispatcher] (typically [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher])
 * whenever the LLM:
 * - stalls (exceeds [inferenceTimeout])
 * - returns an empty decision list (agent found nothing to do or skeleton not yet implemented)
 * - throws any exception (network error, invalid tool call, etc.)
 *
 * ## Design
 *
 * - **Async**: [capabilities.isAsynchronous] is `true`; the Koog agent may suspend during
 *   LLM inference. This requires a pacing [cz.vutbr.fit.interlockSim.context.SimulationController]
 *   (e.g. [cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController]) — use with
 *   GUI-based examples only ([assertPlannerPacingCompatible] enforces this at startup).
 * - **Timeout guard**: [withTimeout] wraps each [KoogDispatchAgent.decideAsync] call with
 *   [inferenceTimeout]. On timeout, the fallback dispatcher takes over for that cycle.
 * - **Lazy agent creation**: the Koog agent is created on the first [plan] invocation via
 *   [KoogAgentFactory.createAgent] (a `suspend` function). This defers Ollama connectivity
 *   checks until the simulation actually starts.
 *
 * ## Fallback priority
 *
 * 1. LLM cycle completes (any [KoogDispatchAgent.decideAsync] result, **including an empty
 *    list**) → use it as-is, never fall back. An empty list is the *normal, successful* outcome
 *    once real Koog tool-calling is wired: actuator tools already post their
 *    [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s directly to the shared
 *    `ActuatorCommandQueue` as a side effect during `decideAsync`, so a completed cycle with no
 *    returned decisions does not mean the LLM did nothing. Treating "empty" as failure and
 *    invoking [fallbackDispatcher] on top would double-dispatch: the LLM's tool-driven decision
 *    plus the rule engine's independently-decided one for the same train/hop, posted to the same
 *    queue in the same drain cycle — risking the duplicate-`ReservePath` train-freeze regression
 *    `DispatchDecisionApplier`'s own KDoc documents as a past incident (Issue #733 follow-up),
 *    from a new source.
 * 2. LLM times out → fall back to [fallbackDispatcher]
 * 3. LLM throws exception → fall back to [fallbackDispatcher] (re-throws [CancellationException])
 *
 * ## Thread safety
 *
 * [getOrCreateAgent] uses a [kotlinx.coroutines.sync.Mutex] to serialize concurrent
 * initializations — exactly one [KoogAgentFactory.createAgent] call is made even if [plan]
 * is invoked from multiple coroutines simultaneously. Subsequent calls read the cached
 * [agent] via the `@Volatile` fast-path without lock contention.
 *
 * @param agentFactory   Per-context factory for building the Koog dispatch agent (scoped).
 * @param context        Simulation context passed to [KoogAgentFactory.createAgent] for
 *                       topology serialization and snapshot tooling.
 * @param fallbackDispatcher Deterministic rule-based fallback invoked when the LLM cannot
 *                       produce valid decisions (typically [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]).
 * @param inferenceTimeout Maximum wall-clock time to wait for a single LLM response before
 *                       invoking the fallback. Defaults to 30 seconds (matches
 *                       [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.DEFAULT_INFERENCE_TIMEOUT]).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogAgentPlanAdapter(
	private val agentFactory: KoogAgentFactory,
	private val context: DefaultSimulationContext,
	private val fallbackDispatcher: Dispatcher,
	private val inferenceTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
) : DispatcherPlanner {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Human-readable name identifying this planner. */
		const val PLANNER_NAME = "KoogAgent+RuleBasedFallback"

		/** Default inference timeout in seconds — matches OllamaExecutorConfig.DEFAULT_INFERENCE_TIMEOUT. */
		const val DEFAULT_TIMEOUT_SECONDS: Long = 30
	}

	override val capabilities: PlannerCapabilities =
		PlannerCapabilities(
			name = PLANNER_NAME,
			isAsynchronous = true,
			maxSpeedMultiplier = PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER
		)

	/**
	 * Lazily-created Koog dispatch agent, guarded by [agentInitMutex].
	 *
	 * `null` until the first [plan] call; `@Volatile` for safe publication after the mutex
	 * is released so that the fast path in [getOrCreateAgent] avoids acquiring the lock on
	 * every subsequent call.
	 */
	@Volatile
	private var agent: KoogDispatchAgent? = null

	/** Ensures exactly one concurrent [KoogAgentFactory.createAgent] call (suspend-friendly). */
	private val agentInitMutex = Mutex()

	/**
	 * Produces dispatch decisions by consulting the Koog LLM agent, falling back to
	 * [fallbackDispatcher] on empty result, timeout, or any exception.
	 *
	 * The fallback is invoked transparently — callers observe valid decisions regardless
	 * of which path (LLM or rule-based) produced them.
	 *
	 * @param observation Read-only snapshot of the current railway network state.
	 * @return Non-null list of decisions; may be the rule-based fallback result.
	 */
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
		val a = getOrCreateAgent()
		return try {
			val decisions =
				withTimeout(inferenceTimeout.toMillis()) {
					a.decideAsync(observation)
				}
			// An empty list is NOT a failure signal — see the class KDoc's "Fallback priority"
			// section for why falling back here would risk double-dispatching alongside the
			// LLM's own tool-driven actuation.
			logger.debug {
				"KoogAgentPlanAdapter: LLM cycle completed with ${decisions.size} directly-returned " +
					"decision(s) (simTime=${observation.snapshot.simTime}); not falling back"
			}
			decisions
		} catch (e: TimeoutCancellationException) {
			logger.warn {
				"KoogAgentPlanAdapter: LLM timed out after ${inferenceTimeout.toSeconds()}s — " +
					"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
			}
			fallbackDispatcher.decide(observation)
		} catch (e: CancellationException) {
			// Parent coroutine was cancelled — propagate rather than swallow.
			throw e
		} catch (e: Exception) {
			logger.warn(e) {
				"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
					"(simTime=${observation.snapshot.simTime})"
			}
			fallbackDispatcher.decide(observation)
		}
	}

	/**
	 * Returns the cached [KoogDispatchAgent], creating it lazily on the first call.
	 *
	 * Thread-safe: a [Mutex] serializes concurrent initializations so exactly one
	 * [KoogAgentFactory.createAgent] call is made even when [plan] is invoked from
	 * multiple coroutines simultaneously. The `@Volatile` fast-path check avoids
	 * lock contention after initialization.
	 */
	private suspend fun getOrCreateAgent(): KoogDispatchAgent {
		agent?.let { return it }
		return agentInitMutex.withLock {
			agent ?: agentFactory.createAgent(context).also { agent = it }
		}
	}
}
