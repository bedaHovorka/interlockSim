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
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
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
 * - **Lazy agent creation with warm-up**: the Koog agent is created on the first [plan]
 *   invocation via [KoogAgentFactory.createAgent] (a `suspend` function). As part of agent
 *   creation, [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelPrewarmer.warmUp]
 *   fires a minimal request to preload the configured model into Ollama memory — this happens
 *   *before* [withTimeout] starts, so cold model-load latency is absorbed here rather than
 *   inside the per-cycle timeout budget (Issue #815).
 *
 * ## Fallback priority
 *
 * 1. LLM cycle completes with a non-empty decision list, **or** the cycle invoked at least one
 *    actuator tool (detected by [commandQueue]'s per-cycle actuator-call counter — see the
 *    "How the LLM acted via tools is detected" section; actuator tools post their
 *    [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s to the shared `ActuatorCommandQueue` as a
 *    side effect) → use the result as-is, never fall back, and run the [maybeForceAdmission]
 *    safety net. An empty list with tool side effects is the *normal, successful* outcome once
 *    real Koog tool-calling is wired: a completed cycle with no returned decisions does not
 *    mean the LLM did nothing. Treating that case as failure and invoking [fallbackDispatcher]
 *    on top would double-dispatch: the LLM's tool-driven decision plus the rule engine's
 *    independently-decided one for the same train/hop, posted to the same queue in the same
 *    drain cycle — risking the duplicate-`ReservePath` train-freeze regression
 *    `DispatchDecisionApplier`'s own KDoc documents as a past incident (Issue #733 follow-up),
 *    from a new source.
 * 2. LLM cycle completes empty **and** invoked no actuator tool (the LLM truly did nothing this
 *    cycle) → fall back to [fallbackDispatcher]. Nothing was posted this cycle, so there is no
 *    double-dispatch risk; and because the LLM is stateless across cycles (a fresh
 *    `singleRunStrategy()` execution per [KoogDispatchAgent.decideAsync] — the agent itself is
 *    cached and reused; see [KoogAgentFactory]), a no-op cycle is not a preamble to a later
 *    routing cycle — not falling back would leave queued trains admitted (by the safety net)
 *    but never routed, stalled at their entry signal indefinitely. The fallback supplies both
 *    admission and routing, so the [maybeForceAdmission] safety net is intentionally NOT run
 *    on this path (the fallback's own admission decisions replace it).
 * 3. LLM times out → fall back to [fallbackDispatcher]
 * 4. LLM throws exception → fall back to [fallbackDispatcher] (re-throws [CancellationException])
 *
 * ## How "the LLM acted via tools" is detected
 *
 * [ActuatorCommandQueue.resetCycleActuatorCount] is called immediately before
 * [KoogDispatchAgent.decideAsync] and [ActuatorCommandQueue.actedViaToolsThisCycle] immediately
 * after; the counter is incremented by each successful [ActuatorCommandQueue.postAll] of
 * non-empty decisions during the LLM's tool-calling loop. This counts actuator-tool post
 * **calls**, not queue **contents**, so it is immune to the kDisco sim thread draining the
 * queue between the two samples — the false-negative window that an
 * [ActuatorCommandQueue.approximateSize] before/after delta could not close under the
 * production decoupled driver/sim threading model (the driver runs on the
 * `dispatcher-agent-driver` daemon thread; `ShuntingLoop.iteration` drains the queue on the
 * kDisco sim thread; there is no strict handshake between them, only
 * [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]'s polling-based snapshot wait). A slow
 * local-Ollama cycle can therefore overlap several sim iterations, and a content-delta could
 * read "no change" after the sim drained the LLM's already-posted decision — the call counter
 * cannot, because draining does not decrement it.
 *
 * **Residual safety** (the backstop if a detection miss ever did occur, e.g. a tool post
 * rejected by queue backpressure so no call is counted): the downstream layers are idempotent —
 * `ShuntingLoop.approveQueuedTrain` is a no-op for an already-active/nonexistent train (SP0.11),
 * and the reservation layer's block-exclusivity rejects a duplicate `ReservePath` for an
 * already-owned block (`AllPathsBlocked`). So a fallback firing on top of an already-acted
 * cycle degrades to a redundant (rejected) decision rather than a corrupting one. The call
 * counter exists to avoid relying on that backstop in the common case.
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
 * @param commandQueue   Shared actuator command queue for this context, used by the admission
 *                       safety net (see [maybeForceAdmission]) to post a fire-and-forget
 *                       [DispatchDecision.ApproveTrain] exactly like the `approve_train` tool
 *                       itself does.
 * @param maxConcurrentTrains Station capacity the safety net admits up to. Defaults to
 *                       [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS], the same constant
 *                       the non-LLM dispatcher and `approve_train` tool use.
 * @param cycleListener  Optional [PlannerCycleListener] notified after every dispatch cycle
 *                       with either [PlannerCycleListener.onLlmSuccess] or
 *                       [PlannerCycleListener.onFallback].  Used by [MeasuringPlanAdapter]
 *                       to collect fallback metrics without coupling the two classes.
 *                       Thread-safe: reads are done under `@Volatile`; write must happen
 *                       before the first [plan] call (typically from the same thread that
 *                       constructs the outer [MeasuringPlanAdapter]).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogAgentPlanAdapter(
	private val agentFactory: KoogAgentFactory,
	private val context: DefaultSimulationContext,
	private val fallbackDispatcher: Dispatcher,
	private val inferenceTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
	private val commandQueue: ActuatorCommandQueue,
	private val maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
) : DispatcherPlanner {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Human-readable name identifying this planner. */
		const val PLANNER_NAME = "KoogAgent+RuleBasedFallback"

		/** Default inference timeout in seconds — matches OllamaExecutorConfig.DEFAULT_INFERENCE_TIMEOUT. */
		const val DEFAULT_TIMEOUT_SECONDS: Long = 30
	}

	/**
	 * Optional [PlannerCycleListener] notified after every dispatch cycle.
	 *
	 * Set by [MeasuringPlanAdapter] immediately after construction to collect fallback metrics.
	 * `null` in production runs that don't need metrics.  `@Volatile` for safe publication:
	 * written once by the constructing thread (before any [plan] call) and read by the
	 * coroutine running [plan] (the `dispatcher-agent-driver` daemon thread).
	 */
	@Volatile
	var cycleListener: PlannerCycleListener? = null

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
			// Advance the correlation-map cycle counter before the LLM cycle so every decision
			// posted by actuator tools during decideAsync receives the correct tick index.
			// SP2c.6 (#829): replaces resetCycleActuatorCount() — the per-cycle actuator-post
			// counter was deleted along with the actedViaToolsThisCycle() heuristic.
			commandQueue.advanceCorrelationCycle()
			val decisions =
				withTimeout(inferenceTimeout.toMillis()) {
					a.decideAsync(observation)
				}
			if (decisions.isEmpty()) {
				// The LLM completed a cycle but returned no decisions. In SP2c.6 the actuator tools
				// emit directly to EmittedActionSink (SinkHolder path), not to this commandQueue —
				// so there is no need to check actedViaToolsThisCycle(). An empty decision list
				// means "the LLM did nothing directly-returned this cycle"; the fallback supplies
				// both admission and routing.
				logger.warn {
					"KoogAgentPlanAdapter: LLM cycle produced no decisions — " +
						"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
				}
				cycleListener?.onFallback(FallbackReason.EMPTY_NO_TOOLS, observation.snapshot.simTime)
				fallbackDispatcher.decide(observation)
			} else {
				logger.debug {
					"KoogAgentPlanAdapter: LLM cycle completed with ${decisions.size} " +
						"directly-returned decision(s) (simTime=${observation.snapshot.simTime}); not falling back"
				}
				cycleListener?.onLlmSuccess(observation.snapshot.simTime)
				maybeForceAdmission(observation)
				decisions
			}
		} catch (e: TimeoutCancellationException) {
			logger.warn {
				"KoogAgentPlanAdapter: LLM timed out after ${inferenceTimeout.toSeconds()}s — " +
					"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
			}
			cycleListener?.onFallback(FallbackReason.TIMEOUT, observation.snapshot.simTime)
			fallbackDispatcher.decide(observation)
		} catch (e: CancellationException) {
			// Parent coroutine was cancelled — propagate rather than swallow.
			throw e
		} catch (e: Exception) {
			logger.warn(e) {
				"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
					"(simTime=${observation.snapshot.simTime})"
			}
			cycleListener?.onFallback(FallbackReason.EXCEPTION, observation.snapshot.simTime)
			fallbackDispatcher.decide(observation)
		}
	}

	/**
	 * Admission safety net (Goal 10 SP2b.9 follow-up).
	 *
	 * Each LLM dispatch cycle is a fresh, stateless Koog session with no memory of prior
	 * cycles (see [KoogAgentFactory]/agent-architect analysis) — the LLM was observed to
	 * successfully admit its first train, then stop calling `approve_train` for many
	 * subsequent cycles despite free capacity remaining and trains queued.
	 * [DispatchDecision.ApproveTrain] is documented idempotent (a no-op for an already-active
	 * or nonexistent train — see `ApproveTrainTool`'s KDoc), so it is always safe to
	 * force-admit the oldest queued train(s), FIFO, up to [maxConcurrentTrains], after every
	 * completed (non-fallback) LLM cycle — mirroring [RuleBasedDispatcher]'s own unconditional
	 * `decideAdmissions()` policy without taking over the rest of the cycle's decisions.
	 *
	 * Not invoked on the fallback path: [fallbackDispatcher] (typically [RuleBasedDispatcher])
	 * already includes its own admission decisions in the list it returns.
	 */
	private fun maybeForceAdmission(observation: DispatchObservation) {
		val freeSlots = maxConcurrentTrains - observation.approvedTrainCount
		if (freeSlots <= 0) return
		val toAdmit = observation.unapprovedTrains.take(freeSlots)
		if (toAdmit.isEmpty()) return
		logger.info {
			"KoogAgentPlanAdapter: admission safety net — force-approving ${toAdmit.map { it.trainId }} " +
				"(LLM left $freeSlots free slot(s) unused this cycle, simTime=${observation.snapshot.simTime})"
		}
		commandQueue.postAll(toAdmit.map { DispatchDecision.ApproveTrain(it.trainId) })
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
