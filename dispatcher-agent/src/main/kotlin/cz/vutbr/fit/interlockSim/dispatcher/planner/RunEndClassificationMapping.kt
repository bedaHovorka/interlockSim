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

import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification

/**
 * The [RunEndCause] a run of this [RunEndClassification] records (Issue #1304, Goal 6 SP6;
 * traffic-simulation-expert ruling 2026-10-31). The cause keeps its own values and is not replaced:
 *
 * - [RunEndClassification.NATURAL_COMPLETION] and [RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS]
 *   → [RunEndCause.NATURAL_COMPLETION]: both reached the end time, and a cutoff run was recorded so
 *   before. The [RunEndCause.STARVED] adjustment stays downstream.
 * - [RunEndClassification.STALLED] and [RunEndClassification.TERMINATED_EARLY] →
 *   [RunEndCause.TERMINATED_EARLY]: the run stopped before its end time.
 *
 * [RunEndCause.MANUAL_STOP], [RunEndCause.TIMEOUT_ABORT] and [RunEndCause.CRASH] are never produced:
 * only the caller that stopped or killed the run knows them.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
fun RunEndClassification.toRunEndCause(): RunEndCause =
	when (this) {
		RunEndClassification.NATURAL_COMPLETION,
		RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS -> RunEndCause.NATURAL_COMPLETION
		RunEndClassification.STALLED,
		RunEndClassification.TERMINATED_EARLY -> RunEndCause.TERMINATED_EARLY
	}
