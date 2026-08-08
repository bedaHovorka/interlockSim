/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.RouteScope
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing end-to-end route reservation to Koog agents (SP1.6, Issue #551;
 * rewired to [SinkHolder] in SP2c.6, Issue #829).
 *
 * Request a route reservation from one endpoint to another for a named train.
 *
 * ## Threading contract (SP2c.6, Issue #829)
 *
 * `execute()` runs on the agent driver thread. It emits [DispatchAction.RequestRoute] to the
 * active [sinkHolder] (fire-and-forget). The [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]
 * collects the emission, validates it through [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator],
 * and posts the validated [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] for the kDisco thread.
 *
 * ## Endpoint-name validation (Goal 10 agent-architect review finding)
 *
 * A live run showed the LLM occasionally hallucinating a plausible-looking endpoint name
 * (e.g. `"kA"`/`"kB"`) instead of copying one from the topology in its system prompt. [execute]
 * validates [fromEndpointName]/[toEndpointName] against [validEndpointNames] synchronously and
 * returns a [ToolResult.Error] naming the valid options when either is unrecognized, so the model
 * gets in-turn feedback and can retry with a real name instead of silently losing the decision.
 *
 * ## Train-name validation (Issue #847 round 2)
 *
 * Endpoint names were validated from the start; `trainName` was not, and that asymmetry proved
 * expensive. A live run had the model copy the literal placeholder train name out of a worked
 * example in its own system prompt; the request passed straight through to `reservePath`, which
 * reserved real blocks for a train that does not exist. Nothing releases such a reservation — no
 * `Train` bears the name, so the tail-clearing release never fires, `ShuntingLoop` has no orphan
 * sweeper, and [CancelRouteTool] rejects the id for the very reason it is bogus — so every later
 * train conflicted on those blocks for the rest of the run.
 *
 * [execute] therefore also validates [trainName] against the union of the **queued** trains
 * ([sensorPort]) and the **active** trains ([perceptionPort]). Both, deliberately: reserving a
 * route before calling `approve_train` is the intended order, so restricting to active trains
 * would reject legitimate requests. Both ports are internal, already injected into
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory], and are not LLM-facing tools, so
 * the actuator surface stays at four and the SP2c "no new query tool" decision holds.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.RequestRoute]
 *   is emitted to the active sink on successful execution.
 * @param validEndpointNames The exact set of InOut and Signal names this network recognizes
 *   (from [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopology], captured once at agent
 *   construction — topology is static and never changes during a run).
 * @param perceptionPort Optional off-thread-safe perception port supplying currently active
 *   trains for the [trainName] pre-check; `null` skips the pre-check.
 * @param sensorPort Optional dispatch-loop sensor port supplying trains queued for admission for
 *   the [trainName] pre-check; `null` skips the pre-check.
 * ## Direction validation (the backwards-route defect)
 *
 * A live `exampleGui shuntingLoopAI 333` run had the model request the **reverse** of every
 * train's timetable: Train #1 runs `B → A` and got `A → B`, Train #2 runs `A → B` and got
 * `B → A`. Nothing on this path checked it, so `reservePath` reserved all seven blocks of
 * `vyhybna.xml` from the wrong end; the train, standing at its real entry point, could then never
 * find a free path because it owned every block itself, and only moved once the
 * `OrphanReservationSweeper` cancelled its own reservation 60 simulated seconds later. No train
 * completed a journey in 333 s.
 *
 * [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] already encodes this rule as
 * [RejectionCode.TARGET_NOT_TRAIN_DESTINATION], but it is reached only from
 * `DispatchTickLoop`, which is never constructed in production. [execute] therefore applies the
 * check here, on the live path: a [toEndpointName] that names an **InOut** other than the train's
 * declared destination is rejected. Signal targets are left alone — those are section hops, which
 * this tool's own description recommends, and they say nothing about where the train finishes.
 *
 * @param blockIds Static Block IDs of this network, used only to classify a rejected endpoint as
 *   [RejectionCode.ENDPOINT_IS_BLOCK_ID] rather than [RejectionCode.UNKNOWN_ENDPOINT]
 *   (Issue #847 round 4). Static topology, so it is correct from the first cycle — unlike the live
 *   snapshot, which carries no blocks until the simulation has captured one. Empty disables only
 *   the finer classification, never the rejection itself.
 * @param inOutNames Static InOut names of this network (`StationTopology.inOuts`), used to tell an
 *   end-to-end target apart from a section hop in the direction check, and to enforce the
 *   queued-train origin rule below. Empty disables both checks entirely — without it, every Signal
 *   target would look like a wrong destination and every origin like a Signal.
 *
 * ## Origin validation for queued trains (Issue #893)
 *
 * The other half of the same live defect: once the direction was corrected, the model asked for a
 * correctly *directed* route in the wrong *place* — an origin the train could not reach. For a
 * train that has not been admitted yet the answer is unambiguous: it stands at its entry InOut and
 * nowhere else, so a Signal origin is always wrong. See [queuedOriginError], including why the rule
 * is "an InOut" rather than "the entry InOut". The authoritative, footprint-aware check lives in
 * `DefaultPathReservationService.reservePath`; its rejection returns through the apply-failure
 * channel rather than in-turn.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to [SinkHolder] in Issue #829 (SP2c.6);
 *   train-name pre-check added in Issue #847 round 2
 */
class RequestRouteTool(
	private val sinkHolder: SinkHolder,
	private val validEndpointNames: Set<String>,
	private val perceptionPort: NetworkPerceptionPort? = null,
	private val sensorPort: DispatchLoopSensorPort? = null,
	private val blockIds: Set<String> = emptySet(),
	private val inOutNames: Set<String> = emptySet()
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "request_route"

	override val description: String =
		"Request the interlocking to find and atomically reserve a free path for a named train, between " +
			"any two endpoints from the InOuts or Signals lists. A Signal-to-Signal route reserves one " +
			"section and is usually preferable to an InOut-to-InOut route, which holds every block in " +
			"between. Fire-and-forget: returns once the request is emitted; the reservation outcome shows " +
			"up in the next cycle's message."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainName",
				description =
					"Identifier of the train that will use the reserved route, copied verbatim from the " +
						"queued or active train list in this cycle's message (non-blank). Never invent " +
						"an id and never copy one out of an example.",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "fromEndpointName",
				description =
					"Where the train starts FROM — the end it is standing at now, NOT where it is going. " +
						"For a queued train this is the opposite end from the destination shown after " +
						"\"->\" in its train-list row; for a train already moving it is the signal ahead " +
						"of it. Exact name, copied verbatim from the InOuts or " +
						"Signals list in the STATION TOPOLOGY section of your system prompt. Do not " +
						"abbreviate, translate, or invent a name — if the name you want isn't listed " +
						"there, do not call this tool. Do NOT pass a Block ID (e.g. a name from the " +
						"Blocks list, such as \"kA\") — a Block ID names a piece of track, not an endpoint.",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "toEndpointName",
				description =
					"Where the train is going TO. For an end-to-end route this MUST be that train's " +
						"destination — the name printed after \"->\" in its row of the train list — " +
						"otherwise the request is rejected. For a section route it is a Signal on the way " +
						"there. Never pass the end the train is departing from: that reserves the line " +
						"against the train itself. Exact name, copied verbatim from the InOuts or " +
						"Signals list in the STATION TOPOLOGY section of your system prompt. Do not " +
						"abbreviate, translate, or invent a name — if the name you want isn't listed " +
						"there, do not call this tool. Do NOT pass a Block ID (e.g. a name from the " +
						"Blocks list, such as \"kA\") — a Block ID names a piece of track, not an endpoint.",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainName =
			args.stringParam("trainName")
				?: return ToolResult.Error(
					"trainName parameter is required and must be a non-blank string",
					rejection = RejectionCode.BLANK_ARGUMENT
				)
		val fromEndpointName =
			args.stringParam("fromEndpointName")
				?: return ToolResult.Error(
					"fromEndpointName parameter is required and must be a non-blank string",
					rejection = RejectionCode.BLANK_ARGUMENT
				)
		val toEndpointName =
			args.stringParam("toEndpointName")
				?: return ToolResult.Error(
					"toEndpointName parameter is required and must be a non-blank string",
					rejection = RejectionCode.BLANK_ARGUMENT
				)

		if (fromEndpointName !in validEndpointNames) {
			return ToolResult.Error(
				"Unknown fromEndpointName '$fromEndpointName' — valid names are: " +
					validEndpointNames.sorted().joinToString(", "),
				rejection = classifyBadEndpoint(fromEndpointName)
			)
		}
		if (toEndpointName !in validEndpointNames) {
			return ToolResult.Error(
				"Unknown toEndpointName '$toEndpointName' — valid names are: " +
					validEndpointNames.sorted().joinToString(", "),
				rejection = classifyBadEndpoint(toEndpointName)
			)
		}
		// Emit the canonical id, never the raw argument — see resolveTrainId for why the bare
		// ordinal ("1" for "Train #1") has to be accepted at all.
		val resolvedTrainName =
			knownTrainIds()?.let { known ->
				resolveTrainId(trainName, known)
					?: return ToolResult.Error(
						"Unknown trainName '$trainName' — routes can only be requested for a train that is " +
							"queued or active. Those trains are: " +
							known.sorted().joinToString(", ").ifEmpty { "(no trains queued or active)" },
						rejection = RejectionCode.UNKNOWN_TRAIN
					)
			} ?: trainName

		declaredDestinationOf(resolvedTrainName)?.let { destination ->
			// A train cannot set out FROM the place it is trying to reach. This holds whatever the
			// target is, so unlike the destination check below it also catches the section-route
			// form of the same mistake — the shape the model actually produced once the end-to-end
			// form was rejected (`from=A, to=doA1` for a train running B → A).
			if (fromEndpointName == destination) {
				return ToolResult.Error(
					"Train '$resolvedTrainName' is travelling TO '$destination', so it cannot start " +
						"FROM '$destination'. fromEndpointName is where the train is now — the opposite " +
						"end of the station, or a signal it is standing at — not where it is headed.",
					rejection = RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION
				)
			}
			if (toEndpointName in inOutNames && toEndpointName != destination) {
				return ToolResult.Error(
					"Train '$resolvedTrainName' runs to '$destination', not to '$toEndpointName'. " +
						"A route to the opposite end of the station sends it the wrong way and reserves the " +
						"whole line against itself. Either request the end-to-end route with " +
						"toEndpointName='$destination', or request a section route whose toEndpointName is a " +
						"Signal on the way there.",
					rejection = RejectionCode.TARGET_NOT_TRAIN_DESTINATION
				)
			}
		}

		queuedOriginError(resolvedTrainName, fromEndpointName)?.let { return it }

		// A route has to start where the train actually is. Only checked for a train standing
		// still at a known signal — the case where the answer is unambiguous and the train is
		// waiting for exactly this route. A moving train's signal ahead changes under the
		// dispatcher's feet, so requiring a match there would reject legitimate look-ahead
		// requests; `ActionValidator` gates its equivalent rule the same way (on TrainPhase.HELD).
		standingAtSignal(resolvedTrainName)?.let { signalAhead ->
			if (fromEndpointName != signalAhead) {
				return ToolResult.Error(
					"Train '$resolvedTrainName' is standing at signal '$signalAhead', so its route must " +
						"start there: fromEndpointName must be '$signalAhead', not '$fromEndpointName'. " +
						"A route reserved somewhere else does not release this train. Do not send another " +
						"origin of your own. Use exactly the from/to pair printed on this train's NEXT " +
						"SECTION line, or make no route request for this train this tick.",
					rejection = RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION
				)
			}
		}

		// A Signal target (not an InOut) is a section hop toward the destination, not the
		// end-to-end route -- mirror RuleBasedEmissionStrategy's scope so ActionValidator, if ever
		// wired onto the live path, does not reject these as TARGET_NOT_TRAIN_DESTINATION.
		val scope = if (toEndpointName in inOutNames) RouteScope.EndToEnd else RouteScope.Section
		val action = DispatchAction.RequestRoute(resolvedTrainName, fromEndpointName, toEndpointName, scope)
		logger.debug {
			"RequestRouteTool.execute: emitting action trainName=$resolvedTrainName (raw='$trainName'), " +
				"from=$fromEndpointName, to=$toEndpointName"
		}
		emitOrRejectForCap(sinkHolder, action)?.let { return it }
		return ToolResult.Success(
			"emitted request_route train=$resolvedTrainName from=$fromEndpointName to=$toEndpointName"
		)
	}

	/**
	 * The InOut this train is timetabled to reach, or `null` when it cannot be determined here.
	 *
	 * Both lists are consulted, and both are needed. The queued list
	 * ([cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation]) covers a train that has not been
	 * admitted yet; the snapshot's active list covers the far more common case, because the
	 * intended call order is `approve_train` **then** `request_route` — by which point the train
	 * has left the queue. A first attempt that consulted only the queue was silently skipped for
	 * exactly the call that exposed the defect.
	 *
	 * The destination is read from [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot], not from
	 * `perceptionPort.trainPerception()`: `snapshot()` is the only off-thread-safe member of that
	 * port (it returns a `@Volatile` reference to the last sim-thread capture), and this tool runs
	 * on the agent driver thread. `trainPerception()` walks live `Train` objects and would be a
	 * cross-thread read of simulation state.
	 */
	private fun declaredDestinationOf(trainId: String): String? {
		val queued =
			sensorPort
				?.getQueuedTrains()
				?.firstOrNull { it.trainId == trainId }
				?.destinationInOutName
		return (queued ?: activePerceptionOf(trainId)?.destinationInOutName)?.takeIf { it.isNotBlank() }
	}

	/**
	 * Rejects a route for a **queued** train that does not start at an entry point (Issue #893).
	 *
	 * A train still waiting for admission is physically nowhere in the network, so the only
	 * origin it can possibly use is the InOut it is queued at. A route reserved from a Signal in
	 * the middle of the station locks track the train cannot reach: it never occupies those
	 * blocks, so it never releases them, and every other train is held out until the orphan
	 * sweeper reclaims the reservation — the "correctly directed, wrongly placed" half of the
	 * Issue #893 stall.
	 *
	 * ## Limitation: "an InOut", not "*the* entry InOut"
	 *
	 * [cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation] carries only `trainId` and
	 * `destinationInOutName` — the queuing InOut is not exposed, and this task's constraint is
	 * that no new `:core` query surface may be added for it. The enforceable rule is therefore
	 * the weaker "a queued train's origin must be an InOut, not a Signal". Combined with the
	 * existing "cannot depart FROM the destination" rule this pins the origin exactly on a
	 * two-InOut network such as `vyhybna.xml`, and is a strict improvement on larger ones. The
	 * authoritative check is `DefaultPathReservationService.reservePath`'s contiguity invariant,
	 * which sees the live footprint; this one exists to give the model in-turn feedback rather
	 * than a silently-dropped decision one tick later.
	 *
	 * Disabled (returns `null`) when [inOutNames] is empty — without it every origin would look
	 * like a Signal — or when the train is also active, in which case the live perception is the
	 * better evidence and [standingAtSignal] governs instead.
	 */
	private fun queuedOriginError(
		trainId: String,
		fromEndpointName: String
	): ToolResult.Error? {
		if (inOutNames.isEmpty()) return null
		if (fromEndpointName in inOutNames) return null
		val isQueued = sensorPort?.getQueuedTrains()?.any { it.trainId == trainId } ?: false
		if (!isQueued || activePerceptionOf(trainId) != null) return null
		val destination = declaredDestinationOf(trainId)
		val candidateOrigins = inOutNames.filter { it != destination }.sorted()
		val legalOrigins = if (candidateOrigins.isEmpty()) inOutNames.sorted() else candidateOrigins
		return ToolResult.Error(
			"Train '$trainId' has not entered the network yet, so its route must start at its entry " +
				"point — an InOut, not signal '$fromEndpointName'. Use fromEndpointName=" +
				legalOrigins.joinToString(" or ") { "'$it'" } +
				". A route reserved elsewhere locks track this train cannot reach and releases nobody. " +
				"Do not retry with the same origin.",
			rejection = RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION
		)
	}

	/**
	 * This train's last captured first-person perception, or `null` if it is not active.
	 *
	 * Read from [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.trainPerceptions] rather than
	 * `perceptionPort.trainPerception()`: `snapshot()` is the only off-thread-safe member of that
	 * port (it returns a `@Volatile` reference to the last sim-thread capture) and this tool runs
	 * on the agent driver thread, whereas `trainPerception()` walks live `Train` objects.
	 */
	private fun activePerceptionOf(trainId: String): TrainPerceptionReading? =
		perceptionPort
			?.snapshot()
			?.trainPerceptions
			?.firstOrNull { it.trainId == trainId }

	/**
	 * The signal this train is stopped at, or `null` if it is moving or has no signal ahead.
	 *
	 * "Stopped" is `velocity == 0.0`, the observable form of the `TrainPhase.HELD`/`DWELLING`
	 * condition `ActionValidator` gates its own origin rule on. A train at a stand in front of a
	 * signal is waiting for precisely one route — the one starting at that signal — so any other
	 * origin is a dispatcher error, and a costly one: the reservation succeeds, holds blocks, and
	 * releases nobody until the orphan sweeper reclaims it 60 simulated seconds later.
	 */
	private fun standingAtSignal(trainId: String): String? =
		activePerceptionOf(trainId)
			?.takeIf { it.velocity == 0.0 }
			?.signalAheadName
			?.takeIf { it.isNotBlank() }

	/**
	 * Every train name the model may legitimately pass this cycle: those queued for admission plus
	 * those already running. Returns `null` when neither port is wired, which disables the
	 * [trainName] pre-check entirely rather than validating against a misleadingly partial set —
	 * with only one port present the union would be incomplete, and rejecting a real train is worse
	 * than the pre-#847 behavior of accepting anything.
	 */
	private fun knownTrainIds(): Set<String>? {
		if (perceptionPort == null || sensorPort == null) return null
		val active = perceptionPort.snapshot().trainPositions.map { it.trainId }
		val queued = sensorPort.getQueuedTrains().map { it.trainId }
		return (active + queued).toSet()
	}

	/**
	 * Classifies a rejected endpoint name: a real Block ID passed where an endpoint was expected, or
	 * a name that exists nowhere in the network (Issue #847 round 4).
	 *
	 * The two say different things and must not be counted together. `vyhybna.xml` names its blocks
	 * `k1`/`kA`/`kB` and its InOuts `A`/`B`, and one round-2 run produced 48 rejected calls naming
	 * block `k1` — a model that has read the topology and picked the wrong list from it. A wholly
	 * invented name is a different failure, addressed by different prompt work. Folding both into
	 * `UNKNOWN_ENDPOINT` would hide the one this PR's prompt changes actually target.
	 *
	 * Falls back to [RejectionCode.UNKNOWN_ENDPOINT] when no perception port is wired: without the
	 * block list the distinction cannot be drawn, and the weaker classification is honest.
	 */
	private fun classifyBadEndpoint(name: String): RejectionCode {
		if (name in blockIds) return RejectionCode.ENDPOINT_IS_BLOCK_ID
		// Fallback for callers that supply no static block list: the live snapshot carries the same
		// ids once the simulation has captured one. Static [blockIds] is preferred because it is
		// available from the very first cycle, before any capture has happened.
		val liveBlockIds =
			perceptionPort
				?.snapshot()
				?.blocks
				?.map { it.blockId }
				?.toSet()
				.orEmpty()
		return if (name in liveBlockIds) RejectionCode.ENDPOINT_IS_BLOCK_ID else RejectionCode.UNKNOWN_ENDPOINT
	}
}
