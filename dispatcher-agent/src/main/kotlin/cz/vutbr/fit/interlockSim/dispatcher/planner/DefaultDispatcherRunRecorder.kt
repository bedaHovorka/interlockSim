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
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.executor.ModelProfileRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Default production [DispatcherRunRecorder] implementation.
 *
 * ## Run boundary
 *
 * One instance == one [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext] lifetime.
 * The Koin `scoped` binding in [cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule]
 * ensures a fresh instance per context, so no `reset()` method is needed.
 *
 * - **Start**: the first [onTick] call.
 * - **End**: [finish] — idempotent; subsequent calls return the frozen snapshot.
 *
 * ## In-flight tick caveat
 *
 * A tick still in flight at the moment [finish] is called is **not** counted. The recorder
 * reports state at the moment of the call, not a strict post-mortem of every tick ever started.
 * This is the documented in-flight caveat preserved from [MeasuringPlanAdapter.logFinalSummary].
 *
 * ## Thread safety
 *
 * [onTick] is called on the dispatcher-agent-driver thread; [onActionOutcome] on the kDisco
 * simulation thread. All counters use [AtomicLong] or [ConcurrentHashMap] with pre-populated
 * [AtomicLong] values, and latency samples use a [java.util.concurrent.ConcurrentLinkedQueue].
 * [snapshot] and [finish] may be called from any thread. The skipped NEXT SECTION offer group is
 * the one counter set [snapshot] reads under a lock ([offerStateLock]): its sums, its measured
 * flag, its failure counter and its streaks move together, so a snapshot copies the whole group
 * atomically and cannot observe one member updated and the others lagging (PR #1408 review round).
 *
 * ## Latency percentiles (Issue #834, SP2c.11)
 *
 * [onTick] appends [TickRecord.latencyMs] (when non-`null`) to an unbounded sample queue;
 * [buildSnapshot] takes a point-in-time copy and derives [DispatcherRunSnapshot.latencyP50Ms],
 * [DispatcherRunSnapshot.latencyP95Ms] and [DispatcherRunSnapshot.latencyMaxMs] from it using
 * [nearestRankPercentile] (nearest-rank convention — see its KDoc). An empty sample set (no
 * ticks carried a latency, e.g. an all-rule-based run) yields `null` for all three fields — the
 * same "absent is not zero" convention [RailwayOutcome] uses: `null` means *not measured*,
 * never *measured as none*. A `0` would misread as "the model answered in 0 ms".
 *
 * ## Railway outcomes (Issue #834, SP2c.11)
 *
 * [recordRailwayOutcome] takes the run's [RailwayOutcome] whole from a caller that can see the
 * simulation context — the recorder itself observes only the dispatcher. Until it is called (and
 * for runs where nobody calls it at all), the snapshot reports [RailwayOutcome.UNMEASURED], whose
 * every figure is `null`. That is deliberate: `null` means *not measured* and `0` means *measured
 * as none*, and a sweep that cannot tell them apart cannot rank on either.
 *
 * ## Stale-origin refusals after a silent non-idle cycle (Issue #1365)
 *
 * [onTick] remembers the correlation tick index of every silent LLM cycle on a non-idle station
 * ([TickRecord.silentNonIdleCycleIndex]); [onActionOutcome] counts an
 * [ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS] apply failure into
 * [DispatcherRunSnapshot.staleOriginRefusalsAfterSilentCycle] when its decision carries one of those
 * indices ([AuthoredAction.tickIndex]) — that is, when the refused decision was **posted by the
 * silent cycle itself**. Those are the rule-based fallback's decisions computed on the kept cycle
 * observation, so their refusals are the ones attributable to it; a refusal of a later cycle's
 * decision is not counted. The index is reported inside `plan()`, before `AgentLoopDriver` posts the
 * cycle's decisions, so the queue's post/drain hand-off orders it before the outcome it attributes.
 *
 * ## Skipped NEXT SECTION offers (Issue #1302)
 *
 * [onTick] sums [TickRecord.nextSectionOffers] — present only on the LLM cycle endings that skip
 * the rule-based fallback — into [DispatcherRunSnapshot.skippedNextSectionOffers],
 * [DispatcherRunSnapshot.offeredNextSectionHops] and
 * [DispatcherRunSnapshot.skippedNextSectionOffersStandingAtSignal]. It also keeps one streak per
 * train: consecutive ticks whose count names the train in
 * [NextSectionOfferCount.skippedTrainIds]. Any other tick ends the train's streak — one that
 * counts offers without skipping it, and one without a count at all (the fallback ran that cycle,
 * so the train was not left behind by the acted-cycle guard). The longest streak, in cycles and
 * in simulation seconds (last minus first [TickRecord.simTime] of the streak), separates one train
 * skipped for many cycles at a STOP signal — the #895 stall shape F8 names — from many trains
 * skipped once each, which the totals cannot.
 *
 * Measured or not (PR #1408 review round): an LLM run is measured unless a count failed — a run
 * whose cycles all fell back skipped nothing and measures `0`, but a run whose count attempt
 * threw ([TickRecord.nextSectionOffersFailed]) publishes the whole five-figure group absent, and
 * later successful counts do not rehabilitate it because the rule is run-level
 * (`offerCountFailures == 0`). Only a rule-based run with no count reports `null`; `null` still
 * means *not measured*, never *measured as none*. The whole group — sums, measured flag, failure
 * counter and streaks — moves under one lock ([offerStateLock]), so snapshots and the frozen
 * [finish] copy it atomically: no observer ever sees offered hops without their skipped offers,
 * or the flag flipped while the sums lag.
 *
 * ## Cut generations (Issue #1342)
 *
 * [onTick] counts every tick whose [TickRecord.generationCut] is set into
 * [DispatcherRunSnapshot.generationCutCycles] — the cycles of the run whose reply a `num_predict`
 * cut hit, whatever their recorded outcome. The no-emission cut ending is already visible as
 * `timeoutNoOpByCause[THINKING_TRUNCATED]`; the counter is the run JSON's structured trace for the
 * mixed cycles the emission seal leaves behind: a command from an earlier complete reply classified
 * their tick (as for any partial emission), and the cut still shows here. A counter, not a verdict:
 * `0` means every run with the field wrote after the cut detection ran and saw none (the
 * rule-based arm makes no LLM calls at all); `null` means not measured — a run JSON written before
 * schema 16, or a synthetic `TIMEOUT_ABORT` / `CRASH` snapshot whose child JVM never reported it.
 *
 * @param runId Opaque unique identifier (typically a UUID or `yyyyMMdd-HHmmss-<short-uuid>`).
 * @param arm Which dispatcher implementation arm is active.
 * @param params Fixed run parameters captured at run start.
 * @param modelProfile The LLM run's model profile read at run start (Issue #1342), copied into every
 *   snapshot as [DispatcherRunSnapshot.modelProfile]; `null` for a run with no LLM.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DefaultDispatcherRunRecorder(
	override val runId: String,
	private val arm: DispatcherArm,
	private val params: RunParameters,
	private val circuitBreaker: LlmCircuitBreaker? = null,
	private val modelProfile: ModelProfileRecord? = null
) : DispatcherRunRecorder {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	// ── Tick counters ────────────────────────────────────────────────────────

	// totalTicks is deliberately NOT a separate counter: onTick updates totalTicks and the
	// per-bucket counter in two atomic steps, so buildSnapshot reading both could observe a
	// tick in flight and trip the DispatcherRunSnapshot.init invariant (sum == total). The
	// snapshot's total is derived from the outcome-bucket sum in buildSnapshot instead.

	private val ticksByOutcome: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(TickOutcome.entries) { it.name }

	private val timeoutNoOpByCause: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(TimeoutNoOpCause.entries) { it.name }

	// Issue #1342 (owner ruling of 2026-11-27): every cycle whose reply was cut, whatever the
	// outcome its emissions classified — the run JSON's structured trace of the mixed cycles, which
	// the THINKING_TRUNCATED cause of timeoutNoOpByCause does not reach (see generationCutCycles).
	private val generationCutCycles = AtomicLong(0L)

	// ── Latency tracking ─────────────────────────────────────────────────────

	// One entry per TickRecord.latencyMs that was non-null (Issue #834, SP2c.11). A tick with no
	// meaningful latency (e.g. a construction site that predates the field) contributes nothing,
	// so the sample set can legitimately stay smaller than totalTicks. ConcurrentLinkedQueue
	// supports the same concurrent-add-while-iterating pattern as the AtomicLong/ConcurrentHashMap
	// fields above: onTick appends from the driver coroutine while buildSnapshot (invoked from
	// snapshot()/finish(), callable from any thread) takes an immutable point-in-time copy via
	// toList() before computing percentiles.
	private val latencySamplesMs: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue()

	// ── Action-outcome counters ──────────────────────────────────────────────

	private val emittedByActionType: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

	private val rejectionsByCode: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(RejectionCode.entries) { it.name }

	private val applyFailuresByCode: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(ApplyFailureCode.entries) { it.name }

	private val actionsByAuthor: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(ActionAuthor.entries) { it.name }

	private val unattributedApplies = AtomicLong(0L)

	// Issue #1476: the counted automatics without an action path, every row present.
	private val countedAutomatics: ConcurrentHashMap<String, AtomicLong> =
		concurrentEnumCounters(CountedAutomatic.entries) { it.name }

	// Issue #1476: null until recorded; the snapshot then derives the source from the arm and model.
	private val commandSource = AtomicReference<CommandSource?>(null)

	// Correlation tick indices of the silent non-idle cycles (Issue #1365): written on the driver
	// thread by onTick, read on the sim thread by onActionOutcome. One entry per such cycle.
	private val silentNonIdleCycleIndices: MutableSet<Long> = ConcurrentHashMap.newKeySet()

	private val staleOriginRefusalsAfterSilentCycle = AtomicLong(0L)

	// Issue #1302: sums of TickRecord.nextSectionOffers; nextSectionOffersMeasured tells a measured
	// zero from "no tick carried a count" (absent is not zero), and offerCountFailures counts the
	// count attempts that threw (TickRecord.nextSectionOffersFailed) — one failure makes the whole
	// offer group unmeasured (PR #1408 review round). The sums, the flag, the failure counter and
	// the streak state below share ONE lock, so buildSnapshot copies the whole group atomically and
	// can never observe a torn group (one sum updated, the others not).
	private val skippedNextSectionOffers = AtomicLong(0L)

	private val offeredNextSectionHops = AtomicLong(0L)

	private val skippedStandingAtSignal = AtomicLong(0L)

	private val offerCountFailures = AtomicLong(0L)

	@Volatile
	private var nextSectionOffersMeasured = false

	// onTick runs on the driver thread and buildSnapshot/snapshot/finish on any thread, so the whole
	// offer group above (and the per-train skip streaks of Issue #1302) is guarded by one lock —
	// the PR #1408 review round widened the former streak-only lock to the whole group.
	private val offerStateLock = Any()

	private val openStreaks = mutableMapOf<String, SkipStreak>()

	private var longestStreakCycles = 0L

	private var longestStreakSimSeconds = 0.0

	/** One train's run of consecutive skipped-offer ticks: how many, and the first and last sim time. */
	private data class SkipStreak(
		val cycles: Long,
		val firstSimTime: Double,
		val lastSimTime: Double
	)

	/** Point-in-time copy of the whole offer state group, taken under [offerStateLock]. */
	private data class OfferState(
		val measuredByCount: Boolean,
		val skippedSum: Long,
		val offeredSum: Long,
		val standingSum: Long,
		val countFailures: Long,
		val streakCycles: Long,
		val streakSimSeconds: Double
	)

	// ── Railway outcome (Issue #834, SP2c.11) ────────────────────────────────

	// Not an accumulator: the recorder has no view of the railway, so the figures arrive whole
	// from whoever holds the simulation context (DispatcherRunSummaries on the live path) and the
	// last writer wins. Starts at RailwayOutcome.UNMEASURED — all-null — so a run nobody measured
	// reports every figure as absent rather than as a zero that would read as a measurement.
	private val railwayOutcome = AtomicReference(RailwayOutcome.UNMEASURED)

	// ── Terminal state ───────────────────────────────────────────────────────

	/** Frozen terminal snapshot; null while the run is still in progress. */
	private val frozenSnapshot = AtomicReference<DispatcherRunSnapshot?>(null)

	// ── DispatcherRunRecorder implementation ─────────────────────────────────

	override fun onTick(record: TickRecord) {
		ticksByOutcome.getValue(record.outcome.name).incrementAndGet()
		record.timeoutNoOpCause?.let { cause -> timeoutNoOpByCause.getValue(cause.name).incrementAndGet() }
		if (record.generationCut) generationCutCycles.incrementAndGet()
		record.latencyMs?.let { latencySamplesMs.add(it) }
		record.silentNonIdleCycleIndex?.let { silentNonIdleCycleIndices.add(it) }
		updateOfferState(record)
	}

	/**
	 * Applies [record]'s whole NEXT SECTION offer state inside one [offerStateLock] critical
	 * section — the three sums and the measured flag (Issue #1302), the failure count of a count
	 * attempt that threw ([TickRecord.nextSectionOffersFailed], PR #1408 review round), and the
	 * per-train skip streaks — so a snapshot concurrent with this tick sees either the tick's
	 * whole effect on the group or none of it. See the class KDoc, Issue #1302.
	 */
	private fun updateOfferState(record: TickRecord) {
		synchronized(offerStateLock) {
			record.nextSectionOffers?.let { count ->
				skippedNextSectionOffers.addAndGet(count.skipped.toLong())
				offeredNextSectionHops.addAndGet(count.offered.toLong())
				skippedStandingAtSignal.addAndGet(count.skippedStandingAtSignal.toLong())
				nextSectionOffersMeasured = true
			}
			if (record.nextSectionOffersFailed) offerCountFailures.incrementAndGet()
			// Extends the streak of every train [record] names as skipped and ends every other open
			// streak — also on a tick with no count (the fallback ran). See the class KDoc, Issue
			// #1302.
			val skipped = record.nextSectionOffers?.skippedTrainIds ?: emptySet()
			openStreaks.keys.retainAll(skipped)
			for (trainId in skipped) {
				val previous = openStreaks[trainId]
				val streak =
					previous?.copy(cycles = previous.cycles + 1, lastSimTime = record.simTime)
						?: SkipStreak(cycles = 1L, firstSimTime = record.simTime, lastSimTime = record.simTime)
				openStreaks[trainId] = streak
				longestStreakCycles = maxOf(longestStreakCycles, streak.cycles)
				longestStreakSimSeconds = maxOf(longestStreakSimSeconds, streak.lastSimTime - streak.firstSimTime)
			}
		}
	}

	override fun onActionOutcome(outcome: ActionOutcome) {
		// Emitted count per action type (all phases)
		emittedByActionType
			.getOrPut(outcome.authored.decisionKind) { AtomicLong(0L) }
			.incrementAndGet()

		// Rejection counts
		outcome.rejection?.let { code -> rejectionsByCode.getValue(code.name).incrementAndGet() }

		// Apply-failure counts
		outcome.applyFailure?.let { code -> applyFailuresByCode.getValue(code.name).incrementAndGet() }

		// Author counts
		actionsByAuthor.getValue(outcome.authored.author.name).incrementAndGet()

		// Unattributed applies: tickIndex == -1 means the correlation map had no entry
		if (outcome.authored.tickIndex == -1L) {
			unattributedApplies.incrementAndGet()
		}

		// Issue #1365: a non-contiguous-origin refusal of a decision the silent cycle posted
		if (outcome.applyFailure == ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS &&
			outcome.authored.tickIndex in silentNonIdleCycleIndices
		) {
			staleOriginRefusalsAfterSilentCycle.incrementAndGet()
		}
	}

	override fun recordRailwayOutcome(outcome: RailwayOutcome) {
		railwayOutcome.set(outcome)
	}

	override fun onCountedAutomatics(counts: Map<CountedAutomatic, Long>) {
		counts.forEach { (automatic, count) -> countedAutomatics.getValue(automatic.name).addAndGet(count) }
	}

	override fun recordCommandSource(source: CommandSource) {
		commandSource.set(source)
	}

	override fun snapshot(): DispatcherRunSnapshot {
		// Return the frozen terminal snapshot when the run has ended.
		frozenSnapshot.get()?.let { return it }
		return buildSnapshot(endCause = null)
	}

	override fun finish(cause: RunEndCause): DispatcherRunSnapshot {
		// Idempotent: only the first call freezes; subsequent calls return the frozen snapshot.
		val existing = frozenSnapshot.get()
		if (existing != null) return existing

		val terminal = buildSnapshot(endCause = cause)
		// compareAndSet ensures only one winner; loser reads the winner's value.
		return if (frozenSnapshot.compareAndSet(null, terminal)) {
			terminal
		} else {
			frozenSnapshot.get()!!
		}
	}

	override fun logFinalSummary() {
		val snap = snapshot()
		val railway = snap.railwayOutcome
		logger.info {
			"[DispatcherRunRecorder] final summary runId=${snap.runId} arm=${snap.arm.name} " +
				"totalTicks=${snap.totalTicks} " +
				"llmSuccessRate=${formatRate(snap.llmSuccessRate)} " +
				"actionableTickRate=${formatRate(snap.actionableTickRate)} " +
				"noOpRate=${formatRate(snap.noOpRate)} " +
				"invalidOutputRate=${formatRate(snap.invalidOutputRate)} " +
				"c7Clean=${snap.c7Clean} " +
				"terminalFallbackEngaged=${snap.terminalFallbackEngaged} " +
				"endCause=${snap.endCause} " +
				// Issue #834 (SP2c.11): what the railway achieved, so a single run is readable
				// without opening the JSON. `n/a` marks a figure nobody measured — never 0.
				"journeysCompleted=${formatFigure(railway.journeysCompleted)} " +
				"trainsEntered=${formatFigure(railway.trainsEntered)} " +
				"trainsExited=${formatFigure(railway.trainsExited)} " +
				"maxConcurrentTrains=${formatFigure(railway.maxConcurrentTrains)} " +
				"blockTransitions=${formatFigure(railway.blockTransitions)} " +
				"conflicts=${formatFigure(railway.conflicts)} " +
				"failedReservations=${formatFigure(railway.failedReservations)} " +
				// Issue #1280: contentions still open at run end, apart from the mid-run `conflicts`.
				"unresolvedAtRunEnd=${formatFigure(railway.unresolvedAtRunEnd)} " +
				// Issue #1074: the breaker figures, so one run is readable without opening the
				// JSON — same reasoning as the railway figures above (#834).
				"circuitBreakerState=${snap.circuitBreakerState ?: "n/a"} " +
				"circuitBreakerTotalSkips=${formatFigure(snap.circuitBreakerTotalSkips)} " +
				"circuitBreakerOpenCount=${formatFigure(snap.circuitBreakerOpenCount)} " +
				// Issue #1302: offered NEXT SECTION hops the LLM left without an accepted request
				// on cycles that skipped the fallback; `n/a` when no such cycle was counted.
				"skippedNextSectionOffers=${formatFigure(snap.skippedNextSectionOffers)} " +
				"offeredNextSectionHops=${formatFigure(snap.offeredNextSectionHops)} " +
				"skippedNextSectionOffersStandingAtSignal=" +
				"${formatFigure(snap.skippedNextSectionOffersStandingAtSignal)} " +
				"longestSkippedOfferStreakCycles=${formatFigure(snap.longestSkippedOfferStreakCycles)} " +
				"longestSkippedOfferStreakSimSeconds=${formatFigure(snap.longestSkippedOfferStreakSimSeconds)}"
		}
		// Issue #834 review finding #6: llmSuccessRate above is reclassified in #834 (idle ticks
		// moved RULE_FALLBACK -> LLM_NO_OP, and REVISED's cap-full no_op turns former fallback ticks
		// into LLM successes) and is not comparable to a pre-#834 run's rate.
		logger.info {
			"[DispatcherRunRecorder] note: llmSuccessRate is reclassified in #834 and not comparable " +
				"to pre-#834 runs"
		}
		// Issue #988: silent ticks with every train moving and a path ahead are relabelled from
		// LLM_SILENT_NONACTIONABLE to LLM_SILENT_ALL_MOVING (an LLM success), which raises both
		// llmSuccessRate and actionableTickRate for the same railway behaviour.
		logger.info {
			"[DispatcherRunRecorder] note: llmSuccessRate and actionableTickRate rise from #988 on " +
				"(LLM_SILENT_ALL_MOVING) and are not comparable to pre-#988 runs"
		}
		// Issue #927: actionableTickRate is a defaulted, not a genuinely measured, figure for any
		// snapshot decoded from a pre-#927 (schema version < 6) file — those files predate
		// TickOutcome.LLM_SILENT_NONACTIONABLE entirely, so the recorder that produced them could
		// never separate a genuinely non-actionable silent tick from a real RULE_FALLBACK tick.
		// The defaulted value (equal to llmSuccessRate — see DispatcherRunSnapshot's KDoc) is the
		// correct value for that data, but it is not the same claim as a run measured under the
		// current taxonomy. This branch is unreachable on the live path (buildSnapshot always
		// stamps CURRENT_SCHEMA_VERSION), but guards any future caller that reuses this method
		// against a snapshot loaded from an older file.
		if (snap.schemaVersion < DispatcherRunSnapshot.SCHEMA_VERSION_INTRODUCING_ACTIONABLE_TICK_RATE) {
			logger.info {
				"[DispatcherRunRecorder] note: actionableTickRate is defaulted (schema version " +
					"${snap.schemaVersion} predates Issue #927) and is not a genuine measurement — it " +
					"equals llmSuccessRate because pre-#927 snapshots cannot distinguish " +
					"LLM_SILENT_NONACTIONABLE ticks from RULE_FALLBACK ticks"
			}
		}
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun buildSnapshot(endCause: RunEndCause?): DispatcherRunSnapshot {
		val byOutcome: Map<String, Long> = ticksByOutcome.mapValues { it.value.get() }
		val byCause: Map<String, Long> = timeoutNoOpByCause.mapValues { it.value.get() }
		// Derive total from the outcome-bucket sum so the snapshot is self-consistent under
		// concurrent onTick calls: the per-bucket counters are updated atomically and only
		// ever grow, so their sum cannot be observed mid-update. A separate totalTicks
		// counter would be updated in a different atomic step and could race a tick in flight,
		// tripping the DispatcherRunSnapshot.init invariant (sum(byOutcome) == total). The
		// snapshot reports state at the call moment (in-flight tick caveat): a tick whose
		// bucket has not yet been incremented is not counted, which matches that caveat.
		val total = byOutcome.values.sum()

		val successCount =
			byOutcome.entries
				.filter { (k, _) -> TickOutcome.valueOf(k).countsAsLlmSuccess }
				.sumOf { it.value }
		// Issue #927: the actionable-rate denominator excludes LLM_SILENT_NONACTIONABLE ticks (a
		// silent-but-correct tick that was never actionable in the first place). The numerator is
		// deliberately the same successCount as llmSuccessRate's — countsAsLlmSuccess is already
		// false for LLM_SILENT_NONACTIONABLE, so it contributes to neither.
		val actionableDenominator =
			byOutcome.entries
				.filter { (k, _) -> TickOutcome.valueOf(k).countsTowardActionableRate }
				.sumOf { it.value }
		val noOpCount = byOutcome[TickOutcome.LLM_NO_OP.name] ?: 0L
		val timeoutNoOpCount = byOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L
		val repairedCount = byOutcome[TickOutcome.LLM_REPAIRED.name] ?: 0L

		val llmSuccessRate = if (total > 0L) successCount.toDouble() / total.toDouble() else 0.0
		val actionableTickRate =
			if (actionableDenominator > 0L) successCount.toDouble() / actionableDenominator.toDouble() else 0.0
		val noOpRate = if (total > 0L) noOpCount.toDouble() / total.toDouble() else 0.0
		val invalidOutputRate = if (total > 0L) timeoutNoOpCount.toDouble() / total.toDouble() else 0.0
		val repairSuccessRate = if (total > 0L) repairedCount.toDouble() / total.toDouble() else 0.0

		// Point-in-time copy so the three percentile computations below all see the same sample
		// set even if onTick appends concurrently (see the latencySamplesMs KDoc).
		val latencySamples = latencySamplesMs.toList()
		val latencyP50 = nearestRankPercentile(latencySamples, 50.0)
		val latencyP95 = nearestRankPercentile(latencySamples, 95.0)
		val latencyMax = nearestRankPercentile(latencySamples, 100.0)

		val counted: Map<String, Long> = countedAutomatics.mapValues { it.value.get() }
		// Issue #1476: the counted automatics join their authors' action counts, so the clean-run rule
		// reads one map; c7Clean below reads only RULE_FALLBACK and SAFETY_NET, which no automatic feeds.
		val automaticsByAuthor: Map<String, Long> =
			CountedAutomatic.entries
				.groupBy { it.author.name }
				.mapValues { (_, rows) -> rows.sumOf { counted.getValue(it.name) } }
		val byAuthor: Map<String, Long> =
			actionsByAuthor.mapValues { (author, count) -> count.get() + (automaticsByAuthor[author] ?: 0L) }
		val c7Clean =
			(byAuthor[ActionAuthor.RULE_FALLBACK.name] ?: 0L) == 0L &&
				(byAuthor[ActionAuthor.SAFETY_NET.name] ?: 0L) == 0L

		// One atomic breaker reading (Issue #1074, PR #1097 review): three independent volatile
		// reads could persist `OPEN` with the pre-transition `openCount` if a `plan()` thread is
		// mid-`recordFailure` while this snapshot is built.
		val breaker = circuitBreaker?.metricsSnapshot()
		// One atomic offer-group reading (PR #1408 review round): the measured flag, the three
		// sums, the failure count and the streak maxima are copied in ONE [offerStateLock] critical
		// section — separate reads could persist a torn group (offered hops without their skipped
		// offers, or the measured flag flipped while the sums still lag) while an onTick is
		// mid-update. Same reasoning as the breaker reading above.
		val (measuredByCount, skippedSum, offeredSum, standingSum, countFailures, streakCycles, streakSimSeconds) =
			synchronized(offerStateLock) {
				OfferState(
					measuredByCount = nextSectionOffersMeasured,
					skippedSum = skippedNextSectionOffers.get(),
					offeredSum = offeredNextSectionHops.get(),
					standingSum = skippedStandingAtSignal.get(),
					countFailures = offerCountFailures.get(),
					streakCycles = longestStreakCycles,
					streakSimSeconds = longestStreakSimSeconds
				)
			}
		// Issue #1302: an LLM run is measured even when every cycle fell back (0/0); only a
		// rule-based run that never carried a count leaves the figures absent. One failed count
		// drops the WHOLE five-figure group (PR #1408 review round): a figure the recorder cannot
		// vouch for is not a measurement, and post-failure successes do not rehabilitate the run —
		// the rule is run-level (countFailures == 0), not per-cycle.
		val offersMeasured = (measuredByCount || arm != DispatcherArm.RULE_BASED) && countFailures == 0L

		return DispatcherRunSnapshot(
			runId = runId,
			arm = arm,
			params = params,
			totalTicks = total,
			ticksByOutcome = byOutcome,
			timeoutNoOpByCause = byCause,
			llmSuccessRate = llmSuccessRate,
			actionableTickRate = actionableTickRate,
			noOpRate = noOpRate,
			invalidOutputRate = invalidOutputRate,
			repairSuccessRate = repairSuccessRate,
			emittedByActionType = emittedByActionType.mapValues { it.value.get() },
			rejectionsByCode = rejectionsByCode.mapValues { it.value.get() },
			applyFailuresByCode = applyFailuresByCode.mapValues { it.value.get() },
			validAt1 = 0.0, // forward-looking — oracle not wired in SP2c.22
			correctAt1 = null,
			oracleAgreementAt1 = null,
			latencyP50Ms = latencyP50,
			latencyP95Ms = latencyP95,
			latencyMaxMs = latencyMax,
			actionsByAuthor = byAuthor,
			unattributedApplies = unattributedApplies.get(),
			terminalFallbackEngaged = false, // wired in SP2c.22 follow-up
			terminalFallbackTickIndex = null,
			c7Clean = c7Clean,
			completedNaturally = endCause == RunEndCause.NATURAL_COMPLETION,
			endCause = endCause,
			railwayOutcome = railwayOutcome.get(),
			circuitBreakerState = breaker?.state,
			circuitBreakerTotalSkips = breaker?.totalSkips,
			circuitBreakerOpenCount = breaker?.openCount,
			staleOriginRefusalsAfterSilentCycle = staleOriginRefusalsAfterSilentCycle.get(),
			skippedNextSectionOffers = if (offersMeasured) skippedSum else null,
			offeredNextSectionHops = if (offersMeasured) offeredSum else null,
			skippedNextSectionOffersStandingAtSignal = if (offersMeasured) standingSum else null,
			longestSkippedOfferStreakCycles = if (offersMeasured) streakCycles else null,
			longestSkippedOfferStreakSimSeconds = if (offersMeasured) streakSimSeconds else null,
			modelProfile = modelProfile,
			generationCutCycles = generationCutCycles.get(),
			commandSource = commandSource.get() ?: CommandSource.forArm(arm, params.model),
			countedAutomatics = counted,
			silentCyclesByCause = SilentCycleCause.countsFrom(byOutcome, byCause)
		)
	}

	/**
	 * Renders one railway figure, printing `n/a` for a figure nobody measured.
	 *
	 * Deliberately not `?: 0`: a zero in this line would be read as "the railway moved nothing",
	 * which is a different claim from "nothing measured it". See [RailwayOutcome].
	 */
	private fun formatFigure(value: Long?): String = value?.toString() ?: "n/a"

	/**
	 * Renders one simulation-seconds figure, printing `n/a` for a figure nobody measured — the
	 * [Double] form of the [Long] overload above, so the offer-streak seconds figure renders its
	 * absence through the same shared mechanism as every other figure on this line.
	 *
	 * Deliberately not `?: 0`: a zero in this line would be read as "no train ever waited at an
	 * offered signal", which is a different claim from "nothing measured it". See [RailwayOutcome].
	 */
	private fun formatFigure(value: Double?): String = value?.toString() ?: "n/a"
}
