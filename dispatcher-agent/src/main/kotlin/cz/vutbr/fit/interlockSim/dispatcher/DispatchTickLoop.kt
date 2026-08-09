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

import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.RenderContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationSource
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Unconditional fixed-tick perceive → decide → act control loop for the Goal 10 dispatcher
 * agent.
 *
 * ## Structural guarantee (C2)
 *
 * Every call to [runTick] is exactly one dispatcher tick: sense the current observation,
 * produce a decision (via [EmissionStrategy]), validate it (via [ActionValidator]), and post
 * it (via [ActuatorCommandQueue]). There is no state in which the dispatcher can silently
 * "forget" to reconsider admission because it is an unconditional per-tick loop.
 *
 * ## Pacing (ported from [AgentLoopDriver])
 *
 * The [snapshotSignal] protocol is used verbatim:
 *
 * - When [snapshotSignal] is non-null, [runTick] blocks on [SnapshotSignal.await] at the top of
 *   each tick. A `false` return (timeout — sim stopping) short-circuits and returns `null`.
 *   No `simTime == prevSimTime` guard is used: the signal already guarantees the snapshot is fresh
 *   and has not been processed before.
 *
 * - The [DispatcherObservation.EMPTY] guard (tick == 0) is preserved: if the projector has not
 *   yet published a real observation, [runTick] returns `null` (no-op short-circuit).
 *
 * - [controller.awaitIfPaused][SimulationController.awaitIfPaused] and
 *   [controller.throttle][SimulationController.throttle] are called at the end of every tick
 *   that ran (same position as in [AgentLoopDriver.runCycle]) — the loop is still async and
 *   still needs a pacing controller.
 *
 * ## Intra-tick optimistic projection (C6)
 *
 * Each action in the batch from [EmissionStrategy.emit] is re-validated against the state left
 * by the previous action via [applyOptimistically] — an in-process projection that does not hit
 * the sim thread. Inaccuracies are bounded to one tick and surface in `applied_outcomes` on the
 * next tick (the [DispatcherObservation.appliedOutcomes] channel). This is the issue's option A.
 *
 * ## ActionAuthor and RunOutcome
 *
 * [TerminalFallbackGuard] observes each completed [TickRecord]. If the LLM is absent
 * (deadline miss → [ActionAuthor.TIMEOUT_NOOP]) for [TerminalFallbackGuard.threshold] consecutive
 * ticks, or if an unhandled exception is thrown by [EmissionStrategy.emit], [runOutcome]
 * transitions to [RunOutcome.Failed] with reason [cz.vutbr.fit.interlockSim.dispatcher.agents.FailureReason.LLM_ABANDONED].
 * After engagement, [fallbackEmission] is used for all subsequent ticks (if non-null).
 * [ActionAuthor.RULE_BASED] actions never trigger failure — a rule-based determinism run stays
 * [RunOutcome.Running] for the entire simulation (P10 gate protection).
 *
 * @property runOutcome Current lifecycle outcome. Starts as [RunOutcome.Running]; transitions
 *   at most once to [RunOutcome.Failed] via [TerminalFallbackGuard] when the LLM is absent for
 *   [threshold][TerminalFallbackGuard.threshold] consecutive ticks or on an unhandled exception.
 *   Read-only for callers.
 *
 * @param observations Source of the most-recently-published [DispatcherObservation].
 * @param annotator Pre-evaluates the candidate action set into a flat [List]<[Affordance][cz.vutbr.fit.interlockSim.dispatcher.agents.Affordance]>.
 * @param renderer Converts a [RenderContext] into the USER-message prompt string.
 * @param emission Decision-making strategy (rule-based or LLM-backed). Used on every tick until
 *   [fallbackGuard] engages.
 * @param validator Pre-execution validator for each [DispatchAction]; called once per action.
 * @param queue Thread-safe handoff queue to the sim-thread [DispatchDecisionApplier].
 * @param ring Bounded ring buffer storing recent tick records for rendering context. Defaults to
 *   a fresh buffer at [TickRingBuffer]'s own default capacity.
 * @param workingMemory Initial cross-tick scratchpad; updated immutably each tick. Defaults to
 *   [WorkingMemory.EMPTY] — a run normally starts with nothing remembered.
 * @param budget Deadline wrapper for [EmissionStrategy.emit]; returns `null` on timeout.
 * @param fallbackGuard Observes each completed [TickRecord] and sets [runOutcome] to
 *   [RunOutcome.Failed] after [threshold][TerminalFallbackGuard.threshold] consecutive
 *   [ActionAuthor.TIMEOUT_NOOP] ticks. Defaults to a fresh guard.
 * @param fallbackEmission Optional emission strategy to activate after [fallbackGuard] engages.
 *   Typically [RuleBasedEmissionStrategy] with [cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor.RULE_FALLBACK]
 *   so that post-engagement actions are distinguishable and the simulation can reach a terminating
 *   state for inspection. When `null`, the original [emission] continues after engagement.
 * @param controller Pacing controller for pause/step and wall-clock throttling.
 * @param snapshotSignal Optional sim-to-driver wake signal. When non-null, each tick blocks on
 *   [SnapshotSignal.await] instead of reading a potentially stale observation. When null,
 *   [runTick] reads whatever [observations.latest][DispatcherObservationSource.latest] returns
 *   (polling behaviour).
 * @param maxActionsPerTick Maximum number of actions to process per tick. Actions beyond this
 *   limit are not posted.
 *
 * @see AgentLoopDriver
 * @see RuleBasedEmissionStrategy
 */
class DispatchTickLoop(
	private val observations: DispatcherObservationSource,
	private val annotator: AffordanceAnnotator,
	private val renderer: ObservationRenderer,
	private val emission: EmissionStrategy,
	private val validator: ActionValidator,
	private val queue: ActuatorCommandQueue,
	private val ring: TickRingBuffer = TickRingBuffer(),
	workingMemory: WorkingMemory = WorkingMemory.EMPTY,
	private val budget: TickBudget,
	private val fallbackGuard: TerminalFallbackGuard = TerminalFallbackGuard(),
	private val fallbackEmission: EmissionStrategy? = null,
	private val controller: SimulationController,
	private val snapshotSignal: SnapshotSignal? = null,
	private val maxActionsPerTick: Int = DEFAULT_MAX_ACTIONS_PER_TICK,
	/**
	 * Optional attribution store. When non-null, each completed [TickRecord] is forwarded to
	 * [DispatcherPreferenceStore.observe] after the ring-buffer push and working-memory update,
	 * so the store accumulates a per-run author-count summary that can be emitted via
	 * [DispatcherPreferenceStore.logFinalSummary] at run end.
	 *
	 * When null (the default) no attribution tracking is performed — existing callers are unaffected.
	 */
	private val preferenceStore: DispatcherPreferenceStore? = null
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default per-tick action limit — mirrors [ActionValidator.DEFAULT_MAX_ACTIONS_PER_TICK]. */
		const val DEFAULT_MAX_ACTIONS_PER_TICK: Int = 3

		/** Nanoseconds per second, for F2 real-time ratio computation. */
		private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
	}

	// ── Per-loop mutable state ──────────────────────────────────────────────

	/** Cross-tick scratchpad; updated immutably each tick via [WorkingMemory.update]. */
	private var workingMemory: WorkingMemory = workingMemory

	/** Previous tick's observation; `null` before the first real tick. Passed to [RenderContext.previous]. */
	private var prevObs: DispatcherObservation? = null

	/** Simulation time at the end of the last completed tick; used for [SimulationController.throttle]. */
	private var prevSimTime: Double = 0.0

	/** Monotonic counter for issuing unique [CommandId]s within this loop's lifetime. */
	private val commandIdCounter: AtomicLong = AtomicLong(0L)

	// ── RunOutcome ──────────────────────────────────────────────────────────

	/**
	 * Current lifecycle outcome. Starts as [RunOutcome.Running]; transitions to
	 * [RunOutcome.Failed] via [fallbackGuard] after [threshold][TerminalFallbackGuard.threshold]
	 * consecutive [ActionAuthor.TIMEOUT_NOOP] ticks or on an unhandled emission exception.
	 * Never reverts to [RunOutcome.Running] once failed.
	 */
	val runOutcome: RunOutcome get() = fallbackGuard.currentOutcome

	/**
	 * The emission strategy active for the current tick.
	 *
	 * After [fallbackGuard] engages, switches to [fallbackEmission] (if non-null) so that
	 * post-engagement actions are authored [ActionAuthor.RULE_FALLBACK] and the simulation
	 * can reach a terminating state for inspection.
	 */
	private val currentEmission: EmissionStrategy
		get() = if (fallbackGuard.engaged && fallbackEmission != null) fallbackEmission else emission

	// ── Tick execution ──────────────────────────────────────────────────────

	/**
	 * Executes one complete perceive → decide → validate → act tick.
	 *
	 * ## Short-circuit cases (returns `null`)
	 *
	 * - [snapshotSignal] non-null and [SnapshotSignal.await] timed out (sim stopping).
	 * - [DispatcherObservation.tick] == 0 (observation is [DispatcherObservation.EMPTY];
	 *   the projector has not yet published a real tick). Both match the [AgentLoopDriver]
	 *   short-circuit cases that prevented a `false` return from releasing the decisions-applied
	 *   barrier prematurely in [RuleBasedDispatcherDeterminismRunner].
	 *
	 * ## Tick steps
	 *
	 * 0. **WAIT** — block on [SnapshotSignal.await] when [snapshotSignal] is non-null.
	 * 1. **SENSE** — read the latest [DispatcherObservation]; skip on EMPTY.
	 * 2. **AFFORDANCES** — annotate candidate actions via [AffordanceAnnotator.annotate].
	 * 3. **RENDER** — produce the USER-message prompt via [ObservationRenderer.render].
	 * 4. **DECIDE** — call [EmissionStrategy.emit] wrapped in [TickBudget.withBudget];
	 *    substitute [DispatchAction.NoOp]/[ActionAuthor.TIMEOUT_NOOP] on null (deadline).
	 * 5. **VALIDATE & ACT** — validate each action via [ActionValidator.validate]; post valid
	 *    ones via [ActuatorCommandQueue.postAll]; update the observation optimistically (C6).
	 * 6. **RECORD** — build a [TickRecord]; push to [ring]; update [workingMemory];
	 *    observe with [fallbackGuard].
	 * 7. **PACE** — [SimulationController.awaitIfPaused]; [SimulationController.throttle].
	 *
	 * @return The completed [TickRecord] when a tick ran; `null` on short-circuit.
	 */
	suspend fun runTick(): TickRecord? {
		// 0. WAIT — signal-based pacing; blocks until the sim thread publishes a fresh tick.
		// A false return means the signal timed out (sim stopping); bail out and let the caller's
		// `while (isSimActive())` loop notice the shutdown. Mirrors AgentLoopDriver.runCycle step 0.
		if (snapshotSignal != null && !snapshotSignal.await()) {
			controller.awaitIfPaused()
			return null
		}

		// 1. SENSE — read the latest observation. Treat tick==0 as "not started yet" and skip.
		// Mirrors the AgentLoopDriver SimulationSnapshot.EMPTY guard.
		val obs0 = observations.latest()
		logger.debug { "DispatchTickLoop: tick=${obs0.tick} simTime=${obs0.simTime}" }
		if (obs0.tick == 0L) {
			controller.awaitIfPaused()
			return null
		}

		// 2. AFFORDANCES — pre-evaluate all candidate actions for the WHAT YOU CAN DO NOW section.
		val affordances = annotator.annotate(obs0)

		// 3. RENDER — produce the prompt string. Rule-based emission strategies ignore this.
		val prompt = renderer.render(RenderContext(obs0, prevObs, ring.snapshot(), workingMemory, affordances))

		// 4. DECIDE — call the emission strategy with the budget wrapper.
		// null return = deadline exceeded; substitute a single TIMEOUT_NOOP.
		// empty list = strategy ran and decided there is nothing to do this tick; substitute a
		// single NoOp authored by the strategy's own author (RULE_BASED / future LLM) so an idle
		// tick records as a deliberate decision, not a degraded timeout.
		// Exception = LLM infrastructure failure. See [runEmission] for the reconciliation
		// (swallow-and-engage when a fallback is configured, rethrow otherwise so the failure is
		// diagnosable by the caller).
		//
		// F2 real-time ratio: measure wall-clock time of the whole emission step (primary attempt
		// + any fallback engage) and log the ratio (simDelta / emissionWallClockSeconds). Wrapping
		// [runEmission] — not the bare `budget.withBudget { emission.emit(...) }` — means the
		// ratio reflects the full pacing latency unit, including fallback work. For the LLM arm
		// this is reported but NOT used to gate the run. Rule-based arm is intrinsically ≥ 1×
		// (synchronous).
		val emissionStartNanos = System.nanoTime()
		val emittedRaw: List<AttributedAction>? = runEmission(prompt, obs0)
		val emissionNanos = System.nanoTime() - emissionStartNanos
		val emitted =
			when {
				emittedRaw == null ->
					listOf(
						noOpAction(
							tick = obs0.tick,
							author = ActionAuthor.TIMEOUT_NOOP,
							reason = "deadline exceeded — budget exhausted, NoOp substituted"
						)
					)
				emittedRaw.isEmpty() ->
					listOf(
						noOpAction(
							tick = obs0.tick,
							author = currentEmission.author,
							reason = "no action warranted this tick"
						)
					)
				else ->
					emittedRaw.map { it.copy(commandId = CommandId(commandIdCounter.incrementAndGet())) }
			}

		// 5. VALIDATE & ACT — validate each action against the current (optionally projected) observation.
		// For C6: after each valid non-NoOp action the observation is updated optimistically so the
		// NEXT action is validated against the expected post-action state rather than the stale pre-tick
		// snapshot. Inaccuracy is bounded to one tick and surfaces in applied_outcomes next tick.
		var currentObs = obs0
		val tickActions = mutableListOf<AttributedAction>()
		val tickVerdicts = mutableListOf<ValidationVerdict>()

		for (attributedAction in emitted.take(maxActionsPerTick)) {
			val verdict = validator.validate(attributedAction.action, currentObs)
			tickActions.add(attributedAction)
			tickVerdicts.add(verdict)

			if (verdict is ValidationVerdict.Rejected) {
				logger.debug {
					"DispatchTickLoop: action ${attributedAction.action.kind} rejected " +
						"(${verdict.code}): ${verdict.detail}"
				}
				continue
			}

			// Valid action: convert to DispatchDecision and post.
			val decisions = toDispatchDecisions(attributedAction.action)
			if (decisions.isNotEmpty()) {
				// Pass author/reason to postAll so the correlation map can
				// attribute the apply-time outcome back to the originating decision-maker.
				val posted = queue.postAll(decisions, attributedAction.author, attributedAction.reason)
				if (!posted) {
					logger.warn {
						"DispatchTickLoop: queue rejected ${decisions.size} decision(s) for " +
							"action ${attributedAction.action.kind} (backpressure limit reached)"
					}
				}
			}

			// Optimistic in-loop projection for C6: update currentObs to the expected state AFTER
			// this action, so the next action is validated against it rather than the stale obs0.
			// Inaccuracy is bounded to one tick: the real sim-thread outcome arrives in
			// applied_outcomes on the NEXT tick, closing the loop observably.
			currentObs = applyOptimistically(currentObs, attributedAction.action)
		}

		// 6. RECORD — build TickRecord; push to ring buffer; update working memory; observe with guard.
		// Outcomes for this tick are the applied outcomes already embedded in obs0 by the projector.
		// For the P10 gate where no outcome channel is wired, this is always empty.
		val outcomes = obs0.appliedOutcomes
		val record =
			TickRecord(
				tick = obs0.tick,
				simTime = obs0.simTime,
				stateDigest = obs0.digest(),
				actions = tickActions,
				verdicts = tickVerdicts,
				outcomes = outcomes
			)

		ring.push(record)
		workingMemory = workingMemory.update(record, obs0)
		fallbackGuard.observe(record)
		preferenceStore?.observe(record)

		logger.debug {
			"DispatchTickLoop: tick=${obs0.tick} actions=${tickActions.size} outcome=$runOutcome"
		}

		// 7. PACE — honour pause/step requests, then throttle wall-clock time.
		// Mirrors AgentLoopDriver.runCycle steps 4.
		controller.awaitIfPaused()
		val simDelta = obs0.simTime - prevSimTime
		controller.throttle(simDelta)

		// F2 real-time ratio reporting:
		// simDelta is the simulation time elapsed since the previous tick.
		// emissionNanos is the wall-clock time the emission strategy took.
		// ratio = simDelta / emissionWallClockSeconds; < 1.0 means the emission was slower
		// than the sim tick period (LLM falling behind). Reported at debug level, UNGATED —
		// it does not fail the run. The rule-based arm is intrinsically ≥ 1× (synchronous).
		// Pairing note: simDelta (prev→current tick sim advance) is paired with THIS tick's
		// emissionNanos, so the ratio describes the tick just emitted — it is not a concurrent
		// measurement of emission wall-clock against an advancing sim clock (F1 freezes the clock
		// during emission anyway, so a concurrent measurement would always be 0 / undefined).
		if (emissionNanos > 0L && simDelta > 0.0) {
			val emissionWallClockSeconds = emissionNanos.toDouble() / NANOS_PER_SECOND
			val realTimeRatio = simDelta / emissionWallClockSeconds
			logger.debug {
				"DispatchTickLoop: tick=${obs0.tick} real-time ratio=%.3f (simDelta=%.3f s, emission=%.3f s)"
					.format(realTimeRatio, simDelta, emissionWallClockSeconds)
			}
		}

		prevSimTime = obs0.simTime
		prevObs = obs0

		return record
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	/**
	 * Builds a single-element substitute [DispatchAction.NoOp] with a fresh [CommandId],
	 * used by the deadline-exceeded and nothing-warranted short-circuits in step 4 (DECIDE).
	 */
	private fun noOpAction(
		tick: Long,
		author: ActionAuthor,
		reason: String
	): AttributedAction =
		AttributedAction(
			commandId = CommandId(commandIdCounter.incrementAndGet()),
			tick = tick,
			action = DispatchAction.NoOp,
			author = author,
			reason = reason
		)

	/**
	 * Invokes [currentEmission.emit] under [budget].
	 *
	 * - When [fallbackEmission] is configured: an unhandled exception engages [fallbackGuard]
	 *   immediately and returns `null` so the tick substitutes a TIMEOUT_NOOP and subsequent ticks
	 *   run via [fallbackEmission] — the run reaches a terminating state for inspection (terminal
	 *   fallback with run-failure marking).
	 * - When [fallbackEmission] is `null`: rethrows — the run cannot continue advancing, and
	 *   surfacing the exception is the only way to make the failure diagnosable by the caller
	 *   (paused-clock propagation invariant).
	 *
	 * [kotlinx.coroutines.CancellationException] is always rethrown for cooperative cancellation.
	 */
	private suspend fun runEmission(
		prompt: String,
		obs0: DispatcherObservation
	): List<AttributedAction>? =
		try {
			budget.withBudget { currentEmission.emit(prompt, obs0) }
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Exception) {
			if (fallbackEmission == null) {
				throw e
			}
			logger.error(e) {
				"DispatchTickLoop: unhandled exception from emission.emit at tick=${obs0.tick}; " +
					"engaging terminal fallback immediately"
			}
			fallbackGuard.engageImmediately()
			null
		}

	/**
	 * Converts one validated [DispatchAction] into a list of [DispatchDecision]s to post.
	 *
	 * [DispatchAction.NoOp] produces an empty list (nothing to post).
	 */
	private fun toDispatchDecisions(action: DispatchAction): List<DispatchDecision> =
		when (action) {
			is DispatchAction.ApproveTrain ->
				listOf(DispatchDecision.ApproveTrain(trainId = action.trainId))

			is DispatchAction.RequestRoute ->
				listOf(
					DispatchDecision.RequestRoute(
						trainName = action.trainId,
						fromEndpointName = action.fromEndpointName,
						toEndpointName = action.toEndpointName
					)
				)

			is DispatchAction.CancelRoute ->
				listOf(DispatchDecision.ReleaseRoute(trainName = action.trainId))

			is DispatchAction.NoOp -> emptyList()
		}

	/**
	 * Produces the expected [DispatcherObservation] state **after** [action] is applied
	 * (optimistic in-loop projection for C6 re-validation).
	 *
	 * This is a **model**, not truth: the sim thread may produce a different outcome (e.g. a
	 * route reservation conflict), but the divergence is bounded to one tick and surfaces in
	 * [DispatcherObservation.appliedOutcomes] on the next tick. The function is intentionally
	 * conservative — it only projects the fields that the [ActionValidator] checks:
	 *
	 * - [DispatchAction.ApproveTrain]: increments [DispatcherObservation.activeCount], removes
	 *   the train from [DispatcherObservation.queued], moves its [TrainPhase] to [TrainPhase.RUNNING].
	 * - [DispatchAction.RequestRoute]: adds a [ReservationView] with empty [ReservationView.blockIds]
	 *   if none exists yet (guards [RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET] /
	 *   [RejectionCode.ROUTE_HELD_TO_DIFFERENT_TARGET]).
	 * - [DispatchAction.CancelRoute]: removes the train's [ReservationView].
	 * - [DispatchAction.NoOp]: no change.
	 *
	 * Non-modelled fields (block states, signal aspects, switch positions) are left unchanged —
	 * a second action that depends on those would be validated as if they haven't changed, which
	 * is safe given the per-tick action limit and the validator's observation-only checks.
	 */
	private fun applyOptimistically(
		obs: DispatcherObservation,
		action: DispatchAction
	): DispatcherObservation =
		when (action) {
			is DispatchAction.ApproveTrain ->
				obs.copy(
					activeCount = obs.activeCount + 1,
					queued = obs.queued.filter { it.trainId != action.trainId },
					trains =
						obs.trains.map { tv ->
							if (tv.trainId == action.trainId) tv.copy(phase = TrainPhase.RUNNING) else tv
						}
				)

			is DispatchAction.RequestRoute -> {
				// Only add a reservation if the train doesn't already hold one.
				// The validator rejects duplicate-target requests, but be conservative here.
				if (obs.reservations.any { it.trainId == action.trainId }) {
					obs
				} else {
					obs.copy(
						reservations =
							(
								obs.reservations +
									ReservationView(
										trainId = action.trainId,
										fromEndpointName = action.fromEndpointName,
										targetName = action.toEndpointName,
										blockIds = emptyList()
									)
							).sortedBy { it.trainId }
					)
				}
			}

			is DispatchAction.CancelRoute ->
				obs.copy(reservations = obs.reservations.filter { it.trainId != action.trainId })

			is DispatchAction.NoOp -> obs
		}
}
