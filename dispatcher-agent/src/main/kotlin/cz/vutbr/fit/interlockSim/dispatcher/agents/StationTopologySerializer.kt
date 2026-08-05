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

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchId
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.cellsOfType

/**
 * Static description of one switch (výhybka) in the controlled area.
 *
 * @property id   Compact switch identifier (SP3.2, e.g. `V7`).
 * @property type Switch [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type] name, e.g.
 *   `SIMPLE_LEFT_TRUE`.
 */
data class SwitchDescriptor(
	val id: SwitchId,
	val type: String
)

/**
 * A topologically valid route between two entry/exit points, referenced purely by ID.
 *
 * @property from   Origin InOut name (entry/exit point).
 * @property to     Destination InOut name.
 * @property blocks Ordered, de-duplicated block sections the route traverses (SP3.2 [BlockId]s).
 */
data class RouteDescriptor(
	val from: String,
	val to: String,
	val blocks: List<BlockId>
)

/**
 * Immutable, static snapshot of a controlled area's topology.
 *
 * Carries only the *static* structure — entry/exit points, signals, switches, block sections,
 * and topologically valid routes — using SP3.2 compact identifiers ([SignalId], [SwitchId],
 * [BlockId]). It intentionally omits all *dynamic* state (occupancy, reservations, pending
 * requests, signal aspects): those are reported per turn by the perception tools, which reference
 * elements by the same IDs used here.
 *
 * @see StationTopologySerializer
 * @since Issue #695 (SP2b.8 — Goal 10)
 */
data class StationTopology(
	val inOuts: List<String>,
	val signals: List<SignalId>,
	val switches: List<SwitchDescriptor>,
	val blocks: List<BlockId>,
	val routes: List<RouteDescriptor>
)

/**
 * Serializes a controlled area's **static** topology into the smallest sufficient form for the
 * DISPATCHER agent's system prompt (SP2b.8, #695).
 *
 * ## Purpose
 *
 * The station's static structure (blocks, switches, signals, valid routes) never changes during a
 * simulation run, so it is loaded into the LLM context **once, at agent construction**, rather than
 * re-transmitted every turn. Per-turn tool calls (`blockOccupancy`, `pendingRequests`,
 * `switchState`, …) then report only *dynamic* state and reference topology purely by ID.
 *
 * All identifiers use SP3.2's compact vocabulary ([SignalId], [SwitchId], [BlockId]). Block IDs are
 * derived with [BlockIdentity.stableBlockId], the same helper the perception port uses, so the IDs
 * in the once-loaded topology match the IDs the agent receives from per-turn dynamic queries.
 *
 * ## Scope
 *
 * One dispatcher per (small) controlled area — for the near-term `vyhybna.xml` / `praha.xml`
 * scenarios, exactly one dispatcher instance. No multi-dispatcher / inter-station hand-off is in
 * scope (see SP3.10, #578).
 *
 * @see StationTopology
 * @since Issue #695 (SP2b.8 — Goal 10)
 */
object StationTopologySerializer {
	/**
	 * Upper bound on routes emitted per ordered InOut pair, to keep the prompt compact for larger
	 * networks. Small stations (e.g. `vyhybna.xml`) stay well under this cap.
	 */
	private const val MAX_ROUTES_PER_PAIR = 16

	/**
	 * Stand-in for the train-name argument in the worked examples (Issue #847 round 2).
	 *
	 * Deliberately shaped so it cannot be mistaken for, or copied as, a real train id: it is
	 * unquoted and angle-bracketed, unlike every genuine name in this prompt, which is quoted.
	 * See [appendWorkedExample] for why a concrete placeholder name here deadlocked a whole run.
	 */
	private const val TRAIN_NAME_PLACEHOLDER = "<exact train id from this cycle's message>"

	/**
	 * Extracts the static [StationTopology] from a live simulation environment.
	 *
	 * Reads only static structure (grid cells and graph edges) and the purely topological route
	 * finder, so it is safe to call at agent-construction time.
	 */
	fun describe(env: SimulationEnvironment): StationTopology {
		val grid = env.getRailWayNetGrid()

		// Only InOuts with a usable name are listed and used as route endpoints, so a route never
		// references an entry/exit point the prompt omitted (Issue #695 review, Important #2).
		val namedInOuts: List<DynamicInOut> = env.getInOuts().toList().filter { it.name.isNotBlank() }
		val inOutNames = namedInOuts.map { it.name }.distinct().sorted()

		val signals =
			grid
				.cellsOfType<DynamicRailSemaphore>()
				.map { it.name }
				.filter { it.isNotBlank() }
				.distinct()
				.sorted()
				.map { SignalId(it) }

		val switches =
			grid
				.cellsOfType<DynamicRailSwitch>()
				.filter { it.name.isNotBlank() }
				.map { SwitchDescriptor(SwitchId(it.name), it.type.name) }
				.distinctBy { it.id.name }
				.sortedBy { it.id.name }

		val blocks =
			env
				.getGraph()
				.values()
				.filterIsInstance<DynamicTrackBlock>()
				.map { BlockId(BlockIdentity.stableBlockId(it)) }
				.distinctBy { it.name }
				.sortedBy { it.name }

		return StationTopology(
			inOuts = inOutNames,
			signals = signals,
			switches = switches,
			blocks = blocks,
			routes = describeRoutes(env, namedInOuts)
		)
	}

	/**
	 * Renders the topology into a compact, ID-only text block suitable for a system prompt.
	 *
	 * ## Anti-hallucination formatting (Issue #847 cleanup pass — agent-architect review finding)
	 *
	 * A live `shuntingLoopAI` run showed the LLM hallucinating endpoint names by confusing them
	 * with visually similar Block IDs (e.g. `"kA"` vs InOut `"A"`) — a real trap in networks like
	 * `vyhybna.xml` where Block IDs are literally `"k" + <InOut name>`. Three changes address this
	 * directly, rather than relying solely on the general instruction in the system prompt:
	 * 1. The anti-hallucination warning is repeated here, at the point of use, naming the specific
	 *    confusable pattern instead of only appearing once, upstream, in the system prompt.
	 * 2. Each name in the InOuts/Signals/Blocks lists is quoted ([joinQuoted]) so the model has an
	 *    unambiguous boundary for "copy this exact string," instead of relying on comma-splitting.
	 * 3. A worked example using this network's own (and specifically confusable, if any) names is
	 *    appended, giving the model something to pattern-match against instead of only an abstract
	 *    rule.
	 *
	 * ## Endpoint vocabulary must match [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]'s
	 * ## `validEndpointNames` (Issue #847 round 2 — regression fix)
	 *
	 * The first cut of the formatting above labelled *only* the InOuts as valid `request_route`
	 * names. That was wrong and actively harmful: `KoogAgentFactory` builds the accepted set as
	 * **InOuts ∪ Signals**, the system prompt and both `request_route` parameter descriptions say
	 * "InOuts or Signals", and `PathReservationService` accepts any [DynamicPathSeparator] — so
	 * semaphores are first-class endpoints. Advertising two of eight legal names left the model
	 * only whole-loop end-to-end routes, instead of the section-by-section semaphore hops
	 * `RuleBasedEmissionStrategy` emits, which massively over-reserved blocks and produced a
	 * `ReservationConflict` flood with no train getting through. The two lists are therefore now
	 * rendered under one shared "valid request_route endpoint names" heading, still labelled by
	 * kind, and the worked example shows a signal-to-signal hop alongside the end-to-end route.
	 */
	fun toPromptText(topology: StationTopology): String {
		val sb = StringBuilder()
		sb.append("=== STATION TOPOLOGY (static) ===\n")
		sb.append(
			"Copy names character-for-character from the lists below. A Block ID that looks " +
				"similar to an InOut name (e.g. \"kA\" vs InOut \"A\") is still NOT a valid " +
				"request_route argument.\n"
		)
		sb.append("Valid request_route endpoint names — BOTH lists below are accepted:\n")
		sb.append("  InOuts (entry/exit): ").append(joinQuoted(topology.inOuts)).append('\n')
		sb.append("  Signals (section boundaries): ").append(joinQuoted(topology.signals.map { it.name })).append('\n')
		sb
			.append("Switches: ")
			.append(joinOrNone(topology.switches.map { "${it.id.name}[${it.type}]" }))
			.append('\n')
		sb
			.append("Blocks (path context only — never valid as a request_route endpoint): ")
			.append(joinQuoted(topology.blocks.map { it.name }))
			.append('\n')
		// Note the per-pair cap so the agent cannot mistake a capped list for an exhaustive one
		// (Issue #695 review, Important #1).
		sb.append(
			"Routes (path context only; block IDs shown are NOT valid request_route arguments; " +
				"at most $MAX_ROUTES_PER_PAIR shown per ordered InOut pair; more may exist):"
		)
		if (topology.routes.isEmpty()) {
			sb.append(" none")
		} else {
			for (route in topology.routes) {
				sb
					.append("\n  ")
					.append(route.from)
					.append("->")
					.append(route.to)
					.append(": ")
					.append(joinOrNone(route.blocks.map { it.name }))
			}
		}
		appendWorkedExample(sb, topology)
		return sb.toString()
	}

	/**
	 * Appends worked `request_route` examples using this network's own endpoint names — and, when
	 * available, a real Block ID to contrast against — so the model has a concrete pattern to
	 * match instead of only the abstract anti-hallucination rule. No-op when the topology doesn't
	 * have at least two InOuts to build an example from (e.g. an empty test topology).
	 *
	 * ## The train-name slot must never contain a copyable name (Issue #847 round 2)
	 *
	 * The first cut of this example wrote a literal placeholder train name (`"T1"`) into the
	 * `trainName` argument. Because this text lives in the *system* prompt it is present on every
	 * cycle, and the model duly copied it: `request_route(trainName="T1", …)` reached
	 * `reservePath("T1", …)` — `RequestRouteTool` validated only the endpoints, and `ActionValidator`
	 * (which owns `UNKNOWN_TRAIN`) is not on the live wiring path — reserving real blocks for a
	 * train that does not exist. Nothing could release them: no `Train` is named `"T1"`, so the
	 * tail-clearing release never fires; `ShuntingLoop` has no orphan-reservation sweeper; and
	 * `CancelRouteTool` rejects the id precisely because no such train is active. One copied
	 * placeholder therefore deadlocked an entire run.
	 *
	 * Train names are dynamic and unknowable here — this text is rendered once at agent
	 * construction — so the slot is now an explicit, syntactically non-copyable placeholder that
	 * points at the per-cycle message ([cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl]
	 * renders both the queued and the active train lists there). Do not reintroduce a concrete
	 * name here, however illustrative.
	 */
	private fun appendWorkedExample(
		sb: StringBuilder,
		topology: StationTopology
	) {
		if (topology.inOuts.size < 2) return
		val exampleFrom = topology.inOuts[0]
		val exampleTo = topology.inOuts[1]
		sb
			.append("\n\nEXAMPLE (end-to-end route). Never invent a train name: use an id copied from ")
			.append("the \"Queued\" or \"Active\" train list in this cycle's message, shown here as ")
			.append(TRAIN_NAME_PLACEHOLDER)
			.append(". To route that train from \"")
			.append(exampleFrom)
			.append("\" to \"")
			.append(exampleTo)
			.append("\", call request_route(trainName=")
			.append(TRAIN_NAME_PLACEHOLDER)
			.append(", fromEndpointName=\"")
			.append(exampleFrom)
			.append("\", toEndpointName=\"")
			.append(exampleTo)
			.append("\").")
		appendSectionHopExample(sb, topology)
		val exampleWrongBlock = topology.blocks.firstOrNull { it.name != exampleFrom && it.name != exampleTo }
		if (exampleWrongBlock != null) {
			sb
				.append(" Do NOT pass \"")
				.append(exampleWrongBlock.name)
				.append("\" as an endpoint — it is a Block ID, which names a piece of track, not a route endpoint.")
		}
	}

	/**
	 * Appends a second example routing between two *signals*, so the model sees that a route need
	 * not span the whole network from InOut to InOut. Reserving one section at a time is what the
	 * rule-based dispatcher does
	 * ([cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy]) and it holds far fewer
	 * blocks per train, which is what keeps concurrent trains from conflicting. No-op when the
	 * network has fewer than two signals.
	 */
	private fun appendSectionHopExample(
		sb: StringBuilder,
		topology: StationTopology
	) {
		if (topology.signals.size < 2) return
		sb
			.append(" EXAMPLE (single section — usually preferable, because it holds fewer blocks): ")
			.append("request_route(trainName=")
			.append(TRAIN_NAME_PLACEHOLDER)
			.append(", fromEndpointName=\"")
			.append(topology.signals[0].name)
			.append("\", toEndpointName=\"")
			.append(topology.signals[1].name)
			.append("\").")
	}

	/**
	 * Convenience: [describe] then [toPromptText].
	 */
	fun serialize(env: SimulationEnvironment): String = toPromptText(describe(env))

	private fun describeRoutes(
		env: SimulationEnvironment,
		inOuts: List<DynamicInOut>
	): List<RouteDescriptor> {
		val routeFinder = env.getRouteFinder()
		val result = mutableListOf<RouteDescriptor>()
		for (from in inOuts) {
			for (to in inOuts) {
				// Structural equality (DynamicInOut.equals compares staticRef) so a self-pair is
				// skipped even when the wrapping layer returns distinct wrappers around the same
				// static InOut (Issue #695 review, Important #3).
				if (from == to) continue
				val routes =
					runCatching {
						routeFinder.findRoutes(from.staticRef, to.staticRef, env, maxRoutes = MAX_ROUTES_PER_PAIR)
					}.getOrDefault(emptyList())
				for (route in routes) {
					result.add(
						RouteDescriptor(
							from = from.name,
							to = to.name,
							blocks = routeBlockIds(route.segments.map { it.getTrackBlock() })
						)
					)
				}
			}
		}
		return result
	}

	/**
	 * Maps an ordered list of blocks to their [BlockId]s, collapsing consecutive duplicates (a
	 * block spans several track sections, which would otherwise repeat the same id).
	 */
	private fun routeBlockIds(blocks: List<TrackBlock>): List<BlockId> {
		val ids = mutableListOf<BlockId>()
		for (block in blocks) {
			val id = BlockId(blockLabel(block))
			if (ids.lastOrNull() != id) ids.add(id)
		}
		return ids
	}

	private fun blockLabel(block: TrackBlock): String =
		when (block) {
			is DynamicTrackBlock -> BlockIdentity.stableBlockId(block)
			else -> block.name?.takeIf { it.isNotBlank() } ?: "unknown"
		}

	private fun joinOrNone(items: List<String>): String = if (items.isEmpty()) "none" else items.joinToString(", ")

	/**
	 * Like [joinOrNone] but quotes each name (Issue #847 cleanup pass) so the model has an
	 * unambiguous "copy exactly this" boundary instead of relying on comma-splitting prose.
	 */
	private fun joinQuoted(items: List<String>): String =
		if (items.isEmpty()) "none" else items.joinToString(", ") { "\"$it\"" }
}
