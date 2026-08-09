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

import ai.koog.agents.core.agent.AIAgent
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Real Koog-based railway dispatch agent.
 *
 * Wraps a Koog `AIAgent<String, String>` (built by [DefaultAgentService] via Koog's
 * `singleRunStrategy()` tool-calling loop: call the LLM, execute any requested tools, send tool
 * results back, repeat until the LLM replies with plain text).
 *
 * ## Where the actual dispatch decisions go
 *
 * Actuator [DomainTool]s (`RequestRouteTool`, `ReleaseRouteTool`, etc.) post
 * [DispatchDecision]s directly to the shared `ActuatorCommandQueue` as a side effect of their own
 * `execute()`, fire-and-forget, during [aiAgent]'s tool-calling loop. [decideAsync] therefore
 * always returns an empty list — that is the **normal, successful outcome**, not a sign the LLM
 * did nothing (see [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter], the only
 * place a non-empty/empty distinction is load-bearing, and it no longer treats empty as failure).
 *
 * @param aiAgent The real Koog agent, pre-wired with the Ollama executor, model, tool registry,
 *   and system prompt (topology + dispatcher persona) at construction time in
 *   [DefaultAgentService.createDispatchAgent].
 * @param cycleHistory Bounded record of the previous N cycles, rendered into the per-cycle prompt
 *   ahead of the current state (lost-in-the-middle ordering). Defaults to a disabled history,
 *   which reproduces the previously stateless-per-cycle behaviour.
 * @param outcomeFeed Optional source of [AppliedOutcome]s accumulated since the previous cycle,
 *   drained once per [buildUserPrompt] call and rendered as the "OUTCOMES OF YOUR PREVIOUS
 *   ACTIONS" block. `null` (the default) renders no block at all. Before this feed existed, the
 *   channel it wraps had no production drainer at all: 173 `ALL_PATHS_BLOCKED` apply failures
 *   were invisible to the model, which kept re-emitting the same refused route.
 */
class KoogDispatchAgentImpl(
	private val aiAgent: AIAgent<String, String>,
	private val cycleHistory: CycleHistory = CycleHistory(capacity = 0),
	private val outcomeFeed: AppliedOutcomeFeed? = null
) : KoogDispatchAgent {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * The built Koog agent, exposed `internal` for the build-contract test to reflect on its
	 * strategy + [ai.koog.agents.core.agent.config.AIAgentConfig] without driving a live LLM run.
	 * Production code must not reach past [decideAsync]; this getter is a test-only seam, not a
	 * public API.
	 */
	internal val builtAgent: AIAgent<String, String> get() = aiAgent

	override suspend fun decideAsync(observation: DispatchObservation): List<DispatchDecision> {
		val userPrompt = buildUserPrompt(observation)
		val finalText = aiAgent.run(userPrompt)
		logger.debug {
			"KoogDispatchAgentImpl.decideAsync: simTime=${observation.snapshot.simTime}, " +
				"agent finished (reply length=${finalText.length}); any actuation already happened " +
				"as tool-call side effects during this call"
		}
		return emptyList()
	}

	/**
	 * Builds the per-cycle user prompt. The static station topology is already in the system prompt
	 * ([cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer]), so only live state
	 * belongs here.
	 *
	 * ## This message is the model's only source of train identity
	 *
	 * The agent used to have perception tools (`queued_trains`, `all_train_positions`, …) and this
	 * prompt could stay minimal and simply point at them. Those tools are gone — the surface is
	 * four actuators ([cz.vutbr.fit.interlockSim.dispatcher.agents.ActuatorToolSurface]) — but the
	 * prompt kept telling the model to "use the perception tools" while reporting active trains as
	 * a bare *count*. The model consequently had no way to name a running train and invented ids (a
	 * live run produced `trainId=4`), and burned its `maxAgentIterations` budget reaching for tools
	 * that do not exist.
	 *
	 * Both lists are therefore rendered in full here. The data needs no new plumbing: queued trains
	 * come from [DispatchObservation.unapprovedTrains] and active ones from
	 * [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.trainPositions], both already on the
	 * observation. Keep it that way — if a future change needs more live state, add it to the
	 * observation and render it here rather than reintroducing an LLM-facing query tool.
	 *
	 * ## Never render a name the model cannot use as an argument
	 *
	 * The first cut of the active-train list also rendered each train's position as
	 * `front at <frontSectionName>`. [cz.vutbr.fit.interlockSim.ports.TrainPositionReading.frontSectionName]
	 * is a **block** name, and blocks are the one category of name that is never a valid
	 * `request_route` endpoint — so the message was handing the model a forbidden name in the
	 * middle of its live state. A verification run confirmed the obvious outcome: the model reused
	 * those block names as endpoints (48 rejected calls naming block `k1`), and even copied the
	 * literal `"unknown section"` null-fallback string as an endpoint 18 more times — together the
	 * dominant source of bad calls in that run.
	 *
	 * Position is decorative here anyway: none of the four actuator tools accepts a block. The
	 * list is therefore ids only. If position is wanted later, render something the tool surface
	 * can actually consume — the *signal ahead* of the train is both a legal `request_route`
	 * endpoint and what `ActionValidator.ORIGIN_NOT_AT_TRAIN_POSITION` expects — and never a bare
	 * placeholder string that can be copied verbatim.
	 *
	 * ## NEXT SECTION facts
	 *
	 * The kernel rejects a queued train's route when the requested origin is not where the train
	 * actually stands — a "correctly directed, wrongly placed" request. That closes the gate but
	 * does not tell the model where the *right* request is. Each active-train row here now also
	 * renders the one forward route request [NextHopResolver] computes as eligible right now (see
	 * [renderActiveTrainLine]), sourced exclusively from
	 * [DispatchObservation.innerBlockInputs]/[DispatchObservation.outerBlockInputs] and
	 * [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.trainPerceptions] — never a block id (same
	 * rule as above).
	 */
	private fun buildUserPrompt(observation: DispatchObservation): String =
		buildString {
			// Lost-in-the-middle: history first, current state last — closest to generation.
			// Empty string when historyN = 0, so the disabled arm renders nothing at all rather
			// than a bare header.
			append(cycleHistory.renderPromptBlock())
			// Drain-once - every outcome published since the last drain is removed from the
			// channel by drainSince here, so a re-published (still-empty) channel on the next
			// call renders nothing for it. Placed after history, before current state, per the
			// same lost-in-the-middle ordering above. Renders the empty string when outcomeFeed
			// is unwired or nothing was published (see renderAppliedOutcomesBlock).
			append(renderAppliedOutcomesBlock(outcomeFeed?.drainSince(0L) ?: emptyList()))
			appendLine("Dispatch cycle at simTime=${observation.snapshot.simTime}.")
			// State the active count/cap directly so the admission precondition can be evaluated
			// in one shot — this stateless cycle has no memory of the same check from last time.
			appendLine(
				"Active (approved) trains right now: ${observation.approvedTrainCount} / " +
					"${RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS}"
			)
			// Ids only — deliberately no position. See "Never render a name the model cannot use"
			// in this method's KDoc: TrainPositionReading.frontSectionName is a *block* name, and
			// rendering it here got it copied straight back as a request_route endpoint. The NEXT
			// SECTION clause added here uses signal/InOut names only — see renderActiveTrainLine.
			observation.snapshot.trainPositions.forEach {
				appendLine(renderActiveTrainLine(it.trainId, observation))
			}
			appendLine("Queued (unapproved) trains: ${observation.unapprovedTrains.size}")
			observation.unapprovedTrains.forEach {
				appendLine(renderQueuedTrainLine(it.trainId))
			}
			// Constrains the *data* the model may name, and keeps the switch/signal side-effect
			// rule. It deliberately makes no claim about which tools exist: that belongs in the
			// system prompt (KoogAgentFactory), which is built alongside the tool surface and
			// knows it, whereas this method is generic. An earlier cut asserted "no tool to query
			// state" here and contradicted callers that legitimately supply one — see
			// KoogRealOllamaToolCallingTest's failing-tool harness.
			appendLine(
				"The two lists above are the complete set of trains you may name this cycle; pass a " +
					"trainId/trainName exactly as written there, never one you inferred. Switch and " +
					"signal aspects change as a side effect of requesting and canceling routes — " +
					"there is no tool to set them directly."
			)
			if (observation.unapprovedTrains.isNotEmpty()) {
				appendLine(
					"Reminder: a granted route reservation alone does not move a train — call " +
						"approve_train(trainId) for each queued train above once you want it to depart."
				)
			}
			// Replaces the legacy "Then use the tools available to you to reserve routes, release
			// routes, or approve queued trains as needed. ... Respond with plain text when
			// finished." boilerplate, which never told the model a tick has a hard end.
			// Deliberately the LAST line appended -- closest to generation, and unconditional so
			// it lands after the reminder above too.
			appendLine(
				"When your actions for this tick are done, finish with one short plain-text sentence " +
					"and no further tool calls."
			)
		}

	/**
	 * Renders one active train's row in the current-state train list, appending the NEXT SECTION
	 * fact so the model can name the one route request that helps this train this cycle.
	 *
	 * ## Field style, quoted names, never a block id
	 *
	 * [NextHopOutcome.Hop.fromSignalName]/[NextHopOutcome.Hop.toSeparatorName] come from
	 * [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.towardSemaphoreName]/
	 * [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.toSeparatorName] — signal or InOut
	 * names, both legal `request_route` endpoints — never
	 * [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.blockId]. See this class's
	 * [buildUserPrompt] KDoc ("Never render a name the model cannot use as an argument") for why
	 * that distinction matters.
	 *
	 * ## `toSeparatorName == null` is never "blocked" or "occupied"
	 *
	 * Per [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.toSeparatorName]'s own contract, a
	 * `null` next separator means "no forward-reservation target applies", not "the track ahead is
	 * occupied" — [NextHopOutcome.NoSectionReservable]'s rendered wording must never claim
	 * otherwise, and must never invite a route request the kernel has no target for.
	 *
	 * ## Standing at a signal reads the same perception field the tool guard reads
	 *
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool]'s own origin guard is
	 * `velocity == 0.0 && signalAheadName != null` off
	 * [cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading] — the same field read here — so the
	 * prompt's "standing at signal" clause and the tool's rejection can never disagree about
	 * whether a train is stopped in front of a named signal.
	 */
	private fun renderActiveTrainLine(
		trainId: String,
		observation: DispatchObservation
	): String {
		val perception = observation.snapshot.trainPerceptions.firstOrNull { it.trainId == trainId }
		val clauses = mutableListOf<String>()
		perception?.destinationInOutName?.takeIf { it.isNotBlank() }?.let {
			clauses += "exit via \"$it\""
		}
		if (perception != null && perception.velocity == 0.0 && !perception.signalAheadName.isNullOrBlank()) {
			clauses += "standing at signal \"${perception.signalAheadName}\" (STOP)"
		}
		clauses += renderNextHopClause(NextHopResolver.resolve(trainId, observation))
		return "- $trainId (active) -> ${clauses.joinToString("; ")}"
	}

	/**
	 * Renders the tail clause of an active train's line for [outcome], exhaustively over the
	 * sealed [NextHopOutcome] type — the compiler enforces coverage here the same way
	 * [renderAppliedOutcome] does over [AppliedOutcome].
	 */
	private fun renderNextHopClause(outcome: NextHopOutcome): String =
		when (outcome) {
			is NextHopOutcome.Hop ->
				"NEXT SECTION to reserve: from \"${outcome.fromSignalName}\" to \"${outcome.toSeparatorName}\" " +
					"— the one route request that helps this train now."

			NextHopOutcome.RouteAlreadySet -> "route already set — needs nothing this cycle."

			NextHopOutcome.NoSectionReservable ->
				"no section ahead is reservable this cycle — make no route request for this train; it waits."
		}

	/**
	 * Renders one queued train's row: approve-only, deliberately naming no topology endpoint at
	 * all. A queued train cannot legally start a route from anywhere yet —
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool]'s queued-origin guard
	 * rejects every non-InOut origin for it, and the entry InOut itself is not exposed on
	 * [cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation] — so printing its destination here
	 * only invited a `request_route` call the tool would refuse. The sole legal action this cycle
	 * is `approve_train`.
	 */
	private fun renderQueuedTrainLine(trainId: String): String =
		"- $trainId (queued) -> needs approve_train only — its first route is offered on a later " +
			"cycle, once it is running."

	/**
	 * Renders the "OUTCOMES OF YOUR PREVIOUS ACTIONS" block from [outcomes], or the empty string
	 * when [outcomes] is empty.
	 *
	 * ## Design rules this must keep (binding)
	 *
	 * - trainId + endpoints + outcome + reason are rendered for every request_route outcome; the
	 *   [AppliedOutcome.OriginNotContiguous.reason] is carried through **verbatim** — it already
	 *   names the offending origin and the legal alternatives, and paraphrasing it would drop
	 *   exactly the information this block exists to surface.
	 * - [AppliedOutcome.Conflicted.blockName] is deliberately **not** rendered — no block id
	 *   belongs next to train-facing context; only the conflicting train ([AppliedOutcome.Conflicted.existingOwner])
	 *   is named.
	 * - Every line is a plain `"- "` bullet, never a numbered line (`^\s*\d+[.)]\s`) — B3's
	 *   no-menu test covers this surface.
	 * - No "option"/"select"/"choose one" substrings anywhere in this block.
	 * - Empty input renders nothing at all, not an empty header — symmetric with
	 *   [CycleHistory.renderPromptBlock] for the same reason: the two arms of a comparison must
	 *   differ by the presence of the block, never by a stray header line.
	 */
	private fun renderAppliedOutcomesBlock(outcomes: List<AppliedOutcome>): String {
		if (outcomes.isEmpty()) return ""
		return buildString {
			appendLine("OUTCOMES OF YOUR PREVIOUS ACTIONS (since your last cycle):")
			outcomes.forEach { outcome -> appendLine("- ${renderAppliedOutcome(outcome)}") }
		}
	}

	/**
	 * Renders one [AppliedOutcome] as a single line (no trailing newline), exhaustively over the
	 * sealed [AppliedOutcome] type — the compiler enforces coverage here the same way
	 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier.applyDecision] does over
	 * [DispatchDecision], so a future outcome subtype fails to compile instead of silently
	 * rendering nothing.
	 */
	private fun renderAppliedOutcome(outcome: AppliedOutcome): String =
		when (outcome) {
			is AppliedOutcome.Reserved ->
				requestRouteHeader(outcome.trainId, outcome.fromEndpointName, outcome.toEndpointName) +
					"applied — ${outcome.blocksCount} block(s) reserved."

			is AppliedOutcome.Blocked ->
				requestRouteHeader(outcome.trainId, outcome.fromEndpointName, outcome.toEndpointName) +
					"REFUSED — all paths blocked (${outcome.attemptedPaths} path(s) attempted)."

			is AppliedOutcome.Conflicted ->
				// Deliberately omits outcome.blockName — see this method's caller KDoc.
				requestRouteHeader(outcome.trainId, outcome.fromEndpointName, outcome.toEndpointName) +
					"REFUSED — conflict: block held by ${outcome.existingOwner}."

			is AppliedOutcome.NoRoute ->
				requestRouteHeader(outcome.trainId, outcome.fromEndpointName, outcome.toEndpointName) +
					"REFUSED — no route exists between these endpoints."

			// The kernel's reason already names the offending origin and every legal alternative
			// for this train, so it is carried through verbatim rather than paraphrased.
			is AppliedOutcome.OriginNotContiguous ->
				requestRouteHeader(outcome.trainId, outcome.fromEndpointName, outcome.toEndpointName) +
					"REFUSED — ${outcome.reason}"

			is AppliedOutcome.Released ->
				if (outcome.anyReleased) {
					"cancel_route for \"${outcome.trainId}\": applied."
				} else {
					"cancel_route for \"${outcome.trainId}\": applied — no reservation was held."
				}

			is AppliedOutcome.Approved ->
				if (outcome.admitted) {
					"approve_train for \"${outcome.trainId}\": applied."
				} else {
					"approve_train for \"${outcome.trainId}\": REFUSED — station is at capacity."
				}

			is AppliedOutcome.DroppedInvalid ->
				"${outcome.commandType} for \"${outcome.trainId}\": REFUSED — ${outcome.message}"
		}

	/**
	 * Common `request_route`-outcome line prefix shared by every [AppliedOutcome] subtype that
	 * carries [from]/[to] endpoint names, factored out of [renderAppliedOutcome] to keep each
	 * branch within the 120-column line-length limit.
	 */
	private fun requestRouteHeader(
		trainId: String,
		from: String,
		to: String
	): String = "request_route for \"$trainId\" ($from -> $to): "
}
