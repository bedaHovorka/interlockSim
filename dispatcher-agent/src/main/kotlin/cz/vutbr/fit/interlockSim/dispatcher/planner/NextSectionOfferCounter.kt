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

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.NextHopOutcome
import cz.vutbr.fit.interlockSim.dispatcher.agents.NextHopResolver
import cz.vutbr.fit.interlockSim.dispatcher.agents.standingAtSignalName
import cz.vutbr.fit.interlockSim.sim.DispatchObservation

/**
 * The NEXT SECTION offers of one LLM cycle that skipped the rule-based fallback (Issue #1302,
 * step 1 "record" of the #992 F8 follow-up).
 *
 * A cycle that emits anything — one `no_op` is enough — counts as acted, so
 * [KoogAgentPlanAdapter] skips the rule-based fallback for the whole cycle (double-dispatch
 * protection). A train whose NEXT SECTION hop was offered and not requested then waits for a later
 * cycle. This record counts those trains; it changes no decision.
 *
 * @property offered NEXT SECTION hops ([NextHopOutcome.Hop]) the cycle message offered — one per
 *   active train at most.
 * @property skippedTrainIds The trains of the offered hops that have no **accepted** `request_route`
 *   this cycle. "Accepted" means the `request_route` tool emitted a [DispatchAction.RequestRoute]
 *   for that train, with any target, whatever the later apply outcome; a request the tool itself
 *   refused was never emitted, so it counts as skipped. Kept by id so the run recorder can tell one
 *   train skipped for many cycles from many trains skipped once (the #895 stall shape F8 names).
 * @property skippedStandingAtSignal How many of [skippedTrainIds] stand at a signal — stopped with a
 *   named signal ahead, the cycle message's "standing at signal" predicate.
 */
data class NextSectionOfferCount(
	val offered: Int,
	val skippedTrainIds: Set<String>,
	val skippedStandingAtSignal: Int
) {
	/** Offered hops left without an accepted `request_route`: one per skipped train. */
	val skipped: Int get() = skippedTrainIds.size
}

/**
 * Counts the NEXT SECTION offers a fallback-skipping cycle left unrequested (Issue #1302).
 *
 * A seam rather than a private function so a test can run the same cycles with a counter that
 * measures nothing and prove the decisions, emissions and history are identical (the counter is
 * reporting only). Production uses [LIVE].
 */
fun interface NextSectionOfferCounter {
	/**
	 * @param observation The cycle's own observation.
	 * @param emitted The actions the agent emitted this cycle.
	 * @return The count, or `null` when this counter measures nothing.
	 */
	fun count(
		observation: DispatchObservation,
		emitted: List<DispatchAction>
	): NextSectionOfferCount?

	companion object {
		/**
		 * The production counter. It recomputes the offers with [NextHopResolver.offeredNextHopOutcomes]
		 * over the active trains of the cycle's own observation — the very call the cycle message's
		 * NEXT SECTION lines are rendered from (`KoogDispatchAgentImpl.buildUserPrompt`), so the
		 * count names exactly the hops the model was shown, after the same-tick same-target dedup.
		 */
		val LIVE: NextSectionOfferCounter =
			NextSectionOfferCounter { observation, emitted ->
				val offeredTrains =
					NextHopResolver
						.offeredNextHopOutcomes(observation)
						.filterValues { it is NextHopOutcome.Hop }
						.keys
				val requestedTrains =
					emitted.filterIsInstance<DispatchAction.RequestRoute>().mapTo(mutableSetOf()) { it.trainId }
				val skippedTrains = offeredTrains.filterTo(mutableSetOf()) { it !in requestedTrains }
				NextSectionOfferCount(
					offered = offeredTrains.size,
					skippedTrainIds = skippedTrains,
					skippedStandingAtSignal = skippedTrains.count { isStandingAtSignal(it, observation) }
				)
			}

		/**
		 * The cycle message's "standing at signal" predicate — [standingAtSignalName] over the
		 * train's perception, the single predicate the prompt clause (`KoogDispatchAgentImpl.renderActiveTrainLine`)
		 * and `RequestRouteTool`'s origin guard also read: stopped, with a named signal ahead.
		 * A train with no perception is not standing.
		 */
		private fun isStandingAtSignal(
			trainId: String,
			observation: DispatchObservation
		): Boolean = observation.snapshot.trainPerception(trainId)?.standingAtSignalName() != null
	}
}
