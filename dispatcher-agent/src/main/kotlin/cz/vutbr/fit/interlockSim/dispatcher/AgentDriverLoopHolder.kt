/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import org.koin.core.scope.Scope

/**
 * Per-context slot for the [AgentDriverLoop] a simulation context runs, if it runs one.
 *
 * ## Why this exists (Issue #1317)
 *
 * The loop used to be published with Koin's `Scope.declare`. In Koin 3.5 that call registers ONE
 * definition per scope *qualifier*, which every `DefaultSimulationContext` scope shares, and the
 * definition's builder returns the instance of the first scope that declared it. Any later scope
 * that never declared a loop of its own therefore resolved the first context's loop — even after
 * that context was closed. In a GUI session a dispatcher-free `multiTrainLoop` started after an agent
 * run found the stale loop and was persisted as a rule-based dispatcher run.
 *
 * This holder is bound `scoped` on the `DefaultSimulationContext` scope in `dispatcherAgentModule`,
 * so Koin builds a fresh, empty one for every context. Wiring layers [install] the loop they built;
 * readers use [agentDriverLoopOrNull]. A context that wires no dispatcher keeps an empty holder.
 */
class AgentDriverLoopHolder {
	/** The loop installed in this context, or `null` when the context runs no dispatcher agent. */
	@Volatile
	var loop: AgentDriverLoop? = null
		private set

	/**
	 * Installs [driverLoop] as this context's loop.
	 *
	 * @throws IllegalStateException if a loop is already installed: one context drives one loop.
	 */
	fun install(driverLoop: AgentDriverLoop) {
		check(loop == null) { "An AgentDriverLoop is already installed in this simulation context" }
		loop = driverLoop
	}
}

/**
 * The [AgentDriverLoop] installed in this simulation context scope, or `null` when the context runs
 * no dispatcher agent, the holder is not bound, or the scope is already closed.
 *
 * Use this, never `getOrNull<AgentDriverLoop>()`: see [AgentDriverLoopHolder] for why a declared
 * loop leaks across contexts.
 */
fun Scope.agentDriverLoopOrNull(): AgentDriverLoop? = getOrNull<AgentDriverLoopHolder>()?.loop
