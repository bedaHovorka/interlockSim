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
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
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
import kotlin.time.TimeSource

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
 * ## Overload handling (Issue #1058)
 *
 * - **Circuit breaker**: [circuitBreaker] tracks consecutive cycle failures (timeout or exception,
 *   after any retry — see below). Once [LlmCircuitBreaker.recordFailure] accumulates
 *   `failureThreshold` failures in a row, the breaker OPENs and [plan] skips the LLM call entirely
 *   for a cooldown window of simulation time, going straight to [fallbackDispatcher] — so sustained
 *   LLM slowness stops paying a full [inferenceTimeout] stall on every tick. After the cooldown, a
 *   single HALF_OPEN probe cycle is attempted; success closes the breaker, failure re-opens it.
 *   The breaker grants exactly one in-flight probe at a time (see [LlmCircuitBreaker]'s
 *   "Single-probe guard"), and [plan] releases the claim in a `try/finally` so a cancelled probe
 *   cannot wedge the breaker in HALF_OPEN.
 * - **One bounded retry**: a plain (non-timeout) exception from `decideAsync` is retried exactly
 *   once within the same cycle before the fallback is consulted — a timeout is never retried
 *   (retrying it would double the 30s stall, the opposite of what an overloaded system needs), and
 *   neither is a failed attempt that already emitted actuator actions (the emissions are posted;
 *   a retry could double-dispatch a second action for the same train). Only the cycle's final
 *   outcome (not each attempt) is reported to [circuitBreaker].
 * - **Log hygiene**: repeated skips while the breaker is OPEN log at `warn` once per open window and
 *   at `debug` afterwards — never a stack trace per tick.
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
 *    do nothing), so the fallback does not run on top of an explicit no-op either — but is
 *    reported as [TickOutcome.LLM_NO_OP] rather than [TickOutcome.LLM_ACTIONS] when *every*
 *    emission this cycle was `no_op` (Issue #834, required change 2): a no-op tick is not an
 *    action tick even though both skip the fallback for the same double-dispatch reason.
 *    **A failed cycle with a partial emission gets the same protection** (#1058 review round):
 *    the posted emissions are kept, no retry runs, the failure still feeds [circuitBreaker], and
 *    the tick is reported through the emission classification rather than [TickOutcome.RULE_FALLBACK].
 * 2. LLM cycle completes, invoked no actuator tool, and the station is **idle** — no approved
 *    (active) trains and no unapproved (queued) trains (Issue #834) → do NOT fall back; report
 *    [TickOutcome.LLM_NO_OP]. There is nothing to dispatch, so a correctly-idle LLM cycle must not
 *    be scored as a rule-based-fallback run failure (the defect Issue #834 reports:
 *    `fallback: reason=EMPTY_NO_TOOLS ... ollamaSuccessRate=27%` on an empty station). See
 *    [isIdleStation] for the exact predicate and its [SimulationSnapshot.EMPTY] guard.
 * 3. LLM cycle completes, invoked no actuator tool, and the station is **not idle** (an active or
 *    queued train the LLM left unaddressed) — the LLM produced nothing this cycle → consult
 *    [fallbackDispatcher] either way (Issue #927): nothing was posted this cycle, so there is no
 *    double-dispatch risk, and because the LLM is stateless across cycles (a fresh
 *    `singleRunStrategy()` execution per [KoogDispatchAgent.decideAsync] — the agent itself is
 *    cached and reused; see [KoogAgentFactory]), a silent cycle is not a preamble to a later
 *    routing cycle — not consulting the fallback would leave queued trains never routed, stalled
 *    at their entry signal indefinitely. If the fallback finds and dispatches something, that is
 *    a genuine miss, reported as [TickOutcome.RULE_FALLBACK]; if it too finds nothing legal to
 *    do, the tick was never actionable, reported as [TickOutcome.LLM_SILENT_NONACTIONABLE] —
 *    see [runFallback]'s "Outcome classification" KDoc.
 * 4. LLM times out → fall back to [fallbackDispatcher]
 * 5. LLM throws exception → fall back to [fallbackDispatcher] (re-throws [CancellationException])
 *
 * ## How "the LLM acted via tools" is detected
 *
 * [SinkHolder.resetCycleEmissionCount] is called at the top of [plan] — before every early exit
 * and before [KoogDispatchAgent.decideAsync] — and [SinkHolder.actedThisCycle] immediately after
 * the attempt. The counter is incremented by each actuator-tool [SinkHolder.emit] during the
 * LLM's tool-calling loop. This
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
 * @param cycleHistory   Bounded history of previous cycles, rendered into the next cycle's
 *                       prompt by the agent. Defaults to a disabled history, reproducing the
 *                       previous stateless-per-cycle behaviour.
 * @param circuitBreaker Guards against sustained LLM overload — see "Overload handling" above.
 *                       Defaults to a fresh breaker with the class default threshold/cooldown.
 */
class KoogAgentPlanAdapter(
	private val agentFactory: KoogAgentFactory,
	private val context: DefaultSimulationContext,
	private val fallbackDispatcher: Dispatcher,
	private val inferenceTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
	private val commandQueue: ActuatorCommandQueue,
	private val sinkHolder: SinkHolder,
	private val cycleHistory: CycleHistory = CycleHistory(capacity = 0),
	private val circuitBreaker: LlmCircuitBreaker = LlmCircuitBreaker()
) : DispatcherPlanner {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Human-readable name identifying this planner. */
		const val PLANNER_NAME = "KoogAgent+RuleBasedFallback"

		/** Default inference timeout in seconds — matches OllamaExecutorConfig.DEFAULT_INFERENCE_TIMEOUT. */
		const val DEFAULT_TIMEOUT_SECONDS: Long = 30
	}

	/**
	 * Fan-out target for every [PlannerTickListener] registered via [addTickListener] — the sole
	 * reporting seam of this adapter since Issue #713 retired the legacy two-callback
	 * cycle-listener slot.
	 *
	 * A [CompositeTickListener] rather than a single nullable slot: [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]
	 * registers its own attribution listener unconditionally in its `init` block, and a single
	 * slot meant a caller that registered a listener first had it silently discarded (Issue #843).
	 * [addTickListener] lets any number of listeners join without displacing each other —
	 * [MeasuringPlanAdapter] and the driver's attribution listener now share this one seam.
	 */
	private val tickListeners = CompositeTickListener()

	/**
	 * Registers [listener] to be notified after every dispatch cycle with the full [TickOutcome]
	 * taxonomy, alongside any other listener already registered (e.g.
	 * [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]'s own attribution listener).
	 *
	 * Thread-safe: backed by [CompositeTickListener]'s `CopyOnWriteArrayList`.
	 */
	fun addTickListener(listener: PlannerTickListener) {
		tickListeners.addListener(listener)
	}

	/**
	 * One-line circuit-breaker status for end-of-run summaries (#1058 review round) — delegates
	 * to [circuitBreaker.summaryLine]. [MeasuringPlanAdapter.logFinalSummary] logs it next to its
	 * own metrics line, so a run that spent its lifetime skipping the LLM is distinguishable in
	 * the log from one whose LLM answered every cycle.
	 */
	fun circuitBreakerSummaryLine(): String = circuitBreaker.summaryLine()

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
	 * [LlmCircuitBreaker.openCount] at the last skip logged at `warn`. Lets [logBreakerSkip] tell
	 * "still the same open window" (log at `debug`) from "a new open window" (log at `warn`) without
	 * the breaker itself needing to know about logging.
	 */
	@Volatile
	private var lastWarnedOpenCount: Long = 0L

	/** Outcome of one attempt at [KoogDispatchAgent.decideAsync], wrapped for [attemptInference]'s callers. */
	private sealed class InferenceAttempt {
		data class Success(
			val decisions: List<DispatchDecision>,
			val latencyMs: Long
		) : InferenceAttempt()

		data class TimedOut(
			val latencyMs: Long
		) : InferenceAttempt()

		data class Failed(
			val exception: Exception,
			val latencyMs: Long
		) : InferenceAttempt()
	}

	/**
	 * Runs one [KoogDispatchAgent.decideAsync] call under [inferenceTimeout], converting a timeout
	 * or a plain exception into an [InferenceAttempt] rather than throwing — the caller ([plan])
	 * decides whether to retry, fall back, or feed the [circuitBreaker]. A [CancellationException]
	 * that is not a timeout still propagates: it means the parent coroutine was cancelled, not that
	 * this attempt failed.
	 */
	private suspend fun attemptInference(
		agent: KoogDispatchAgent,
		observation: DispatchObservation,
		mark: TimeSource.Monotonic.ValueTimeMark
	): InferenceAttempt =
		try {
			val decisions = withTimeout(inferenceTimeout.toMillis()) { agent.decideAsync(observation) }
			InferenceAttempt.Success(decisions, mark.elapsedNow().inWholeMilliseconds)
		} catch (e: TimeoutCancellationException) {
			InferenceAttempt.TimedOut(mark.elapsedNow().inWholeMilliseconds)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			InferenceAttempt.Failed(e, mark.elapsedNow().inWholeMilliseconds)
		}

	/**
	 * Produces dispatch decisions by consulting the Koog LLM agent, falling back to
	 * [fallbackDispatcher] on an empty result on a non-idle station, on timeout, or on any
	 * exception. An empty result on an *idle* station (no active or queued trains, per
	 * [isIdleStation]) is a correct no-op, not a fallback trigger — see "Fallback priority" above
	 * (Issue #834).
	 *
	 * The fallback is invoked transparently — callers observe valid decisions regardless
	 * of which path (LLM or rule-based) produced them.
	 *
	 * ## Latency measurement (Issue #834, SP2c.11)
	 *
	 * Every listener registered via [addTickListener] receives a latency figure via
	 * [TickRecord.latencyMs], measured with a monotonic clock ([TimeSource.Monotonic]) around the
	 * `withTimeout { a.decideAsync(observation) }` call only — deliberately **not** the whole
	 * `plan()` attempt. Including [getOrCreateAgent] would fold the one-time
	 * `OllamaModelPrewarmer.warmUp` cost into the very first cycle's sample, making it a durable
	 * outlier that skews every run's p95. The mark is taken right before `withTimeout` starts (the
	 * cheap `commandQueue`/`sinkHolder` bookkeeping calls before it are negligible next to an LLM
	 * round-trip) so the measured window is, as closely as this call boundary allows, the
	 * inference itself.
	 *
	 * Every cycle ending that reached the measured window reports a latency computed from that same
	 * mark:
	 * - **success** / **idle no-op**: elapsed time once `withTimeout` returns.
	 * - **timeout** (including one after a partial emission): elapsed time at the moment
	 *   [TimeoutCancellationException] is caught — this is not a missing measurement, it IS the
	 *   deadline, and is exactly as real and reportable as any other cycle's latency.
	 * - **exception** (including one after a partial emission): elapsed time at the moment the
	 *   exception is caught, when it was thrown from inside the measured window (the common case —
	 *   a network or parsing failure during `decideAsync`).
	 *
	 * The two endings that never reach the window — a [getOrCreateAgent] failure and a
	 * circuit-breaker skip (Issue #1058) — report `null` for [TickRecord.latencyMs]: no mark
	 * exists because inference was never attempted, honestly reporting "no cycle latency exists"
	 * rather than inventing a number for work that was never attempted.
	 *
	 * @param observation Read-only snapshot of the current railway network state.
	 * @return Non-null list of decisions; may be the rule-based fallback result.
	 */
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
		// Zero the per-cycle emission counter at the start of EVERY cycle — LLM attempt, breaker
		// skip, or agent-creation failure — so actedThisCycle()/emittedActionsThisCycle() reflect
		// only this cycle (#1058 review round). Without this, a cycle cancelled mid-inference
		// leaves its partial emissions behind and the NEXT cycle's early exits would report the
		// previous cycle's actions as their own.
		sinkHolder.resetCycleEmissionCount()
		// Agent creation gets its own narrow try/catch, deliberately NOT wrapping the rest of this
		// cycle: createAgent runs OllamaModelPrewarmer.warmUp — real network I/O that can fail —
		// and if that call sat outside a try its exception would escape plan() altogether,
		// propagating out of AgentLoopDriver.runCycle() into a daemon thread with no
		// uncaught-exception handler and killing the dispatcher for the rest of the run. A creation
		// failure is an ordinary counted fallback like any other LLM failure, and `agent` stays
		// null so the next cycle retries rather than the whole run being demoted to rule-based by
		// one transient fault.
		//
		// HAZARD (issue #999, narrowed by #1058): this catch also swallows a tick-listener throw if
		// one somehow occurred during getOrCreateAgent — it cannot today (no reportTick call sits
		// inside this try), so the hazard is dormant, unlike before #1058 when this same catch also
		// wrapped every reportTick call for the whole cycle. Widening this try back to cover the
		// rest of the cycle would reintroduce that: a fallback whose own `decide()` throws (see
		// "exception fallback that also throws still records the tick before propagating") would be
		// caught here too and produce a second, spurious fallback attempt.
		val a =
			try {
				getOrCreateAgent()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				return runFallback(
					observation = observation,
					latencyMs = null,
					outcomeFromFallbackOracle = false
				) {
					logger.warn(e) {
						"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
							"(simTime=${observation.snapshot.simTime})"
					}
				}
			}

		if (!circuitBreaker.shouldAttempt(observation.snapshot.simTime)) {
			// Sustained overload (Issue #1058): the breaker is OPEN and the cooldown has not
			// elapsed yet. Skip the LLM call entirely — no withTimeout stall — but still advance
			// the correlation cycle so AgentLoopDriver's postAll and Sp2c21MetricsRecorder's
			// tickIndex stay aligned across consecutive OPEN-window skips (#1073 review round).
			// latencyMs is null: no inference was attempted this cycle, exactly like the
			// getOrCreateAgent-failure case.
			commandQueue.advanceCorrelationCycle()
			return runFallback(
				observation = observation,
				latencyMs = null,
				outcomeFromFallbackOracle = false
			) {
				logBreakerSkip(observation.snapshot.simTime)
			}
		}

		// Advance the correlation-map cycle counter before the LLM cycle so every decision
		// posted by actuator tools during decideAsync receives the correct tick index.
		commandQueue.advanceCorrelationCycle()
		// Latency mark starts here, deliberately after agent creation — see "Latency
		// measurement" above.
		val cycleStart = TimeSource.Monotonic.markNow()

		// Release the HALF_OPEN probe claim if this cycle ends without reaching a breaker verdict
		// (e.g. the parent coroutine is cancelled mid-inference) — a no-op on every normal path,
		// where recordSuccess/recordFailure have already cleared it. Without the release, the
		// breaker would grant no probe ever again while stuck in HALF_OPEN (#1058 review round).
		try {
			var attempt = attemptInference(a, observation, cycleStart)
			if (attempt is InferenceAttempt.Failed && !sinkHolder.actedThisCycle()) {
				// One bounded retry (Issue #1058) on a plain failure only — never on a timeout,
				// which would double the 30s stall the breaker exists to avoid, and never after a
				// partial emission: those actions are already posted, and a retry could
				// double-dispatch a second action for the same train. Only the cycle's final
				// outcome (this retry included) is reported to the circuit breaker below.
				attempt = attemptInference(a, observation, cycleStart)
			}

			return when (val result = attempt) {
				is InferenceAttempt.Success -> {
					circuitBreaker.recordSuccess()
					handleSuccess(result.decisions, result.latencyMs, observation)
				}

				is InferenceAttempt.TimedOut ->
					recordFailureAndFallback(
						latencyMs = result.latencyMs,
						observation = observation,
						exception = null
					) {
						"KoogAgentPlanAdapter: LLM timed out after ${inferenceTimeout.toSeconds()}s — " +
							"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
					}

				is InferenceAttempt.Failed ->
					recordFailureAndFallback(
						latencyMs = result.latencyMs,
						observation = observation,
						exception = result.exception
					) {
						"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
							"(simTime=${observation.snapshot.simTime})"
					}
			}
		} finally {
			circuitBreaker.abandonProbe()
		}
	}

	/**
	 * The success-path branching (acted via tools / idle station / silent-non-idle station) that
	 * used to sit inline in [plan] — extracted so [plan] can reach it after either a first-try or a
	 * retried [InferenceAttempt.Success], both indistinguishable from here on.
	 *
	 * @param latencyMs Elapsed time of whichever attempt succeeded, from [attemptInference]'s mark.
	 */
	private fun handleSuccess(
		decisions: List<DispatchDecision>,
		latencyMs: Long,
		observation: DispatchObservation
	): List<DispatchDecision> =
		if (sinkHolder.actedThisCycle() || decisions.isNotEmpty()) {
			// The LLM acted via its actuator tools (the emissions were already posted to the
			// queue through sinkHolder.current) and/or returned decisions directly. Either way
			// the LLM did its job this cycle — do NOT fall back (would double-dispatch). An
			// empty returned list with tool emissions is the normal, successful outcome:
			// decideAsync always returns empty (see KoogDispatchAgentImpl); the load-bearing
			// signal is the emission counter.
			//
			// The `decisions.isNotEmpty()` disjunct is therefore dead-on-purpose under the
			// current KoogDispatchAgentImpl: decideAsync posts every decision through actuator
			// tools and returns an empty list, so `decisions` is always empty here. It is kept
			// as a defensive guard against a future decideAsync that returns decisions directly
			// (the contract allows it — `plan` returns `List<DispatchDecision>`); if that ever
			// ships, this disjunct is what makes those decisions count instead of silently
			// falling back. Do not reason about it as a live path today.
			//
			// The emitted actions further split LLM_ACTIONS from LLM_NO_OP (Issue #834,
			// required change 2): a cycle whose only tool emission(s) were an explicit no_op
			// is a no-op tick, not an action tick, even though actedThisCycle() is true for
			// both (see SinkHolder's KDoc on why no_op counts as "acted" for the
			// double-dispatch guard). The classifier is shared with the failure path
			// ([outcomeFromEmissions]) so the two cannot drift apart.
			run {
				val outcome = outcomeFromEmissions()
				logger.debug {
					"KoogAgentPlanAdapter: LLM cycle acted via tools " +
						"(emitted=${sinkHolder.actedThisCycle()}, returned=${decisions.size}) " +
						"(simTime=${observation.snapshot.simTime}); not falling back"
				}
				reportTick(outcome, observation.snapshot.simTime, latencyMs)
				decisions
			}
		} else if (isIdleStation(observation)) {
			// The LLM completed a cycle with no decisions and no tool emissions, and the
			// station is idle — no active or queued trains, so there is genuinely nothing to
			// dispatch. This is a correct, healthy outcome (Issue #834), not a failure: report
			// it as LLM_NO_OP and do NOT consult the fallback dispatcher (there is nothing for
			// it to do either, and consulting it would mis-score a correct cycle as a
			// rule-based-fallback run failure — the exact defect #834 reports).
			logger.debug {
				"KoogAgentPlanAdapter: LLM cycle produced no decisions and no tool emissions on " +
					"an idle station (no active or queued trains) — reporting LLM_NO_OP, not " +
					"falling back (simTime=${observation.snapshot.simTime})"
			}
			reportTick(TickOutcome.LLM_NO_OP, observation.snapshot.simTime, latencyMs)
			emptyList()
		} else {
			// The LLM completed a cycle but neither acted via tools nor returned a decision,
			// and the station is NOT idle (there is an active or queued train the LLM left
			// unaddressed). Consult the fallback dispatcher either way — to get real
			// decisions, or to discover there are none (Issue #927): a fallback that itself
			// finds nothing legal to do means this tick was never actionable in the first
			// place, not a genuine dispatch miss. runFallback classifies the reported
			// TickOutcome from the returned decision list — see its KDoc.
			runFallback(observation = observation, latencyMs = latencyMs, outcomeFromFallbackOracle = true) {
				logger.warn {
					"KoogAgentPlanAdapter: LLM cycle produced no decisions and no tool emissions — " +
						"consulting rule-based fallback (simTime=${observation.snapshot.simTime})"
				}
			}
		}

	/**
	 * The shared failure-branch sequence for [plan]'s TimedOut/Failed endings (extracted in the
	 * #1058 review round — the two branches were near-identical and must not drift apart):
	 * record the failure with [circuitBreaker] — a health signal even when the fallback is
	 * suppressed — then either run the rule-based fallback ([warnLog] names which failure
	 * happened), or, when the cycle already emitted actuator actions, keep those posted emissions
	 * as the cycle's result.
	 *
	 * ## Partial emissions suppress the fallback
	 *
	 * A cycle that emitted an actuator action and then failed has already posted that action
	 * through [sinkHolder]'s queue-posting wrapper. Layering the fallback's own independently
	 * decided action on top would double-dispatch for the same train — the same hazard the
	 * success path's guard exists to prevent, extended to the failure paths in the #1058 review
	 * round. The failure still reaches [circuitBreaker] (the LLM is unhealthy regardless), and
	 * the tick is reported through the emission classification ([outcomeFromEmissions]) rather
	 * than [TickOutcome.RULE_FALLBACK]: the LLM did act this cycle.
	 *
	 * @param latencyMs Elapsed time of the failed attempt, from [attemptInference]'s mark — for a
	 *   timeout, the elapsed time IS the deadline (see [plan]'s "Latency measurement" KDoc).
	 * @param observation Read-only snapshot: feeds the fallback and carries the cycle's sim time.
	 * @param exception The failure's exception, or `null` for a timeout — the helper attaches it
	 *   to whichever warn line this cycle logs, so a real failure's stack trace is never lost.
	 * @param fallbackWarnMessage Branch-specific warn message, logged only on the fallback path
	 *   (handed to [runFallback] as its [logAction]). The partial-emission path logs its own
	 *   message instead, because "applying rule-based fallback" would be a lie there.
	 */
	private fun recordFailureAndFallback(
		latencyMs: Long,
		observation: DispatchObservation,
		exception: Throwable?,
		fallbackWarnMessage: () -> String
	): List<DispatchDecision> {
		circuitBreaker.recordFailure(observation.snapshot.simTime)
		if (sinkHolder.actedThisCycle()) {
			logger.warn(exception) {
				"KoogAgentPlanAdapter: LLM cycle failed after a partial emission — keeping the " +
					"emitted actions, suppressing retry and fallback (simTime=${observation.snapshot.simTime})"
			}
			reportTick(outcomeFromEmissions(), observation.snapshot.simTime, latencyMs)
			return emptyList()
		}
		return runFallback(
			observation = observation,
			latencyMs = latencyMs,
			outcomeFromFallbackOracle = false,
			logAction = { logger.warn(exception) { fallbackWarnMessage() } }
		)
	}

	/**
	 * Classifies an acting cycle from its emissions: [TickOutcome.LLM_ACTIONS], or
	 * [TickOutcome.LLM_NO_OP] when every emission this cycle was an explicit `no_op` (Issue #834,
	 * required change 2). Shared by the success path ([handleSuccess]) and the failure path
	 * ([recordFailureAndFallback]) since the #1058 review round, so the two classify identically.
	 *
	 * An empty emission list classifies as [TickOutcome.LLM_ACTIONS]: [handleSuccess] also calls
	 * this for a cycle that returned decisions directly (the defensive `decisions.isNotEmpty()`
	 * disjunct), where "no no_op among zero emissions" means the actions came back as the return
	 * value — the action case.
	 */
	private fun outcomeFromEmissions(): TickOutcome {
		val emitted = sinkHolder.emittedActionsThisCycle()
		return if (emitted.isNotEmpty() && emitted.all { it is DispatchAction.NoOp }) {
			TickOutcome.LLM_NO_OP
		} else {
			TickOutcome.LLM_ACTIONS
		}
	}

	/**
	 * Logs one circuit-breaker skip (Issue #1058): `warn` once per open window (tracked via
	 * [LlmCircuitBreaker.openCount] against [lastWarnedOpenCount]), `debug` for every further skip
	 * in that same window — so a sustained outage never floods the log with one line per tick.
	 */
	private fun logBreakerSkip(simTime: Double) {
		val openCount = circuitBreaker.openCount
		if (openCount != lastWarnedOpenCount) {
			lastWarnedOpenCount = openCount
			logger.warn {
				"KoogAgentPlanAdapter: circuit breaker OPEN after repeated LLM failures — skipping " +
					"the LLM call and using the rule-based fallback until it probes recovery " +
					"(simTime=$simTime, consecutiveFailures=${circuitBreaker.consecutiveFailures})"
			}
		} else {
			logger.debug {
				"KoogAgentPlanAdapter: circuit breaker still OPEN — skipping the LLM call " +
					"(simTime=$simTime)"
			}
		}
	}

	/**
	 * Runs the shared rule-based-fallback sequence: log via [logAction], consult
	 * [fallbackDispatcher], then report the [TickOutcome] the consultation earned.
	 * Shared by every fallback site in this adapter — agent-creation failure, circuit-breaker
	 * skip (Issue #1058), inference timeout and LLM exception in [plan], plus the empty LLM cycle
	 * on a non-idle station in [handleSuccess] — so they cannot drift out of sync with each other.
	 *
	 * ## Outcome classification (Issue #927)
	 *
	 * For [outcomeFromFallbackOracle] == `true` (the LLM answered silently on a non-idle
	 * station), the reported outcome depends on what [fallbackDispatcher] actually found:
	 * - `decide()` returns at least one **actionable** decision (not just
	 *   [DispatchDecision.NoAction]) → [TickOutcome.RULE_FALLBACK] — a genuine miss, the fallback
	 *   actually dispatches something the LLM should have caught.
	 * - `decide()` returns **only [DispatchDecision.NoAction]** →
	 *   [TickOutcome.LLM_SILENT_NONACTIONABLE] — the fallback oracle confirms the tick was never
	 *   actionable in the first place.
	 *
	 * Note: the [Dispatcher.decide] contract guarantees the returned list is never empty
	 * (implementations return `listOf(NoAction)` when nothing is actionable), so the split is on
	 * whether the decisions contain anything actionable — not on list emptiness. An `isEmpty()`
	 * check would be dead code against any contract-compliant dispatcher and would let the
	 * non-actionable classification never fire in production.
	 *
	 * For [outcomeFromFallbackOracle] == `false` (inference timeout, LLM exception) the LLM path
	 * itself failed — whatever [fallbackDispatcher] returns is always reported as [TickOutcome.RULE_FALLBACK],
	 * unchanged from before #927: a timed-out or exception-throwing cycle is a genuine LLM-side
	 * failure regardless of how many decisions the fallback happens to find.
	 *
	 * ## Tick-accounting ordering
	 *
	 * When [outcomeFromFallbackOracle] is `false` the tick is reported BEFORE
	 * [fallbackDispatcher.decide] is called, so a throwing fallback cannot drop the cycle from
	 * tick accounting (the pre-#927 ordering). When it is `true` the tick is reported AFTER
	 * `decide()` returns, because the outcome depends on the oracle's result; if `decide()` throws
	 * there, the catch below reports a degraded `RULE_FALLBACK` before the exception propagates
	 * (#1058 review round — the pre-#1058 restructure of [plan] left this throw with no handler
	 * at all, silently dropping the cycle from accounting). Either way the cycle is accounted for
	 * exactly once, never silently dropped and (unlike the pre-#713 two-callback listener path)
	 * never double-counted. A [CancellationException] is re-thrown without reporting: a cancelled
	 * run is ending, and a cancellation is not a fallback failure.
	 *
	 * @param latencyMs Cycle latency to report alongside the tick, or `null` if no meaningful
	 *   inference attempt was measured for this cycle — see [plan]'s "Latency measurement" KDoc.
	 * @param outcomeFromFallbackOracle `true` to let [fallbackDispatcher]'s result select the
	 *   reported outcome — the silent-cycle case, where the dispatcher is consulted as an oracle
	 *   and finding nothing actionable means [TickOutcome.LLM_SILENT_NONACTIONABLE]. `false` to
	 *   report [TickOutcome.RULE_FALLBACK] unconditionally, because the LLM path itself failed.
	 */
	private fun runFallback(
		observation: DispatchObservation,
		latencyMs: Long?,
		outcomeFromFallbackOracle: Boolean,
		logAction: () -> Unit
	): List<DispatchDecision> {
		logAction()
		// The oracle does not select the outcome (timeout/exception): always RULE_FALLBACK. Report
		// the tick BEFORE consulting the fallback so a throwing fallback cannot drop it from
		// accounting.
		if (!outcomeFromFallbackOracle) {
			reportTick(TickOutcome.RULE_FALLBACK, observation.snapshot.simTime, latencyMs)
			return fallbackDispatcher.decide(observation)
		}
		// Oracle-selected: the outcome depends on what the fallback oracle finds, so decide()
		// must run before the tick is reported. If decide() throws, report a degraded
		// RULE_FALLBACK before propagating — the cycle is still accounted for exactly once,
		// never silently dropped (#1058 review round restores the #927/#999 invariant).
		val decisions =
			try {
				fallbackDispatcher.decide(observation)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				reportTick(TickOutcome.RULE_FALLBACK, observation.snapshot.simTime, latencyMs)
				throw e
			}
		// The Dispatcher contract guarantees decide() never returns empty (it returns
		// listOf(NoAction) when nothing is actionable), so classify on whether the fallback
		// found anything genuinely actionable, not on list emptiness.
		val nothingActionable = decisions.all { it is DispatchDecision.NoAction }
		val outcome =
			if (nothingActionable) {
				TickOutcome.LLM_SILENT_NONACTIONABLE
			} else {
				TickOutcome.RULE_FALLBACK
			}
		reportTick(outcome, observation.snapshot.simTime, latencyMs)
		return decisions
	}

	/**
	 * `true` when [observation] describes an idle station: no approved (active) trains and no
	 * unapproved (queued) trains — there is genuinely nothing for a dispatcher to do this cycle.
	 *
	 * Deliberately narrow (Issue #834): defined *only* as
	 * `approvedTrainCount == 0 && unapprovedTrains.isEmpty()`, not the wider "no action was
	 * applicable" (e.g. every queued train blocked, every reservation already extended). The
	 * wider variant was considered and rejected during planning — it would fold genuine LLM
	 * failures on a busy station into the same success bucket as this narrow, unambiguous case.
	 *
	 * **Guards against [SimulationSnapshot.EMPTY]** — the pre-first-capture sentinel returned by
	 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.snapshot] before the first on-thread
	 * `captureSnapshot()` call. It carries no train positions and therefore looks idle by the
	 * predicate above without being a real idle tick, so a cycle observing it must keep the
	 * pre-#834 fallback behaviour. Checked by reference identity (`!==`) against the singleton,
	 * which is both the cheapest possible check and the only one that cannot misclassify a
	 * genuinely idle *real* snapshot (structural equality would also match a real snapshot whose
	 * fields all happen to equal [SimulationSnapshot.EMPTY]'s defaults, e.g. `simTime == 0.0`
	 * with zero trains at the very start of a run).
	 */
	private fun isIdleStation(observation: DispatchObservation): Boolean =
		observation.snapshot !== SimulationSnapshot.EMPTY &&
			observation.approvedTrainCount == 0 &&
			observation.unapprovedTrains.isEmpty()

	/**
	 * Publishes one completed cycle to the tick listener and to [cycleHistory].
	 *
	 * A single funnel rather than a call pair at each cycle ending: the history and
	 * the tick taxonomy must never disagree about how a cycle ended, and they cannot drift if
	 * there is only one place that reports both.
	 *
	 * The recorded actions are read from [sinkHolder], so they are what the **agent** emitted.
	 * On a `RULE_FALLBACK` cycle that list is normally empty even though the fallback dispatcher
	 * did act — deliberately: [cycleHistory] is the model's memory of its own behaviour, and the
	 * outcome name already tells it the cycle was taken over.
	 *
	 * @param latencyMs Cycle latency measured by [plan] (see its "Latency measurement" KDoc), or
	 *   `null` when this cycle never reached the measured window. Forwarded to
	 *   [TickRecord.latencyMs]; not part of [cycleHistory] (the model's own memory does not need
	 *   its own timing).
	 */
	private fun reportTick(
		outcome: TickOutcome,
		simTime: Double,
		latencyMs: Long?
	) {
		tickListeners.onTick(TickRecord(outcome, simTime, latencyMs = latencyMs))
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
