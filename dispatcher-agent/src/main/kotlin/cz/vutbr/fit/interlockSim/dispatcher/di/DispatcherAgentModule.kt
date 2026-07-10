/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:dispatcher-agent` SP0.5-new components.
 *
 * ## Bindings provided
 *
 * | Component | Scope | Default |
 * |---|---|---|
 * | [Dispatcher] | singleton | [RuleBasedDispatcher] |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 *
 * ## Pending SP1.4 (#549) bindings
 *
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * and [AgentLoopDriver][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] require
 * sensor/actuator port bindings ([NetworkPerceptionPort][cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort]
 * / [NetworkActuatorPort][cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort]) that are
 * SP1.4 (#549)'s responsibility. Until SP1.4 lands, callers (ExampleRegistry, Frame) wire
 * the applier and driver manually with the ShuntingLoop callbacks they require.
 *
 * @since Issue #733 (SP0.11 — Goal 10)
 */
val dispatcherAgentModule: Module = module {
	// Dispatcher: global singleton — RuleBasedDispatcher is stateless (pure function).
	single<Dispatcher> { RuleBasedDispatcher() }

	scope<DefaultSimulationContext> {
		// ActuatorCommandQueue: one thread-safe handoff queue per simulation context.
		scoped<ActuatorCommandQueue> { ActuatorCommandQueue() }
	}
}
