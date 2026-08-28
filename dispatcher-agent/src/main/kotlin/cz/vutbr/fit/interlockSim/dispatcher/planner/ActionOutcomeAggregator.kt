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
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal production [ActionOutcomeSink] that aggregates every [ActionOutcome] into live
 * counters, so SP2c.20 attribution data (Issue #843) actually reaches somewhere in a real
 * run instead of only being observed by tests.
 *
 * ## Threading
 *
 * [onActionOutcome] is called on the kDisco simulation thread only (same contract as
 * [ActionOutcomeSink]) and is allocation-light with no logging. All counters for
 * [ActionPhase], [ApplyFailureCode], and [ActionAuthor] are pre-populated at construction
 * time (one [AtomicLong] per enum entry) so the single writer thread never mutates the map
 * structures — only [AtomicLong.incrementAndGet] calls happen on the hot path — while
 * [getSnapshot] can safely be called from any other thread at any time.
 *
 * @since Issue #843 (SP2c.20 follow-up — Goal 10 action attribution wiring)
 */
class ActionOutcomeAggregator : ActionOutcomeSink {
	/** Immutable point-in-time view of the accumulated counters. */
	data class OutcomeSnapshot(
		val totalOutcomes: Long,
		val countsByPhase: Map<ActionPhase, Long>,
		val countsByApplyFailure: Map<ApplyFailureCode, Long>,
		val countsByAuthor: Map<ActionAuthor, Long>
	)

	private val totalOutcomes = AtomicLong(0L)
	private val phaseCounts: Map<ActionPhase, AtomicLong> =
		ActionPhase.entries.associateWith { AtomicLong(0L) }
	private val applyFailureCounts: Map<ApplyFailureCode, AtomicLong> =
		ApplyFailureCode.entries.associateWith { AtomicLong(0L) }
	private val authorCounts: Map<ActionAuthor, AtomicLong> =
		ActionAuthor.entries.associateWith { AtomicLong(0L) }

	/** Called on the sim thread. Allocation-light; no logging (SP2c.20 review checklist). */
	override fun onActionOutcome(outcome: ActionOutcome) {
		totalOutcomes.incrementAndGet()
		phaseCounts.getValue(outcome.phase).incrementAndGet()
		outcome.applyFailure?.let { applyFailureCounts.getValue(it).incrementAndGet() }
		authorCounts.getValue(outcome.authored.author).incrementAndGet()
	}

	/** Returns an immutable point-in-time snapshot of all counters. Safe to call from any thread. */
	fun getSnapshot(): OutcomeSnapshot =
		OutcomeSnapshot(
			totalOutcomes = totalOutcomes.get(),
			countsByPhase = phaseCounts.mapValues { it.value.get() },
			countsByApplyFailure = applyFailureCounts.mapValues { it.value.get() },
			countsByAuthor = authorCounts.mapValues { it.value.get() }
		)
}
