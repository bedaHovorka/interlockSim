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

/**
 * [ActionOutcomeSink] that forwards each outcome to several delegates in registration order.
 *
 * ## Why this exists (Issue #847 round 4, finding R4-5)
 *
 * `DispatchDecisionApplier` takes exactly **one** [ActionOutcomeSink], and production had already
 * spent it on `ActionOutcomeAggregator`. As a result
 * [DispatcherRunRecorder.onActionOutcome] — the method that fills `emittedByActionType`,
 * `rejectionsByCode`, `applyFailuresByCode`, `actionsByAuthor` and `unattributedApplies` in the
 * per-run JSON — had **no production caller anywhere**. Even the GUI, which does call
 * `DispatcherRunRecorder.finish()`, was freezing an all-zero snapshot, and SP2c.23's cross-run
 * aggregator (#846) had no producer at all.
 *
 * ## Sim-thread contract
 *
 * [ActionOutcomeSink.onActionOutcome] is invoked inside the kDisco event loop and is contractually
 * forbidden from logging — every log call injects latency into the physics loop. This class
 * therefore adds nothing of its own: no logging, no per-call allocation beyond the iteration.
 *
 * Exceptions are **not** swallowed. A delegate that throws aborts the fan-out and propagates to the
 * applier, which is the caller entitled to decide what a broken metrics consumer means. Swallowing
 * here would recreate the "the metric was silently never recorded" failure mode that this whole
 * round exists to remove.
 *
 * @since Issue #847 round 4 (PR #891)
 */
class CompositeActionOutcomeSink(
	private val delegates: List<ActionOutcomeSink>
) : ActionOutcomeSink {
	constructor(vararg delegates: ActionOutcomeSink) : this(delegates.toList())

	override fun onActionOutcome(outcome: ActionOutcome) {
		delegates.forEach { it.onActionOutcome(outcome) }
	}

	companion object {
		/**
		 * Builds a composite from possibly-absent delegates, dropping the nulls.
		 *
		 * Most dispatcher components are resolved with `scope.getOrNull(...)` because a context may
		 * legitimately lack them; this spares every call site a null branch.
		 */
		fun of(vararg delegates: ActionOutcomeSink?): CompositeActionOutcomeSink =
			CompositeActionOutcomeSink(delegates.filterNotNull())
	}
}
