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

import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord

/**
 * The counted automatics of #1475 that act on the railway without passing through the dispatcher's action
 * path, each under the [author] its row names (Issue #1476). The run JSON keeps one counter per row in
 * [DispatcherRunSnapshot.countedAutomatics] and adds each into [DispatcherRunSnapshot.actionsByAuthor], the
 * one map the clean-run rule reads ([CleanRunRule]).
 *
 * An automatic is counted once per act: on the first call of its episode that changes railway state (a
 * freed block, a signal dropped to STOP). Deferred calls of the same episode are not counted again, and a
 * deferred episode is counted even when an LLM cancel or the run end finishes it.
 *
 * The counted rows with an action path are counted through the action's author instead: C8
 * ([ActionAuthor.RULE_FALLBACK]), C9-C11 ([ActionAuthor.SAFETY_NET], once and never also under
 * [ActionAuthor.LLM]), C12 ([ActionAuthor.OPERATOR]) and C15 ([ActionAuthor.RULE_BASED]). C7 (a command
 * that retries its own deferral) keeps the author of its command and has no counter.
 *
 * @property author The author the #1475 row names.
 * @since Issue #1476 (Goal 9B SP5.3)
 */
enum class CountedAutomatic(
	val author: ActionAuthor
) {
	/** C1: `DefaultReservationExpiry` phase 1 on the loop tick, a whole holding released by its TTL. */
	EXPIRY_WHOLE_HOLDING(ActionAuthor.EXPIRY),

	/** C2: `DefaultReservationExpiry` phase 2 on the loop tick, the RESERVED tail of a stalled train. */
	EXPIRY_OCCUPYING_TAIL(ActionAuthor.EXPIRY),

	/** C3: an expiry of either kind at the top of **another** train's request; never the requester's author. */
	EXPIRY_ON_REQUEST(ActionAuthor.EXPIRY),

	/** C4: `OrphanReservationSweeper` stale branch, a known train's un-travelled route. */
	SWEEP_STALE(ActionAuthor.ORPHAN_SWEEP),

	/** C5: `OrphanReservationSweeper` partial branch, the un-travelled tail ahead of a stopped train. */
	SWEEP_PARTIAL(ActionAuthor.ORPHAN_SWEEP),

	/** C6: `OrphanReservationSweeper` phantom branch, a route whose owner is neither active nor queued. */
	SWEEP_PHANTOM(ActionAuthor.ORPHAN_SWEEP),

	/** C13: a restricting command the R9 emergency path issues by itself. No producer yet. */
	EMERGENCY_RESTRICTION(ActionAuthor.EMERGENCY_SYSTEM),

	/** C14: the #1485 watchdog's stop of the run. No producer yet. */
	WATCHDOG_STOP(ActionAuthor.WATCHDOG)
}

/**
 * The counted automatics of a run without an action path, read from their sources at run end (Issue
 * #1476): the kernel's expiry acts from [record] (C1-C3) and the sweeper's acts from [sweeper] (C4-C6). A
 * source that is not there contributes no row; the recorder writes every row, a missing one as `0`.
 *
 * The sweeper's act counters are read, not the record's `staleReleaseCount` / `partialReleaseCount`, which
 * count every release call of a deferred episode.
 */
fun countedAutomaticsFrom(
	record: RunRecord?,
	sweeper: OrphanReservationSweeper?
): Map<CountedAutomatic, Long> =
	buildMap {
		record?.let {
			put(CountedAutomatic.EXPIRY_WHOLE_HOLDING, it.wholeHoldingExpiryActs.toLong())
			put(CountedAutomatic.EXPIRY_OCCUPYING_TAIL, it.tailExpiryActs.toLong())
			put(CountedAutomatic.EXPIRY_ON_REQUEST, it.requestExpiryActs.toLong())
		}
		sweeper?.let {
			put(CountedAutomatic.SWEEP_STALE, it.staleActCount.toLong())
			put(CountedAutomatic.SWEEP_PARTIAL, it.partialActCount.toLong())
			put(CountedAutomatic.SWEEP_PHANTOM, it.phantomActCount.toLong())
		}
	}
