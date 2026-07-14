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
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:dispatcher-agent` SP0.5-new, SP1-new, and SP1.3-new components.
 *
 * ## Bindings provided
 *
 * | Component | Scope | Default |
 * |---|---|---|
 * | [Dispatcher] | singleton | [RuleBasedDispatcher] |
 * | [AgentService] | singleton | [DefaultAgentService] (SP1.2) |
 * | [OllamaExecutorConfig] | singleton | [OllamaExecutorConfig.default] (SP1.3) |
 * | [ToolGroupRegistry] | singleton | [ToolGroupRegistry] (SP1.3) |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 * | [KoogAgentFactory] | per [DefaultSimulationContext] | [KoogAgentFactory] (SP1.3) |
 *
 * ## SP1.3 (#548) additions
 *
 * SP1.3 extends the module with Koog runtime configuration:
 * - [OllamaExecutorConfig] — singleton for model/endpoint/timeout settings
 * - [ToolGroupRegistry] — singleton for assembling perception/actuator tools per context
 * - [KoogAgentFactory] — per-context factory for instantiating agents
 *
 * **Note:** Per-context [KoogDispatchAgent] binding is deferred to SP1.4+ because agent
 * creation is a `suspend` function and cannot be directly wired in the Koin module
 * (which is not a suspend context). Instead, callers retrieve [KoogAgentFactory] from
 * the scope and call `factory.createAgent(context)` when appropriate in their suspend context.
 *
 * ### Scope decisions (SP1.3 design rationale)
 *
 * **Singletons (shared globally):**
 * - [OllamaExecutorConfig]: Model/endpoint choice is runtime-global (all agents use same Ollama)
 * - [ToolGroupRegistry]: Registry logic is stateless; can be shared (tools assembled per context)
 * - [AgentService]: Service for creating agents is stateless (SP1.2)
 * - [Dispatcher]: Dispatcher implementation (rule-based or future LLM) is stateless
 *
 * **Per-context scope (one per [DefaultSimulationContext]):**
 * - [ActuatorCommandQueue]: One handoff queue per simulation (SP0.5)
 * - [KoogAgentFactory]: Factory receives context-scoped dependencies and creates agents on demand
 *
 * This design allows multiple simultaneous simulations (e.g., in tests) each with:
 * - Independent agent instances (created on-demand via factory)
 * - Context-specific tool assembly (perception/actuator ports)
 * - Isolated command queues
 *
 * ## Pending SP1.4 (#549) bindings
 *
 * [NetworkPerceptionPort][cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort]
 * and [NetworkActuatorPort][cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort]
 * (tool implementations) are SP1.4's responsibility.
 *
 * ## Pending SP1.5 (#550) bindings
 *
 * Ollama client initialization and LLM executor backend setup.
 *
 * @since Issue #733 (SP0.11 — Goal 10), expanded in Issue #547 (SP1.2), extended in Issue #548 (SP1.3)
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

		// SP1.3: Ollama executor configuration (singleton)
		// All agents share the same Ollama endpoint, model, and inference parameters.
		// The config is immutable and stateless, safe for global sharing.
		single<OllamaExecutorConfig> { OllamaExecutorConfig.default() }

		// SP1.3: Tool group registry (singleton)
		// Registry logic is stateless; it just coordinates tool assembly per context.
		// Actual tools (perception/actuator) are assembled per-context in SP1.4.
		single<ToolGroupRegistry> { ToolGroupRegistry() }

		scope<DefaultSimulationContext> {
			// SP0.5: ActuatorCommandQueue: one thread-safe handoff queue per simulation context.
			scoped<ActuatorCommandQueue> { ActuatorCommandQueue() }

			// SP1.3: KoogAgentFactory (per-context builder, receives tools/config)
			// Factory is scoped because it receives context-scoped dependencies (tools, ports in SP1.4).
			// Each context gets its own factory instance.
			scoped<KoogAgentFactory> {
				KoogAgentFactory(
					toolRegistry = get(), // Singleton
					ollamaConfig = get(), // Singleton
					agentService = get() // Singleton
				)
			}

			// SP1.3: Per-context Koog dispatch agent factory (scoped to this context)
			// Note: Agent creation is deferred to caller code because createAgent is a suspend function.
			// This allows callers to create agents when appropriate in their suspend context.
			// In practice, AgentLoopDriver will call factory.createAgent(context) when it's ready.
			// Full per-context agent binding (scoped<KoogDispatchAgent>) comes in SP1.4+
			// once tool implementations are available.
		}
	}
