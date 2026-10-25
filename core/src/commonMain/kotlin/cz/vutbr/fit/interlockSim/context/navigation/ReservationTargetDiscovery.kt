/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFree
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFreeOrOwnedBy
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * One target of the `reservePathToAny` attempt loop, as [ReservationTargetDiscovery.reservePathToAnyTargets]
 * hands it over (Issue #1209). Sealed, so the two kinds of target cannot be told apart by a silent
 * `null`: an [InOutTarget] carries the topological paths enumerated for sorting (Issue #966), a
 * [SemaphoreTarget] carries none.
 */
internal sealed interface TargetAttempt {
	/** The separator the attempt reserves a route to. */
	val target: DynamicPathSeparator

	/**
	 * What the attempt hands to `reservePath` as its `topologicalPaths`: this target's
	 * `findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH)`, or `null` when `reservePath`
	 * has to enumerate them itself.
	 */
	val topologicalPaths: Lazy<List<List<TrackSection>>>?

	/**
	 * An InOut target with its topological candidate paths, enumerated lazily and at most once
	 * (Issue #966): [paths] is shared by the sort key and the reservation attempt. It is
	 * `findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH)` for this exact target and
	 * the `start` the targets were discovered for, valid for one `reservePathToAny` call.
	 */
	class InOutTarget(
		override val target: DynamicInOut,
		val paths: Lazy<List<List<TrackSection>>>
	) : TargetAttempt {
		override val topologicalPaths: Lazy<List<List<TrackSection>>> get() = paths
	}

	/** A semaphore target: secondary, unsorted, with no precomputed paths. */
	class SemaphoreTarget(
		override val target: DynamicRailSemaphore
	) : TargetAttempt {
		override val topologicalPaths: Lazy<List<List<TrackSection>>>? get() = null
	}
}

/**
 * Reservation target discovery (Issue #1171, SP12.5): which separators a route can be set to,
 * and in which order they are tried. Read-only — nothing here reserves, registers or signals.
 *
 * Two enumerations live here:
 * - **One section ahead** ([findNextSemaphoresVia]): the forward-facing semaphores and InOuts
 *   reachable through one given track section. It serves
 *   [PathReservationService.reservePathToAnyNextSemaphore], and, with each target's availability
 *   attached by the shared primitive [evaluateTargets] (Issue #1208), the read-only candidate
 *   query [findReservationTargetCandidates] (Issue #970) and [isAnyNextTargetAvailable].
 * - **Whole network** ([reservePathToAnyTargets]): every InOut and semaphore except the start,
 *   in the priority order [PathReservationService.reservePathToAny] tries them.
 *
 * The attempt loops that call `reservePath` stay in [DefaultPathReservationService]; this class
 * only says what to try. Extracted from that service without behaviour change: the target order
 * is pinned by `ReservationTargetDiscoveryTest`.
 *
 * [reservePathToAnyTargets] takes an [OrientedPathSeparator]: a route runs signal-to-signal, and
 * `reservePathToAny` refuses a non-oriented origin before discovery (Issue #1188 guard), so the
 * orientation-free sort a switch start once needed (Issue #1207) no longer exists.
 *
 * Not thread-safe, like the service it serves.
 *
 * @property navigator Static topology navigator; paths for sorting and availability checks
 * @property environment Simulation environment; grid, graph and dynamic wrappers
 * @since Issue #1171 (extracted from [DefaultPathReservationService])
 */
internal class ReservationTargetDiscovery(
	private val navigator: TopologyNavigator,
	private val environment: SimulationEnvironment
) {
	/**
	 * Every [DynamicRailSemaphore] in this context's grid, in grid-scan order (column by column,
	 * top to bottom), read once on first use.
	 *
	 * The simulation grid is populated only while the context is built and is frozen before the
	 * per-context reservation service can be obtained, so cells are never added or removed
	 * afterwards. Only the semaphores' live state changes, and that is read off these references.
	 * Lazy because the owning service is constructed inside the per-context Koin scope, and
	 * touching the grid at construction time would couple service creation to grid readiness.
	 * Mirrors [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]'s semaphore cache.
	 *
	 * @since Issue #965 — replaces a full grid scan on every `reservePathToAny` call
	 */
	private val allSemaphoresCache: List<DynamicRailSemaphore> by lazy {
		environment.getRailWayNetGrid().cellsOfType<DynamicRailSemaphore>().also { semaphores ->
			logger.trace { "allSemaphores: Found ${semaphores.size} semaphore(s) in grid" }
		}
	}

	/** All semaphores of this context's grid, from [allSemaphoresCache]. */
	fun allSemaphores(): List<DynamicRailSemaphore> = allSemaphoresCache

	/**
	 * Resolve the track section leading forward from [start], read-only.
	 *
	 * 1. Convert [start] to its dynamic wrapper ([SimulationEnvironment.toDynamic] is idempotent).
	 * 2. Pick the forward segment from the separator's orientation. SPECIAL CASE: an InOut connects
	 *    bidirectionally at `direction()` ([InOut.getTrackConnectionDirection]); a semaphore's
	 *    `direction()` is its forward travel direction.
	 * 3. Look up the separator's grid location and the graph edge assigned to that segment.
	 *
	 * Shared by [PathReservationService.reservePathToAnyNextSemaphore] (the oriented overload) and
	 * [findReservationTargetCandidates] so the two cannot drift (Issue #957).
	 *
	 * @param caller name of the calling method, used as the log prefix
	 * @return the dynamic start and the forward section, or `null` (logged as a warning) when
	 *   [start] has no grid location or no track section in its forward direction
	 */
	fun resolveForwardSection(
		start: OrientedPathSeparator,
		caller: String
	): Pair<DynamicPathSeparator, TrackSection>? {
		val dynamicStart = environment.toDynamic(start)
		val forwardSegment =
			when (start) {
				is InOut -> start.getTrackConnectionDirection()
				is DynamicInOut -> start.getTrackConnectionDirection()
				else -> start.direction()
			}
		logger.debug {
			"$caller: START=$start orientation=${start.getOrientation()} forwardSegment=$forwardSegment"
		}

		val location = environment.getRailWayNetGrid().getLocation(start)
		if (location == null) {
			logger.warn { "$caller: No location found for $start" }
			return null
		}
		logger.debug { "$caller: Location=$location" }

		val next = environment.getGraph().assignedEdges(location)[forwardSegment]
		if (next == null) {
			logger.warn {
				"$caller: No outgoing track section from $start at $location in direction $forwardSegment"
			}
			return null
		}
		return dynamicStart to next
	}

	/**
	 * Every next separator one section ahead of [start], each with its evaluated availability —
	 * the body of [ReservationTargetQuery.findReservationTargetCandidates]; that interface's KDoc
	 * is the contract.
	 */
	fun findReservationTargetCandidates(
		start: OrientedPathSeparator,
		ownerTrainId: String?
	): List<ReservationTargetCandidate> {
		logger.debug {
			"findReservationTargetCandidates: Listing next targets from oriented separator $start" +
				(ownerTrainId?.let { ", blocks owned by $it count as free (Issue #1060)" } ?: "")
		}

		val (dynamicStart, next) =
			resolveForwardSection(start, "findReservationTargetCandidates") ?: return emptyList()

		val targets = findNextSemaphoresVia(dynamicStart, next)
		if (targets.isEmpty()) {
			logger.debug { "findReservationTargetCandidates: No separators found from $start via $next" }
			return emptyList()
		}
		// Every candidate is evaluated (Issue #970 ruling: never report an unevaluated candidate
		// as unavailable), so the lazy sequence is materialised. isPathAvailableFor is read-only,
		// so the extra evaluations past the first available target change no state and the pick
		// stays byte-identical.
		val candidates = evaluateTargets(dynamicStart, targets, ownerTrainId).toList()
		logger.debug {
			"findReservationTargetCandidates: ${targets.size} target(s) from $start via $next, " +
				"available = ${candidates.filter { it.available }.map { it.separator }}"
		}
		return candidates
	}

	/**
	 * Whether any next separator reachable from [start] via [next] has an available route — the
	 * body of [PathReservationService.isPathToAnyNextSemaphoreAvailable].
	 */
	fun isAnyNextTargetAvailable(
		start: PathSeparator,
		next: TrackSection?
	): Boolean {
		// Handle null next parameter (no direction to search)
		if (next == null) {
			logger.debug { "isPathToAnyNextSemaphoreAvailable: next is null, no path exists" }
			return false
		}

		// Find ALL reachable semaphores via this track section
		val semaphores = findNextSemaphoresVia(start, next)
		if (semaphores.isEmpty()) {
			logger.debug {
				"isPathToAnyNextSemaphoreAvailable: No semaphores found from $start via $next"
			}
			return false
		}

		logger.trace {
			"isPathToAnyNextSemaphoreAvailable: Found ${semaphores.size} semaphore(s), " +
				"checking availability: $semaphores"
		}

		// Check if ANY semaphore has an available path: the sequence is lazy, so the targets after
		// the first available one are never evaluated. The owner is not known on this probe path.
		val available = evaluateTargets(start, semaphores, ownerTrainId = null).firstOrNull { it.available }
		if (available != null) {
			logger.debug {
				"isPathToAnyNextSemaphoreAvailable: Path available to ${available.separator}"
			}
			return true
		}

		logger.debug {
			"isPathToAnyNextSemaphoreAvailable: All ${semaphores.size} path(s) blocked"
		}
		return false
	}

	/**
	 * The shared section-level primitive behind [findReservationTargetCandidates] and
	 * [isAnyNextTargetAvailable] (Issue #1208): pairs each of [targets] — the result of
	 * [findNextSemaphoresVia] for [start] — with its availability from [isPathAvailableFor] under
	 * [ownerTrainId].
	 *
	 * Takes the target list rather than the section so a caller enumerates once and can log what it
	 * found before anything is evaluated. The sequence is lazy: a target's availability is
	 * evaluated only when the sequence reaches it, in the order of [targets], once per iteration.
	 * A caller that needs one answer stops early; a caller that needs them all materialises it.
	 * It does not log, so each caller keeps its own vocabulary.
	 */
	fun evaluateTargets(
		start: PathSeparator,
		targets: List<DynamicPathSeparator>,
		ownerTrainId: String?
	): Sequence<ReservationTargetCandidate> =
		targets.asSequence().map { target ->
			ReservationTargetCandidate(target, isPathAvailableFor(start, target, DEFAULT_MAX_PATH_DEPTH, ownerTrainId))
		}

	/**
	 * Whether a physically legal route from [start] to [target] exists whose blocks are all FREE —
	 * or, with a non-null [ownerTrainId], all FREE or owned by that train (Issue #1060), exactly as
	 * `reservePath` treats the train's own blocks, so that an extension of the train's own route
	 * is not judged blocked by the route itself.
	 *
	 * Switch-CONSTRAINED enumeration (Issue #797 follow-up): the switch-blind
	 * `findAllTopologicalPaths` admits phantom routes that reverse through a switch to reach a
	 * target's back side while bypassing the occupied block in front of it (e.g. reaching doB1 via
	 * the free k2 + a vB reversal while k1 is occupied), a false positive that steers target
	 * selection onto the blocked track and deadlocks opposing trains.
	 */
	fun isPathAvailableFor(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int,
		ownerTrainId: String?
	): Boolean {
		val candidatePaths = navigator.findAllSwitchConstrainedPaths(start, target, maxDepth)

		return candidatePaths.any { path ->
			val blocks = extractUniqueBlocks(path)
			blocks.areAllFree() || (ownerTrainId != null && blocks.areAllFreeOrOwnedBy(ownerTrainId))
		}
	}

	/**
	 * Every whole-network target of [PathReservationService.reservePathToAny] from [start], in the
	 * order it tries them.
	 *
	 * ## Three steps
	 *
	 * **Step 1: Target discovery** — every InOut of the environment and every semaphore of the
	 * grid ([allSemaphores], cached once per service, Issue #965), except [start] itself.
	 * O(grid size + InOuts).
	 *
	 * **Step 2: InOut sorting** — [start] is oriented (a semaphore or an InOut):
	 * 1. partition the InOuts into opposite-side (orientation differs from the start's: crossing
	 *    the network, forward progress) and same-side (returning to the same side),
	 * 2. sort each partition by shortest topological path length (unreachable last),
	 * 3. combine: [opposite-side InOuts] + [same-side InOuts].
	 *
	 * Partitioning before sorting ensures ALL opposite-side targets are tried before ANY same-side
	 * target, even a closer one.
	 *
	 * **Step 3: Prioritization** — sorted InOuts first (reaching an exit is better than stopping
	 * mid-network), then the semaphores unsorted, in grid order: they are secondary targets, the
	 * sorting cost is not justified, and if every InOut is blocked any semaphore is acceptable.
	 *
	 * ## Paths are enumerated lazily, once (Issue #966)
	 *
	 * `sortedBy` evaluates its selector on both sides of every comparison, so enumerating inside it
	 * ran `findAllTopologicalPaths` O(n log n) times, and the reservation attempt enumerated the
	 * same paths again. Each InOut is therefore an [TargetAttempt.InOutTarget] holding one lazy
	 * enumeration shared by the sort key and the attempt. The key is unchanged and `sortedBy` is stable,
	 * so the order is identical. `LazyThreadSafetyMode.NONE` is safe and cheaper: each lazy is
	 * created and consumed inside a single `reservePathToAny` call.
	 *
	 * ## Example: shunting loop
	 *
	 * Network: A ← zA ← vA ← (doA1/doA2) ↔ (doB1/doB2) → vB → zB → B. From zA (points toward A):
	 * InOuts [A, B] sort to [B, A] (B is on the opposite side), then the semaphores in grid order:
	 * [B, A, doA1, doA2, doB2, doB1, zB].
	 *
	 * ## Why the sorting is not in the navigator
	 *
	 * The order encodes reservation semantics (orientation preference, exits before signals);
	 * [TopologyNavigator] stays a stateless graph traversal.
	 *
	 * @return the targets in attempt order; empty when the network has no other separator
	 */
	fun reservePathToAnyTargets(start: OrientedPathSeparator): List<TargetAttempt> {
		// Step 1: target discovery. InOuts and semaphores in two lists: only InOuts are sorted.
		// toDynamic() converts a static InOut to its DynamicInOut for state tracking.
		val inouts = mutableListOf<DynamicInOut>()
		environment.getInOuts().forEach { inout ->
			val dynamicInOut = environment.toDynamic(inout)
			if (dynamicInOut != start) {
				inouts.add(dynamicInOut as DynamicInOut)
			}
		}
		val semaphores = allSemaphores().filter { semaphore -> semaphore != start }

		logger.debug {
			"reservePathToAny: Found ${inouts.size} InOut(s) and ${semaphores.size} semaphore(s) from $start"
		}

		// Step 2: InOut sorting, each InOut holding its one lazy path enumeration (Issue #966).
		val inOutAttempts =
			inouts.map { target ->
				TargetAttempt.InOutTarget(
					target,
					lazy(LazyThreadSafetyMode.NONE) {
						navigator.findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH)
					}
				)
			}
		// findAllTopologicalPaths returns the paths sorted by length (empty if none exists), so
		// firstOrNull() is the shortest; Int.MAX_VALUE sorts unreachable targets last.
		val shortestPathLength: (TargetAttempt.InOutTarget) -> Int = { attempt ->
			val shortest = attempt.paths.value.firstOrNull()
			shortest?.size ?: Int.MAX_VALUE
		}
		val startOrientation = start.getOrientation()
		val (oppositeSide, sameSide) =
			inOutAttempts.partition { it.target.getOrientation() != startOrientation }
		val sortedInOuts = oppositeSide.sortedBy(shortestPathLength) + sameSide.sortedBy(shortestPathLength)

		// Step 3: sorted InOuts first, then the semaphores in grid order. Semaphores carry no
		// precomputed paths: reservePath enumerates theirs.
		return sortedInOuts + semaphores.map { TargetAttempt.SemaphoreTarget(it) }
	}

	/**
	 * Find all semaphores reachable from start separator via specific track section.
	 *
	 * This method navigates from the starting separator through the given track section
	 * to discover ALL oriented semaphores (OrientedPathSeparator instances) reachable
	 * in the network, skipping backward-facing semaphores that show RED.
	 *
	 * ## Algorithm (Modified for Multi-Path Discovery)
	 *
	 * 1. Calculate travel direction from start + next track section
	 * 2. Use BFS to explore ALL reachable separators through the network
	 * 3. At switches, explore ALL outgoing edges (enables parallel path discovery)
	 * 4. Filter separators to include only:
	 *    - InOut elements (always valid endpoints)
	 *    - Forward-facing semaphores (direction() matches travel direction)
	 * 5. Return list of all valid targets, InOuts first
	 *
	 * ## Rationale for Multi-Path Discovery
	 *
	 * **Why return ALL reachable semaphores instead of just the first?**
	 * - In networks with parallel paths (e.g., shunting loops with k1 and k2 tracks),
	 *   multiple semaphores may be reachable from the same starting point.
	 * - When the first path is blocked (e.g., k1 occupied by Train 1), the service
	 *   should retry alternative paths (e.g., k2 for Train 2).
	 * - This enables true parallel operations with shared switches but disjoint tracks.
	 *
	 * **Backward-facing semaphores must be skipped**: A semaphore whose direction() does NOT
	 * match the travel direction shows RED and cannot be changed. Path discovery must continue
	 * until finding a forward-facing semaphore (can show GREEN) or an InOut destination.
	 *
	 * **InOut elements are bidirectional**: InOut points represent entry/exit to external
	 * network and are always valid endpoints regardless of orientation.
	 *
	 * ## Type Safety
	 *
	 * In SimulationContext, all PathSeparators are DynamicPathSeparator instances: the navigator
	 * uses the SimulationContext graph and every separator found is converted with
	 * [SimulationEnvironment.toDynamic].
	 *
	 * ## Example Networks
	 *
	 * **Parallel paths with shared switches:**
	 * ```
	 * Travel RIGHT →
	 * zA (orient=false) → vA (switch) → {
	 *   doA1 (backward) → k1 → doB1 (forward) ← vB (switch)
	 *   doA2 (backward) → k2 → doB2 (forward) ← vB (switch)
	 * }
	 * Result: [doB1, doB2]  (both forward-facing semaphores reachable via k1 and k2)
	 * ```
	 *
	 * **Skip backward-facing semaphore:**
	 * ```
	 * Travel RIGHT →
	 * zA (orient=false, faces RIGHT) → doA1 (orient=true, faces LEFT) → doB1 (orient=false, faces RIGHT)
	 * Result: [doB1]  (skips doA1 because it's backward-facing/RED)
	 * ```
	 *
	 * **Stop at InOut:**
	 * ```
	 * Travel LEFT ←
	 * doA2 (orient=true) → zA (orient=false, faces RIGHT/backward) → A (InOut)
	 * Result: [A]  (skips zA, reaches InOut destination)
	 * ```
	 *
	 * @param start Starting path separator (typically InOut or semaphore)
	 * @param next First track section after start (direction to search)
	 * @return Unique forward-facing semaphores and InOuts, InOuts first; empty at a dead end
	 * @throws IllegalStateException if [start] has no location or no connection to [next]
	 */
	fun findNextSemaphoresVia(
		start: PathSeparator,
		next: TrackSection
	): List<DynamicPathSeparator> {
		logger.trace {
			"findNextSemaphoresVia: Starting multi-path search from $start via $next"
		}

		val travelDirection = calculateTravelDirection(start, next)
		logger.trace { "findNextSemaphoresVia: Travel direction=$travelDirection" }

		// BFS to explore all reachable separators
		val validTargets = mutableListOf<DynamicPathSeparator>()
		val queue = mutableListOf<Pair<PathSeparator, TrackSection?>>()
		queue.add(Pair(start, next))
		val visited = mutableSetOf<PathSeparator>()
		visited.add(start)

		while (queue.isNotEmpty()) {
			val (currentSep, currentSection) = queue.removeAt(0)
			if (currentSection == null) continue

			val nextSeparator = currentSection.getSecondEnd(currentSep)
			if (!visited.add(nextSeparator)) continue

			// Check if separator is a valid target and whether to stop exploring beyond it
			val stopExploration = processReachedSeparator(nextSeparator, travelDirection, validTargets)

			// Only explore outgoing paths if we haven't reached a stopping point
			// This discovers parallel paths UP TO the first layer of forward-facing semaphores
			if (!stopExploration) {
				exploreOutgoingPaths(nextSeparator, currentSection, queue)
			}
		}

		return prioritizeInOuts(validTargets)
	}

	/**
	 * Process a reached separator to check if it's a valid target.
	 *
	 * @return true if exploration should stop beyond this separator (forward-facing semaphore/InOut),
	 *         false if exploration should continue (backward-facing semaphore/junction)
	 */
	private fun processReachedSeparator(
		separator: PathSeparator,
		travelDirection: Cell.Segment,
		validTargets: MutableList<DynamicPathSeparator>
	): Boolean =
		when {
			// InOut is always a valid endpoint (bidirectional) - stop exploration
			separator is InOut || separator is DynamicInOut -> {
				val dynamicSep = environment.toDynamic(separator)
				logger.trace { "findNextSemaphoresVia: Found InOut: $dynamicSep" }
				validTargets.add(dynamicSep)
				true // Stop exploring beyond InOuts
			}

			// Oriented semaphore - check if facing forward
			separator is OrientedPathSeparator -> {
				val isFacingForward = (separator.direction() == travelDirection)
				if (isFacingForward) {
					val dynamicSep = environment.toDynamic(separator)
					logger.trace { "findNextSemaphoresVia: Found forward-facing semaphore: $dynamicSep" }
					validTargets.add(dynamicSep)
					true // Stop exploring beyond forward-facing semaphores
				} else {
					logger.trace { "findNextSemaphoresVia: Skipping backward-facing semaphore: $separator" }
					false // Continue exploring beyond backward-facing semaphores
				}
			}

			else -> false // Continue exploring
		}

	/**
	 * Enqueue the sections to continue exploring from [separator], which was reached via
	 * [currentSection].
	 *
	 * At a junction — more than one outgoing section — every branch is enqueued, which is what
	 * makes parallel path discovery possible. Where exactly one outgoing section remains, that
	 * one is enqueued. If none remains, or [separator] has no location in the grid, nothing is
	 * enqueued and this arm of the search ends here.
	 *
	 * [currentSection] is excluded, so the search does not immediately turn back on the section
	 * it arrived on. The comparison is `!=`, which is reference equality today because no
	 * `TrackSection` implementation overrides `equals`; an override would silently widen this
	 * filter. The exclusion is local — it does not stop the same section being enqueued later
	 * from its other end. Traversal-level cycle protection is the caller's `visited` set in
	 * [findNextSemaphoresVia], and it is keyed on separators, not sections.
	 */
	private fun exploreOutgoingPaths(
		separator: PathSeparator,
		currentSection: TrackSection,
		queue: MutableList<Pair<PathSeparator, TrackSection?>>
	) {
		val location = environment.getRailWayNetGrid().getLocation(separator) ?: return
		val outgoingSections: List<TrackSection> =
			environment
				.getGraph()
				.assignedEdges(location)
				.values
				.filter { block -> block != currentSection }

		when {
			outgoingSections.size > 1 -> {
				// At ANY junction: explore ALL branches (enables full parallel path discovery)
				logger.trace {
					"findNextSemaphoresVia: At junction $separator, exploring ${outgoingSections.size} branches"
				}
				outgoingSections.forEach { section -> queue.add(Pair(separator, section)) }
			}
			outgoingSections.size == 1 -> {
				// Single path forward
				queue.add(Pair(separator, outgoingSections.first()))
			}
		}
	}

	/** Prioritize InOuts over semaphores in the (de-duplicated) result list. */
	private fun prioritizeInOuts(validTargets: List<DynamicPathSeparator>): List<DynamicPathSeparator> {
		val distinctTargets = validTargets.distinct()
		val (inouts, semaphores) = distinctTargets.partition { it is DynamicInOut }
		logger.trace {
			"findNextSemaphoresVia: Returning ${inouts.size} InOut(s) + ${semaphores.size} semaphore(s)"
		}
		return inouts + semaphores
	}

	/**
	 * Calculate the travel direction from a starting separator through a track section: the
	 * segment of [start]'s grid location whose assigned graph edge is [next].
	 *
	 * ```
	 * Grid location (14,8): zA semaphore
	 * Graph edges: {Segment.A → track1, Segment.F → track2}
	 * If next=track2, return Segment.F (traveling RIGHT)
	 * ```
	 *
	 * @throws IllegalStateException if start has no location or no connection to next
	 */
	private fun calculateTravelDirection(
		start: PathSeparator,
		next: TrackSection
	): Cell.Segment {
		val location =
			environment.getRailWayNetGrid().getLocation(start)
				?: throw IllegalStateException("No location for $start")

		// Find which segment connects to 'next' track section
		for ((segment, block) in environment.getGraph().assignedEdges(location)) {
			if (block == next) {
				logger.trace {
					"calculateTravelDirection: start=$start, next=$next, direction=$segment"
				}
				return segment
			}
		}

		throw IllegalStateException("No connection from $start to $next")
	}
}
