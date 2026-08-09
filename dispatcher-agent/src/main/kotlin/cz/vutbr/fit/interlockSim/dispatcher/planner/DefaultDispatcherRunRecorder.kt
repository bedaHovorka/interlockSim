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
 * [snapshot] and [finish] may be called from any thread.
 *
 * ## Latency percentiles (Issue #834, SP2c.11)
 *
 * [onTick] appends [TickRecord.latencyMs] (when non-`null`) to an unbounded sample queue;
 * [buildSnapshot] takes a point-in-time copy and derives [DispatcherRunSnapshot.latencyP50Ms],
 * [DispatcherRunSnapshot.latencyP95Ms] and [DispatcherRunSnapshot.latencyMaxMs] from it using
 * [nearestRankPercentile] (nearest-rank convention — see its KDoc). An empty sample set (no
 * ticks carried a latency, e.g. an all-rule-based run) yields `0L` for all three fields,
 * preserving the pre-#834 behaviour for runs with nothing to measure.
 *
 * ## Railway outcomes (Issue #834, SP2c.11)
 *
 * [recordRailwayOutcome] takes the run's [RailwayOutcome] whole from a caller that can see the
 * simulation context — the recorder itself observes only the dispatcher. Until it is called (and
 * for runs where nobody calls it at all), the snapshot reports [RailwayOutcome.UNMEASURED], whose
 * every figure is `null`. That is deliberate: `null` means *not measured* and `0` means *measured
 * as none*, and a sweep that cannot tell them apart cannot rank on either.
 *
 * @param runId Opaque unique identifier (typically a UUID or `yyyyMMdd-HHmmss-<short-uuid>`).
 * @param arm Which dispatcher implementation arm is active.
 * @param params Fixed run parameters captured at run start.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DefaultDispatcherRunRecorder(
	override val runId: String,
	private val arm: DispatcherArm,
	private val params: RunParameters
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
		ConcurrentHashMap<String, AtomicLong>().also { map ->
			TickOutcome.entries.forEach { outcome -> map[outcome.name] = AtomicLong(0L) }
		}

	private val timeoutNoOpByCause: ConcurrentHashMap<String, AtomicLong> =
		ConcurrentHashMap<String, AtomicLong>().also { map ->
			TimeoutNoOpCause.entries.forEach { cause -> map[cause.name] = AtomicLong(0L) }
		}

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
		ConcurrentHashMap<String, AtomicLong>().also { map ->
			RejectionCode.entries.forEach { code -> map[code.name] = AtomicLong(0L) }
		}

	private val applyFailuresByCode: ConcurrentHashMap<String, AtomicLong> =
		ConcurrentHashMap<String, AtomicLong>().also { map ->
			ApplyFailureCode.entries.forEach { code -> map[code.name] = AtomicLong(0L) }
		}

	private val actionsByAuthor: ConcurrentHashMap<String, AtomicLong> =
		ConcurrentHashMap<String, AtomicLong>().also { map ->
			ActionAuthor.entries.forEach { author -> map[author.name] = AtomicLong(0L) }
		}

	private val unattributedApplies = AtomicLong(0L)

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
		record.latencyMs?.let { latencySamplesMs.add(it) }
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
	}

	override fun recordRailwayOutcome(outcome: RailwayOutcome) {
		railwayOutcome.set(outcome)
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
				"failedReservations=${formatFigure(railway.failedReservations)}"
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
		val noOpCount = byOutcome[TickOutcome.LLM_NO_OP.name] ?: 0L
		val timeoutNoOpCount = byOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L
		val repairedCount = byOutcome[TickOutcome.LLM_REPAIRED.name] ?: 0L

		val llmSuccessRate = if (total > 0L) successCount.toDouble() / total.toDouble() else 0.0
		val noOpRate = if (total > 0L) noOpCount.toDouble() / total.toDouble() else 0.0
		val invalidOutputRate = if (total > 0L) timeoutNoOpCount.toDouble() / total.toDouble() else 0.0
		val repairSuccessRate = if (total > 0L) repairedCount.toDouble() / total.toDouble() else 0.0

		// Point-in-time copy so the three percentile computations below all see the same sample
		// set even if onTick appends concurrently (see the latencySamplesMs KDoc).
		val latencySamples = latencySamplesMs.toList()
		val latencyP50 = nearestRankPercentile(latencySamples, 50.0)
		val latencyP95 = nearestRankPercentile(latencySamples, 95.0)
		val latencyMax = nearestRankPercentile(latencySamples, 100.0)

		val byAuthor: Map<String, Long> = actionsByAuthor.mapValues { it.value.get() }
		val c7Clean =
			(byAuthor[ActionAuthor.RULE_FALLBACK.name] ?: 0L) == 0L &&
				(byAuthor[ActionAuthor.SAFETY_NET.name] ?: 0L) == 0L

		return DispatcherRunSnapshot(
			runId = runId,
			arm = arm,
			params = params,
			totalTicks = total,
			ticksByOutcome = byOutcome,
			timeoutNoOpByCause = byCause,
			llmSuccessRate = llmSuccessRate,
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
			railwayOutcome = railwayOutcome.get()
		)
	}

	private fun formatRate(rate: Double): String = "${(rate * 100.0).toLong()}%"

	/**
	 * Renders one railway figure, printing `n/a` for a figure nobody measured.
	 *
	 * Deliberately not `?: 0`: a zero in this line would be read as "the railway moved nothing",
	 * which is a different claim from "nothing measured it". See [RailwayOutcome].
	 */
	private fun formatFigure(value: Long?): String = value?.toString() ?: "n/a"
}
