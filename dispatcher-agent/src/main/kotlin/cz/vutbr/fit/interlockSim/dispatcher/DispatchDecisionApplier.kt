/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.ports.TrainLifecyclePort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListener
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.applyToolDrivenToActuator
import cz.vutbr.fit.interlockSim.sim.toRationaleLogSuffix
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Sim-thread applier that drains [ActuatorCommandQueue] at each simulation control
 * step and applies every [DispatchDecision] through the actuator ports.
 *
 * Registered as a [ControlStepListener] on [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop]
 * before the simulation starts.  [onControlStep] is called synchronously by
 * [ShuntingLoop.iteration][cz.vutbr.fit.interlockSim.sim.ShuntingLoop] at the
 * beginning of every control step, which is always on the single kDisco simulation
 * thread.  All simulation-state mutation therefore originates on the sim thread
 * regardless of which thread posted the decisions into the queue.
 *
 * ## Decision routing
 *
 * | Decision | Applied via |
 * |---|---|
 * | [DispatchDecision.ApproveTrain] | [onApproveTrain] callback (into [ShuntingLoop.approveQueuedTrain][cz.vutbr.fit.interlockSim.sim.ShuntingLoop.approveQueuedTrain]) |
 * | [DispatchDecision.ReservePath] | [NetworkActuatorPort.requestRoute] |
 * | [DispatchDecision.HoldTrain] | [TrainLifecyclePort.holdTrain] (no-op + warning if port is null) |
 * | [DispatchDecision.NoAction] | no-op |
 *
 * Decisions are applied in FIFO order matching the posting order on the driver thread.
 *
 * ## Mode gating
 *
 * When a [DispatcherModeState] is wired (default: `null`, gate disabled) every drained
 * decision is filtered through the effective [DispatcherMode] before it reaches the
 * actuator ports:
 *
 * - [DispatcherMode.AUTO] — apply directly.
 * - [DispatcherMode.SEMI_AUTO] — forward to the wired approver callback and apply
 *   only if it returns `true`; if no approver is wired, drop with a warning.
 * - [DispatcherMode.MANUAL] — drop every actuating decision (a debug line is logged).
 *   [DispatchDecision.NoAction] is unaffected — it is a no-op regardless of mode.
 *
 * ## Wiring (before `context.run()`)
 *
 * ```kotlin
 * val queue          = ActuatorCommandQueue()
 * val networkActuator = DefaultNetworkActuatorPort(context)
 * val loop           = ShuntingLoop(context, endTime)
 * val applier        = DispatchDecisionApplier(queue, networkActuator, loop::approveQueuedTrain)
 * loop.controlStepListener = applier
 * context.run()
 * ```
 *
 * To enable [DispatchDecision.HoldTrain] support, supply a [TrainLifecyclePort]:
 * ```kotlin
 * val trainLifecycle = DefaultTrainLifecyclePort(context)
 * val applier = DispatchDecisionApplier(queue, networkActuator, loop::approveQueuedTrain,
 *     trainLifecyclePort = trainLifecycle)
 * ```
 *
 * @param queue             The command queue to drain on each control step.
 * @param networkActuator   Network actuator port used to apply path-reservation commands.
 * @param onApproveTrain    Callback invoked (on the sim thread) to admit a queued train.
 *   Typically [ShuntingLoop.approveQueuedTrain][cz.vutbr.fit.interlockSim.sim.ShuntingLoop.approveQueuedTrain].
 *
 *   Kept as a `(String) -> Unit` callback for backward compatibility; `HoldTrain` is routed
 *   through [trainLifecyclePort] instead, the dedicated port symmetric with [NetworkActuatorPort].
 * @param activeTrainCountProvider Optional callback (on the sim thread) that returns the
 *   **live** number of currently approved trains. When non-null, the applier checks
 *   `activeTrainCountProvider() >= maxConcurrentTrains` before invoking [onApproveTrain]:
 *   if the cap is already reached, [onApproveTrain] is **not** called and an
 *   [AppliedOutcome.Approved] with `admitted = false` and reason [ApplyFailureCode.CAP_EXCEEDED_APPLY]
 *   is published through [outcomeSink]. When `null` (the default), no apply-time cap check
 *   is performed — existing callers that rely on the inner [ShuntingLoop.approveQueuedTrain]
 *   guard are unaffected. The provider **must not throw** — an exception escapes into
 *   [onControlStep] and crashes the kDisco simulation thread (the production provider
 *   `{ loop.getApprovedTrains().size }` is safe).
 * @param maxConcurrentTrains Maximum concurrent trains allowed; only consulted when
 *   [activeTrainCountProvider] is non-null. Defaults to
 *   [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS], matching the constant used by
 *   [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]'s own admission gate and
 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ApproveTrainTool]'s pre-queue check.
 * @param trainLifecyclePort Train lifecycle actuator port for [DispatchDecision.HoldTrain]
 *   commands. `null` by default for backward compatibility with callers that do not need
 *   train-lifecycle commands; when `null` and a [DispatchDecision.HoldTrain] is received, a
 *   warning is logged and the decision is dropped.
 */
class DispatchDecisionApplier(
	private val queue: ActuatorCommandQueue,
	private val networkActuator: NetworkActuatorPort,
	private val onApproveTrain: (trainId: String) -> Unit,
	/**
	 * Optional provider for the live active-train count on the sim thread. When non-null,
	 * the cap check [maxConcurrentTrains] is enforced at apply time.
	 */
	private val activeTrainCountProvider: (() -> Int)? = null,
	/**
	 * Maximum concurrent trains allowed (only consulted when [activeTrainCountProvider]
	 * is non-null).
	 */
	private val maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
	/**
	 * Callback invoked on the sim thread when a path reservation succeeds. Increments
	 * [ShuntingLoop.incrementBlockTransition].
	 */
	private val onBlockTransition: (trainId: String) -> Unit = {},
	/**
	 * Callback invoked on the sim thread when a path reservation fails (any of
	 * AllPathsBlocked, Conflict, or NoRouteExists). Increments
	 * [ShuntingLoop.incrementFailedReservation].
	 */
	private val onFailedReservation: () -> Unit = {},
	/**
	 * Train lifecycle actuator port for [DispatchDecision.HoldTrain] commands. Null by
	 * default for backward compatibility with callers that do not supply one.
	 */
	private val trainLifecyclePort: TrainLifecyclePort? = null,
	/**
	 * Dispatcher operating-mode state that gates decision application.
	 *
	 * When `null` (the default), the applier behaves as if the mode gate did not exist —
	 * every drained decision is applied.  When non-null, the effective mode
	 * ([DispatcherModeState.getEffectiveMode]) is consulted on every drained
	 * decision and controls whether it is applied, forwarded to [semiAutoApprover] for
	 * human approval, or dropped:
	 *
	 * | Effective mode | Behaviour |
	 * |---|---|
	 * | [DispatcherMode.AUTO] | Apply directly. |
	 * | [DispatcherMode.SEMI_AUTO] | Forward to [semiAutoApprover]; apply only if it returns `true`. If no approver is wired, the decision is dropped with a warning. |
	 * | [DispatcherMode.MANUAL] | Drop the decision without invoking the actuator port; a debug line is logged. [DispatchDecision.NoAction] passes through as a no-op regardless of mode. |
	 *
	 * The mode is read once per decision via [DispatcherModeState.getEffectiveMode], so
	 * a mode change becomes effective on the next drained decision.
	 */
	private val modeState: DispatcherModeState? = null,
	/**
	 * Approver callback consulted when the effective [DispatcherMode] is
	 * [DispatcherMode.SEMI_AUTO].
	 *
	 * Called on the sim thread (same thread as [onControlStep]) with the pending
	 * decision; must return `true` to apply the decision or `false` to drop it.  Wired to
	 * the Goal 9 `ConflictResolutionPanel` propose-approve flow; `null` in headless
	 * contexts, in which case every actuating decision is dropped with a warning while in
	 * SEMI_AUTO mode.
	 *
	 * Implementations must be non-blocking (or at least short-lived) — the callback
	 * runs inline on the kDisco simulation thread and stalling it stalls the whole
	 * simulation.
	 */
	private val semiAutoApprover: ((DispatchDecision) -> Boolean)? = null,
	/**
	 * Optional observer notified (on the kDisco simulation thread) for each applied,
	 * non-[DispatchDecision.NoAction] decision.
	 *
	 * When `null` (the default) the applier does not surface applied decisions to any
	 * listener. When non-null (e.g. a
	 * [cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub] pulled from the
	 * per-context Koin scope), every gated, non-`NoAction` decision is forwarded
	 * so a GUI ("Why this route?" panel) can display its rationale.
	 *
	 * Dropped decisions (under [DispatcherMode.MANUAL]), [DispatchDecision.NoAction], and
	 * apply-time refusals (an [DispatchDecision.ApproveTrain] refused with
	 * [ApplyFailureCode.CAP_EXCEEDED_APPLY] — see [applyApproveTrain]) do not trigger the listener:
	 * it fires only for decisions that were actually applied.
	 *
	 * @see cz.vutbr.fit.interlockSim.sim.DispatchDecisionListener
	 */
	private val onDecisionApplied: DispatchDecisionListener? = null,
	/**
	 * Identity-keyed side map used to correlate each drained [DispatchDecision]
	 * back to the [CommandId] and tick index assigned at post time.
	 *
	 * When `null` (the default) no correlation is performed and [outcomeSink] is never
	 * called — existing callers that do not need the feedback channel are unaffected.
	 */
	private val correlationMap: CommandCorrelationMap? = null,
	/**
	 * Sink that receives an [AppliedOutcome] for each decision applied on the sim
	 * thread. Populated outcomes are drained by [DispatcherObservationProjector] and included
	 * in the next tick's pushed observation so the agent learns what actually happened.
	 *
	 * Implementations **must** be allocation-light and **must not** call any `logger.*`
	 * method — a log call on the sim thread injects latency into the physics loop.
	 *
	 * When `null` (the default) no outcomes are published — existing callers that do not
	 * need the feedback channel are unaffected.
	 */
	private val outcomeSink: AppliedOutcomeSink? = null,
	/**
	 * Optional sink notified (on the kDisco simulation thread) for each applied
	 * or apply-failed [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction] with full
	 * author attribution.
	 *
	 * **Must not log** — see [ActionOutcomeSink] contract. A log call on the sim thread
	 * injects latency into the physics loop.
	 *
	 * When `null` (the default) no action outcomes are published — existing callers that
	 * do not need attribution at apply granularity are unaffected.
	 */
	private val actionOutcomeSink: ActionOutcomeSink? = null
) : ControlStepListener {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Fails fast at wiring time when the apply-time cap is misconfigured. With a non-null
	 * [activeTrainCountProvider], a `maxConcurrentTrains <= 0` would silently refuse every
	 * `approve_train` (live count `>= 0` is always true) with only a debug log — mirroring
	 * [RuleBasedDispatcher]'s own `require(maxConcurrentTrains > 0)` guard.
	 */
	init {
		if (activeTrainCountProvider != null) {
			require(maxConcurrentTrains > 0) {
				"maxConcurrentTrains must be > 0 when activeTrainCountProvider is wired, but was $maxConcurrentTrains"
			}
		}
	}

	/**
	 * Key for one train's hop/endpoint-pair reservation, used by [appliedReservations] and
	 * [appliedRequestRoutes] to guard against re-applying an in-flight duplicate.
	 *
	 * A structured key (rather than a manually delimited `"$trainId|$from|$to"` string) is
	 * used for two reasons:
	 *
	 * 1. **Correctness for [evictReservationsFor]**: that method needs to remove every entry
	 *    belonging to one `trainId` without touching entries for any other train. A prefix
	 *    scan over a joined string (`startsWith("$trainId|")`) is unsound for this: evicting
	 *    `trainId = "T1"` would also wrongly evict entries for a distinct train literally
	 *    named `"T1|zB"`, since its joined key also starts with `"T1|"`. Exact structural
	 *    equality on the three fields (`it.trainId == trainId`) has no such false-positive.
	 * 2. **Defense-in-depth**: [from]/[to] are today always validated network element names
	 *    (`[a-zA-Z0-9_-]{1,50}` per `data.xsd`, and pre-checked against `validEndpointNames`
	 *    for the LLM tool path — see `RequestRouteTool`), so they can never contain `|` and a
	 *    delimiter-joined string key would in practice be unambiguous. [trainId], however, is
	 *    externally supplied and not similarly constrained. Should endpoint-name validation
	 *    ever be relaxed, a string-concatenation key could silently become ambiguous (e.g.
	 *    `trainId="T1|zB", from="doA1"` vs. `trainId="T1", from="zB|doA1"` — see
	 *    `delimiterStraddlingTriples_notConflated` in `DispatchDecisionApplierTest`, which
	 *    exercises exactly that shape). The structured key removes the dependency on that
	 *    upstream invariant entirely, rather than relying on it never changing.
	 */
	private data class ReservationKey(
		val trainId: String,
		val from: String,
		val to: String
	)

	/**
	 * Reservation keys already successfully applied via [applyReservePath], for the
	 * duplicate-suppression guard documented on that function.
	 *
	 * Entries are evicted via [evictReservationsFor] once the wiring layer observes a
	 * block release for that train (see [evictReservationsFor] KDoc) — this set must
	 * NOT be permanent: interlockSim supports bidirectional train operation (a train
	 * can reverse and later legitimately need to reserve an identical hop it already
	 * traversed once), which would otherwise collide with an entry left over from the
	 * first pass and silently stall the train.
	 */
	private val appliedReservations: MutableSet<ReservationKey> = mutableSetOf()

	/**
	 * Request-route keys already successfully applied via [applyRequestRoute], for the
	 * duplicate-suppression guard symmetric to [appliedReservations] (see
	 * [applyRequestRoute] KDoc).
	 *
	 * Evicted together with [appliedReservations] by [evictReservationsFor] — the same
	 * bidirectional-operation rationale applies: a train that reverses and re-approaches
	 * an endpoint pair it already traversed must not stay suppressed.
	 *
	 * This is an anchor for a future `ActionValidator` relaxation; see [applyRequestRoute]
	 * for why this guard is dormant today but load-bearing once the validator conflict
	 * rule permits forward extensions.
	 */
	private val appliedRequestRoutes: MutableSet<ReservationKey> = mutableSetOf()

	/**
	 * Evicts every [appliedReservations] entry recorded for [trainId].
	 *
	 * ## Why eviction is needed
	 *
	 * [appliedReservations] exists to suppress a *duplicate* [DispatchDecision.ReservePath]
	 * decided twice before its first application is reflected back (see [applyReservePath]).
	 * That in-flight race is only possible while the reservation is still active. Once ANY
	 * block release happens for [trainId] (e.g. after a reversal re-approaches a hop it
	 * already traversed once, or simply as it completes its journey and exits), a later
	 * identical [DispatchDecision.ReservePath] is by construction a fresh request, not a
	 * stale duplicate in flight — so it must not stay suppressed.
	 *
	 * This is intentionally coarse (it clears every recorded hop for [trainId], not just the
	 * one whose block was released): the dedup guard is a best-effort in-flight-race filter,
	 * not an ownership ledger, and re-issuing an already-owned reservation is already a
	 * harmless no-op at the [NetworkActuatorPort]/registry level. Callers should invoke this
	 * from a [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener] subscribed to
	 * [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType.BLOCK_RELEASED] events.
	 *
	 * Clears both [appliedReservations] (per-hop `ReservePath`) and [appliedRequestRoutes]
	 * (destination-level `RequestRoute`) so a single block-release event re-enables either
	 * vocabulary's dedup guard for that train.
	 */
	fun evictReservationsFor(trainId: String) {
		appliedReservations.removeAll { it.trainId == trainId }
		appliedRequestRoutes.removeAll { it.trainId == trainId }
	}

	/**
	 * Result of applying one [DispatchDecision] — decouples two independent signals that used
	 * to be conflated into a single `Boolean`.
	 *
	 * @property decisionApplied Whether [onDecisionApplied] should fire for this decision.
	 *   `false` only for [DispatchDecision.NoAction] and an [DispatchDecision.ApproveTrain]
	 *   refused at apply time ([ApplyFailureCode.CAP_EXCEEDED_APPLY]).
	 * @property applyFailure The [ApplyFailureCode] to report to [actionOutcomeSink], or `null`
	 *   on success.
	 */
	private data class ApplyResult(
		val decisionApplied: Boolean,
		val applyFailure: ApplyFailureCode? = null
	)

	/**
	 * Drains [queue] and applies each pending [DispatchDecision] via the actuator ports.
	 *
	 * Always called on the kDisco simulation thread from
	 * [ShuntingLoop.iteration][cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
	 * All simulation-state mutations performed here are serialised with all other
	 * sim-thread operations by kDisco's single-threaded scheduler.
	 *
	 * ## Per-decision exception isolation
	 *
	 * Tool-driven decisions ([DispatchDecision.RequestRoute] etc.) can carry
	 * LLM-hallucinated string arguments (an endpoint name that doesn't exist in this
	 * network). [NetworkActuatorPort.requestRoute]'s `requireEndpoint` check throws
	 * [IllegalArgumentException] for that case — correct for its other, trusted callers
	 * (an unknown endpoint there really is a caller bug), but for the LLM tool path it is
	 * a routine, expected external-input error, not a bug. Without the guard below, that
	 * exception propagated out of this method and killed the entire kDisco simulation
	 * thread (confirmed via a live local-model run), after which the simulation stopped
	 * dispatching anything at all. Each decision is applied in its own try/catch so one
	 * bad LLM argument only drops that single decision — every other decision in the same
	 * drained batch is still applied, and the simulation keeps running. Only
	 * [IllegalArgumentException] is caught here: it is the specific, established idiom
	 * this codebase uses for "caller violated an API precondition"; anything else (e.g. a
	 * `NullPointerException` from a genuine bug) still propagates and crashes loud,
	 * preserving today's dev-time bug visibility.
	 */
	override fun onControlStep() {
		val decisions = queue.drain()
		if (decisions.isEmpty()) return
		logger.debug { "onControlStep: applying ${decisions.size} pending decision(s)" }
		for (decision in decisions) {
			// Look up the CommandId and tick index assigned at post time. Evicts the
			// entry so the map doesn't grow unboundedly.
			val correlation = correlationMap?.correlate(decision)
			if (shouldApply(decision)) {
				try {
					val result = applyDecision(decision, correlation)
					// Surface applied decisions to a GUI observer so the "Why this route?"
					// panel can display the rationale. NoAction carries no rationale, and
					// dropped/refused decisions (under MANUAL, or an apply-time
					// CAP_EXCEEDED_APPLY refusal — see applyApproveTrain) are not "applied",
					// so applyDecision returns decisionApplied=false and the listener is not
					// invoked.
					if (result.decisionApplied) {
						onDecisionApplied?.onDecisionApplied(decision)
					}
					// Notify the action outcome sink with author attribution. No logging
					// here — this runs on the sim thread and log calls inject latency.
					reportActionOutcome(decision, correlation, result.applyFailure)
				} catch (e: IllegalArgumentException) {
					logger.warn(e) {
						"onControlStep: dropping decision ${decision::class.simpleName} — invalid " +
							"argument(s): ${e.message}"
					}
					// Publish DroppedInvalid so the agent learns that its command was
					// dropped — a silently lost outcome is how this class of bug hides.
					publishOutcome(correlation) {
						AppliedOutcome.DroppedInvalid(
							commandType = decision.commandTypeName(),
							trainId = decision.extractTrainId(),
							message = e.message ?: "invalid argument",
							id = it.id,
							tickIndex = it.tickIndex
						)
					}
					// Notify the action outcome sink about the drop. No logging here —
					// this runs on the sim thread.
					reportActionOutcome(decision, correlation, ApplyFailureCode.DROPPED_INVALID)
				}
			}
		}
	}

	/**
	 * Publishes the [ActionOutcome] for one applied/failed decision to [actionOutcomeSink].
	 * No-logging — runs on the sim thread; a log call here would inject latency into the
	 * physics loop (see [ActionOutcomeSink] contract).
	 *
	 * No-op when [actionOutcomeSink] is null or [decision] is [DispatchDecision.NoAction]
	 * — NoAction carries no author attribution. The [applyFailure] argument drives the
	 * phase: `null` → [ActionPhase.APPLIED]; non-null → [ActionPhase.APPLIED_THEN_FAILED]
	 * (with that code). The catch path passes [ApplyFailureCode.DROPPED_INVALID].
	 *
	 * (Extracted from [onControlStep] to keep that method within the detekt LongMethod budget.)
	 */
	private fun reportActionOutcome(
		decision: DispatchDecision,
		correlation: CommandCorrelationMap.CommandAndTick?,
		applyFailure: ApplyFailureCode?
	) {
		if (actionOutcomeSink == null || decision is DispatchDecision.NoAction) return
		val authored =
			AuthoredAction(
				author = correlation?.author ?: ActionAuthor.LLM,
				reason = correlation?.reason ?: "",
				decisionKind = decision::class.simpleName ?: "Unknown",
				tickIndex = correlation?.tickIndex ?: -1L
			)
		val phase =
			if (applyFailure != null) ActionPhase.APPLIED_THEN_FAILED else ActionPhase.APPLIED
		actionOutcomeSink.onActionOutcome(ActionOutcome(phase, null, applyFailure, authored))
	}

	/**
	 * Consults [modeState] (if wired) to decide whether [decision] should be applied, sent
	 * to the [semiAutoApprover], or dropped outright.
	 *
	 * Returns `true` when the caller should proceed to [applyDecision], `false` when
	 * the decision has been dropped by the mode gate (a debug/warn line has been
	 * logged and no actuator side effect must occur).
	 *
	 * [DispatchDecision.NoAction] always returns `true` — applying it is a no-op
	 * regardless of mode, and short-circuiting it here would suppress the exhaustive-
	 * `when` guarantee in [applyDecision].
	 */
	private fun shouldApply(decision: DispatchDecision): Boolean {
		val state = modeState ?: return true
		if (decision is DispatchDecision.NoAction) return true
		return when (state.getEffectiveMode()) {
			DispatcherMode.AUTO -> true
			DispatcherMode.MANUAL -> {
				logger.debug {
					"Dropping decision under DispatcherMode.MANUAL: ${decision::class.simpleName}"
				}
				false
			}
			DispatcherMode.SEMI_AUTO -> {
				val approver = semiAutoApprover
				if (approver == null) {
					logger.warn {
						"Dropping decision under DispatcherMode.SEMI_AUTO: no approver wired " +
							"(${decision::class.simpleName})"
					}
					false
				} else {
					val approved = approver(decision)
					if (!approved) {
						logger.debug {
							"Decision rejected by SEMI_AUTO approver: ${decision::class.simpleName}"
						}
					}
					approved
				}
			}
		}
	}

	// Expression-body `when` so the compiler enforces exhaustiveness over the sealed
	// DispatchDecision type — adding a future subtype becomes a compile error here
	// rather than a silently-dropped decision. Returns `true` when the decision was actually
	// applied (so [onDecisionApplied] should fire); `false` for [DispatchDecision.NoAction]
	// and for an [DispatchDecision.ApproveTrain] refused at apply time (CAP_EXCEEDED_APPLY) —
	// those are not "applied" and must not surface to the GUI "Why this route?" observer.
	private fun applyDecision(
		decision: DispatchDecision,
		correlation: CommandCorrelationMap.CommandAndTick?
	): ApplyResult =
		when (decision) {
			is DispatchDecision.ApproveTrain -> {
				val admitted = applyApproveTrain(decision, correlation)
				ApplyResult(admitted, if (!admitted) ApplyFailureCode.CAP_EXCEEDED_APPLY else null)
			}
			is DispatchDecision.ReservePath -> {
				applyReservePath(decision)
				ApplyResult(true)
			}
			DispatchDecision.NoAction -> ApplyResult(false)
			// ── Train-lifecycle subtypes ──────────────────────────────────────────
			is DispatchDecision.HoldTrain -> {
				applyHoldTrain(decision)
				ApplyResult(true)
			}
			// ── Tool-driven actuator subtypes ─────────────────────────────────────
			// SetSignalAspect and SetSwitchPosition still delegate to the shared helper in :core
			// (no outcome type defined for them). ReleaseRoute and RequestRoute are handled
			// directly here so the outcome can be captured and published without touching
			// :core's applyToolDrivenToActuator (zero :core changes).
			is DispatchDecision.SetSignalAspect,
			is DispatchDecision.SetSwitchPosition -> {
				decision.applyToolDrivenToActuator(networkActuator, "DispatchDecisionApplier")
				ApplyResult(true)
			}
			is DispatchDecision.ReleaseRoute -> {
				applyReleaseRoute(decision, correlation)
				ApplyResult(true)
			}
			is DispatchDecision.RequestRoute -> {
				val applyFailure = applyRequestRoute(decision, correlation)
				// decisionApplied stays true regardless of RouteRequestResult: the actuator was
				// reached and the attempt is surfaced to the GUI "Why this route?" observer either
				// way. Only applyFailure — which drives actionOutcomeSink's phase/code — varies
				// with the actual result.
				ApplyResult(true, applyFailure)
			}
		}

	/**
	 * Publishes an [AppliedOutcome] built by [outcome] to [outcomeSink] when [correlation] is
	 * non-null; a no-op otherwise.
	 *
	 * Every apply-time branch that reports an outcome (approve, release, reserve, and each
	 * `RequestRoute` result variant) repeats the same "only publish when correlated, using the
	 * correlation's `id`/`tickIndex`" shape — this collapses that repeated boilerplate into one
	 * place. [outcome] receives the non-null [CommandCorrelationMap.CommandAndTick] so callers
	 * that have nothing to publish ([correlation] `== null`) never construct the [AppliedOutcome]
	 * instance.
	 */
	private inline fun publishOutcome(
		correlation: CommandCorrelationMap.CommandAndTick?,
		outcome: (CommandCorrelationMap.CommandAndTick) -> AppliedOutcome
	) {
		if (correlation != null) {
			outcomeSink?.publish(outcome(correlation))
		}
	}

	/**
	 * Applies [decision] by checking the live active-train count (if
	 * [activeTrainCountProvider] is wired) and either admitting the train or refusing
	 * with [ApplyFailureCode.CAP_EXCEEDED_APPLY].
	 *
	 * ## Cap enforcement at apply time
	 *
	 * When [activeTrainCountProvider] is non-null, this method reads the **live** count
	 * from the sim thread before invoking [onApproveTrain].  Because both the read and
	 * the admission callback are on the same kDisco thread, two `ApproveTrain` commands
	 * drained in the same tick see the updated count after the first admission: the first
	 * command is admitted (count goes from N to N+1), and if N+1 equals [maxConcurrentTrains]
	 * the second command receives [ApplyFailureCode.CAP_EXCEEDED_APPLY] — the stale-snapshot race
	 * that was possible with [ActionValidator]'s pre-queue check alone is closed.
	 *
	 * When [activeTrainCountProvider] is `null`, no apply-time cap check is performed and
	 * [onApproveTrain] is always invoked (the behaviour for callers that rely on
	 * [ShuntingLoop.approveQueuedTrain]'s own inner guard).
	 *
	 * @return `true` when the train was admitted ([onApproveTrain] invoked); `false` when the
	 *   cap was already reached and the admission was refused with [ApplyFailureCode.CAP_EXCEEDED_APPLY].
	 *   The Boolean lets [onControlStep] skip [onDecisionApplied] for a refusal — a refused
	 *   admission was not "applied" and must not surface to the GUI "Why this route?" observer.
	 */
	private fun applyApproveTrain(
		decision: DispatchDecision.ApproveTrain,
		correlation: CommandCorrelationMap.CommandAndTick?
	): Boolean {
		val provider = activeTrainCountProvider
		if (provider != null) {
			val liveCount = provider()
			if (liveCount >= maxConcurrentTrains) {
				logger.debug {
					"ApproveTrain CAP_EXCEEDED_APPLY at apply time: trainId=${decision.trainId} " +
						"live=$liveCount max=$maxConcurrentTrains — admission refused"
				}
				publishOutcome(correlation) {
					AppliedOutcome.Approved(
						trainId = decision.trainId,
						admitted = false,
						reason = ApplyFailureCode.CAP_EXCEEDED_APPLY,
						id = it.id,
						tickIndex = it.tickIndex
					)
				}
				return false
			}
		}
		logger.debug {
			"Applying ApproveTrain: trainId=${decision.trainId}" +
				decision.rationale.toRationaleLogSuffix()
		}
		onApproveTrain(decision.trainId)
		publishOutcome(correlation) {
			AppliedOutcome.Approved(
				trainId = decision.trainId,
				admitted = true,
				id = it.id,
				tickIndex = it.tickIndex
			)
		}
		return true
	}

	/**
	 * Applies [decision] via [NetworkActuatorPort.requestRoute], guarding against
	 * duplicate application of an already-successfully-reserved hop.
	 *
	 * ## Duplicate-decision race
	 *
	 * [AgentLoopDriver][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] runs on its
	 * own thread, decoupled from the sim thread that applies its decisions. Because
	 * headless runs are not wall-clock throttled, several sim ticks can elapse before the
	 * driver thread is scheduled again — so it can legitimately decide the *same*
	 * `ReservePath` more than once before its first application is reflected back into
	 * the observation it reads next.
	 *
	 * Re-applying an already-owned hop is not a harmless no-op: it corrupts
	 * `PathReservationRegistry`'s merge logic (the incoming path's `start` no longer
	 * overlaps the already-advanced registered `target`), splicing a stale segment back
	 * into the train's registered path and permanently stalling its position tracking.
	 *
	 * Skipping a triple already recorded in [appliedReservations] restores "at most one
	 * real application per reservation need", without touching the shared
	 * navigation/registry code that other callers (e.g. `InOutWorker`) also depend on.
	 */
	private fun applyReservePath(decision: DispatchDecision.ReservePath) {
		val reservationKey = ReservationKey(decision.trainId, decision.fromSemaphoreName, decision.toSeparatorName)
		if (reservationKey in appliedReservations) {
			logger.debug {
				"Skipping duplicate ReservePath: trainId=${decision.trainId} " +
					"${decision.fromSemaphoreName} → ${decision.toSeparatorName} (already applied)"
			}
			return
		}

		logger.debug {
			"Applying ReservePath: trainId=${decision.trainId} " +
				"${decision.fromSemaphoreName} → ${decision.toSeparatorName}"
		}
		val result =
			networkActuator.requestRoute(
				decision.trainId,
				decision.fromSemaphoreName,
				decision.toSeparatorName
			)
		// Exhaustive `when` *expression* over the sealed RouteRequestResult type —
		// returning the `when` forces the compiler to enforce coverage, so a future
		// subtype addition fails to compile here instead of being silently ignored
		// (matches the pattern in DefaultNetworkActuatorPort.requestRoute).
		return when (result) {
			is RouteRequestResult.Reserved -> {
				logger.debug {
					"ReservePath: reserved ${result.blocksCount} block(s) for ${decision.trainId}"
				}
				appliedReservations.add(reservationKey)
				onBlockTransition(decision.trainId)
			}
			is RouteRequestResult.AllPathsBlocked -> {
				logger.warn {
					"ReservePath: all paths blocked for ${decision.trainId} " +
						"(${decision.fromSemaphoreName} → ${decision.toSeparatorName}); " +
						"attempted: ${result.attemptedPaths}"
				}
				onFailedReservation()
			}
			is RouteRequestResult.Conflict -> {
				logger.warn {
					"ReservePath: conflict for ${decision.trainId} — " +
						"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
				}
				onFailedReservation()
			}
			is RouteRequestResult.NoRouteExists -> {
				logger.warn {
					"ReservePath: no route exists " +
						"${decision.fromSemaphoreName} → ${decision.toSeparatorName} " +
						"for ${decision.trainId}"
				}
				onFailedReservation()
			}
			is RouteRequestResult.OriginNotContiguous -> {
				logger.warn {
					"ReservePath: origin '${decision.fromSemaphoreName}' is not contiguous " +
						"with ${decision.trainId}: ${result.reason}"
				}
				onFailedReservation()
			}
			is RouteRequestResult.ConditionFailed -> {
				logger.warn {
					"ReservePath: four-condition refusal for ${decision.trainId} " +
						"(${decision.fromSemaphoreName} → ${decision.toSeparatorName}" +
						"${if (result.retryable) ", transient" else ", permanent"}): ${result.reason}"
				}
				onFailedReservation()
			}
		}
	}

	/**
	 * Applies [decision] via [NetworkActuatorPort.releaseRoute] and publishes
	 * an [AppliedOutcome.Released] when a [correlation] is available.
	 *
	 * Inlined from [cz.vutbr.fit.interlockSim.sim.applyToolDrivenToActuator] so the outcome can
	 * be captured without modifying `:core`. Logging is identical to the shared helper.
	 */
	private fun applyReleaseRoute(
		decision: DispatchDecision.ReleaseRoute,
		correlation: CommandCorrelationMap.CommandAndTick?
	) {
		logger.debug {
			"DispatchDecisionApplier: applying ReleaseRoute trainName=${decision.trainName}" +
				decision.rationale.toRationaleLogSuffix()
		}
		val released = networkActuator.releaseRoute(decision.trainName)
		if (!released) {
			logger.debug {
				"DispatchDecisionApplier: ReleaseRoute train '${decision.trainName}' held no reservation (no-op)"
			}
		}
		publishOutcome(correlation) {
			AppliedOutcome.Released(
				trainId = decision.trainName,
				anyReleased = released,
				id = it.id,
				tickIndex = it.tickIndex
			)
		}
	}

	/**
	 * Applies [decision] via [NetworkActuatorPort.requestRoute] and publishes
	 * an [AppliedOutcome] subtype matching the [RouteRequestResult] when a [correlation] is
	 * available.
	 *
	 * Inlined from [cz.vutbr.fit.interlockSim.sim.applyToolDrivenToActuator] so the outcome can
	 * be captured without modifying `:core`. Logging is identical to the shared helper.
	 *
	 * ## Duplicate-suppression guard
	 *
	 * Symmetric to [applyReservePath]: an exact `trainName|fromEndpointName|toEndpointName` triple
	 * already successfully applied is skipped on re-entry. The guard is **dormant today** because
	 * `ActionValidator.validateRequestRouteConflict` rejects any second `RequestRoute` for a train
	 * that already holds a reservation before it reaches this applier — so a duplicate never gets
	 * this far. It becomes **load-bearing once a future validator fix** lets the validator accept
	 * a contiguous forward extension (`from == existing.target`): at that point a train may
	 * legitimately re-emit the *same* `RequestRoute` while its first application is still in
	 * flight, and re-applying it would corrupt the `PathReservationRegistry` merge exactly as
	 * `applyReservePath`'s guard prevents for `ReservePath`. Recording the triple only on
	 * `Reserved` keeps the guard aligned with actual successful application.
	 */
	private fun applyRequestRoute(
		decision: DispatchDecision.RequestRoute,
		correlation: CommandCorrelationMap.CommandAndTick?
	): ApplyFailureCode? {
		val reservationKey = ReservationKey(decision.trainName, decision.fromEndpointName, decision.toEndpointName)
		if (reservationKey in appliedRequestRoutes) {
			logger.debug {
				"DispatchDecisionApplier: skipping duplicate RequestRoute: " +
					"trainName=${decision.trainName} ${decision.fromEndpointName} → ${decision.toEndpointName} (already applied)"
			}
			return null
		}
		logger.debug {
			"DispatchDecisionApplier: applying RequestRoute trainName=${decision.trainName}, " +
				"from=${decision.fromEndpointName}, to=${decision.toEndpointName}${decision.rationale.toRationaleLogSuffix()}"
		}
		return when (
			val result =
				networkActuator.requestRoute(
					decision.trainName,
					decision.fromEndpointName,
					decision.toEndpointName
				)
		) {
			is RouteRequestResult.Reserved -> {
				handleRequestRouteReserved(decision, result, reservationKey, correlation)
				null
			}
			is RouteRequestResult.AllPathsBlocked -> {
				logger.warn {
					"DispatchDecisionApplier: RequestRoute all paths blocked for ${decision.trainName} " +
						"(${decision.fromEndpointName} → ${decision.toEndpointName}); attempted: ${result.attemptedPaths}"
				}
				publishOutcome(correlation) {
					AppliedOutcome.Blocked(
						trainId = decision.trainName,
						fromEndpointName = decision.fromEndpointName,
						toEndpointName = decision.toEndpointName,
						attemptedPaths = result.attemptedPaths,
						id = it.id,
						tickIndex = it.tickIndex
					)
				}
				ApplyFailureCode.ALL_PATHS_BLOCKED
			}
			is RouteRequestResult.Conflict -> {
				logger.warn {
					"DispatchDecisionApplier: RequestRoute conflict for ${decision.trainName} — " +
						"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
				}
				publishOutcome(correlation) {
					AppliedOutcome.Conflicted(
						trainId = decision.trainName,
						fromEndpointName = decision.fromEndpointName,
						toEndpointName = decision.toEndpointName,
						blockName = result.blockName,
						existingOwner = result.existingOwner,
						id = it.id,
						tickIndex = it.tickIndex
					)
				}
				ApplyFailureCode.CONFLICT
			}
			is RouteRequestResult.NoRouteExists -> {
				logger.warn {
					"DispatchDecisionApplier: RequestRoute no route exists " +
						"${decision.fromEndpointName} → ${decision.toEndpointName} for ${decision.trainName}"
				}
				publishOutcome(correlation) {
					AppliedOutcome.NoRoute(
						trainId = decision.trainName,
						fromEndpointName = decision.fromEndpointName,
						toEndpointName = decision.toEndpointName,
						id = it.id,
						tickIndex = it.tickIndex
					)
				}
				ApplyFailureCode.NO_ROUTE_EXISTS
			}
			is RouteRequestResult.OriginNotContiguous -> {
				handleRequestRouteOriginNotContiguous(decision, result, correlation)
				ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS
			}
			is RouteRequestResult.ConditionFailed -> {
				handleRequestRouteConditionFailed(decision, result, correlation)
				ApplyFailureCode.CONDITION_FAILED
			}
		}
	}

	/**
	 * Handles the [RouteRequestResult.OriginNotContiguous] branch of [applyRequestRoute] —
	 * extracted for the same reason as [handleRequestRouteReserved] (detekt LongMethod budget).
	 *
	 * Publishes [AppliedOutcome.OriginNotContiguous] so the agent learns *why* its route was
	 * refused. Symmetric with the `Blocked`/`Conflicted`/`NoRoute` branches: leaving this one
	 * silent would tell the model less about the worst error class it can produce than about
	 * routine contention.
	 */
	private fun handleRequestRouteOriginNotContiguous(
		decision: DispatchDecision.RequestRoute,
		result: RouteRequestResult.OriginNotContiguous,
		correlation: CommandCorrelationMap.CommandAndTick?
	) {
		logger.warn {
			"DispatchDecisionApplier: RequestRoute origin not contiguous for " +
				"${decision.trainName}: ${result.reason}"
		}
		publishOutcome(correlation) {
			AppliedOutcome.OriginNotContiguous(
				trainId = decision.trainName,
				fromEndpointName = decision.fromEndpointName,
				toEndpointName = decision.toEndpointName,
				reason = result.reason,
				id = it.id,
				tickIndex = it.tickIndex
			)
		}
	}

	/**
	 * Handles the [RouteRequestResult.ConditionFailed] branch of [applyRequestRoute] — extracted
	 * for the same reason as [handleRequestRouteOriginNotContiguous] (detekt LongMethod budget).
	 *
	 * Publishes [AppliedOutcome.ConditionFailed] so the agent learns the four-condition interlocking
	 * refusal carried a retryability flag (Issue #834, review finding #2): a transient refusal
	 * (contention) reads differently from a permanent one (an output defect an identical retry would
	 * reproduce), and the [ApplyFailureCode.CONDITION_FAILED] the branch returns collapses that
	 * distinction — the prose here preserves it.
	 */
	private fun handleRequestRouteConditionFailed(
		decision: DispatchDecision.RequestRoute,
		result: RouteRequestResult.ConditionFailed,
		correlation: CommandCorrelationMap.CommandAndTick?
	) {
		logger.warn {
			"DispatchDecisionApplier: RequestRoute four-condition refusal for " +
				"${decision.trainName} (${decision.fromEndpointName} → " +
				"${decision.toEndpointName}${if (result.retryable) ", transient" else ", permanent"}): " +
				result.reason
		}
		publishOutcome(correlation) {
			AppliedOutcome.ConditionFailed(
				trainId = decision.trainName,
				fromEndpointName = decision.fromEndpointName,
				toEndpointName = decision.toEndpointName,
				retryable = result.retryable,
				reason = result.reason,
				id = it.id,
				tickIndex = it.tickIndex
			)
		}
	}

	/**
	 * Handles the [RouteRequestResult.Reserved] branch of [applyRequestRoute] — extracted to
	 * keep that method within the detekt LongMethod budget. [onBlockTransition] is called here
	 * to restore block-transition counting for the [DispatchTickLoop]-emitted `RequestRoute`
	 * path, mirroring [applyReservePath].
	 */
	private fun handleRequestRouteReserved(
		decision: DispatchDecision.RequestRoute,
		result: RouteRequestResult.Reserved,
		reservationKey: ReservationKey,
		correlation: CommandCorrelationMap.CommandAndTick?
	) {
		logger.debug {
			"DispatchDecisionApplier: RequestRoute reserved ${result.blocksCount} block(s) for ${decision.trainName}"
		}
		appliedRequestRoutes.add(reservationKey)
		onBlockTransition(decision.trainName)
		publishOutcome(correlation) {
			AppliedOutcome.Reserved(
				trainId = decision.trainName,
				fromEndpointName = decision.fromEndpointName,
				toEndpointName = decision.toEndpointName,
				blocksCount = result.blocksCount,
				id = it.id,
				tickIndex = it.tickIndex
			)
		}
	}

	/**
	 * Applies [decision] via [trainLifecyclePort].
	 *
	 * Instructs the named train to hold in place for [DispatchDecision.HoldTrain.holdDurationSeconds]
	 * simulation seconds.  If no [trainLifecyclePort] was supplied at construction time, this
	 * logs a warning and drops the decision (backward-compatible no-op for callers that do not
	 * need train-lifecycle commands).
	 */
	private fun applyHoldTrain(decision: DispatchDecision.HoldTrain) {
		val port = trainLifecyclePort
		if (port == null) {
			logger.warn {
				"HoldTrain decision received (trainId=${decision.trainId}, " +
					"duration=${decision.holdDurationSeconds}s) but no TrainLifecyclePort is wired — " +
					"decision dropped. Supply a TrainLifecyclePort to DispatchDecisionApplier to enable " +
					"hold-train support."
			}
			return
		}
		logger.debug {
			"Applying HoldTrain: trainId=${decision.trainId}, " +
				"duration=${decision.holdDurationSeconds}s" +
				decision.rationale.toRationaleLogSuffix()
		}
		val accepted = port.holdTrain(decision.trainId, decision.holdDurationSeconds)
		if (!accepted) {
			logger.warn {
				"HoldTrain: no active train found with id='${decision.trainId}' — decision dropped"
			}
		}
	}
}

/**
 * Returns the tool name for this decision type, used in
 * [AppliedOutcome.DroppedInvalid.commandType] to identify which tool was attempted.
 *
 * Top-level `internal` so exhaustive-branch coverage can be asserted directly by tests
 * without constructing a [DispatchDecisionApplier] — the function is a pure projection of
 * the sealed [DispatchDecision] type and references no applier state.
 */
internal fun DispatchDecision.commandTypeName(): String =
	when (this) {
		is DispatchDecision.RequestRoute -> "request_route"
		// The LLM-facing tool name is `cancel_route` (see CancelRouteTool). This projection
		// feeds AppliedOutcome.DroppedInvalid.commandType, which both renderers emit
		// verbatim to the model, so it must speak the current tool name — not the retired
		// `release_route`.
		is DispatchDecision.ReleaseRoute -> "cancel_route"
		is DispatchDecision.ApproveTrain -> "approve_train"
		is DispatchDecision.ReservePath -> "reserve_path"
		is DispatchDecision.HoldTrain -> "hold_train"
		is DispatchDecision.SetSignalAspect -> "set_signal_aspect"
		is DispatchDecision.SetSwitchPosition -> "set_switch_position"
		DispatchDecision.NoAction -> "no_action"
	}

/**
 * Returns the train identifier carried by this decision, or an empty string when no train
 * identifier is applicable (e.g. [DispatchDecision.NoAction]).
 *
 * Top-level `internal` for the same reason as [commandTypeName] — pure projection, no
 * applier state, exhaustively testable.
 */
internal fun DispatchDecision.extractTrainId(): String =
	when (this) {
		is DispatchDecision.RequestRoute -> trainName
		is DispatchDecision.ReleaseRoute -> trainName
		is DispatchDecision.ApproveTrain -> trainId
		is DispatchDecision.ReservePath -> trainId
		is DispatchDecision.HoldTrain -> trainId
		is DispatchDecision.SetSignalAspect -> ""
		is DispatchDecision.SetSwitchPosition -> ""
		DispatchDecision.NoAction -> ""
	}
