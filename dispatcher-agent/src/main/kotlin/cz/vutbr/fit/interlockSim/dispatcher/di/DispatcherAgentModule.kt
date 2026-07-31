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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeChannel
import cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationSource
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
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
 * | [AgentService] | singleton | [DefaultAgentService] (SP1.2; real Koog wiring SP2b.9, #566) |
 * | [OllamaExecutorConfig] | singleton | [OllamaExecutorConfig.default] (SP1.3) |
 * | [OllamaSimpleExecutor] | singleton | [OllamaSimpleExecutor] (SP1.5) |
 * | [ToolGroupRegistry] | singleton | [ToolGroupRegistry] (SP1.3) |
 * | [NetworkPerceptionPort] | per [DefaultSimulationContext] | [DefaultNetworkPerceptionPort] (SP1.4) |
 * | [NetworkActuatorPort] | per [DefaultSimulationContext] | [DefaultNetworkActuatorPort] (SP1.4) |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 * | [DispatchLoopSensorPort] | per [DefaultSimulationContext] | [MainProcessDispatchLoopSensorPort] (Goal 10 fix) |
 * | [DispatcherObservationSource] | per [DefaultSimulationContext] | [DispatcherObservationProjector] (SP2c.1, #824) |
 * | [DispatcherModeState] | per [DefaultSimulationContext] | new instance (SP2b.6) |
 * | [SemiAutoApprovalGateway] | per [DefaultSimulationContext] | new instance (SP2b.6 follow-up) |
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
 * - [DispatcherModeState]: One mode controller per simulation (SP2b.6, Issue #561) — manages AUTO/SEMI_AUTO/MANUAL mode and human override
 * - [SemiAutoApprovalGateway]: One approval gateway per simulation (SP2b.6 follow-up, Issue #806) — bridges sim-thread approver call to GUI dialog
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
		// SP2b.9 (Issue #566): DefaultAgentService now builds a real Koog AIAgent, so it needs
		// the Ollama executor + config singletons bound below.
		// No Spring Boot: uses lightweight Koin DI instead.
		single<AgentService> { DefaultAgentService(get(), get()) } // OllamaSimpleExecutor, OllamaExecutorConfig

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

			// SP2c.17 (#840): CommandCorrelationMap (scoped per context)
			// Identity-keyed side map that correlates DispatchDecisions from post time (driver
			// thread) to apply time (sim thread), enabling outcome attribution.
			scoped<CommandCorrelationMap> { CommandCorrelationMap() }

			// SP0.5: ActuatorCommandQueue: one thread-safe handoff queue per simulation context.
			// SP2c.17 (#840): wired with the CommandCorrelationMap so every postAll() registers
			// decisions for later outcome correlation.
			scoped<ActuatorCommandQueue> { ActuatorCommandQueue(correlationMap = get()) }

			// SP2c.17 (#840): AppliedOutcomeChannel (scoped per context)
			// Bounded ring buffer that receives outcomes published by DispatchDecisionApplier (sim
			// thread) and is drained by DispatcherObservationProjector on the next captureOnSimThread
			// call to populate DispatcherObservation.appliedOutcomes.
			scoped<AppliedOutcomeChannel> { AppliedOutcomeChannel() }

			// Goal 10 dispatcher-cannot-approve-trains fix: DispatchLoopSensorPort (scoped per
			// context), backing KoogAgentFactory's queued_trains/block_inputs tools.
			// MainProcessDispatchLoopSensorPort resolves context.getMainProcess() lazily at query
			// time (always after context.setMainProcess(loop) has run) via the interface-based
			// lookup pattern (see mainProcessActiveTrains / ProvidesDispatchLoopObservation).
			scoped<DispatchLoopSensorPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				MainProcessDispatchLoopSensorPort(context)
			}

			// SP2c.1 (Issue #824): DispatcherObservationProjector — the single sim-thread-captured
			// perception value that replaces query-tool-based perception for the SP2c control-loop
			// redesign (#822). Scoped, never single: it holds context-bound live references
			// (NetworkPerceptionPort, DispatchLoopSensorPort, PathReservationRegistry, and the
			// SimulationEnvironment itself for the switch grid walk) and small per-train
			// "waiting since" bookkeeping that must not leak across concurrent simulations.
			// PathReservationRegistry comes from the navigation module (CoreModule.kt), which
			// shares this same DefaultSimulationContext scope.
			// SP2c.17 (#840): also wired with the AppliedOutcomeChannel as outcomeFeed so
			// applied outcomes flow into each tick's observation.
			scoped<DispatcherObservationProjector> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DispatcherObservationProjector(
					perceptionPort = get(),
					dispatchLoopSensorPort = get(),
					pathReservationRegistry = get<PathReservationRegistry>(),
					environment = context,
					outcomeFeed = get<AppliedOutcomeChannel>()
				)
			}

			// Downstream code (renderers, annotators, validators — SP2c.2+) depends on the
			// narrower DispatcherObservationSource interface only, per #824's acceptance
			// criterion, never on DispatcherObservationProjector directly.
			scoped<DispatcherObservationSource> { get<DispatcherObservationProjector>() }

			// SP2b.6 (Issue #561): DispatcherModeState — dispatcher operating mode controller
			// One per context for independent mode management across concurrent simulations.
			// Defaults to AUTO mode; can be overridden to SEMI_AUTO (require human approval) or
			// MANUAL (monitor-only, no automatic routing). The GUI DispatcherControlPanel binds
			// to this state to display and allow mode selection.
			scoped<DispatcherModeState> { DispatcherModeState() }

			// SP2b.6 (Issue #561): DispatchDecisionListenerHub — mutable sink that bridges the
			// sim-thread DispatchDecisionApplier to the GUI DispatcherControlPanel. The applier
			// captures this scoped instance at construction (ExampleRegistry.wireDispatcherAgent)
			// and calls onDecisionApplied for each applied decision; the GUI later points setSink
			// at a lambda that pushes the decision to the panel on the EDT. Headless/console runs
			// leave the sink null (no-op). One per context so concurrent sims never cross-wire.
			scoped<DispatchDecisionListenerHub> { DispatchDecisionListenerHub() }

			// SP2b.6 follow-up (Issue #806): SemiAutoApprovalGateway — mutable holder that bridges
			// the sim-thread DispatchDecisionApplier (which calls gateway.approve synchronously) to
			// the GUI SemiAutoApprovalDialog (wired by Frame.wireDispatcherControlPanel on sim start).
			// Headless/console runs leave the approver null, so approve() returns false and every
			// actuating SEMI_AUTO decision is dropped (logged as a warning). One per context so
			// concurrent simulations never share the same modal dialog.
			scoped<SemiAutoApprovalGateway> { SemiAutoApprovalGateway() }

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
					commandQueue = get(), // Scoped to this context (SP1.7)
					dispatchLoopSensorPort = get() // Scoped to this context (Goal 10 tool-registration fix)
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
