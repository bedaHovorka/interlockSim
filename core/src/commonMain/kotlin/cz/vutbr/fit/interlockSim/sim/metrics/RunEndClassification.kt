/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * How a run ended, as the metrics service sees it from events (Issue #1304, Goal 6 SP6).
 *
 * Decided by [classify] at [cz.vutbr.fit.interlockSim.sim.RunEnded], in this order: a stall wins;
 * otherwise a run whose main loop reached its end time is a natural completion unless an admitted
 * train is still on the network; anything else ended early. Trains that were only queued, never
 * admitted, do not count as unfinished. The dispatcher-agent `RunEndCause` maps onto this
 * classification; it is not replaced. The service cannot see who stopped a run, so a manual or an
 * error stop also reads [TERMINATED_EARLY]; the caller that knows keeps its own cause.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
enum class RunEndClassification {
	/** The end time was reached and every admitted train had left the network. */
	NATURAL_COMPLETION,

	/** The end time was reached with at least one admitted train still on the network (Issue #1280). */
	CUTOFF_WITH_UNFINISHED_JOURNEYS,

	/**
	 * The deadlock watchdog reported a stall (`SimulationStalled`) and aborted the run. Only the
	 * `MultiTrainLoop` watchdog emits it today, so another harness that stops for a stall reads
	 * [TERMINATED_EARLY].
	 */
	STALLED,

	/** The run stopped before its end time without a stall: the kDisco queue drained, or a stop. */
	TERMINATED_EARLY;

	/** @since Issue #1304 (Goal 6 SP6) */
	companion object {
		/**
		 * The classification of a run from its three run-end facts.
		 *
		 * @param stalled whether a watchdog stall was seen
		 * @param endTimeReached whether the main loop reached its end time
		 * @param unfinishedJourneys admitted trains that had not exited when the run ended
		 * @since Issue #1304 (Goal 6 SP6)
		 */
		fun classify(
			stalled: Boolean,
			endTimeReached: Boolean,
			unfinishedJourneys: Int
		): RunEndClassification =
			when {
				stalled -> STALLED
				!endTimeReached -> TERMINATED_EARLY
				unfinishedJourneys > 0 -> CUTOFF_WITH_UNFINISHED_JOURNEYS
				else -> NATURAL_COMPLETION
			}
	}
}
