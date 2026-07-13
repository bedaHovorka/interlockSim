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
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:dispatcher-agent` SP0.5-new and SP1-new components.
 *
 * ## Bindings provided
 *
 * | Component | Scope | Default |
 * |---|---|---|
 * | [Dispatcher] | singleton | [RuleBasedDispatcher] |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 * | [AgentService] | singleton | [DefaultAgentService] (SP1.2) |
 *
 * ## Pending SP1.3 (#548) bindings
 *
 * SP1.3 will extend this module with:
 * - Per-context Koog agent instances (scoped to [DefaultSimulationContext])
 * - Perception/actuator tool implementations
 * - Koog model configuration (Ollama endpoint, model name, etc.)
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
 * @since Issue #733 (SP0.11 — Goal 10), expanded in Issue #547 (SP1.2)
 */
val dispatcherAgentModule: Module =
	module {
		// Dispatcher: global singleton — RuleBasedDispatcher is stateless (pure function).
		// In future, alternate implementations may include an Agentic/LLM dispatcher alongside Rule.
		single<Dispatcher> { RuleBasedDispatcher() }

		// AgentService: global singleton for creating Koog agents (SP1.2 skeleton, Issue #547).
		// In SP1.3+, this will be injected into per-context agent instances.
		// No Spring Boot: uses lightweight Koin DI instead.
		single<AgentService> { DefaultAgentService() }

		scope<DefaultSimulationContext> {
			// ActuatorCommandQueue: one thread-safe handoff queue per simulation context.
			scoped<ActuatorCommandQueue> { ActuatorCommandQueue() }

			// SP1.3 will add: per-context Koog agent instance (with perception/actuator tools)
			// scoped<KoogDispatchAgent> { agent factory using tools and AgentService }
		}
	}
