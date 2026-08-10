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
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationSource
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeAggregator
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
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
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.nio.file.Path
import java.util.UUID

/**
 * Resolves the [DefaultSimulationContext] this scope was opened for.
 *
 * All `scoped<...>` bindings below need the live context that backs their
 * [DefaultSimulationContext]-scoped Koin scope; this is the single place that resolves it and
 * throws consistently if the scope was somehow opened without one.
 */
private fun Scope.requireContextSource(): DefaultSimulationContext =
	getSource<DefaultSimulationContext>()
		?: throw IllegalStateException("DefaultSimulationContext source not found in scope")

/**
 * Builds the [RunParameters] recorded for a rule-based-arm run from [runConfig].
 *
 * Extracted to a standalone, non-Koin function so tests can exercise the exact mapping the
 * `scoped<DispatcherRunRecorder>` binding below uses — including a non-default
 * [DispatcherRunConfig.inferenceTimeoutSeconds] read from an injected property lookup — without
 * needing a live Koin scope. Constructing a [DispatcherRunConfig] never needs Koin at all.
 *
 * `temperature`/`model`/`promptVariant` stay at their "no prompt" values (`0.0`/`""`/`""`)
 * because a rule-based run has no prompt at all — see [RunParameters.model]'s and
 * [RunParameters.promptVariant]'s own KDoc for why the report's "rule-based" label depends on
 * exactly these sentinels rather than the LLM-arm defaults.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading)
 */
internal fun ruleBasedRunParameters(runConfig: DispatcherRunConfig): RunParameters =
	RunParameters(
		tickPeriodMs = runConfig.tickPeriodMs,
		historyN = runConfig.historyN,
		temperature = 0.0,
		maxActionsPerTick = runConfig.maxActionsPerTick,
		model = "",
		seed = null,
		inferenceTimeoutSeconds = runConfig.inferenceTimeoutSeconds,
		promptVariant = ""
	)

/**
 * Koin DI module for `:dispatcher-agent`'s components: the rule-based/LLM dispatcher, the Ollama
 * executor and tool registry, and the per-simulation sensor/actuator ports and agent factory.
 *
 * ## Bindings provided
 *
 * | Component | Scope | Default |
 * |---|---|---|
 * | [Dispatcher] | singleton | [RuleBasedDispatcher] |
 * | [DispatcherPlanner] | singleton | [RuleBasedPlanAdapter] |
 * | [AgentService] | singleton | [DefaultAgentService] |
 * | [DispatcherRunConfig] | singleton | [DispatcherRunConfig.fromProperties] |
 * | [OllamaExecutorConfig] | singleton | [OllamaExecutorConfig.default] |
 * | [OllamaSimpleExecutor] | singleton | [OllamaSimpleExecutor] |
 * | [ToolGroupRegistry] | singleton | [ToolGroupRegistry] |
 * | [NetworkPerceptionPort] | per [DefaultSimulationContext] | [DefaultNetworkPerceptionPort] |
 * | [NetworkActuatorPort] | per [DefaultSimulationContext] | [DefaultNetworkActuatorPort] |
 * | [CommandCorrelationMap] | per [DefaultSimulationContext] | new instance |
 * | [ActuatorCommandQueue] | per [DefaultSimulationContext] | new instance |
 * | [AppliedOutcomeChannel] | per [DefaultSimulationContext] | new instance |
 * | [ActionOutcomeAggregator] | per [DefaultSimulationContext] | new instance |
 * | [DispatchLoopSensorPort] | per [DefaultSimulationContext] | [MainProcessDispatchLoopSensorPort] |
 * | [DispatcherObservationSource] | per [DefaultSimulationContext] | [DispatcherObservationProjector] |
 * | [DispatcherModeState] | per [DefaultSimulationContext] | new instance |
 * | [DispatchDecisionListenerHub] | per [DefaultSimulationContext] | new instance |
 * | [SemiAutoApprovalGateway] | per [DefaultSimulationContext] | new instance |
 * | [DelegatingSimulationController] | per [DefaultSimulationContext] | new instance |
 * | [SinkHolder] | per [DefaultSimulationContext] | new instance |
 * | [CycleHistory] | per [DefaultSimulationContext] | new instance |
 * | [KoogAgentFactory] | per [DefaultSimulationContext] | [KoogAgentFactory] |
 * | [RunSnapshotStore] | per [DefaultSimulationContext] | [DefaultRunSnapshotStore] |
 * | [DispatcherRunRecorder] | per [DefaultSimulationContext] | [DefaultDispatcherRunRecorder] |
 *
 * **Note:** Per-context `KoogDispatchAgent` binding does not exist because agent creation is a
 * `suspend` function and cannot be directly wired in a Koin module (which is not a suspend
 * context). Instead, callers retrieve [KoogAgentFactory] from the scope and call
 * `factory.createAgent(context)` in their own suspend context.
 *
 * ### Scope rationale
 *
 * Singletons are stateless or explicitly shared-by-design components: the Ollama config and
 * executor, the tool registry, the agent service, and the pure rule-based dispatcher/planner.
 * Everything else is scoped per [DefaultSimulationContext] because it holds live references or
 * per-simulation state (ports, command queues, mode/approval state, the agent factory), so
 * concurrent simulations (e.g. in tests) never cross-wire.
 */
val dispatcherAgentModule: Module =
	module {
		// Dispatcher: global singleton — RuleBasedDispatcher is stateless (pure function).
		// In future, alternate implementations may include an Agentic/LLM dispatcher alongside Rule.
		single<Dispatcher> { RuleBasedDispatcher() }

		// DispatcherPlanner — pluggable planning interface.
		// Default is RuleBasedPlanAdapter wrapping the Dispatcher singleton above.
		// Swap this binding to plug in a search-based or LLM-backed planner.
		single<DispatcherPlanner> { RuleBasedPlanAdapter(get()) }

		// AgentService: global singleton for creating Koog agents. DefaultAgentService builds a
		// real Koog AIAgent, so it needs the Ollama executor + config singletons bound below.
		// No Spring Boot: uses lightweight Koin DI instead.
		single<AgentService> { DefaultAgentService(get(), get()) } // OllamaSimpleExecutor, OllamaExecutorConfig

		// Per-run knobs read from -D system properties, falling back to the committed
		// dispatcher-defaults.properties resource and then to the code constant (Issue #834,
		// SP2c.11 — see DispatcherRunConfig's "Configuration precedence" KDoc). Absent -D
		// properties and an absent/unchanged resource reproduce the previous defaults exactly,
		// so a plain `java -jar … example shuntingLoopAI 600` is unchanged.
		single<DispatcherRunConfig> { DispatcherRunConfig.fromProperties() }

		// Ollama executor configuration (singleton).
		// All agents share the same Ollama endpoint, model, and inference parameters.
		// The config is immutable and stateless, safe for global sharing.
		//
		// model and temperature are the two grid axes that were already live; they are
		// overridden here rather than in OllamaExecutorConfig.default() so the environment-variable
		// contract (OLLAMA_BASE_URL — machine configuration) stays separate from the sweep's
		// per-run contract (-D properties — measurement parameters). OllamaExecutorConfig.default()
		// itself now resolves modelName/temperature against the same committed
		// dispatcher-defaults.properties resource DispatcherRunConfig reads (Issue #834); runConfig's
		// -D-only override below still wins over that file, giving the full
		// -D property > committed file > code constant chain without reading the file twice.
		single<OllamaExecutorConfig> {
			val runConfig = get<DispatcherRunConfig>()
			OllamaExecutorConfig.default().let { base ->
				base.copy(
					modelName = runConfig.model ?: base.modelName,
					temperature = runConfig.temperature ?: base.temperature
				)
			}
		}

		// Ollama executor backend (singleton).
		// Wraps Koog's simpleOllamaAIExecutor for local LLM inference.
		// Lazy-initialized on first access (defers network connectivity check).
		// All agents share the same Ollama-backed executor (heavyweight stateful resource).
		single<OllamaSimpleExecutor> { OllamaSimpleExecutor(get()) }

		// Tool group registry (singleton).
		// Registry logic is stateless; it just coordinates tool assembly per context.
		// Actual tools (perception/actuator) are assembled per-context below.
		single<ToolGroupRegistry> { ToolGroupRegistry() }

		scope<DefaultSimulationContext> {
			// NetworkPerceptionPort (scoped per context).
			// Reads current signal/block state from the simulation environment.
			// Each context gets its own perception port instance with its own
			// perception/block/train state snapshots.
			scoped<NetworkPerceptionPort> {
				val context = requireContextSource()
				DefaultNetworkPerceptionPort(
					env = context,
					// Interface-based lookup, no reflection.
					// See mainProcessActiveTrains() for rationale.
					activeTrains = { mainProcessActiveTrains(context) }
				)
			}

			// NetworkActuatorPort (scoped per context).
			// Commands for routes, signals, and switches route through the interlocking's
			// safety logic. Each context gets its own actuator port instance with its own
			// routing services and dynamic wrappers.
			scoped<NetworkActuatorPort> {
				val context = requireContextSource()
				// InterlockingFacade is wired as the single chokepoint so all requestRoute calls
				// (tool → queue → applier → port) pass through the safety kernel.
				DefaultNetworkActuatorPort(
					env = context,
					interlockingFacade = context.scope.get<InterlockingFacade>()
				)
			}

			// CommandCorrelationMap (scoped per context).
			// Identity-keyed side map that correlates DispatchDecisions from post time (driver
			// thread) to apply time (sim thread), enabling outcome attribution.
			scoped<CommandCorrelationMap> { CommandCorrelationMap() }

			// ActuatorCommandQueue: one thread-safe handoff queue per simulation context, wired
			// with the CommandCorrelationMap so every postAll() registers decisions for later
			// outcome correlation.
			scoped<ActuatorCommandQueue> { ActuatorCommandQueue(correlationMap = get()) }

			// AppliedOutcomeChannel (scoped per context).
			// Bounded ring buffer that receives outcomes published by DispatchDecisionApplier (sim
			// thread). Drained from two places: DispatcherObservationProjector on the next
			// captureOnSimThread call (test-only DispatchTickLoop path, populates
			// DispatcherObservation.appliedOutcomes) and KoogDispatchAgentImpl.buildUserPrompt on
			// the dispatcher-agent-driver thread (the live path — see the KoogAgentFactory binding
			// below, which wires the SAME instance).
			scoped<AppliedOutcomeChannel> { AppliedOutcomeChannel() }

			// ActionOutcomeAggregator (scoped per context).
			// Production ActionOutcomeSink wired into DispatchDecisionApplier so per-action
			// attribution data (author, phase, ApplyFailureCode) actually reaches a live consumer
			// instead of only being observed by tests.
			scoped<ActionOutcomeAggregator> { ActionOutcomeAggregator() }

			// DispatchLoopSensorPort (scoped per context), backing KoogAgentFactory's
			// queued_trains/block_inputs tools. MainProcessDispatchLoopSensorPort resolves
			// context.getMainProcess() lazily at query time (always after
			// context.setMainProcess(loop) has run) via the interface-based lookup pattern (see
			// mainProcessActiveTrains / ProvidesDispatchLoopObservation).
			scoped<DispatchLoopSensorPort> {
				val context = requireContextSource()
				MainProcessDispatchLoopSensorPort(context)
			}

			// DispatcherObservationProjector — the single sim-thread-captured perception value
			// that replaces query-tool-based perception. Scoped, never single: it holds
			// context-bound live references (NetworkPerceptionPort, DispatchLoopSensorPort,
			// PathReservationRegistry, and the SimulationEnvironment itself for the switch grid
			// walk) and small per-train "waiting since" bookkeeping that must not leak across
			// concurrent simulations. PathReservationRegistry comes from the navigation module
			// (CoreModule.kt), which shares this same DefaultSimulationContext scope. Also wired
			// with the AppliedOutcomeChannel as outcomeFeed so applied outcomes flow into each
			// tick's observation.
			scoped<DispatcherObservationProjector> {
				val context = requireContextSource()
				DispatcherObservationProjector(
					perceptionPort = get(),
					dispatchLoopSensorPort = get(),
					pathReservationRegistry = get<PathReservationRegistry>(),
					environment = context,
					outcomeFeed = get<AppliedOutcomeChannel>()
				)
			}

			// Downstream code (renderers, annotators, validators) depends on the narrower
			// DispatcherObservationSource interface only, never on DispatcherObservationProjector
			// directly.
			scoped<DispatcherObservationSource> { get<DispatcherObservationProjector>() }

			// DispatcherModeState — dispatcher operating mode controller. One per context for
			// independent mode management across concurrent simulations. Defaults to AUTO mode;
			// can be overridden to SEMI_AUTO (require human approval) or MANUAL (monitor-only, no
			// automatic routing). The GUI DispatcherControlPanel binds to this state to display
			// and allow mode selection.
			scoped<DispatcherModeState> { DispatcherModeState() }

			// DispatchDecisionListenerHub — mutable sink that bridges the sim-thread
			// DispatchDecisionApplier to the GUI DispatcherControlPanel. The applier captures this
			// scoped instance at construction (ExampleRegistry.wireDispatcherAgent) and calls
			// onDecisionApplied for each applied decision; the GUI later points setSink at a
			// lambda that pushes the decision to the panel on the EDT. Headless/console runs
			// leave the sink null (no-op). One per context so concurrent sims never cross-wire.
			scoped<DispatchDecisionListenerHub> { DispatchDecisionListenerHub() }

			// SemiAutoApprovalGateway — mutable holder that bridges the sim-thread
			// DispatchDecisionApplier (which calls gateway.approve synchronously) to the GUI
			// SemiAutoApprovalDialog (wired by Frame.wireDispatcherControlPanel on sim start).
			// Headless/console runs leave the approver null, so approve() returns false and every
			// actuating SEMI_AUTO decision is dropped (logged as a warning). One per context so
			// concurrent simulations never share the same modal dialog.
			scoped<SemiAutoApprovalGateway> { SemiAutoApprovalGateway() }

			// Late-bound pacing controller for the agent-driver loop. Wiring layers (e.g.
			// :desktop-ui's ExampleRegistry.wireDispatcherAgent) hand this to AgentLoopDriver at
			// context-creation time; the GUI attaches the live SimulationRunner as delegate when
			// the run starts, pacing the agent loop with the existing real-time sync (speed
			// multiplier, pause). One per context so concurrent simulations pace independently.
			scoped<DelegatingSimulationController> { DelegatingSimulationController() }

			// SinkHolder — one per context, shared by the four actuator tools and
			// KoogAgentPlanAdapter. KoogAgentFactory installs the queue-posting wrapper on its
			// `current` at agent construction; KoogAgentPlanAdapter reads its per-cycle emission
			// counter to decide whether the LLM acted via tools (and thus whether the rule-based
			// fallback must run). Defaults to EmittedActionSink.NO_OP until the factory wires it.
			// maxActionsPerTick is enforced here (the model could otherwise emit without bound).
			scoped<SinkHolder> { SinkHolder(maxActionsPerTick = get<DispatcherRunConfig>().maxActionsPerTick) }

			// Bounded per-cycle history on the live path. One per context, written by
			// KoogAgentPlanAdapter and read by the agent it built — the same
			// two-objects-one-thread sharing pattern as SinkHolder above.
			scoped<CycleHistory> { CycleHistory(capacity = get<DispatcherRunConfig>().historyN) }

			// KoogAgentFactory (per-context builder, receives tools/config).
			// Factory is scoped because it receives context-scoped dependencies (ports, tools).
			// Each context gets its own factory instance.
			scoped<KoogAgentFactory> {
				KoogAgentFactory(
					toolRegistry = get(), // Singleton
					ollamaConfig = get(), // Singleton
					agentService = get(), // Singleton
					perceptionPort = get(), // Scoped to this context (live port)
					commandQueue = get(), // Scoped to this context
					dispatchLoopSensorPort = get(), // Scoped to this context
					sinkHolder = get(), // Scoped to this context (shared with KoogAgentPlanAdapter)
					// Resolved lazily per rejection, not captured here: ExampleRegistry declares the
					// correctly-armed recorder AFTER resolving this factory, so an eagerly-captured
					// instance would be the default rule-based one.
					runRecorderProvider = { getOrNull<DispatcherRunRecorder>() },
					cycleHistory = get(), // Scoped to this context (shared with KoogAgentPlanAdapter)
					// Same scoped AppliedOutcomeChannel instance wireDispatcherAgent (ExampleRegistry)
					// hands to DispatchDecisionApplier as its outcomeSink below — this is what closes
					// the live feedback loop end to end.
					outcomeFeed = get<AppliedOutcomeChannel>(),
					// Which system-prompt revision this run assembles (#834, SP2c.11). Read from the
					// same DispatcherRunConfig every other per-run knob comes from, so the sweep's
					// existing forked-JVM `-D` mechanism selects the A/B arm with no new channel.
					promptVariant = get<DispatcherRunConfig>().promptVariant
				)
			}

			// Per-run JSON sink. Scoped rather than single so it shares the recorder's lifetime.
			// The sweep driver points runs at its own output directory so a sweep's gate is
			// computed over that sweep's runs only, and not over whatever earlier diagnostic runs
			// happen to be lying in the default directory.
			scoped<RunSnapshotStore> {
				val root = get<DispatcherRunConfig>().runsRoot
				if (root == null) DefaultRunSnapshotStore() else DefaultRunSnapshotStore(Path.of(root))
			}

			// DispatcherRunRecorder (scoped per context).
			// One run recorder per simulation context — the Koin scope boundary replaces any
			// need for a reset() method. Each context gets an independent recorder with its own
			// counters and runId; singletons would merge counters across concurrent simulations.
			// RunParameters are seeded with conservative defaults matching the rule-based arm;
			// LLM arms will override them when they wire a KoogAgentPlanAdapter.
			scoped<DispatcherRunRecorder> {
				DefaultDispatcherRunRecorder(
					// Honour a sweep-assigned run id here too, not only in the LLM example's
					// override — the rule-based baseline is a grid cell like any other and its
					// runs have to be resumable by the same file-name scan.
					runId = get<DispatcherRunConfig>().runId ?: UUID.randomUUID().toString(),
					arm = DispatcherArm.RULE_BASED,
					// Report what this run was actually given — see ruleBasedRunParameters's KDoc.
					params = ruleBasedRunParameters(get<DispatcherRunConfig>())
				)
			}

			// Agent creation is deferred to caller code because createAgent is a suspend
			// function. This allows callers to create agents when appropriate in their suspend
			// context. In practice, AgentLoopDriver calls factory.createAgent(context) when it's
			// ready.
		}
	}
