/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

/**
 * Compact cross-tick scratchpad carried into every [RenderContext] (SP2c.2, #825).
 *
 * All four fields are **deterministic**: given the same sequence of
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord]s and
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation]s, the same
 * [WorkingMemory] value is always produced. There is no LLM-state dependency here — the
 * memory is updated by the caller (e.g. the control-loop harness) before passing it into
 * [RenderContext], not inside a renderer.
 *
 * Rationale for the four fields (prompt section 5 in the SP2c.2 layout):
 * - [consecutiveNoOpTicks]: signals a potential stall — high values warrant intervention.
 * - [longestQueuedWaitSecs]: longest wait of any currently queued train; prioritisation hint.
 * - [blockedTrainCount]: number of approved trains stopped at a STOP signal ([TrainPhase.HELD]);
 *   non-zero means one or more trains need a path.
 * - [lastTickOutcome]: name of the previous tick's
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome], or `null` when there is no
 *   history yet (first tick). Rendered as-is — enum `.name` is ASCII.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
data class WorkingMemory(
	/**
	 * Number of consecutive most-recent ticks whose
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome] was a passive outcome
	 * (TIMEOUT_NOOP, LLM_EXCEPTION, LLM_NO_OP, or RULE_FALLBACK). Resets to zero the tick a
	 * LLM_ACTIONS or LLM_REPAIRED outcome is recorded.
	 */
	val consecutiveNoOpTicks: Int,

	/**
	 * Simulation-time wait (seconds) of the longest-waiting currently queued train, or `0.0`
	 * when no trains are queued. Computed from
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView.waitSeconds] for
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.QUEUED] trains.
	 */
	val longestQueuedWaitSecs: Double,

	/**
	 * Count of approved trains whose phase is
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.HELD] (stopped at a STOP
	 * signal, not at a commanded station dwell). Non-zero indicates path-assignment work.
	 */
	val blockedTrainCount: Int,

	/**
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome] name from the most recent
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord], or `null` when [history] was
	 * empty (first tick). Rendered verbatim — enum `.name` contains only ASCII characters.
	 */
	val lastTickOutcome: String?
) {
	companion object {
		/** A safe zero-state memory for tick 0, before any history has accumulated. */
		val EMPTY = WorkingMemory(
			consecutiveNoOpTicks = 0,
			longestQueuedWaitSecs = 0.0,
			blockedTrainCount = 0,
			lastTickOutcome = null
		)
	}
}
