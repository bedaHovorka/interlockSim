/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidator
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer

/**
 * Builds an [ActionValidator] whose endpoint and block name sets are **derived from [context]**
 * instead of hand-written (Issue #955, cluster D2).
 *
 * The endpoint set is the union of the station's `InOut` names and its signal names; the block set
 * is every block name. This is the same derivation `KoogAgentFactory` performs in production, so a
 * validator built here accepts exactly what the live dispatcher would accept for the same station.
 *
 * Use this whenever a test drives a **real** loaded context. Tests that exercise the validator's own
 * rules on a synthetic topology (`"A"`, `"kB"`, …) keep their literal sets — those names are the
 * fixture under test, not a copy of a station's names.
 */
fun actionValidatorFor(context: DefaultSimulationContext): ActionValidator {
	val topology = StationTopologySerializer.describe(context)
	return ActionValidator(
		validEndpointNames = (topology.inOuts + topology.signals.map { it.name }).toSet(),
		blockIds = topology.blocks.map { it.name }.toSet()
	)
}
