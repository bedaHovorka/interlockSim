/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelPrewarmer
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Factory for creating per-context Koog dispatch agents.
 *
 * ## Tool surface
 *
 * [createAgent] builds a [SinkHolder] initialized with a queue-posting wrapper that converts
 * every [DispatchAction] emitted by the four actuator tools into a [cz.vutbr.fit.interlockSim.sim.DispatchDecision]
 * and posts it to the [commandQueue]. The agent receives exactly four tools from
 * [ToolGroupRegistry.assembleAllTools]: `approve_train`, `request_route`, `cancel_route`, `no_op`.
 * No perception tools and no dispatch-loop sensor tools are bundled in for the LLM; they are
 * assembled separately for other uses if needed.
 *
 * @property toolRegistry Tool group registry (singleton, injected into scope)
 * @property ollamaConfig Ollama executor config (singleton, global model/endpoint)
 * @property agentService Agent creation service (singleton, handles Koog wiring)
 * @property perceptionPort Live sensor port for network perception (scoped per context). Passed
 *   through unchanged to the actuator tools so they can pre-validate train ids in-turn; static
 *   topology itself is read from the [DefaultSimulationContext] argument, not through this port.
 * @property commandQueue Command queue for fire-and-forget actuator commands (scoped per
 *   context). Receives converted [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s from
 *   the [sinkHolder] queue-posting wrapper.
 * @property dispatchLoopSensorPort Dispatch-loop sensor port for this context. Retained for
 *   topology reads and future use; dispatch-loop sensor tools (`queued_trains`/`block_inputs`)
 *   are NOT added to the LLM's tool surface.
 * @property sinkHolder Per-context shared [SinkHolder] for the four actuator tools. Holds the
 *   queue-posting wrapper installed here so every actuator tool's `emit` posts its converted
 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to [commandQueue]; the same instance is
 *   read by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] to detect via
 *   the per-cycle emission counter whether the LLM acted via tools (and therefore the
 *   rule-based fallback must not double-dispatch).
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService,
	private val perceptionPort: NetworkPerceptionPort,
	private val commandQueue: ActuatorCommandQueue,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort,
	private val sinkHolder: SinkHolder,
	/**
	 * Optional per-run recorder receiving every coded in-turn tool rejection.
	 *
	 * The live path rejects arguments at the tool boundary and nowhere else — `ActionValidator`
	 * is reached only from the test-only `DispatchTickLoop` — so without this the per-run JSON's
	 * `rejectionsByCode` would be structurally always empty.
	 *
	 * Resolved **lazily, per rejection** rather than captured at construction. `ExampleRegistry`
	 * overrides the scoped recorder with the correct arm *after* it has already resolved this
	 * factory (it needs the factory to build the planner first), so a field captured in the
	 * constructor would hold the module's default rule-based recorder — a different instance from
	 * the one the run actually persists, and rejections would be counted into an object nobody ever
	 * writes. A provider makes the wiring order irrelevant.
	 *
	 * Returns `null` for agents built outside a run (tests, tooling); the tool surface is unaffected
	 * either way.
	 */
	private val runRecorderProvider: () -> DispatcherRunRecorder? = { null },
	/**
	 * Bounded per-cycle history handed to the built agent and written by
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] after each cycle.
	 * Shared per context exactly like [sinkHolder], and for the same reason: the writer and the
	 * reader are two different objects on the same driver thread.
	 *
	 * Defaults to a disabled history so agents built outside a run behave as before this history
	 * existed.
	 */
	private val cycleHistory: CycleHistory = CycleHistory(capacity = 0),
	/**
	 * Optional per-context feed of previously-applied outcomes, threaded straight through to
	 * [AgentService.createDispatchAgent] exactly like [cycleHistory] above (same per-context
	 * scoped-sharing rationale). `null` by default so agents built outside a run (tests, tooling)
	 * behave as before this feed existed.
	 */
	private val outcomeFeed: AppliedOutcomeFeed? = null,
	/**
	 * Which revision of the system prompt this factory assembles (#834, SP2c.11).
	 *
	 * Defaults to [PromptVariant.DEFAULT] ([PromptVariant.BASELINE]) so a factory built without an
	 * opinion — every test that predates this seam, and every caller outside a configured run —
	 * produces the exact prompt PR #896 shipped. The live path receives the per-run value through
	 * [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.promptVariant], which resolves
	 * `-D` system property > committed `dispatcher-defaults.properties` > code constant like every
	 * other run knob; nothing here introduces a second configuration channel.
	 */
	private val promptVariant: PromptVariant = PromptVariant.DEFAULT
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Builds the DISPATCHER system prompt for [variant].
		 *
		 * Selection only — each variant's text lives in its own builder below, so the two can never
		 * partially bleed into each other and [PromptVariant.BASELINE] stays reproducible byte for
		 * byte. Wording within a variant is subject to agent-architect review; only the stable
		 * phrases [KoogAgentFactoryTest] asserts on are load-bearing.
		 *
		 * Not a precomputed constant: [maxActions] is read per call from the [SinkHolder] instance
		 * [createAgent] was constructed with, so the stated budget can never drift from what
		 * [SinkHolder.tryEmit] actually enforces — [SinkHolder] itself is built from
		 * [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.maxActionsPerTick] by
		 * [cz.vutbr.fit.interlockSim.dispatcher.di.DispatcherAgentModule]'s existing scoped
		 * wiring, so this threads the real per-run value with no new DI path. That property is
		 * variant-independent by construction: every variant interpolates the same [maxActions]
		 * argument, never a literal. `cap` (the concurrent-train admission ceiling) is a distinct
		 * constant, [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS] — the non-LLM dispatcher's
		 * own policy — kept exactly as before so the LLM and rule-based arms never disagree about
		 * station capacity.
		 *
		 * ## No-menu compliance
		 *
		 * Every variant's procedure and rules are dash-bulleted, never numbered (`^\s*\d+[.)]\s` is
		 * forbidden), and the text avoids "option"/"optional"/"choose one"/"select" everywhere —
		 * [LivePromptNoMenuTest] locks this against regression on the real, assembled prompt, for
		 * every variant.
		 *
		 * ## Queued trains carry no destination clause in any variant
		 *
		 * [KoogDispatchAgentImpl.renderQueuedTrainLine] deliberately renders a queued train as
		 * approve-only, naming no topology endpoint at all — no admission step below may imply
		 * otherwise, so none of them mentions a queued train's exit.
		 */
		internal fun buildSystemPrompt(
			maxActions: Int,
			variant: PromptVariant
		): String =
			when (variant) {
				PromptVariant.BASELINE -> buildBaselineSystemPrompt(maxActions)
				PromptVariant.REVISED -> buildRevisedSystemPrompt(maxActions)
			}

		/**
		 * The prompt exactly as PR #896 shipped it — the measurement's control arm.
		 *
		 * **Do not edit this text.** It is the zero point every [PromptVariant.REVISED] number is
		 * compared against; changing it silently moves that zero point and invalidates every
		 * comparison already recorded. A new idea belongs in a new variant, not in this function.
		 */
		private fun buildBaselineSystemPrompt(maxActions: Int): String {
			val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
			return buildString {
				appendLine(
					"You are a railway dispatcher. Every call you receive is one tick: read the " +
						"cycle message, decide, act through your tools, and stop — nothing carries " +
						"forward except what you actually called a tool for."
				)
				appendLine(
					"The only actuator tools available are approve_train, request_route, " +
						"cancel_route, and no_op — there is no tool to set a signal aspect or " +
						"switch position directly; signals and switches change only as a side " +
						"effect of request_route/cancel_route. Always prioritize safety."
				)
				appendLine(
					"All entry/exit points, signals, switches, and blocks you may reference are " +
						"listed by exact name in the STATION TOPOLOGY section below. Never invent, " +
						"abbreviate, or guess a name — if you need a name you don't see there, do " +
						"not call the tool. request_route's fromEndpointName/toEndpointName " +
						"arguments accept InOut and Signal names — never a Block ID from the Blocks " +
						"list."
				)
				appendLine(
					"You have no tool for querying state: every train id, count, and position you " +
						"may act on is already written in the cycle message you are given. Only " +
						"ever pass a train id that appears there verbatim — never one taken from an " +
						"example, and never one you inferred."
				)
				appendLine(
					"At most $maxActions actions besides no_op are accepted this tick; no_op never " +
						"counts against that budget."
				)
				appendLine("On every tick, follow these steps in order:")
				appendLine(
					"- If the cycle message has an OUTCOMES OF YOUR PREVIOUS ACTIONS section, read " +
						"it first: it names what your last calls actually did."
				)
				appendLine(
					"- admission comes first: while there are queued (unapproved) trains and " +
						"fewer than $cap trains are currently active, call approve_train for queued " +
						"trains in the order listed, up to $cap total active. Queued trains take " +
						"approve_train only, nothing else."
				)
				appendLine(
					"- For each active train whose line names a NEXT SECTION to reserve, call " +
						"request_route once for it, copying the from/to names from that NEXT " +
						"SECTION clause exactly."
				)
				appendLine(
					"- a train with no NEXT SECTION line gets no route request this tick — its " +
						"line already says why (route already set, or nothing reservable yet); " +
						"leave it be."
				)
				appendLine(
					"- Call cancel_route only for a train whose reservation is no longer needed " +
						"and that is not currently standing on it. A REFUSED request in OUTCOMES " +
						"reserved nothing and needs no cancel_route."
				)
				appendLine("- Otherwise, call no_op with a brief reason.")
				appendBaselineNonNegotiableRules(maxActions)
			}.trimEnd('\n')
		}

		/**
		 * Appends the "Rules that never bend:" section to [buildBaselineSystemPrompt]'s
		 * [StringBuilder]; factored out to keep that function within detekt's `LongMethod` budget,
		 * wording unchanged from when it lived inline. Frozen for the same reason its caller is.
		 */
		private fun StringBuilder.appendBaselineNonNegotiableRules(maxActions: Int) {
			appendLine("Rules that never bend:")
			appendLine(
				"- Reservation is not movement: request_route only reserves interlocking " +
					"resources for a train that is already active — it never admits a queued " +
					"train; only approve_train does that."
			)
			appendLine(
				"- Copy every name character-for-character from STATION TOPOLOGY or a NEXT " +
					"SECTION line — never a Block ID."
			)
			appendLine(
				"- Reserving a section that is not directly in front of a train does not " +
					"release anything that train already holds; it only locks that track " +
					"against other trains."
			)
			appendLine(
				"- This cycle's message always supersedes anything recorded earlier: where " +
					"they disagree, trust this cycle."
			)
			appendLine(
				"- Never call approve_train for a train already listed as active — " +
					"approve_train applies only to a queued train."
			)
			appendLine(
				"- Once the per-tick action budget is spent, end your turn: further tool " +
					"calls this tick are refused."
			)
			appendLine(
				"- When you have taken the actions this tick needs — never more than $maxActions " +
					"— end the tick: reply with one short plain-text sentence and make no further " +
					"tool calls. When no action is needed this tick, call no_op once and then reply " +
					"the same way. Only a plain-text reply ends the tick."
			)
			appendLine(
				"- If a tool call for a train is rejected twice in one tick, stop acting on that " +
					"train and move on or reply."
			)
			appendLine(
				"no_op is a correct and frequent answer, not a failure to act — most ticks " +
					"have nothing new to do. Repeating an action already in force is refused, " +
					"wastes the tick, and tells the next tick nothing new."
			)
		}

		/**
		 * The #834 revision of the system prompt: same guarded properties, fewer tokens, plus an
		 * explicit empty-station idle path.
		 *
		 * ## Why shorter at all
		 *
		 * Cycle latency is the binding constraint on the whole LLM arm. PR #896 measured 60-100 s
		 * per cycle against `qwen2.5:7b-instruct`, and every token of system prompt is re-read on
		 * every cycle. A slower cycle is not merely a slower run: `DefaultSnapshotSignal` keeps at
		 * most one pending control tick, so ticks that arrive while a cycle is still thinking are
		 * coalesced away and the run simply gets fewer decisions. #847 measured the same mechanism
		 * from the other side — enabling the history block alone cost roughly a fifth of the
		 * decision rate. Length is therefore a measured cost, not a style preference.
		 *
		 * ## What was cut, and why each cut is safe
		 *
		 * Redundancy only — no property that a measured failure bought was dropped:
		 *
		 * - **The action budget was stated three times** in [buildBaselineSystemPrompt] (a standalone
		 *   sentence, the "Once the per-tick action budget is spent" rule, and the "never more than
		 *   N" clause of the turn-termination rule). All three now live in one sentence that still
		 *   interpolates the same [maxActions] value #847's 146 `ACTION_LIMIT_EXCEEDED` rejections
		 *   established must be stated.
		 * - **Name discipline was stated three times** (the STATION TOPOLOGY paragraph, the
		 *   verbatim-train-id paragraph, and the "Copy every name character-for-character" rule).
		 *   Merged into one sentence that keeps both load-bearing clauses: names copied
		 *   character-for-character, and `fromEndpointName`/`toEndpointName` take an InOut or Signal
		 *   name and **never a Block ID**. #847 recorded zero `UNKNOWN_ENDPOINT`, zero
		 *   `ENDPOINT_IS_BLOCK_ID`, zero `UNKNOWN_TRAIN` and zero `BLANK_ARGUMENT` rejections, so
		 *   this is a solved problem being stated once instead of three times, not a relaxed one.
		 * - **"Reservation is not movement"** duplicated the admission step's own "queued trains take
		 *   approve_train only"; the two are now one clause on the admission bullet.
		 * - **The NEXT SECTION pair of bullets** (act on a train that has one, leave alone a train
		 *   that does not) is one bullet — #893's fix is what took journeys per run from 0 to 1-3, so
		 *   the procedure itself is untouched, only its line count.
		 * - **"Always prioritize safety"** is gone: it is the one sentence in the prompt that no
		 *   measurement ever asked for and that names no action the model can take.
		 * - **"You have no tool for querying state"** is gone. The four-tool inventory two lines
		 *   above already says which tools exist, and
		 *   [KoogDispatchAgentImpl.buildUserPrompt] asserts every cycle that its two train lists are
		 *   "the complete set of trains you may name this cycle". The property this sentence guarded
		 *   is therefore still stated twice; only this third statement of it is gone.
		 * - **"no_op is a correct and frequent answer…"** is gone *as a separate closing line*. The
		 *   property it carries is not: the catch-all bullet below ends "That is the correct and
		 *   expected result for such a tick, not a failure to act", which is the same claim in the
		 *   place the model reaches it — inside the procedure, at the moment it applies. Its second
		 *   half ("Repeating an action already in force is refused…") survives verbatim as a rule.
		 *
		 * ## What was NOT cut, despite being redundant, and why
		 *
		 * `no_op` is legitimised **three** times in [buildBaselineSystemPrompt] and the obvious
		 * "state it once" cut is exactly the wrong one to make here. Redundancy is cheap where
		 * compliance is already measured at 100% (the name rules, whose rejection counts #847 puts
		 * at zero) and expensive to remove where compliance was measured as *failing* — #896 measured
		 * 2-7 ticks per run ending in a bare text reply instead of the taught `no_op`. So the
		 * procedure's final bullet stays an **unconditional catch-all**, exactly as
		 * [buildBaselineSystemPrompt]'s "- Otherwise, call no_op with a brief reason." is.
		 *
		 * An earlier cut of this revision narrowed that bullet to the empty-station case ("no queued
		 * trains, and no active train with a NEXT SECTION line"). That leaves a real and routine
		 * state uncovered: `queued > 0` **and** `active == cap` **and** no active train has a NEXT
		 * SECTION line, which any run with more than `cap` trains reaches regularly. Admission's
		 * guard is false (the station is full), routing's is false, cancelling's is false, and the
		 * narrowed idle bullet is false *because queued trains exist* — no bullet would fire and the
		 * prompt would say nothing at all. The model then replies in plain text,
		 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] sees no emission, and
		 * the tick is scored `RULE_FALLBACK` — the very outcome #834 exists to reduce.
		 *
		 * ## What was kept, and what was added
		 *
		 * Kept verbatim in substance: the turn-termination affordance (before it, the model never
		 * ended a turn — zero `no_op` calls ever and iteration exhaustion in 3-4 of 5 cycles), the
		 * two-rejections-stop rule, the terminal never-approve-an-active-train directive, the
		 * explicit interpolated action budget, and the whole `NEXT SECTION` procedure. The
		 * turn-termination sentence moved *up* into the opening framing rather than sitting last in
		 * the rules: it is the instruction whose absence broke every cycle, and it now occupies the
		 * most salient position in the prompt while costing fewer tokens than the rule it replaces.
		 *
		 * Added: the catch-all bullet now says what the correct output *is*, not merely that `no_op`
		 * is available. Task 1 of #834 made a tick with no tool calls a scored success
		 * ([cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome.LLM_NO_OP]) rather than a
		 * rule-based-fallback failure, so naming the expected output — one `no_op`, then a closing
		 * sentence — is now worth its tokens. [buildBaselineSystemPrompt]'s catch-all says only
		 * "call no_op with a brief reason", never that replying afterwards is what ends the tick nor
		 * that this is the expected result rather than a failure to act; #896 measured 2-7 ticks per
		 * run ending in a bare text reply instead of the taught `no_op`.
		 *
		 * ## This variant is a bundle, not a single-variable experiment
		 *
		 * Its stated hypothesis is length, but it also **moves** the turn-termination affordance to
		 * the first line and **adds** the idle-output instruction above. Both are deliberate and
		 * both are argued from measurements, but they are separate interventions: a difference the
		 * sweep measures between the arms cannot be attributed to token count alone. Whoever reads
		 * the sweep result must read it as "this bundle vs. #896's prompt", and isolating the three
		 * would need three more variants and three more arms.
		 */
		private fun buildRevisedSystemPrompt(maxActions: Int): String {
			val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
			return buildString {
				appendLine(
					"You are a railway dispatcher. Every call you receive is one tick: read the " +
						"cycle message, act through your tools, then end the tick with one short " +
						"plain-text sentence. Only a plain-text reply ends the tick, and nothing " +
						"carries forward except what you actually called a tool for."
				)
				appendLine(
					"The only actuator tools available are approve_train, request_route, " +
						"cancel_route, and no_op — there is no tool to set a signal aspect or " +
						"switch position directly; signals and switches change only as a side " +
						"effect of request_route/cancel_route."
				)
				appendLine(
					"Copy every endpoint name character-for-character from the STATION TOPOLOGY " +
						"section below or from a NEXT SECTION line, and every train id verbatim " +
						"from the cycle message; never invent, abbreviate, or infer one. " +
						"request_route's fromEndpointName/toEndpointName take an InOut or Signal " +
						"name — never a Block ID."
				)
				appendLine(
					"At most $maxActions actions besides no_op are accepted this tick; no_op never " +
						"counts against that budget, and once the budget is spent every further " +
						"action this tick is refused — end your turn instead."
				)
				appendLine("On every tick, follow these steps in order:")
				appendLine(
					"- When the cycle message has an OUTCOMES OF YOUR PREVIOUS ACTIONS section, " +
						"read it first: it names what your last calls actually did."
				)
				appendLine(
					"- Admission comes first: while there are queued (unapproved) trains and fewer " +
						"than $cap trains are currently active, call approve_train for queued " +
						"trains in the order listed, up to $cap total active. A queued train takes " +
						"approve_train and nothing else — request_route reserves track, it never " +
						"admits a train."
				)
				appendLine(
					"- For each active train whose line names a NEXT SECTION to reserve, call " +
						"request_route once for it, copying the from/to names from that NEXT " +
						"SECTION clause exactly. A train with no NEXT SECTION line gets no route " +
						"request this tick — its line already says why (route already set, or " +
						"nothing reservable yet); leave it be."
				)
				appendLine(
					"- Call cancel_route only for a train whose reservation is no longer needed and " +
						"that is not currently standing on it. A REFUSED request in OUTCOMES " +
						"reserved nothing and needs no cancel_route."
				)
				appendLine(
					"- Otherwise — nothing to approve, nothing to reserve, nothing to cancel — call " +
						"no_op once with a brief reason and then reply. That is the correct and " +
						"expected result for such a tick, not a failure to act."
				)
				appendRevisedNonNegotiableRules()
			}.trimEnd('\n')
		}

		/**
		 * Appends [buildRevisedSystemPrompt]'s "Rules that never bend:" section.
		 *
		 * Factored out for the same reason [appendBaselineNonNegotiableRules] is — detekt's
		 * `LongMethod` budget — and takes no `maxActions` argument, because the revision states the
		 * budget exactly once, up in the framing paragraphs.
		 */
		private fun StringBuilder.appendRevisedNonNegotiableRules() {
			appendLine("Rules that never bend:")
			appendLine(
				"- Never call approve_train for a train already listed as active — approve_train " +
					"applies only to a queued train."
			)
			appendLine(
				"- Reserving a section that is not directly in front of a train does not release " +
					"anything that train already holds; it only locks that track against other " +
					"trains."
			)
			appendLine(
				"- This cycle's message always supersedes anything recorded earlier: where they " +
					"disagree, trust this cycle."
			)
			appendLine(
				"- If a tool call for a train is rejected twice in one tick, stop acting on that " +
					"train and move on or reply."
			)
			appendLine(
				"- Repeating an action already in force is refused, wastes the tick, and tells the " +
					"next tick nothing new."
			)
		}
	}

	/**
	 * Create a Koog dispatch agent for the given simulation context.
	 *
	 * Assembles the four-tool actuator surface using a [SinkHolder] backed by a queue-posting
	 * wrapper. The wrapper converts each emitted [DispatchAction] to a
	 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision] via [DispatchTickLoop.toDispatchDecisions]
	 * and posts it to [commandQueue] (fire-and-forget; applied on the kDisco thread by
	 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]).
	 *
	 * Model warm-up ([OllamaModelPrewarmer.warmUp]) runs concurrently with the topology/prompt/tool
	 * assembly below rather than before it: the two are independent (warm-up is network I/O against
	 * Ollama, assembly is local CPU work). Both are joined — warm-up is awaited — *before* the final
	 * [AgentService.createDispatchAgent] call, which is the synchronization point, so overlapping
	 * the two shortens the time this suspend function takes overall without changing what it waits
	 * for before returning. `warmUp` is non-fatal: it catches every non-cancellation failure and
	 * returns normally, so the await throws only on cooperative cancellation of the driver coroutine.
	 *
	 * The warm-up is launched with `async(Dispatchers.IO)`, not plain `async`, on purpose: every
	 * real caller reaches [createAgent] through a single-threaded `runBlocking` event loop
	 * (`AgentDriverLoop`, `AgentLoopDriver`, `DelegatingSimulationController`). On such a
	 * dispatcher, a plain `async { ... }` child is merely *queued* — it only starts running once
	 * the parent coroutine suspends — so without an explicit dispatcher the child would not begin
	 * until `warmUp.await()` is reached below, after all the local assembly work has already run,
	 * defeating the overlap entirely. Explicitly dispatching to [Dispatchers.IO] starts the child
	 * on an IO thread immediately, genuinely concurrent with the assembly below.
	 * [OllamaModelPrewarmer.warmUp] already confines its own HTTP call to `Dispatchers.IO`
	 * internally, so this only changes *when* the coroutine is first dispatched, not where the
	 * network call executes — no redundant or nested dispatch.
	 *
	 * @param context Current simulation context (for static topology extraction).
	 * @return A configured Koog dispatch agent ready for dispatch decisions.
	 */
	suspend fun createAgent(context: DefaultSimulationContext): KoogDispatchAgent =
		coroutineScope {
			// Preload the model before the dispatch timeout window; started here, awaited below.
			// Dispatchers.IO ensures this actually starts now (not merely queued) even when the
			// caller is on a single-threaded runBlocking dispatcher — see KDoc above.
			val warmUp = async(Dispatchers.IO) { OllamaModelPrewarmer.warmUp(ollamaConfig) }

			logger.debug {
				"KoogAgentFactory.createAgent: context=${context.javaClass.simpleName}, " +
					"model=${ollamaConfig.modelName}, promptVariant=$promptVariant " +
					"(SP2c.6 SinkHolder 4-tool surface)"
			}

			// Static topology never changes during a run — read once at agent construction.
			// The InOut/Signal names double as the valid-endpoint set request_route validates against.
			val topology = StationTopologySerializer.describe(context)
			val validEndpointNames: Set<String> = (topology.inOuts + topology.signals.map { it.name }).toSet()

			// Install the queue-posting wrapper on the per-context SinkHolder. Every DispatchAction
			// emitted by an actuator tool is converted to a DispatchDecision and posted to
			// commandQueue (fire-and-forget). The SinkHolder is shared by all four tools and with
			// KoogAgentPlanAdapter, which reads its per-cycle emission counter.
			sinkHolder.current =
				EmittedActionSink { action ->
					val decisions: List<DispatchDecision> =
						when (action) {
							is DispatchAction.ApproveTrain ->
								listOf(DispatchDecision.ApproveTrain(trainId = action.trainId))
							is DispatchAction.RequestRoute ->
								listOf(
									DispatchDecision.RequestRoute(
										trainName = action.trainId,
										fromEndpointName = action.fromEndpointName,
										toEndpointName = action.toEndpointName
									)
								)
							is DispatchAction.CancelRoute ->
								listOf(DispatchDecision.ReleaseRoute(trainName = action.trainId))
							is DispatchAction.NoOp -> emptyList()
						}
					commandQueue.postAll(decisions)
				}

			// Assemble the four-tool actuator surface for this context. Both ports are passed through so
			// the actuator tools can pre-validate train ids in-turn: perceptionPort supplies the active
			// trains, dispatchLoopSensorPort the queued ones. Both are existing internal ports —
			// reusing them adds no LLM-facing query tool, and ActuatorToolSurface.assertExactly below
			// still holds the surface at four.
			val tools =
				toolRegistry.assembleAllTools(
					validEndpointNames,
					sinkHolder,
					perceptionPort,
					dispatchLoopSensorPort,
					topology.blocks.map { it.name }.toSet(),
					topology.inOuts.toSet()
				)
			// Assert the surface on the REAL tools, before decoration — the decorator is transparent
			// (it forwards name/description/parameters) but asserting first keeps the four-tool contract
			// checked against what the registry actually built.
			ActuatorToolSurface.assertExactly(tools)
			val instrumentedTools = tools.map { tool -> RejectionRecordingTool(tool, ::recordRejection) }

			// Serialize static topology into the system prompt once.
			val topologyPrompt = StationTopologySerializer.toPromptText(topology)
			// Budget is read from this factory's own sinkHolder, not a hardcoded literal — see
			// buildSystemPrompt's KDoc for why that is safe. The topology half is variant-independent
			// on purpose (#834 AC: StationTopologySerializer is untouched by the prompt rebuild), so
			// only the instruction half varies between A/B arms.
			val systemPrompt = "${buildSystemPrompt(sinkHolder.maxActionsPerTick, promptVariant)}\n\n$topologyPrompt"

			// Join the warm-up before building the agent: this is the documented synchronization
			// point — both warm-up (network I/O) and the local assembly above are finished before
			// the final createDispatchAgent call. warmUp is non-fatal (OllamaModelPrewarmer catches
			// every non-CancellationException failure and returns normally), so this await only
			// throws on cooperative cancellation of the driver coroutine.
			warmUp.await()

			val agent =
				agentService.createDispatchAgent(
					modelName = ollamaConfig.modelName,
					tools = instrumentedTools,
					systemPrompt = systemPrompt,
					cycleHistory = cycleHistory,
					outcomeFeed = outcomeFeed
				)

			logger.debug { "KoogAgentFactory: created agent with ${instrumentedTools.size} tools (SP2c.6 4-tool surface)" }
			agent
		}

	/**
	 * Records one coded in-turn tool rejection on the per-run recorder.
	 *
	 * Phase is [ActionPhase.REJECTED_BY_VALIDATOR] — the action never reached the applier, so it is
	 * a validator-stage rejection in every sense the snapshot distinguishes.
	 *
	 * `tickIndex` is `0`, deliberately **not** `-1`: the recorder treats `-1` as "the correlation
	 * map had no entry" and counts it in `unattributedApplies`, which is a statement about applied
	 * actions. A rejected call was never applied and never correlated, so borrowing that sentinel
	 * would corrupt an unrelated metric.
	 */
	private fun recordRejection(
		toolName: String,
		code: RejectionCode
	) {
		runRecorderProvider()?.onActionOutcome(
			ActionOutcome(
				phase = ActionPhase.REJECTED_BY_VALIDATOR,
				rejection = code,
				applyFailure = null,
				authored =
					AuthoredAction(
						author = ActionAuthor.LLM,
						reason = "tool_rejected",
						decisionKind = toolName,
						tickIndex = 0L
					)
			)
		)
	}
}
