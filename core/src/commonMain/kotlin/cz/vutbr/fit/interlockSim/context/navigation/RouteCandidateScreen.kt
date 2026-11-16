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
 * - **Candidate screens** (Steps 1.4, 1.5 and 1.6), applied to the enumerated candidate paths:
 *   [screenRearFacingStarts] (the semaphore START must face the train, Issue #1378),
 *   [screenRearFacingEnds] (G8, the END must face the train) and [screenDivergentCandidates]
 *   (the candidate must continue the route the train already holds). All three report the screened-out
 *   candidates as [GeometricFailures], with the survivors keeping their original index.
 *
 * [forwardOnlyPathInfo] is the shared PathInfo trimming rule: the divergence screen uses it to
 * build the probe, and [CandidateCommitter] uses it at Step 2i to build the merge candidate.
 *
 * Extracted from [DefaultPathReservationService] in Issue #1210 without behaviour change: at the
 * extraction, the denial reasons and log lines were byte-identical, and the service-level tests
 * (`PathReservationContiguityTest`, `PathReservationNonOrientedOriginTest`, `PathReservationNonOrientedEndTest`,
 * `RouteEndDirectionTest`, `Issue1066RegressionTest`, `PathReservationSwitchStartPathInfoRegressionTest`)
 * still pin them through the public API. Issue #1378 later added the Step 1.4 START screen and
 * reshaped Step 1.5's input; the pins of this class and the service-level suite state the current
 * semantics.
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
			allBlocksCache.filter { physicallyOccupies(it, trainId) }

	/**
	 * The names of every train that physically stands on a block of the network — the occupancy arm
	 * of [footprintOf] for all trains at once, in one pass over the cached block list, so it also sees
	 * a block the registry does not attribute to its train (Issue #893). The kernel reservation
	 * expiry exempts such a train from the whole-route expiry (Issue #1174) and reads this set once
	 * per call rather than scanning once per candidate (Issue #1229).
	 */
	internal fun standingTrainIds(): Set<String> = allBlocksCache.mapNotNullTo(HashSet()) { it.occupant?.name }

	/** [block]'s physical occupant is [trainId]: the un-attributed occupancy notion of the scan. */
	private fun physicallyOccupies(
		block: DynamicTrackBlock,
		trainId: String
	): Boolean = block.occupant?.name == trainId

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
	 * Step 1.4 screening of [candidatePaths] (Issue #1378, traffic-simulation-expert ruling): splits off
	 * every candidate whose first block lies behind its semaphore START ([rearFacingStartReason]) and
	 * records each one as a geometric failure. Nothing is mutated, so a screened-out candidate leaves
	 * nothing to roll back; it runs before the candidate loop, so it also covers the already-owned
	 * early return, which used to grant a switch-free reversal onto the train's own block.
	 *
	 * @return the geometric failures of the screened-out candidates, and the remaining candidates
	 *   with their original index, which later screens and denial reasons use to name a candidate
	 */
	internal fun screenRearFacingStarts(
		start: DynamicPathSeparator,
		candidatePaths: List<List<TrackSection>>
	): Pair<GeometricFailures, List<IndexedValue<List<TrackSection>>>> {
		// The facing list of the denial reason depends on [start] alone, not on the candidate: it is
		// resolved at most once per call, lazily on the rule's first hit, and shared by every
		// screened-out candidate of this call (Issue #1378 review round: the Praha reversal was
		// resolving one request-constant list thousands of times). A facing candidate pays nothing.
		val facingStarts = lazy(LazyThreadSafetyMode.NONE) { facingStartList(start) }
		return splitOnReason(
			candidatePaths.withIndex(),
			reasonFor = { candidate ->
				if (rearFacingStartShape(start, candidate.value) == null) {
					null
				} else {
					startFacingReason(start, candidate.index, facingStarts.value)
				}
			},
			skipLine = ::fixedSkipLine
		)
	}

	/**
	 * The START-side rule of Step 1.4 (Issue #1378): the G4 rule of
	 * [RouteSignalConfigurator.configureStartSignal], applied before anything is reserved. A route
	 * whose semaphore START faces away from the candidate's first block asks the train to leave
	 * against the signal it stands at -- the switch-free reversal `S9 -> S15` onto the platform track
	 * the train already occupies (Praha), which no switch check would ever catch.
	 *
	 * The facing test is [semaphoreFacesNextBlockOrOpen], the one [RouteSignalConfigurator] uses, so
	 * an unresolvable pair fails open here as it does there. Not screened: an InOut START (its
	 * embedded signal is configured per direction) and an empty [path]. The G4 checks in
	 * [CandidateCommitter] / [RouteSignalConfigurator.configureStartSignal] and the already-owned SKIP
	 * arm stay as defence in depth.
	 *
	 * Unit-rule seam: the production screen ([screenRearFacingStarts]) goes through
	 * [rearFacingStartShape] and its memoized [facingStartList]; this wrapper resolves the facing
	 * list per call.
	 *
	 * @return the English denial reason, naming the signals or exits that do face the train from [start]
	 *   (`none` when there are none); `null` when [start] is not a semaphore, [path] is empty, its first
	 *   section has no dynamic block, or the START faces the first block
	 */
	internal fun rearFacingStartReason(
		start: DynamicPathSeparator,
		path: List<TrackSection>,
		candidateIndex: Int
	): String? =
		if (rearFacingStartShape(start, path) == null) {
			null
		} else {
			startFacingReason(start, candidateIndex, facingStartList(start))
		}

	/**
	 * The START-side shape of Step 1.4: `null` when the candidate survives the screen — [start] is
	 * not a semaphore, [path] is empty, its first section has no dynamic block (the fail-open arms,
	 * like the G4 check: a first section with no dynamic block cannot be judged), or the START faces
	 * the first block — and non-`null` when the rule fires.
	 */
	private fun rearFacingStartShape(
		start: DynamicPathSeparator,
		path: List<TrackSection>
	): DynamicTrackBlock? {
		if (start !is DynamicRailSemaphore || path.isEmpty()) return null
		val firstBlock = path.first().getTrackBlock() as? DynamicTrackBlock ?: return null
		return firstBlock.takeUnless { semaphoreFacesNextBlockOrOpen(environment, start, it) }
	}

	/** The reason text of a Step 1.4 screening; substitutes [facingStarts] verbatim. */
	private fun startFacingReason(
		start: DynamicPathSeparator,
		candidateIndex: Int,
		facingStarts: String
	): String =
		"Route origin '${separatorLabel(start)}' faces away from the direction of travel on candidate " +
			"$candidateIndex; a route must start at a signal facing the train. The next signals or exits " +
			"facing the train from '${separatorLabel(start)}' are: $facingStarts."

	/**
	 * The signals or exits that face the train from [start] — the request-constant part of the Step
	 * 1.4 denial reason, `none` when there are none. [screenRearFacingStarts] resolves it at most
	 * once per call (Issue #1378 review round), while the [rearFacingStartReason] unit seam resolves
	 * it per call; a failing lookup degrades to `none` and is logged at DEBUG, never swallowed
	 * silently — like [semaphoreFacesNextBlockOrOpen]'s own fail-open.
	 */
	private fun facingStartList(start: DynamicPathSeparator): String {
		// Only the semaphore arm of [rearFacingStartShape] reaches this lookup, so the cast is the
		// guard's type-level restatement; if it still ever misses, the arm degrades to `none` the
		// same fail-open way.
		val semaphore = start as? DynamicRailSemaphore ?: return "none"
		return runCatching {
			val location = environment.getRailWayNetGrid().getLocation(semaphore)
			val facingSection = location?.let { environment.getGraph().assignedEdges(it)[semaphore.direction()] }
			facingSection?.let { targetDiscovery.findNextSemaphoresVia(semaphore, it) } ?: emptyList()
		}.onFailure {
			logger.debug(it) {
				"reservePath: Could not resolve the signals facing the train from " +
					"'${separatorLabel(start)}' (unexpectedly failed grid lookup); treating the " +
					"facing list as none"
			}
		}.getOrDefault(emptyList())
			.joinToString(", ") { separatorLabel(it) }
			.ifEmpty { "none" }
	}

	/**
	 * The fixed per-candidate DEBUG trace of the START and END screens; the refusal summary promises
	 * it ("the screens' skip lines", Issue #1378 review round). Step 1.6 carries its own variant with
	 * the train id.
	 */
	private fun fixedSkipLine(
		candidate: IndexedValue<List<TrackSection>>,
		reason: String
	): String = "reservePath: skipping candidate ${candidate.index} before any mutation: $reason"

	/**
	 * The shared split-and-record scaffold of the three candidate screens (Issue #1378 review round):
	 * [reasonFor] judges one candidate (`null` = keep, with its original index), [skipLine] builds the
	 * per-candidate DEBUG trace. Survivors keep their original index; every failure is recorded with
	 * [recordGeometricFailureOnce]'s first-reason-first rule.
	 */
	private fun splitOnReason(
		candidates: Iterable<IndexedValue<List<TrackSection>>>,
		reasonFor: (IndexedValue<List<TrackSection>>) -> String?,
		skipLine: (IndexedValue<List<TrackSection>>, String) -> String
	): Pair<GeometricFailures, List<IndexedValue<List<TrackSection>>>> {
		var failures = GeometricFailures()
		val usable = mutableListOf<IndexedValue<List<TrackSection>>>()
		for (candidate in candidates) {
			val reason = reasonFor(candidate)
			if (reason == null) {
				usable += candidate
			} else {
				logger.debug { skipLine(candidate, reason) }
				failures = recordGeometricFailureOnce(failures, reason)
			}
		}
		return failures to usable
	}

	/**
	 * G8 screening of [candidates] (Issue #1064): splits off every candidate whose end faces away
	 * from the train ([rearFacingEndReason]) and records each one as a geometric failure. Extracted so
	 * the screening adds no branch to [DefaultPathReservationService.reservePath]'s own cyclomatic complexity.
	 *
	 * [candidates] are the Step 1.4 survivors with their original index (Issue #1378), so a denial
	 * reason still names a candidate by its position in the enumerated list.
	 *
	 * @return the geometric failures of the screened-out candidates, and the remaining candidates
	 *   with their original index, which later denial reasons use to name a candidate
	 */
	internal fun screenRearFacingEnds(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidates: List<IndexedValue<List<TrackSection>>>
	): Pair<GeometricFailures, List<IndexedValue<List<TrackSection>>>> =
		splitOnReason(
			candidates,
			reasonFor = { candidate -> rearFacingEndReason(start, target, candidate.value, candidate.index) },
			skipLine = ::fixedSkipLine
		)

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
		// Early-out for the common case: a train with no stored route cannot diverge from one, so
		// skip the per-candidate PathInfo build entirely. reservePath sits on the per-tick polling
		// path (see [ReservationContentionTracker.recordContentionAndEmitIfNew]'s KDoc), so this guard
		// saves a full probe build per candidate on every first reservation.
		if (registry.getPathInfo(trainId) == null) return GeometricFailures() to candidates
		return splitOnReason(
			candidates,
			reasonFor = { candidate ->
				val forwardBlocks = extractUniqueBlocks(candidate.value).excludingOwnedBy(trainId)
				if (forwardBlocks.isEmpty()) {
					null
				} else {
					registry.nonContinuingCandidateReason(
						trainId,
						forwardOnlyPathInfo(start, target, candidate.value, forwardBlocks)
					)
				}
			},
			skipLine = { candidate, reason ->
				"reservePath: skipping candidate ${candidate.index} for $trainId before any mutation: $reason"
			}
		)
	}

	/**
	 * G8 (Issue #1064): the END-side twin of G4. A route whose target signal faces away from the
	 * direction the train arrives in gives the train nothing it can use: navigation cannot build a
	 * leg past the last signal facing it, so the train stops there holding track it cannot leave and
	 * waits for an extension (Issue #1031, Issue #1060). The rule-based path never asks for such a
	 * route — [ReservationTargetDiscovery.findNextSemaphoresVia] skips backward-facing signals with
	 * this same rule ([semaphoreFacesArrivalVia], Issue #1390) — so only a free-form
	 * `request_route(from, to)` reaches this, or a candidate that reaches an offered target over a
	 * different, rear-facing arrival.
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
				.onFailure {
					// The mirrored facingStartList lesson (Issue #1378 review round): the degraded
					// facing list renders an empty suffix after "are: ", so the degradation must not
					// fail silently.
					logger.debug(it) {
						"reservePath: Could not resolve the signals facing the train from " +
							"'${separatorLabel(start)}' (unexpectedly failed grid lookup); treating the " +
							"facing list as empty"
					}
				}.getOrDefault(emptyList())
				.joinToString(", ") { separatorLabel(it) }
		return "Route end '${separatorLabel(target)}' faces away from the direction of travel on candidate " +
			"$candidateIndex; a route must end at a signal facing the train. The next signals facing the " +
			"train from '${separatorLabel(start)}' are: $facingEnds."
	}

	/**
	 * The segment of [target]'s cell through which [lastSection] reaches it, or `null` if they are not
	 * adjacent. Delegates to the shared lookup that candidate discovery also uses (Issue #1390).
	 */
	internal fun arrivalSegmentAt(
		target: PathSeparator,
		lastSection: TrackSection
	): Cell.Segment? = arrivalSegmentAt(environment, target, lastSection)

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
	 * the merge-abort rollback of [CandidateCommitter] (its private `rollbackIfMergeAborted`) to
	 * fire only for genuinely pathological aborts (duplicated new-start, the direction reversal
	 * guard of Issue #944, the 3rd-occurrence cycle guard), not for this routine extension shape.
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
