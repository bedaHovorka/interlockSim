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
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:dispatcher-agent` SP0.5-new, SP1-new, SP1.3-new, and SP1.5-new components.
 *
 * ## Bindings provided
 *
 * | Component | Scope | Default |
 * |---|---|---|
 * | [Dispatcher] | singleton | [RuleBasedDispatcher] |
 * | [DispatcherPlanner] | singleton | [RuleBasedPlanAdapter] (SP3.6) |
 * | [AgentService] | singleton | [DefaultAgentService] (SP1.2) |
 * | [OllamaExecutorConfig] | singleton | [OllamaExecutorConfig.default] (SP1.3) |
 * | [OllamaSimpleExecutor] | singleton | [OllamaSimpleExecutor] (SP1.5) |
 * | [ToolGroupRegistry] | singleton | [ToolGroupRegistry] (SP1.3) |
 * | [NetworkPerceptionPort] | per [DefaultSimulationContext] | [DefaultNetworkPerceptionPort] (SP1.4) |
 * | [NetworkActuatorPort] | per [DefaultSimulationContext] | [DefaultNetworkActuatorPort] (SP1.4) |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 * | [DispatcherModeState] | per [DefaultSimulationContext] | new instance (SP2b.6) |
 * | [DelegatingSimulationController] | per [DefaultSimulationContext] | new instance (SP4.2) |
 * | [KoogAgentFactory] | per [DefaultSimulationContext] | [KoogAgentFactory] (SP1.3, updated in SP1.4) |
 *
 * ## SP1.3 (#548) additions
 *
 * SP1.3 extends the module with Koog runtime configuration:
 * - [OllamaExecutorConfig] — singleton for model/endpoint/timeout settings
 * - [ToolGroupRegistry] — singleton for assembling perception/actuator tools per context
 * - [KoogAgentFactory] — per-context factory for instantiating agents
 *
 * ## SP1.4 (#549) additions
 *
 * SP1.4 binds the sensor/actuator port implementations per context:
 * - [NetworkPerceptionPort] via [DefaultNetworkPerceptionPort] — reads signal/block state
 * - [NetworkActuatorPort] via [DefaultNetworkActuatorPort] — commands routes/signals
 * - [KoogAgentFactory] updated to accept and use injected ports for tool assembly
 *
 * **Note:** Per-context [KoogDispatchAgent] binding is deferred to SP1.5+ because agent
 * creation is a `suspend` function and cannot be directly wired in the Koin module
 * (which is not a suspend context). Instead, callers retrieve [KoogAgentFactory] from
 * the scope and call `factory.createAgent(context)` when appropriate in their suspend context.
 *
 * ## SP1.5 (#550) additions
 *
 * SP1.5 extends the module with Ollama executor backend:
 * - [OllamaSimpleExecutor] — singleton wrapping Koog's `simpleOllamaAIExecutor` for local inference
 *
 * The executor is lazy-initialized on first access, deferring network connectivity checks
 * until it's actually needed.
 *
 * ### Scope decisions (SP1.3 design rationale)
 *
 * **Singletons (shared globally):**
 * - [OllamaExecutorConfig]: Model/endpoint choice is runtime-global (all agents use same Ollama)
 * - [OllamaSimpleExecutor]: Ollama client is a heavyweight stateful resource; shared per application
 * - [ToolGroupRegistry]: Registry logic is stateless; can be shared (tools assembled per context)
 * - [AgentService]: Service for creating agents is stateless (SP1.2)
 * - [Dispatcher]: Underlying synchronous rule-based decision function (pure function)
 * - [DispatcherPlanner]: Pluggable planning interface (SP3.6); default is [RuleBasedPlanAdapter]
 *
 * **Per-context scope (one per [DefaultSimulationContext]):**
 * - [NetworkPerceptionPort]: One perception port per context (SP0.2 / SP1.4)
 * - [NetworkActuatorPort]: One actuator port per context (SP0.3 / SP1.4)
 * - [ActuatorCommandQueue]: One handoff queue per simulation (SP0.5)
 * - [KoogAgentFactory]: Factory receives context-scoped dependencies (ports) and creates agents on demand
 *
 * This design allows multiple simultaneous simulations (e.g., in tests) each with:
 * - Independent agent instances (created on-demand via factory)
 * - Context-specific perception/actuator ports (scoped per context)
 * - Context-specific tool assembly (populated in SP1.4)
 * - Isolated command queues
 * - Shared Ollama executor backend (single local LLM for all simulations)
 *
 * ## Pending SP1.4 (#549) bindings
 *
 * [NetworkPerceptionPort][cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort]
 * and [NetworkActuatorPort][cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort]
 * (tool implementations) are SP1.4's responsibility.
 *
 * @since Issue #733 (SP0.11 — Goal 10), expanded in Issue #547 (SP1.2), extended in Issue #548 (SP1.3), Issue #550 (SP1.5), and Issue #574 (SP3.6)
 */
val dispatcherAgentModule: Module =
	module {
		// Dispatcher: global singleton — RuleBasedDispatcher is stateless (pure function).
		// In future, alternate implementations may include an Agentic/LLM dispatcher alongside Rule.
		single<Dispatcher> { RuleBasedDispatcher() }

		// SP3.6: DispatcherPlanner — pluggable planning interface (Issue #574).
		// Default is RuleBasedPlanAdapter wrapping the Dispatcher singleton above.
		// Swap this binding to plug in a search-based or LLM-backed planner.
		single<DispatcherPlanner> { RuleBasedPlanAdapter(get()) }

		// AgentService: global singleton for creating Koog agents (SP1.2 skeleton, Issue #547).
		// In SP1.3+, this will be injected into per-context agent instances.
		// No Spring Boot: uses lightweight Koin DI instead.
		single<AgentService> { DefaultAgentService() }

		// SP1.3: Ollama executor configuration (singleton)
		// All agents share the same Ollama endpoint, model, and inference parameters.
		// The config is immutable and stateless, safe for global sharing.
		single<OllamaExecutorConfig> { OllamaExecutorConfig.default() }

		// SP1.5: Ollama executor backend (singleton, Issue #550)
		// Wraps Koog's simpleOllamaAIExecutor for local LLM inference.
		// Lazy-initialized on first access (defers network connectivity check).
		// All agents share the same Ollama-backed executor (heavyweight stateful resource).
		single<OllamaSimpleExecutor> { OllamaSimpleExecutor(get()) }

		// SP1.3: Tool group registry (singleton)
		// Registry logic is stateless; it just coordinates tool assembly per context.
		// Actual tools (perception/actuator) are assembled per-context in SP1.4.
		single<ToolGroupRegistry> { ToolGroupRegistry() }

		scope<DefaultSimulationContext> {
			// SP1.4: NetworkPerceptionPort (scoped per context)
			// Reads current signal/block state from the simulation environment.
			// Each context gets its own perception port instance with its own
			// perception/block/train state snapshots.
			scoped<NetworkPerceptionPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultNetworkPerceptionPort(
					env = context,
					// SP1.4b follow-up (PR #769 review): interface-based lookup, no reflection.
					// See mainProcessActiveTrains() for rationale.
					activeTrains = { mainProcessActiveTrains(context) }
				)
			}

			// SP1.4: NetworkActuatorPort (scoped per context)
			// Commands for routes, signals, and switches route through the interlocking's
			// safety logic. Each context gets its own actuator port instance with its own
			// routing services and dynamic wrappers.
			scoped<NetworkActuatorPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				// SP3.5 (Issue #573): wire InterlockingFacade as the single chokepoint so all
				// requestRoute calls (tool → queue → applier → port) pass through the safety kernel.
				DefaultNetworkActuatorPort(
					env = context,
					interlockingFacade = context.scope.get<InterlockingFacade>()
				)
			}

			// SP0.5: ActuatorCommandQueue: one thread-safe handoff queue per simulation context.
			scoped<ActuatorCommandQueue> { ActuatorCommandQueue() }

			// SP2b.4 (Issue #559): DispatcherModeState — dispatcher operating mode controller
			// One per context for independent mode management across concurrent simulations.
			// Defaults to AUTO mode; can be overridden to SEMI_AUTO (require human approval) or
			// MANUAL (monitor-only, no automatic routing). The GUI DispatcherControlPanel binds
			// to this state to display and allow mode selection.
			scoped<DispatcherModeState> { DispatcherModeState() }

			// SP4.2 (Issue #564): Late-bound pacing controller for the agent-driver loop.
			// Wiring layers (e.g. :desktop-ui's ExampleRegistry.wireDispatcherAgent) hand this
			// to AgentLoopDriver at context-creation time; the GUI attaches the live
			// SimulationRunner as delegate when the run starts, pacing the agent loop with
			// the existing real-time sync (speed multiplier, pause). One per context so
			// concurrent simulations pace independently.
			scoped<DelegatingSimulationController> { DelegatingSimulationController() }

			// SP1.3: KoogAgentFactory (per-context builder, receives tools/config)
			// Factory is scoped because it receives context-scoped dependencies (ports from SP1.4, tools in SP1.4+).
			// Each context gets its own factory instance.
			scoped<KoogAgentFactory> {
				KoogAgentFactory(
					toolRegistry = get(), // Singleton
					ollamaConfig = get(), // Singleton
					agentService = get(), // Singleton
					perceptionPort = get(), // Scoped to this context (SP1.4 — live port)
					commandQueue = get() // Scoped to this context (SP1.7)
				)
			}

			// SP1.3: Per-context Koog dispatch agent factory (scoped to this context)
			// Note: Agent creation is deferred to caller code because createAgent is a suspend function.
			// This allows callers to create agents when appropriate in their suspend context.
			// In practice, AgentLoopDriver will call factory.createAgent(context) when it's ready.
			// Full per-context agent binding (scoped<KoogDispatchAgent>) comes in SP1.5+
			// once tool implementations are available.
		}
	}
