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
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
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
 *   inside the per-cycle timeout budget.
 *
 * ## Fallback priority
 *
 * 1. LLM cycle completes **and the LLM acted via its actuator tools** this cycle (detected by
 *    the [sinkHolder] per-cycle emission counter — see the "How the LLM acted via tools is
 *    detected" section; actuator tools emit their [DispatchAction]s to the shared [sinkHolder],
 *    whose queue-posting wrapper converts them to [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s
 *    and posts them to [commandQueue] as a side effect) → use the result as-is, never fall back.
 *    An empty returned list with tool side effects is the *normal, successful* outcome once real
 *    Koog tool-calling is wired: a completed cycle whose `decideAsync` returns empty (it always
 *    does — see [KoogDispatchAgentImpl]) does not mean the LLM did nothing. Treating that case
 *    as failure and invoking [fallbackDispatcher] on top would double-dispatch: the LLM's
 *    tool-driven decision plus the rule engine's independently-decided one for the same train/hop,
 *    posted to the same queue in the same drain cycle — risking the duplicate-`ReservePath`
 *    train-freeze regression `DispatchDecisionApplier`'s own KDoc documents as a past incident,
 *    from a new source. A deliberately emitted `no_op` also counts as "acted" (the LLM chose to
 *    do nothing), so the fallback does not run on top of an explicit no-op either.
 * 2. LLM cycle completes **and the LLM invoked no actuator tool** (the LLM truly did nothing this
 *    cycle — `decideAsync` returned empty and the emission counter is zero) → fall back to
 *    [fallbackDispatcher]. Nothing was posted this cycle, so there is no double-dispatch risk;
 *    and because the LLM is stateless across cycles (a fresh `singleRunStrategy()` execution per
 *    [KoogDispatchAgent.decideAsync] — the agent itself is cached and reused; see
 *    [KoogAgentFactory]), a no-op cycle is not a preamble to a later routing cycle — not falling
 *    back would leave queued trains never routed, stalled at their entry signal indefinitely.
 *    The fallback supplies both admission and routing.
 * 3. LLM times out → fall back to [fallbackDispatcher]
 * 4. LLM throws exception → fall back to [fallbackDispatcher] (re-throws [CancellationException])
 *
 * ## How "the LLM acted via tools" is detected
 *
 * [SinkHolder.resetCycleEmissionCount] is called immediately before
 * [KoogDispatchAgent.decideAsync] and [SinkHolder.actedThisCycle] immediately after; the counter
 * is incremented by each actuator-tool [SinkHolder.emit] during the LLM's tool-calling loop. This
 * counts [SinkHolder.emit] **calls**, not queue **contents**, so it is immune to the kDisco sim
 * thread draining the queue between the two samples — the false-negative window that an
 * [ActuatorCommandQueue.approximateSize] before/after delta could not close under the production
 * decoupled driver/sim threading model (the driver runs on the `dispatcher-agent-driver` daemon
 * thread; `ShuntingLoop.iteration` drains the queue on the kDisco sim thread; there is no strict
 * handshake between them, only [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]'s
 * polling-based snapshot wait). A slow local-Ollama cycle can therefore overlap several sim
 * iterations, and a content-delta could read "no change" after the sim drained the LLM's
 * already-posted decision — the call counter cannot, because draining does not decrement it.
 *
 * The counter lives on the [sinkHolder] shared with [KoogAgentFactory] (which installs the
 * queue-posting wrapper on `sinkHolder.current`), so the same instance both routes emissions to
 * the queue and records that they happened.
 *
 * **Residual safety** (the backstop if a detection miss ever did occur, e.g. a tool post
 * rejected by queue backpressure so no call is counted): the downstream layers are idempotent —
 * `ShuntingLoop.approveQueuedTrain` is a no-op for an already-active/nonexistent train, and the
 * reservation layer's block-exclusivity rejects a duplicate `ReservePath` for an already-owned
 * block (`AllPathsBlocked`). So a fallback firing on top of an already-acted cycle degrades to a
 * redundant (rejected) decision rather than a corrupting one. The call counter exists to avoid
 * relying on that backstop in the common case.
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
 * @param commandQueue   Shared actuator command queue for this context. Used by this adapter to
 *                       advance the correlation cycle before each LLM inference call via
 *                       [ActuatorCommandQueue.advanceCorrelationCycle].
 * @param sinkHolder     Per-context [SinkHolder] shared with [KoogAgentFactory]. The factory
 *                       installs the queue-posting wrapper on its `current`; this adapter reads
 *                       its per-cycle emission counter to detect whether the LLM acted via tools
 *                       (see the "How the LLM acted via tools is detected" section).
 * @param cycleListener  Optional [PlannerCycleListener] notified after every dispatch cycle
 *                       with either [PlannerCycleListener.onLlmSuccess] or
 *                       [PlannerCycleListener.onFallback].  Used by [MeasuringPlanAdapter]
 *                       to collect fallback metrics without coupling the two classes.
 *                       Thread-safe: reads are done under `@Volatile`; write must happen
 *                       before the first [plan] call (typically from the same thread that
 *                       constructs the outer [MeasuringPlanAdapter]).
 */
class KoogAgentPlanAdapter(
	private val agentFactory: KoogAgentFactory,
	private val context: DefaultSimulationContext,
	private val fallbackDispatcher: Dispatcher,
	private val inferenceTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
	private val commandQueue: ActuatorCommandQueue,
	private val sinkHolder: SinkHolder,
	/**
	 * Bounded history of previous cycles, rendered into the next cycle's prompt by the agent.
	 * This adapter is its only writer: it is the one place that knows both what the agent
	 * emitted (via [sinkHolder]) and how the cycle was classified.
	 *
	 * Defaults to a disabled history, reproducing the previous stateless-per-cycle behaviour.
	 */
	private val cycleHistory: CycleHistory = CycleHistory(capacity = 0)
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

	/**
	 * Optional [PlannerTickListener] notified after every dispatch cycle with the full
	 * [TickOutcome] taxonomy — the non-deprecated replacement for [cycleListener].
	 *
	 * Independent of [cycleListener]: setting this does not disturb [MeasuringPlanAdapter]'s
	 * claim on [cycleListener], and both fire for the same cycle without interfering. `null` in
	 * runs that don't need per-cycle attribution (e.g. [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]
	 * sets it to attribute `commandQueue.postAll` calls correctly).
	 *
	 * `@Volatile` for the same safe-publication reason as [cycleListener].
	 */
	@Volatile
	var tickListener: PlannerTickListener? = null

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
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> =
		try {
			// Agent creation is deliberately INSIDE the try: createAgent runs
			// OllamaModelPrewarmer.warmUp — real network I/O that can fail — and if that call sat
			// outside the try its exception would escape plan() altogether, propagating out of
			// AgentLoopDriver.runCycle() into a daemon thread with no uncaught-exception handler and
			// killing the dispatcher for the rest of the run. A creation failure is an ordinary
			// counted fallback like any other LLM failure, and `agent` stays null so the next cycle
			// retries rather than the whole run being demoted to rule-based by one transient fault.
			val a = getOrCreateAgent()
			// Advance the correlation-map cycle counter before the LLM cycle so every decision
			// posted by actuator tools during decideAsync receives the correct tick index, and
			// zero the per-cycle emission counter so actedThisCycle() reflects only this cycle.
			commandQueue.advanceCorrelationCycle()
			sinkHolder.resetCycleEmissionCount()
			val decisions =
				withTimeout(inferenceTimeout.toMillis()) {
					a.decideAsync(observation)
				}
			if (sinkHolder.actedThisCycle() || decisions.isNotEmpty()) {
				// The LLM acted via its actuator tools (the emissions were already posted to the
				// queue through sinkHolder.current) and/or returned decisions directly. Either way
				// the LLM did its job this cycle — do NOT fall back (would double-dispatch). An
				// empty returned list with tool emissions is the normal, successful outcome:
				// decideAsync always returns empty (see KoogDispatchAgentImpl); the load-bearing
				// signal is the emission counter.
				logger.debug {
					"KoogAgentPlanAdapter: LLM cycle acted via tools " +
						"(emitted=${sinkHolder.actedThisCycle()}, returned=${decisions.size}) " +
						"(simTime=${observation.snapshot.simTime}); not falling back"
				}
				cycleListener?.onLlmSuccess(observation.snapshot.simTime)
				reportTick(TickOutcome.LLM_ACTIONS, observation.snapshot.simTime)
				decisions
			} else {
				// The LLM completed a cycle but neither acted via tools nor returned a decision —
				// it truly did nothing this cycle. Nothing was posted, so there is no
				// double-dispatch risk; the fallback supplies both admission and routing.
				runFallback(FallbackReason.EMPTY_NO_TOOLS, observation) {
					logger.warn {
						"KoogAgentPlanAdapter: LLM cycle produced no decisions and no tool emissions — " +
							"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
					}
				}
			}
		} catch (e: TimeoutCancellationException) {
			runFallback(FallbackReason.TIMEOUT, observation) {
				logger.warn {
					"KoogAgentPlanAdapter: LLM timed out after ${inferenceTimeout.toSeconds()}s — " +
						"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
				}
			}
		} catch (e: CancellationException) {
			// Parent coroutine was cancelled — propagate rather than swallow.
			throw e
		} catch (e: Exception) {
			runFallback(FallbackReason.EXCEPTION, observation) {
				logger.warn(e) {
					"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
						"(simTime=${observation.snapshot.simTime})"
				}
			}
		}

	/**
	 * Runs the shared rule-based-fallback sequence: log via [logAction], notify [cycleListener],
	 * report [TickOutcome.RULE_FALLBACK] (the fallback dispatcher always actually runs and
	 * returns decisions here — a dispatching event, not a no-op), then delegate to
	 * [fallbackDispatcher]. Shared by all three fallback sites in [plan] (empty LLM cycle,
	 * inference timeout, LLM exception) so they cannot drift out of sync with each other.
	 */
	private fun runFallback(
		reason: FallbackReason,
		observation: DispatchObservation,
		logAction: () -> Unit
	): List<DispatchDecision> {
		logAction()
		cycleListener?.onFallback(reason, observation.snapshot.simTime)
		reportTick(TickOutcome.RULE_FALLBACK, observation.snapshot.simTime)
		return fallbackDispatcher.decide(observation)
	}

	/**
	 * Publishes one completed cycle to the tick listener and to [cycleHistory].
	 *
	 * A single funnel rather than a call pair at each of the four cycle endings: the history and
	 * the tick taxonomy must never disagree about how a cycle ended, and they cannot drift if
	 * there is only one place that reports both.
	 *
	 * The recorded actions are read from [sinkHolder], so they are what the **agent** emitted.
	 * On a `RULE_FALLBACK` cycle that list is normally empty even though the fallback dispatcher
	 * did act — deliberately: [cycleHistory] is the model's memory of its own behaviour, and the
	 * outcome name already tells it the cycle was taken over.
	 */
	private fun reportTick(
		outcome: TickOutcome,
		simTime: Double
	) {
		tickListener?.onTick(TickRecord(outcome, simTime))
		cycleHistory.record(simTime, outcome, sinkHolder.emittedActionsThisCycle())
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
