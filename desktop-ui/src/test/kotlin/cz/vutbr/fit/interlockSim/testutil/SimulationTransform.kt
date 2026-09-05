/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared test utilities
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory

/**
 * Transform-and-verify scaffolding for the editing-to-simulation integration tests
 * (PR #1043 review round): collapse the repeated
 * `transformer.createSimulationContext(editingContext, processFactory).use { ... }`
 * nesting into one call.
 *
 * [withSimulationContext] closes the SIMULATION context after [verify] runs. The
 * SOURCE [EditingContext] is owned by the caller — wrap it in `.use` yourself,
 * the same ownership contract as [saveAndReloadThroughFile].
 */
fun ContextTransformer.withSimulationContext(
	editingContext: EditingContext,
	processFactory: SimulationProcessFactory,
	verify: (simulationContext: SimulationContext) -> Unit
) {
	createSimulationContext(editingContext, processFactory).use(verify)
}

/**
 * Assert the shared topology preamble of the complex-network integration tests:
 * the expected number of InOut points and a non-empty track graph.
 */
fun assertNetworkTopology(
	simulationContext: SimulationContext,
	expectedInOutCount: Int
) {
	assertThat(simulationContext.getInOuts()).hasSize(expectedInOutCount)
	assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
}
