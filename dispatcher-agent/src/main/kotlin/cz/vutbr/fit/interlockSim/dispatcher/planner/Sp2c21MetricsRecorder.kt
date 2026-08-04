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

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SP2c.21 metrics recorder: latency percentiles, `valid@1`, `correct@1`, and shadow-oracle
 * `oracleAgreementAt1` (Issue #844).
 *
 * ## Usage
 *
 * Wrap a [KoogAgentPlanAdapter] (or any [DispatcherPlanner]) with this recorder to collect
 * per-tick latency and correctness metrics.  Simultaneously implement [ActionOutcomeSink] and
 * wire it into [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] so apply-time
 * failures are correlated back to the originating tick.
 *
 * ```kotlin
 * val recorder = Sp2c21MetricsRecorder(inner = koogAdapter, oracle = ruleBasedDispatcher)
 * // Use recorder as DispatcherPlanner:
 * val driver = AgentLoopDriver(planner = recorder, ...)
 * // Wire apply-time outcomes:
 * val applier = DispatchDecisionApplier(..., outcomeSink = recorder)
 * // At run end:
 * recorder.signalRunCompleted(naturalCompletion = true)
 * val snapshot = recorder.getMetricsSnapshot()
 * ```
 *
 * ## Shadow oracle
 *
 * For each LLM tick [plan] calls `oracle.decide(observation)` on the driver thread **after** the
 * LLM result is known, compares normalised action kinds order-insensitively, and **discards**
 * the oracle result — it is never posted to the actuator queue.  This is a reproducibility
 * guard, not a gate.
 *
 * ## Metrics
 *
 * - **`valid@1`** — fraction of ticks where the first LLM output was valid (passed validator).
 *   A tick is counted valid when its [TickOutcome] is a success outcome
 *   ([TickOutcome.countsAsLlmSuccess]).
 * - **`correct@1`** — fraction of *valid* ticks where no applied action failed with a
 *   non-`ALL_PATHS_BLOCKED` [ApplyFailureCode] AND the run reached natural completion.
 *   See [Sp2c21MetricsSnapshot.correctAt1] for the full honesty caveat.
 * - **`oracleAgreementAt1`** — fraction of oracle-callable ticks where the normalised LLM
 *   action set equalled the oracle's action set.  Diagnostic colour only, never a gate.
 *
 * ## Latency
 *
 * Wall-clock via [System.nanoTime], from immediately before the inner [plan] call to
 * immediately after it returns.  [Sp2c21MetricsSnapshot.latencyP50Ms],
 * [Sp2c21MetricsSnapshot.latencyP95Ms], and [Sp2c21MetricsSnapshot.latencyMaxMs] are
 * computed over all samples without reservoir sampling (tick counts are in the tens-to-hundreds).
 *
 * ## Thread safety
 *
 * Per-tick counters use [AtomicLong].  The latency sample list is append-only under
 * `@Synchronized` on [recordLatency].  [onActionOutcome] may be called from the kDisco sim
 * thread concurrently with [plan] on the driver thread; the per-tick failure set uses
 * [ConcurrentHashMap] for safe cross-thread access.
 *
 * @param inner The [DispatcherPlanner] to delegate [plan] calls to (typically
 *   [KoogAgentPlanAdapter] or [MeasuringPlanAdapter]).
 * @param oracle The synchronous oracle dispatcher ([cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher])
 *   used for the shadow comparison.  Its [Dispatcher.decide] result is always discarded.
 *
 * @see Sp2c21MetricsSnapshot
 * @see OracleVerdict
 * @see OracleComparison
 * @since Issue #844 (SP2c.21 — Goal 10 latency percentiles + valid@1 / correct@1 + shadow oracle)
 */
class Sp2c21MetricsRecorder(
	private val inner: DispatcherPlanner,
	private val oracle: Dispatcher
) : DispatcherPlanner by inner,
	ActionOutcomeSink {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Normalises a [DispatchDecision] to a canonical string key for order-insensitive
		 * comparison between the LLM and oracle outputs.
		 *
		 * Format: `kind|trainId|from|to` where absent fields are empty strings.
		 * [DispatchDecision.rationale] is intentionally excluded — it differs by construction
		 * between the LLM and rule-based oracle and must never influence the comparison.
		 */
		fun normaliseDecision(decision: DispatchDecision): String =
			when (decision) {
				is DispatchDecision.ApproveTrain -> "ApproveTrain|${decision.trainId}||"
				is DispatchDecision.ReservePath ->
					"ReservePath|${decision.trainId}|${decision.fromSemaphoreName}|${decision.toSeparatorName}"
				is DispatchDecision.NoAction -> "NoAction|||"
				is DispatchDecision.HoldTrain -> "HoldTrain|${decision.trainId}||"
				is DispatchDecision.SetSignalAspect -> "SetSignalAspect||${decision.semaphoreName}|"
				is DispatchDecision.SetSwitchPosition -> "SetSwitchPosition||${decision.switchName}|"
				is DispatchDecision.ReleaseRoute -> "ReleaseRoute|${decision.trainName}||"
				is DispatchDecision.RequestRoute ->
					"RequestRoute|${decision.trainName}|${decision.fromEndpointName}|${decision.toEndpointName}"
			}
	}

	// ── Atomic counters ────────────────────────────────────────────────────────

	private val totalTicks = AtomicLong(0L)
	private val validTicks = AtomicLong(0L)
	private val oracleAgreeTicks = AtomicLong(0L)
	private val oracleCallableTicks = AtomicLong(0L)

	/** Whether the run completed naturally (all trains exited, no terminal failure). */
	private val runCompletedNaturally = AtomicReference<Boolean?>(null)

	// ── Per-tick failure tracking ──────────────────────────────────────────────

	/**
	 * Set of tick indices that had at least one [ActionPhase.APPLIED_THEN_FAILED] outcome
	 * with a non-[ApplyFailureCode.ALL_PATHS_BLOCKED] failure code.
	 *
	 * Populated on the sim thread by [onActionOutcome]; read on the driver/reporting thread
	 * by [getMetricsSnapshot].  Uses [ConcurrentHashMap.newKeySet] for safe concurrent access.
	 */
	private val ticksWithHardFailure: MutableSet<Long> = ConcurrentHashMap.newKeySet()

	/**
	 * Set of tick indices that were valid (LLM success outcome).  Used to compute
	 * [Sp2c21MetricsSnapshot.correctTickCount] as the intersection of valid ticks and
	 * ticks without hard failures.
	 */
	private val validTickIndices: MutableSet<Long> = ConcurrentHashMap.newKeySet()

	// ── Latency samples ────────────────────────────────────────────────────────

	/** All per-tick first-call latency samples in milliseconds.  Append-only; guarded by this. */
	private val latencySamplesMs: MutableList<Double> = ArrayList()

	@Synchronized
	private fun recordLatency(ms: Double) {
		latencySamplesMs.add(ms)
	}

	@Synchronized
	private fun getLatencyCopy(): List<Double> = ArrayList(latencySamplesMs)

	// ── Correlation: tick index for oracle comparison ──────────────────────────

	/**
	 * Current tick index, incremented by [plan] before each call.  Mirrors
	 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue.advanceCorrelationCycle] which
	 * is called by [KoogAgentPlanAdapter].  This counter is independent — it counts [plan]
	 * invocations on this recorder, not the inner adapter's cycle counter.
	 *
	 * **Invariant the DIVERGES_UNSAFE upgrade depends on:** this counter must stay aligned
	 * with [AuthoredAction.tickIndex] (sourced from the same `advanceCorrelationCycle`). Both
	 * start at 0 and increment exactly once per [plan] call, so `provisionalComparisons` keys
	 * (this counter) match [ticksWithHardFailure] entries (`AuthoredAction.tickIndex`). If the
	 * inner planner ever advanced the queue cycle more than once per [plan] (e.g. a retry
	 * path), the lazy upgrade in [getMetricsSnapshot] would silently stop matching — keep them
	 * in lockstep.
	 */
	private val tickIndex = AtomicLong(0L)

	// ── Per-tick oracle comparison results ────────────────────────────────────

	/**
	 * Map from tick index → provisional oracle comparison produced on the driver thread.
	 * Comparisons that are initially [OracleVerdict.DIVERGES_SAFE] may be upgraded to
	 * [OracleVerdict.DIVERGES_UNSAFE] at snapshot time when [ticksWithHardFailure] overlaps
	 * with the divergent tick set.
	 */
	private val provisionalComparisons: ConcurrentHashMap<Long, OracleComparison> = ConcurrentHashMap()

	// ── DispatcherPlanner implementation ──────────────────────────────────────

	/**
	 * Delegates to [inner] while capturing wall-clock latency and firing the shadow oracle.
	 *
	 * The shadow oracle runs **after** the inner [plan] returns, on the driver thread only.
	 * Its result is always discarded; it is never posted to the actuator queue.
	 *
	 * Oracle comparisons are stored provisionally as [OracleVerdict.AGREES] or
	 * [OracleVerdict.DIVERGES_SAFE]; [OracleVerdict.DIVERGES_UNSAFE] is resolved at snapshot
	 * time by checking whether the tick also had a hard apply failure.
	 */
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> {
		val thisTick = tickIndex.getAndIncrement()

		val startNs = System.nanoTime()
		val decisions = inner.plan(observation)
		val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

		recordLatency(elapsedMs)

		// Shadow oracle call: pure read, result discarded.
		val comparison = runShadowOracle(observation, decisions, thisTick)
		provisionalComparisons[thisTick] = comparison

		if (comparison.verdict != OracleVerdict.ORACLE_UNAVAILABLE) {
			oracleCallableTicks.incrementAndGet()
			if (comparison.verdict == OracleVerdict.AGREES) {
				oracleAgreeTicks.incrementAndGet()
			}
		}

		return decisions
	}

	// ── ActionOutcomeSink implementation ──────────────────────────────────────

	/**
	 * Called on the kDisco simulation thread when an action is applied or fails at apply time.
	 *
	 * Records hard failures (non-[ApplyFailureCode.ALL_PATHS_BLOCKED] apply failures) against
	 * the tick index carried by [outcome.authored.tickIndex][cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction.tickIndex].
	 * Must not log.
	 */
	override fun onActionOutcome(outcome: ActionOutcome) {
		if (outcome.phase == ActionPhase.APPLIED_THEN_FAILED) {
			val code = outcome.applyFailure
			if (code != null && code != ApplyFailureCode.ALL_PATHS_BLOCKED) {
				// Hard failure: CONFLICT or NO_ROUTE_EXISTS or others → this tick is not "correct"
				val ti = outcome.authored.tickIndex
				if (ti >= 0L) {
					ticksWithHardFailure.add(ti)
				}
			}
		}
	}

	// ── Tick validity tracking ────────────────────────────────────────────────

	/**
	 * Called once per tick with the full [TickRecord]; tracks total tick count and validity.
	 *
	 * This method takes the tick index explicitly because it does NOT implement
	 * [PlannerTickListener] (whose `onTick(record)` signature carries no index) — the
	 * [tickIndex]-keyed correlation between tick validity and oracle comparisons requires the
	 * index. The wiring layer bridges the inner planner's single-arg tick listener to this
	 * two-arg call, passing the same index this recorder uses in [plan]
	 * (the [tickIndex] counter, which mirrors
	 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue.advanceCorrelationCycle]).
	 * Callers may also invoke this directly when the inner planner exposes no tick listener.
	 */
	fun onTick(
		record: TickRecord,
		tickIdx: Long
	) {
		totalTicks.incrementAndGet()
		if (record.outcome.countsAsLlmSuccess) {
			validTicks.incrementAndGet()
			validTickIndices.add(tickIdx)
		}
	}

	// ── Run lifecycle ──────────────────────────────────────────────────────────

	/**
	 * Must be called exactly once when the run ends to set the run-completion flag.
	 *
	 * - Pass `true` when all trains exited and no terminal failure was observed
	 *   (natural completion — [cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome.Completed]).
	 * - Pass `false` when the run ended abnormally
	 *   ([cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome.Failed]).
	 *
	 * Until this is called, [Sp2c21MetricsSnapshot.correctAt1] returns `0.0`.
	 */
	fun signalRunCompleted(naturalCompletion: Boolean) {
		runCompletedNaturally.set(naturalCompletion)
	}

	// ── Snapshot ──────────────────────────────────────────────────────────────

	/**
	 * Returns an immutable [Sp2c21MetricsSnapshot] reflecting the current accumulated state.
	 *
	 * [Sp2c21MetricsSnapshot.divergeUnsafeCount] is computed here by checking whether any
	 * provisionally-[OracleVerdict.DIVERGES_SAFE] tick is also in [ticksWithHardFailure].
	 */
	fun getMetricsSnapshot(): Sp2c21MetricsSnapshot {
		val totalT = totalTicks.get()
		val validT = validTicks.get()
		val invalidT = totalT - validT

		// correctTickCount = valid ticks whose index was not recorded as a hard failure
		val hardFailSet = HashSet(ticksWithHardFailure)
		val validSet = HashSet(validTickIndices)
		val correctT = validSet.count { idx -> !hardFailSet.contains(idx) }.toLong()

		// divergeUnsafeCount = ticks that were DIVERGES_SAFE provisionally AND had a hard failure
		val divergeUnsafe =
			provisionalComparisons.entries
				.count { (tickIdx, comparison) ->
					comparison.verdict == OracleVerdict.DIVERGES_SAFE && hardFailSet.contains(tickIdx)
				}.toLong()

		val samples = getLatencyCopy().sorted()

		return Sp2c21MetricsSnapshot(
			validTickCount = validT,
			totalTickCount = totalT,
			invalidTickCount = invalidT,
			correctTickCount = correctT,
			runCompletedNaturally = runCompletedNaturally.get(),
			oracleAgreeTicks = oracleAgreeTicks.get(),
			oracleCallableTicks = oracleCallableTicks.get(),
			divergeUnsafeCount = divergeUnsafe,
			latencyP50Ms = percentile(samples, 50.0),
			latencyP95Ms = percentile(samples, 95.0),
			latencyMaxMs = percentile(samples, 100.0)
		)
	}

	/**
	 * Returns a snapshot of all oracle comparisons with [OracleVerdict.DIVERGES_UNSAFE]
	 * correctly applied (upgraded from [OracleVerdict.DIVERGES_SAFE] when the tick also had
	 * a hard apply failure).
	 *
	 * Not `@Synchronized`: [provisionalComparisons] and [ticksWithHardFailure] are
	 * [ConcurrentHashMap], so iteration is weakly consistent and safe under concurrent
	 * [plan]/[onActionOutcome] writers — which do not lock `this` anyway, so a method-level
	 * `@Synchronized` would give a false impression of a lock-ordering guarantee without
	 * actually providing one. Mirrors [getMetricsSnapshot], which is likewise unsynchronised.
	 */
	fun getOracleComparisons(): List<OracleComparison> = computeFinalComparisons()

	/**
	 * Computes the final oracle comparison list with [OracleVerdict.DIVERGES_UNSAFE] applied.
	 *
	 * A provisionally-[OracleVerdict.DIVERGES_SAFE] comparison is upgraded to
	 * [OracleVerdict.DIVERGES_UNSAFE] when the same tick index also appears in
	 * [ticksWithHardFailure] (i.e. the tick had a `CONFLICT` or `NO_ROUTE_EXISTS` failure).
	 */
	private fun computeFinalComparisons(): List<OracleComparison> {
		val hardFailSet = HashSet(ticksWithHardFailure)
		return provisionalComparisons.entries
			.sortedBy { it.key }
			.map { (tickIdx, comparison) ->
				if (comparison.verdict == OracleVerdict.DIVERGES_SAFE && hardFailSet.contains(tickIdx)) {
					comparison.copy(verdict = OracleVerdict.DIVERGES_UNSAFE)
				} else {
					comparison
				}
			}
	}

	// ── Private helpers ────────────────────────────────────────────────────────

	/**
	 * Calls the shadow oracle on [observation] (pure read, result discarded) and compares
	 * normalised action kinds against [llmDecisions].
	 *
	 * Runs on the driver thread after the LLM result is known; the oracle result is never
	 * posted to the actuator queue.  Any exception from the oracle produces
	 * [OracleVerdict.ORACLE_UNAVAILABLE].
	 */
	private fun runShadowOracle(
		observation: DispatchObservation,
		llmDecisions: List<DispatchDecision>,
		thisTick: Long
	): OracleComparison {
		val llmKinds = llmDecisions.map { normaliseDecision(it) }.toSortedSet().toList()

		return try {
			val oracleDecisions = oracle.decide(observation)
			val oracleKinds = oracleDecisions.map { normaliseDecision(it) }.toSortedSet().toList()

			val llmSet = llmKinds.toSet()
			val oracleSet = oracleKinds.toSet()

			val verdict =
				when {
					llmSet == oracleSet -> OracleVerdict.AGREES
					else -> {
						// Check if divergence is unsafe: did any action that's in LLM but not oracle fail?
						// (We don't yet have apply-time outcome for this tick — that arrives later on the
						// sim thread.  We mark DIVERGES_SAFE provisionally and upgrade to DIVERGES_UNSAFE
						// in a finalisation step if the tick is later recorded as having CONFLICT/NO_ROUTE.)
						// For the initial comparison, record DIVERGES_SAFE; upgradeToUnsafeIfNeeded will
						// be checked at snapshot time based on ticksWithHardFailure.
						OracleVerdict.DIVERGES_SAFE
					}
				}

			OracleComparison(
				verdict = verdict,
				oracleActionKinds = oracleKinds,
				llmActionKinds = llmKinds
			)
		} catch (e: Exception) {
			logger.warn(e) { "[Sp2c21MetricsRecorder] shadow oracle threw at tick $thisTick — oracle unavailable" }
			OracleComparison(
				verdict = OracleVerdict.ORACLE_UNAVAILABLE,
				oracleActionKinds = emptyList(),
				llmActionKinds = llmKinds
			)
		}
	}

	/**
	 * Computes the p-th percentile of [sortedSamples] (0–100).
	 * Returns [Double.NaN] when the list is empty.
	 */
	private fun percentile(
		sortedSamples: List<Double>,
		p: Double
	): Double {
		if (sortedSamples.isEmpty()) return Double.NaN
		if (sortedSamples.size == 1) return sortedSamples[0]
		val index = (p / 100.0 * (sortedSamples.size - 1)).coerceIn(0.0, (sortedSamples.size - 1).toDouble())
		val lower = index.toInt()
		val upper = (lower + 1).coerceAtMost(sortedSamples.size - 1)
		val fraction = index - lower
		return sortedSamples[lower] + fraction * (sortedSamples[upper] - sortedSamples[lower])
	}
}
