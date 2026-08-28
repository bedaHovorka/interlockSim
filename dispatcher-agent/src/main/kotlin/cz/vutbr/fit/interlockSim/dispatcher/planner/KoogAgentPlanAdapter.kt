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
	 * Fan-out target for every [PlannerTickListener] registered via [addTickListener] — the
	 * non-deprecated replacement for [cycleListener].
	 *
	 * A [CompositeTickListener] rather than a single nullable slot: [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]
	 * registers its own attribution listener unconditionally in its `init` block, and a single
	 * slot meant a caller that registered a listener first had it silently discarded (Issue #843).
	 * [addTickListener] lets any number of listeners join without displacing each other.
	 *
	 * Independent of [cycleListener]: registering here does not disturb [MeasuringPlanAdapter]'s
	 * claim on [cycleListener], and both fire for the same cycle without interfering.
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
	 * [cycleListener] and every listener registered via [addTickListener] receive a latency figure via
	 * [TickRecord.latencyMs], measured with a monotonic clock ([TimeSource.Monotonic]) around the
	 * `withTimeout { a.decideAsync(observation) }` call only — deliberately **not** the whole
	 * `plan()` attempt. Including [getOrCreateAgent] would fold the one-time
	 * `OllamaModelPrewarmer.warmUp` cost into the very first cycle's sample, making it a durable
	 * outlier that skews every run's p95. The mark is taken right before `withTimeout` starts (the
	 * cheap `commandQueue`/`sinkHolder` bookkeeping calls before it are negligible next to an LLM
	 * round-trip) so the measured window is, as closely as this call boundary allows, the
	 * inference itself.
	 *
	 * All four cycle endings report a latency computed from that same mark:
	 * - **success** / **idle no-op**: elapsed time once `withTimeout` returns.
	 * - **timeout**: elapsed time at the moment [TimeoutCancellationException] is caught — this is
	 *   not a missing measurement, it IS the deadline, and is exactly as real and reportable as
	 *   any other cycle's latency.
	 * - **exception**: elapsed time at the moment the exception is caught, when it was thrown
	 *   from inside the measured window (the common case — a network or parsing failure during
	 *   `decideAsync`). The one exception to "always non-null" is a [getOrCreateAgent] failure: no
	 *   mark exists yet because inference never started, so [TickRecord.latencyMs] is `null` for
	 *   that specific sub-case — honestly reporting "no cycle latency exists" rather than
	 *   inventing a number for work that was never attempted.
	 *
	 * @param observation Read-only snapshot of the current railway network state.
	 * @return Non-null list of decisions; may be the rule-based fallback result.
	 */
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
		var cycleStart: TimeSource.Monotonic.ValueTimeMark? = null
		return try {
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
			// Latency mark starts here, deliberately after agent creation — see "Latency
			// measurement" above.
			cycleStart = TimeSource.Monotonic.markNow()
			val decisions =
				withTimeout(inferenceTimeout.toMillis()) {
					a.decideAsync(observation)
				}
			val latencyMs = cycleStart.elapsedNow().inWholeMilliseconds
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
				// double-dispatch guard).
				val emittedThisCycle = sinkHolder.emittedActionsThisCycle()
				val outcome =
					if (emittedThisCycle.isNotEmpty() && emittedThisCycle.all { it is DispatchAction.NoOp }) {
						TickOutcome.LLM_NO_OP
					} else {
						TickOutcome.LLM_ACTIONS
					}
				logger.debug {
					"KoogAgentPlanAdapter: LLM cycle acted via tools " +
						"(emitted=${sinkHolder.actedThisCycle()}, returned=${decisions.size}) " +
						"(simTime=${observation.snapshot.simTime}); not falling back"
				}
				cycleListener?.onLlmSuccess(observation.snapshot.simTime)
				reportTick(outcome, observation.snapshot.simTime, latencyMs)
				decisions
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
				cycleListener?.onLlmSuccess(observation.snapshot.simTime)
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
				runFallback(FallbackReason.EMPTY_NO_TOOLS, observation, latencyMs) {
					logger.warn {
						"KoogAgentPlanAdapter: LLM cycle produced no decisions and no tool emissions — " +
							"consulting rule-based fallback (simTime=${observation.snapshot.simTime})"
					}
				}
			}
		} catch (e: TimeoutCancellationException) {
			// cycleStart is always set here: TimeoutCancellationException can only originate from
			// inside the withTimeout block, which starts after the mark is taken. The elapsed time
			// is the deadline itself — a real, reportable latency, not a missing one.
			runFallback(FallbackReason.TIMEOUT, observation, cycleStart?.elapsedNow()?.inWholeMilliseconds) {
				logger.warn {
					"KoogAgentPlanAdapter: LLM timed out after ${inferenceTimeout.toSeconds()}s — " +
						"applying rule-based fallback (simTime=${observation.snapshot.simTime})"
				}
			}
		} catch (e: CancellationException) {
			// Parent coroutine was cancelled — propagate rather than swallow.
			throw e
		} catch (e: Exception) {
			// cycleStart is null only if getOrCreateAgent() itself threw — inference never
			// started, so there is no cycle latency to report (null, not a fabricated 0).
			runFallback(FallbackReason.EXCEPTION, observation, cycleStart?.elapsedNow()?.inWholeMilliseconds) {
				logger.warn(e) {
					"KoogAgentPlanAdapter: LLM call failed — applying rule-based fallback " +
						"(simTime=${observation.snapshot.simTime})"
				}
			}
		}
	}

	/**
	 * Runs the shared rule-based-fallback sequence: log via [logAction], notify [cycleListener],
	 * consult [fallbackDispatcher], then report the [TickOutcome] the consultation earned.
	 * Shared by all three fallback sites in [plan] (empty LLM cycle on a non-idle station — see
	 * [isIdleStation], inference timeout, LLM exception) so they cannot drift out of sync with
	 * each other.
	 *
	 * ## Outcome classification (Issue #927)
	 *
	 * For [reason] == [FallbackReason.EMPTY_NO_TOOLS] (the LLM answered silently on a non-idle
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
	 * For the other two [reason] values (`TIMEOUT`, `EXCEPTION`) the LLM path itself failed —
	 * whatever [fallbackDispatcher] returns is always reported as [TickOutcome.RULE_FALLBACK],
	 * unchanged from before #927: a timed-out or exception-throwing cycle is a genuine LLM-side
	 * failure regardless of how many decisions the fallback happens to find.
	 *
	 * ## Tick-accounting ordering
	 *
	 * For `TIMEOUT`/`EXCEPTION` the tick is reported BEFORE [fallbackDispatcher.decide] is
	 * called, so a throwing fallback cannot drop the cycle from tick accounting (the pre-#927
	 * ordering). For `EMPTY_NO_TOOLS` the tick is reported AFTER `decide()` returns, because the
	 * outcome depends on the oracle's result; if `decide()` throws there, the exception escapes
	 * to [plan]'s `EXCEPTION` handler, which reports `RULE_FALLBACK` via this method — so the
	 * cycle is still accounted for, never silently dropped.
	 *
	 * @param latencyMs Cycle latency to report alongside the tick, or `null` if no meaningful
	 *   inference attempt was measured for this cycle — see [plan]'s "Latency measurement" KDoc.
	 */
	private fun runFallback(
		reason: FallbackReason,
		observation: DispatchObservation,
		latencyMs: Long?,
		logAction: () -> Unit
	): List<DispatchDecision> {
		logAction()
		cycleListener?.onFallback(reason, observation.snapshot.simTime)
		// TIMEOUT/EXCEPTION: always RULE_FALLBACK. Report the tick BEFORE consulting the
		// fallback so a throwing fallback dispatcher cannot drop this cycle from accounting.
		if (reason != FallbackReason.EMPTY_NO_TOOLS) {
			reportTick(TickOutcome.RULE_FALLBACK, observation.snapshot.simTime, latencyMs)
			return fallbackDispatcher.decide(observation)
		}
		// EMPTY_NO_TOOLS: the outcome depends on what the fallback oracle finds, so decide()
		// must run before the tick is reported. If decide() throws, the exception escapes to
		// plan()'s EXCEPTION handler, which reports RULE_FALLBACK above — the cycle is still
		// accounted for (as a degraded RULE_FALLBACK), never silently dropped.
		val decisions = fallbackDispatcher.decide(observation)
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
	 * A single funnel rather than a call pair at each of the four cycle endings: the history and
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
