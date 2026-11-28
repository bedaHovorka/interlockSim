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
 * [onActionOutcome] is called from two threads (same contract as [ActionOutcomeSink]): the kDisco
 * simulation thread, when [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] processes a
 * command, and the dispatcher-agent driver thread, when a cycle that reported no tick outcome drops
 * its returned batch (`AgentLoopDriver.failUnreportedCycle`, Issue #1477). It is allocation-light
 * with no logging. All counters for [ActionPhase], [ApplyFailureCode], and [ActionAuthor] are
 * pre-populated at construction time (one [AtomicLong] per enum entry), and the unattributed
 * counter is an [AtomicLong] too, so no writer ever mutates the map structures — only
 * [AtomicLong.incrementAndGet] calls happen on the hot path, which is safe from both threads — while
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
		val countsByAuthor: Map<ActionAuthor, Long>,
		/** Outcomes whose author is unknown (Issue #1477); they are in no [countsByAuthor] entry. */
		val unattributedOutcomes: Long
	)

	private val totalOutcomes = AtomicLong(0L)
	private val phaseCounts: Map<ActionPhase, AtomicLong> = concurrentEnumCounters(ActionPhase.entries)
	private val applyFailureCounts: Map<ApplyFailureCode, AtomicLong> =
		concurrentEnumCounters(ApplyFailureCode.entries)
	private val authorCounts: Map<ActionAuthor, AtomicLong> = concurrentEnumCounters(ActionAuthor.entries)
	private val unattributedOutcomes = AtomicLong(0L)

	/**
	 * Called on the sim thread by the applier and on the driver thread for a dropped batch (Issue #1477);
	 * every accumulator is atomic, so both are safe. Allocation-light; no logging (SP2c.20 review
	 * checklist).
	 */
	override fun onActionOutcome(outcome: ActionOutcome) {
		totalOutcomes.incrementAndGet()
		phaseCounts.getValue(outcome.phase).incrementAndGet()
		outcome.applyFailure?.let { applyFailureCounts.getValue(it).incrementAndGet() }
		// Issue #1477: the same unattributed rule as DefaultDispatcherRunRecorder (AuthoredAction.isUnattributed).
		val author = outcome.authored.author
		// `|| author == null` adds no case (isUnattributed covers it); it only smart-casts author below.
		if (outcome.authored.isUnattributed || author == null) {
			unattributedOutcomes.incrementAndGet()
		} else {
			authorCounts.getValue(author).incrementAndGet()
		}
	}

	/** Returns an immutable point-in-time snapshot of all counters. Safe to call from any thread. */
	fun getSnapshot(): OutcomeSnapshot =
		OutcomeSnapshot(
			totalOutcomes = totalOutcomes.get(),
			countsByPhase = phaseCounts.mapValues { it.value.get() },
			countsByApplyFailure = applyFailureCounts.mapValues { it.value.get() },
			countsByAuthor = authorCounts.mapValues { it.value.get() },
			unattributedOutcomes = unattributedOutcomes.get()
		)
}
