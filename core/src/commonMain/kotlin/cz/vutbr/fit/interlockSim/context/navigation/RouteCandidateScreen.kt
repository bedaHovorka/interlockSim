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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Candidate geometry screening (Issue #1210, SP12.9): the read-only checks that decide whether a
 * route request, or one of its candidate paths, is geometrically admissible at all, before
 * [DefaultPathReservationService] reserves a single block. Nothing here reserves, registers,
 * configures or signals, so a refused request or candidate leaves nothing to roll back.
 *
 * Two kinds of check live here:
 * - **Endpoint guards** (Step 0), applied once per request: [rejectNonOrientedStart],
 *   its END-side twin [rejectNonOrientedEnd], and [rejectNonContiguousStart].
 * - **Candidate screens** (Steps 1.5 and 1.6), applied to the enumerated candidate paths:
 *   [screenRearFacingEnds] (G8, the END must face the train) and [screenDivergentCandidates]
 *   (the candidate must continue the route the train already holds). Both report the screened-out
 *   candidates as [GeometricFailures], with the survivors keeping their original index.
 *
 * [forwardOnlyPathInfo] is the shared PathInfo trimming rule: the divergence screen uses it to
 * build the probe, and the candidate commit in the service uses it to build the merge candidate.
 *
 * Extracted from [DefaultPathReservationService] without behaviour change: the denial reasons and
 * log lines are byte-identical, and the service-level tests (`PathReservationContiguityTest`,
 * `PathReservationNonOrientedOriginTest`, `PathReservationNonOrientedEndTest`, `RouteEndDirectionTest`,
 * `Issue1066RegressionTest`, `PathReservationSwitchStartPathInfoRegressionTest`) still pin them
 * through the public API.
 *
 * **NOT thread-safe**, like its owner: single-threaded simulation access only.
 *
 * @property registry Ownership registry; footprint and the train's stored PathInfo
 * @property environment Simulation environment; graph, grid and dynamic wrappers
 * @property targetDiscovery Names the signals facing the train in a rear-facing denial reason
 * @property pathInfoBuilder Builder for the (trimmed) PathInfo of a candidate
 * @since Issue #1210 (extracted from [DefaultPathReservationService])
 */
internal class RouteCandidateScreen(
	private val registry: PathReservationRegistry,
	private val environment: SimulationEnvironment,
	private val targetDiscovery: ReservationTargetDiscovery,
	private val pathInfoBuilder: PathInfoBuilder
) {
	/**
	 * Every [DynamicTrackBlock] edge in this context's graph, read once on first use.
	 *
	 * The graph's edges are static for the lifetime of a simulation context — blocks are never
	 * added or removed at runtime — so the list is built lazily once and never invalidated; only
	 * the live state read off those references ([DynamicTrackBlock.occupant]) changes. Mirrors
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]'s block cache.
	 *
	 * Lazy rather than eager: this screen is constructed together with [DefaultPathReservationService]
	 * inside the per-context Koin scope, and touching the graph at construction time would couple
	 * service creation to graph readiness.
	 *
	 * @since Issue #893 (task A-R1) — backing store of the contiguity predicate's occupancy arm
	 */
	private val allBlocksCache: List<DynamicTrackBlock> by lazy {
		environment
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.toList()
	}

	/**
	 * Every block that constitutes [trainId]'s current authority: those the registry records as
	 * reserved for it, plus those it physically occupies.
	 *
	 * The occupancy arm is a **graph scan**, not [PathReservationRegistry.getOccupiedBlocks].
	 * That method filters the registry's own `trainToBlocks` map and is therefore blind to a
	 * train admitted onto the network without a registered route — precisely the state that
	 * produced Issue #893's stall at t=17, where a train stood on block `kB` with an empty
	 * registry entry.
	 */
	internal fun footprintOf(trainId: String): Set<DynamicTrackBlock> =
		registry.getBlocks(trainId).toSet() +
			allBlocksCache.filter { it.occupant?.name == trainId }

	/**
	 * The contiguity invariant (Issue #893, task A-R1): a route may only start where the
	 * requesting train actually is.
	 *
	 * Returns `null` when [start] is an acceptable origin for [trainId], or the
	 * [PathReservationService.ReservationResult.NonContiguousStart] to return otherwise.
	 *
	 * ## Rules
	 *
	 * - **Empty footprint passes vacuously.** A train that holds and occupies nothing is still
	 *   outside the network and starts at an entry InOut by design. Which layer guards that
	 *   queued-train case, and why it is not this one, is the table at
	 *   [PathReservationService.ReservationResult.NonContiguousStart] (ruling D8).
	 * - **Otherwise [start] must bound one of the footprint blocks.** Membership of
	 *   `block.ends()`, nothing more.
	 *
	 * ## Why no direction restriction
	 *
	 * Mid-transition a train occupies two blocks at once, so "the" forward boundary is genuinely
	 * ambiguous and any attempt to pick one would reject legitimate look-ahead extensions.
	 * Direction is a separate concern, already covered by the backwards-route guards on the
	 * request path.
	 */
	internal fun rejectNonContiguousStart(
		trainId: String,
		start: DynamicPathSeparator
	): PathReservationService.ReservationResult.NonContiguousStart? {
		val footprint = footprintOf(trainId)
		if (footprint.isEmpty()) {
			logger.debug {
				"reservePath: '$trainId' holds and occupies no block; contiguity check passes " +
					"vacuously for start ${separatorLabel(start)}"
			}
			return null
		}
		if (footprint.any { block -> start in block.ends() }) return null

		val legalStarts =
			footprint
				.flatMap { it.ends().toList() }
				.map { separatorLabel(it) }
				.distinct()
				.sorted()
		val startName = separatorLabel(start)
		val reason =
			"Route origin '$startName' is not contiguous with train '$trainId': the train holds or " +
				"occupies ${footprint.size} block(s), none of which is bounded by '$startName'. " +
				"Legal origins for this train are: ${legalStarts.joinToString(", ")}."
		logger.warn { "reservePath: rejected non-contiguous start — $reason" }
		return PathReservationService.ReservationResult.NonContiguousStart(startName, reason)
	}

	/**
	 * The endpoint invariant (Issue #1188): a route runs signal-to-signal, so its origin must be
	 * an [OrientedPathSeparator] (a semaphore or an InOut). A switch is interior track, never an
	 * endpoint — the same rule [forwardOnlyPathInfo] enforces for a trimmed PathInfo start
	 * (Issue #938).
	 *
	 * Returns `null` when [start] is oriented, or the
	 * [PathReservationService.ReservationResult.GeometricallyImpossible] to return otherwise.
	 *
	 * ## Why this must be a Step 0 guard
	 *
	 * Without it a switch start passed Step 2c/2d (blocks reserved AND registered to the train)
	 * and only then threw `SimulationException[FATAL]` out of
	 * [cz.vutbr.fit.interlockSim.context.SimulationContext.getSegment] ("PathSeparator must be
	 * OrientedPathSeparator") from inside `configureSwitchesInPath`, past every rollback path.
	 * The blocks stayed registered to a route that was never granted — a leak that made later
	 * requests from an unrelated separator fail as `NonContiguousStart`, because the train's
	 * footprint was no longer empty.
	 *
	 * ## Why `GeometricallyImpossible` and not a new result class
	 *
	 * It is exactly that contract: permanent for the identical request, never retryable, and the
	 * caller has to ask for a different origin — unlike ordinary
	 * [PathReservationService.ReservationResult.AllPathsBlocked] contention.
	 */
	internal fun rejectNonOrientedStart(
		trainId: String,
		start: DynamicPathSeparator,
		entryPoint: String
	): PathReservationService.ReservationResult.GeometricallyImpossible? {
		if (start is OrientedPathSeparator) return null
		val startName = separatorLabel(start)
		val reason =
			"Route origin '$startName' is not an oriented separator (it is a " +
				"${start::class.simpleName ?: "unknown"}): a route runs signal-to-signal, so it can only " +
				"start at a semaphore or an InOut. Request for train '$trainId' refused before any " +
				"block, switch or signal was touched."
		logger.warn { "$entryPoint: rejected non-oriented start — $reason" }
		return PathReservationService.ReservationResult.GeometricallyImpossible(reason)
	}

	/**
	 * The END-side twin of [rejectNonOrientedStart] (Issue #1200, traffic-simulation-expert
	 * ruling): a route endpoint, origin **or** end, must be an [OrientedPathSeparator] (a semaphore
	 * or an InOut). A switch is interior track, never an endpoint — the same switch rule
	 * [PathReservationRegistry.isValidPathInfoEnd] already applies to the end of a stored PathInfo
	 * (Issue #938).
	 *
	 * Returns `null` when [target] is oriented, or the
	 * [PathReservationService.ReservationResult.GeometricallyImpossible] to return otherwise.
	 *
	 * ## Why this must be a Step 0 guard
	 *
	 * Without it a switch end was granted: G8 ([rearFacingEndReason]) declines to judge the
	 * direction of a non-oriented end, the switch configuration skips a terminal switch, and
	 * [PathInfoBuilder.buildPathInfo] stores the target verbatim. The train then held a route
	 * ending at a switch, and every extension FROM that switch was refused by
	 * [rejectNonOrientedStart], so it held track it could never leave. Refusing before candidate
	 * discovery leaves nothing reserved, registered, configured or merged.
	 */
	internal fun rejectNonOrientedEnd(
		trainId: String,
		target: DynamicPathSeparator,
		entryPoint: String
	): PathReservationService.ReservationResult.GeometricallyImpossible? {
		if (target is OrientedPathSeparator) return null
		val targetName = separatorLabel(target)
		val reason =
			"Route end '$targetName' is not an oriented separator (it is a " +
				"${target::class.simpleName ?: "unknown"}): a route runs signal-to-signal, so it can only " +
				"end at a semaphore or an InOut. Request for train '$trainId' refused before any " +
				"block, switch or signal was touched."
		logger.warn { "$entryPoint: rejected non-oriented end — $reason" }
		return PathReservationService.ReservationResult.GeometricallyImpossible(reason)
	}

	/**
	 * G8 screening of [candidatePaths] (Issue #1064): splits off every candidate whose end faces away
	 * from the train ([rearFacingEndReason]) and records each one as a geometric failure. Extracted so
	 * the screening adds no branch to [DefaultPathReservationService.reservePath]'s own cyclomatic complexity.
	 *
	 * @return the geometric failures of the screened-out candidates, and the remaining candidates
	 *   with their original index, which later denial reasons use to name a candidate
	 */
	internal fun screenRearFacingEnds(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidatePaths: List<List<TrackSection>>
	): Pair<GeometricFailures, List<IndexedValue<List<TrackSection>>>> {
		var failures = GeometricFailures()
		val usable = mutableListOf<IndexedValue<List<TrackSection>>>()
		for (candidate in candidatePaths.withIndex()) {
			val reason = rearFacingEndReason(start, target, candidate.value, candidate.index)
			if (reason == null) {
				usable += candidate
			} else {
				failures = recordGeometricFailureOnce(failures, reason)
			}
		}
		return failures to usable
	}

	/**
	 * Step 1.6 screening of [candidates] (Issue #1066): splits off every candidate whose merge
	 * candidate would not continue the route the train already holds
	 * ([PathReservationRegistry.nonContinuingCandidateReason]), and records each one as a
	 * "divergent" failure. Nothing is mutated, so a skipped candidate leaves nothing to roll back.
	 *
	 * A candidate whose blocks the train already owns entirely (empty forward-block list) is
	 * never screened: it takes `reservePath`'s already-owned early return, which has its own merge
	 * handling. Ownership does not change between this screening and the candidate loop, because a
	 * candidate that reserves anything either succeeds (and the loop returns) or rolls back fully.
	 *
	 * @return the divergent failures of the screened-out candidates, and the remaining candidates
	 *   with their original index
	 */
	internal fun screenDivergentCandidates(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidates: List<IndexedValue<List<TrackSection>>>
	): Pair<GeometricFailures, List<IndexedValue<List<TrackSection>>>> {
		var failures = GeometricFailures()
		val usable = mutableListOf<IndexedValue<List<TrackSection>>>()
		// Early-out for the common case: a train with no stored route cannot diverge from one, so
		// skip the per-candidate PathInfo build entirely. reservePath sits on the per-tick polling
		// path (see [ReservationContentionTracker.recordContentionAndEmitIfNew]'s KDoc), so this guard
		// saves a full probe build per candidate on every first reservation.
		if (registry.getPathInfo(trainId) == null) return failures to candidates
		for (candidate in candidates) {
			val forwardBlocks = extractUniqueBlocks(candidate.value).filterNot { it.trainName == trainId }
			val reason =
				if (forwardBlocks.isEmpty()) {
					null
				} else {
					registry.nonContinuingCandidateReason(
						trainId,
						forwardOnlyPathInfo(start, target, candidate.value, forwardBlocks)
					)
				}
			if (reason == null) {
				usable += candidate
			} else {
				logger.debug { "reservePath: skipping candidate ${candidate.index} for $trainId before any mutation: $reason" }
				failures = recordGeometricFailureOnce(failures, reason)
			}
		}
		return failures to usable
	}

	/**
	 * G8 (Issue #1064): the END-side twin of G4. A route whose target signal faces away from the
	 * direction the train arrives in gives the train nothing it can use: navigation cannot build a
	 * leg past the last signal facing it, so the train stops there holding track it cannot leave and
	 * waits for an extension (Issue #1031, Issue #1060). The rule-based path never asks for such a
	 * route — [ReservationTargetDiscovery.findNextSemaphoresVia] skips backward-facing signals — so
	 * only a free-form `request_route(from, to)` reaches this.
	 *
	 * The direction is taken per candidate at the target's own cell: the segment through which
	 * [path]'s last section enters the target, reversed, is the direction of travel there. A loop-
	 * around candidate therefore arrives from the other side and is judged on its own.
	 *
	 * The `target !is OrientedPathSeparator` arm performs the smart cast for [OrientedPathSeparator.direction]:
	 * it does not grant a switch end. Such an end is unreachable from
	 * [DefaultPathReservationService.reservePath] after the Step 0 END guard
	 * ([rejectNonOrientedEnd], Issue #1200), so the arm no longer fires — but it must stay, because
	 * the [OrientedPathSeparator.direction] call below compiles only through its smart cast.
	 *
	 * @return the English denial reason when [target] faces away from the train at the end of
	 *   [path]; `null` when the end is usable — an InOut (bidirectional) or a signal facing the
	 *   direction of travel — or when there is nothing to judge (an empty [path], or a
	 *   non-oriented [target], which the END guard has already refused).
	 */
	internal fun rearFacingEndReason(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<TrackSection>,
		candidateIndex: Int
	): String? {
		if (target is DynamicInOut || target !is OrientedPathSeparator || path.isEmpty()) return null
		val arrival = arrivalSegmentAt(target, path.last()) ?: return null
		if (target.direction() == anti(arrival)) return null
		val facingEnds =
			runCatching { targetDiscovery.findNextSemaphoresVia(start, path.first()) }
				.getOrDefault(emptyList())
				.joinToString(", ") { separatorLabel(it) }
		return "Route end '${separatorLabel(target)}' faces away from the direction of travel on candidate " +
			"$candidateIndex; a route must end at a signal facing the train. The next signals facing the " +
			"train from '${separatorLabel(start)}' are: $facingEnds."
	}

	/** The segment of [target]'s cell through which [lastSection] reaches it, or `null` if they are not adjacent. */
	internal fun arrivalSegmentAt(
		target: PathSeparator,
		lastSection: TrackSection
	): Cell.Segment? {
		val location = environment.getRailWayNetGrid().getLocation(target) ?: return null
		return environment
			.getGraph()
			.assignedEdges(location)
			.entries
			.firstOrNull { (_, section) -> section == lastSection }
			?.key
	}

	/**
	 * Issue #904 root-cause fix (traffic-simulation-expert ruling): builds the PathInfo passed to
	 * [PathReservationRegistry.registerPathInfo] using the FORWARD-ONLY segment of [path] --
	 * starting where [forwardBlocks] actually begins, not at [start].
	 *
	 * `PathInfoBuilder.buildPathInfo` always sets `PathInfo.start = start`. A route EXTENSION
	 * that reuses the train's ORIGINAL start (the shape Issue #911 already established happens
	 * in production) passes a `start` that no longer bounds the first forward block -- every
	 * block adjacent to it is already owned. Building the merge candidate from that `start`
	 * therefore makes `new.start != old.target` on every such call, not a rare shape but the
	 * routine one, and [PathInfoMerger.merge]'s Step 0a fail-safe aborts every
	 * time, discarding a legitimate extension. Starting the merge candidate where the NEW blocks
	 * actually begin makes `new.start` agree with `old.target` naturally, so the merge succeeds
	 * and properly extends PathInfo instead of aborting -- leaving
	 * [CandidateCommitter.rollbackIfMergeAborted]'s rollback to fire only for genuinely
	 * pathological aborts (duplicated new-start, the 3rd-occurrence cycle guard), not for this
	 * routine extension shape.
	 *
	 * @param path The FULL candidate path (including any already-owned prefix).
	 * @param forwardBlocks The blocks this candidate newly reserves (excludes any already-owned
	 *   prefix by construction, see [DefaultPathReservationService.reservePath] Step 2a.5).
	 */
	internal fun forwardOnlyPathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<TrackSection>,
		forwardBlocks: List<DynamicTrackBlock>
	): PathInfo {
		var currentSeparator = start
		for ((index, section) in path.withIndex()) {
			if (section.getTrackBlock() in forwardBlocks) {
				// Issue #938: a route runs signal-to-signal; a switch is interior, never an
				// endpoint. If the separator before the first new block is a DynamicRailSwitch
				// (e.g. vA or vB on the siding branch), using it as PathInfo.start would make
				// the merge non-contiguous (stored path ends at a semaphore; new path starts at
				// a switch → new.start != old.target → Step 0a abort). Fall back to `start`
				// (the bounding semaphore) and include the full path so the PathInfo is always
				// semaphore/InOut-bounded and the merge can succeed.
				if (currentSeparator is DynamicRailSwitch) {
					return pathInfoBuilder.buildPathInfo(start, target, path)
				}
				return pathInfoBuilder.buildPathInfo(currentSeparator, target, path.subList(index, path.size))
			}
			val staticNext = section.getSecondEnd(currentSeparator)
			currentSeparator = environment.toDynamic(staticNext)
		}
		// No already-owned prefix to trim -- the ordinary, non-extension case.
		return pathInfoBuilder.buildPathInfo(start, target, path)
	}
}
