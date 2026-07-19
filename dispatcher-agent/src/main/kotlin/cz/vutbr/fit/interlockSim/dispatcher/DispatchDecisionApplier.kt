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

import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.ports.TrainLifecyclePort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.applyToolDrivenToActuator
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Sim-thread applier that drains [ActuatorCommandQueue] at each simulation control
 * step and applies every [DispatchDecision] through the SP0.6 actuator ports.
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
 * ## Mode gating (SP2b.4)
 *
 * When a [DispatcherModeState] is wired (default: `null`, gate disabled) every drained
 * decision is filtered through the effective [DispatcherMode] before it reaches the
 * actuator ports:
 *
 * - [DispatcherMode.AUTO] — apply directly (pre-SP2b.4 behaviour).
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
 *   **Design note (SP0.9 review, Minor #4 / SP2b.1 follow-up):** `ApproveTrain` is
 *   kept as a `(String) -> Unit` callback for backward compatibility; `HoldTrain`
 *   is routed through [trainLifecyclePort] — the new dedicated port introduced by
 *   SP2b.1 (#556), symmetric with [NetworkActuatorPort].
 * @param trainLifecyclePort Train lifecycle actuator port for [DispatchDecision.HoldTrain]
 *   commands (SP2b.1 — Issue #556).  `null` by default for backward compatibility with
 *   callers that do not need train-lifecycle commands; when `null` and a [DispatchDecision.HoldTrain]
 *   is received, a warning is logged and the decision is dropped.
 *
 * @since Issue #731 (SP0.9 — Goal 10); [trainLifecyclePort] added in Issue #556 (SP2b.1);
 *   [modeState] + [semiAutoApprover] added in Issue #559 (SP2b.4)
 */
class DispatchDecisionApplier(
	private val queue: ActuatorCommandQueue,
	private val networkActuator: NetworkActuatorPort,
	private val onApproveTrain: (trainId: String) -> Unit,
	/**
	 * SP0.11: Callback invoked on the sim thread when a path reservation succeeds.
	 * Increments [ShuntingLoop.incrementBlockTransition] (the counter previously
	 * updated inside the removed [ShuntingLoop.tryReservePath]).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	private val onBlockTransition: (trainId: String) -> Unit = {},
	/**
	 * SP0.11: Callback invoked on the sim thread when a path reservation fails
	 * (any of AllPathsBlocked, Conflict, or NoRouteExists).
	 * Increments [ShuntingLoop.incrementFailedReservation] (the counter previously
	 * updated inside the removed [ShuntingLoop.tryReservePath]).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	private val onFailedReservation: () -> Unit = {},
	/**
	 * SP2b.1: Train lifecycle actuator port for [DispatchDecision.HoldTrain] commands.
	 * Null by default for backward compatibility with callers that do not supply one.
	 *
	 * @since Issue #556 (SP2b.1 — Goal 10)
	 */
	private val trainLifecyclePort: TrainLifecyclePort? = null,
	/**
	 * SP2b.4: Dispatcher operating-mode state that gates decision application.
	 *
	 * When `null` (the default), the applier behaves exactly as before SP2b.4 — every
	 * drained decision is applied.  When non-null, the effective mode
	 * ([DispatcherModeState.getEffectiveMode]) is consulted on every drained
	 * decision and controls whether it is applied, forwarded to [semiAutoApprover] for
	 * human approval, or dropped:
	 *
	 * | Effective mode | Behaviour |
	 * |---|---|
	 * | [DispatcherMode.AUTO] | Apply directly (pre-SP2b.4 behaviour). |
	 * | [DispatcherMode.SEMI_AUTO] | Forward to [semiAutoApprover]; apply only if it returns `true`. If no approver is wired, the decision is dropped with a warning. |
	 * | [DispatcherMode.MANUAL] | Drop the decision without invoking the actuator port; a debug line is logged. [DispatchDecision.NoAction] passes through as a no-op regardless of mode. |
	 *
	 * The mode is read once per decision via [DispatcherModeState.getEffectiveMode], so
	 * a mode change becomes effective on the next drained decision.
	 *
	 * @since Issue #559 (SP2b.4 — Goal 10)
	 */
	private val modeState: DispatcherModeState? = null,
	/**
	 * SP2b.4: Approver callback consulted when the effective [DispatcherMode] is
	 * [DispatcherMode.SEMI_AUTO].
	 *
	 * Called on the sim thread (same thread as [onControlStep]) with the pending
	 * decision; must return `true` to apply the decision or `false` to drop it.  Wired
	 * in Stage B1 (Issue #532) to the Goal 9 `ConflictResolutionPanel` propose-approve
	 * flow; `null` in headless contexts, in which case every actuating decision is
	 * dropped with a warning while in SEMI_AUTO mode.
	 *
	 * Implementations must be non-blocking (or at least short-lived) — the callback
	 * runs inline on the kDisco simulation thread and stalling it stalls the whole
	 * simulation.
	 *
	 * @since Issue #559 (SP2b.4 — Goal 10)
	 */
	private val semiAutoApprover: ((DispatchDecision) -> Boolean)? = null
) : ControlStepListener {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Format a rationale list as a log suffix: returns empty string when the list
		 * is empty, otherwise `" | rationale: [entry1; entry2; …]"` (SP2b.5).
		 *
		 * @since Issue #560 (SP2b.5 — Goal 10)
		 */
		internal fun List<String>.toRationaleLogSuffix(): String =
			if (isEmpty()) "" else " | rationale: [${joinToString("; ")}]"
	}

	/**
	 * Reservation triples (`trainId|fromSemaphoreName|toSeparatorName`) already
	 * successfully applied via [applyReservePath], for the duplicate-suppression
	 * guard documented on that function.
	 *
	 * Entries are evicted via [evictReservationsFor] once the wiring layer observes a
	 * block release for that train (see [evictReservationsFor] KDoc) — this set must
	 * NOT be permanent: interlockSim supports bidirectional train operation (a train
	 * can reverse and later legitimately need to reserve an identical hop it already
	 * traversed once), which would otherwise collide with an entry left over from the
	 * first pass and silently stall the train.
	 */
	private val appliedReservations: MutableSet<String> = mutableSetOf()

	/**
	 * Evicts every [appliedReservations] entry recorded for [trainId].
	 *
	 * ## Why eviction is needed (Goal 10 code-review fix)
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
	 * @since Goal 10 code-review fix — latent bug found during the SP0.11 review
	 */
	fun evictReservationsFor(trainId: String) {
		val prefix = "$trainId|"
		appliedReservations.removeAll { it.startsWith(prefix) }
	}

	/**
	 * Drains [queue] and applies each pending [DispatchDecision] via the actuator ports.
	 *
	 * Always called on the kDisco simulation thread from
	 * [ShuntingLoop.iteration][cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
	 * All simulation-state mutations performed here are serialised with all other
	 * sim-thread operations by kDisco's single-threaded scheduler.
	 */
	override fun onControlStep() {
		val decisions = queue.drain()
		if (decisions.isEmpty()) return
		logger.debug { "onControlStep: applying ${decisions.size} pending decision(s)" }
		for (decision in decisions) {
			if (shouldApply(decision)) {
				applyDecision(decision)
			}
		}
	}

	/**
	 * SP2b.4 gate: consults [modeState] (if wired) to decide whether [decision] should
	 * be applied, sent to the [semiAutoApprover], or dropped outright.
	 *
	 * Returns `true` when the caller should proceed to [applyDecision], `false` when
	 * the decision has been dropped by the mode gate (a debug/warn line has been
	 * logged and no actuator side effect must occur).
	 *
	 * [DispatchDecision.NoAction] always returns `true` — applying it is a no-op
	 * regardless of mode, and short-circuiting it here would suppress the exhaustive-
	 * `when` guarantee in [applyDecision].
	 *
	 * @since Issue #559 (SP2b.4 — Goal 10)
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
	// rather than a silently-dropped decision.
	private fun applyDecision(decision: DispatchDecision) =
		when (decision) {
			is DispatchDecision.ApproveTrain -> {
				logger.debug {
					"Applying ApproveTrain: trainId=${decision.trainId}" +
						decision.rationale.toRationaleLogSuffix()
				}
				onApproveTrain(decision.trainId)
			}
			is DispatchDecision.ReservePath -> applyReservePath(decision)
			DispatchDecision.NoAction -> Unit
			// ── SP2b.1 train-lifecycle subtypes (Issue #556) ─────────────────────
			is DispatchDecision.HoldTrain -> applyHoldTrain(decision)
			// ── SP1.7 tool-driven actuator subtypes (Issue #774) ─────────────────
			// Delegated to the shared DispatchDecision.applyToolDrivenToActuator helper in :core
			// so the asynchronous path and SynchronousDispatcherWiring cannot drift apart.
			is DispatchDecision.SetSignalAspect,
			is DispatchDecision.SetSwitchPosition,
			is DispatchDecision.ReleaseRoute,
			is DispatchDecision.RequestRoute ->
				decision.applyToolDrivenToActuator(networkActuator, "DispatchDecisionApplier")
		}

	/**
	 * Applies [decision] via [NetworkActuatorPort.requestRoute], guarding against
	 * duplicate application of an already-successfully-reserved hop.
	 *
	 * ## Duplicate-decision race (SP0.11 regression, Issue #733 follow-up)
	 *
	 * [AgentLoopDriver][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] runs
	 * on its own thread, decoupled from the sim thread that applies its decisions.
	 * Because [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop]'s own
	 * polling ticks are not wall-clock throttled (no `SimulationController`
	 * pacing in a headless run), several ShuntingLoop ticks can elapse before the
	 * driver thread gets scheduled again. The block-input observation it reads —
	 * in particular [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.pathAlreadyExtendedBeyond],
	 * the guard [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] relies on to
	 * avoid re-deciding an already-reserved hop — only updates once a decided
	 * [DispatchDecision.ReservePath] has actually been drained and applied here.
	 * This means the driver can legitimately decide the *same* `ReservePath` more
	 * than once before its first application is reflected back.
	 *
	 * Re-applying an already-owned hop is not a harmless no-op:
	 * `DefaultPathReservationService.reservePath`'s already-owned fast path calls
	 * `PathReservationRegistry.registerPathInfo` again, and that registry's merge
	 * logic assumes the incoming path's `start` overlaps the *current* registered
	 * `target`. Once the first (legitimate) application has already advanced that
	 * target past the duplicate's `start`, the overlap check fails, the duplicate
	 * segment is spliced back into the train's registered path, and the train's
	 * `Front` process (`Train.kt`) loses track of its position — permanently
	 * stalling (confirmed via `PathReservationRegistry` merge tracing during root
	 * cause analysis; the malformed splice is silently "allowed" as a false-positive
	 * "circular route" before a *third* occurrence trips the existing cycle guard).
	 *
	 * Skipping a triple already recorded in [appliedReservations] restores the
	 * "at most one real application per reservation need" invariant that held
	 * naturally under the pre-SP0.11 synchronous (decide-and-apply-in-one-step)
	 * architecture, without touching the shared navigation/registry code that
	 * other callers (e.g. `InOutWorker`) also depend on.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); duplicate-suppression guard added as a
	 *   regression fix for the resulting train-freeze/deadlock
	 */
	private fun applyReservePath(decision: DispatchDecision.ReservePath) {
		val reservationKey = "${decision.trainId}|${decision.fromSemaphoreName}|${decision.toSeparatorName}"
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
		}
	}

	/**
	 * Applies [decision] via [trainLifecyclePort].
	 *
	 * Instructs the named train to hold in place for [DispatchDecision.HoldTrain.holdDurationSeconds]
	 * simulation seconds.  If no [trainLifecyclePort] was supplied at construction time, this
	 * logs a warning and drops the decision (backward-compatible no-op for callers that do not
	 * need train-lifecycle commands).
	 *
	 * @since Issue #556 (SP2b.1 — Goal 10)
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
