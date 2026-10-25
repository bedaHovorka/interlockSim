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

import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFreeOrOwnedBy
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of path reservation service.
 *
 * ## Architecture
 *
 * This service coordinates three components:
 * - **TopologyNavigator**: Finds all possible paths (static topology)
 * - **SimulationEnvironment**: Accesses dynamic block state (FREE/RESERVED/OCCUPIED)
 * - **PathReservationRegistry**: Tracks train ownership of blocks
 *
 * ## Atomic Reservation Algorithm
 *
 * For each candidate path:
 * 1. Extract unique blocks from track sections
 * 2. Check all blocks are FREE (validation phase)
 * 3. Reserve all blocks via setUpPath() (reservation phase)
 * 4. Register ownership in registry (tracking phase)
 * 5. If any step fails, rollback all changes
 *
 * ## Rollback Strategy
 *
 * When partial reservation fails:
 * ```kotlin
 * try {
 *     blocks.forEach { it.setUpPath(start) }
 * } catch (e: Exception) {
 *     // Rollback: release all blocks reserved so far
 *     blocks.forEach { it.cancelPathSetup(start) }
 *     throw e
 * }
 * ```
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** Assumes single-threaded access to simulation state.
 *
 * @property navigator Static topology navigator for path finding
 * @property environment Simulation environment for dynamic block access
 * @property registry Ownership registry for tracking train reservations
 * @property pathInfoBuilder Builder for PathInfo metadata (Issue #295/#296 Phase 4)
 * @property routeFinder Cost-based route planner for InOut-to-InOut automatic route selection (Issue #597)
 * @property reservationTtlSimSeconds Simulated seconds a holding of un-entered blocks may go without a
 *   new block registration before [expireStaleReservations] releases it (Issue #1174); see
 *   [DEFAULT_RESERVATION_TTL_SIM_SECONDS]
 * @since Issue #294 (Phase 2 of Issue #292)
 */
class DefaultPathReservationService(
	private val navigator: TopologyNavigator,
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry,
	private val pathInfoBuilder: PathInfoBuilder,
	private val routeFinder: RouteFinder,
	private val reservationTtlSimSeconds: Double = DEFAULT_RESERVATION_TTL_SIM_SECONDS
) : PathReservationService,
	ReservationTargetQuery,
	BlockRollbackStep {
	companion object {
		/**
		 * Default reservation TTL, in simulated seconds (Issue #1174).
		 *
		 * The same 60 simulated seconds as the dispatcher's
		 * `OrphanReservationSweeper.DEFAULT_STALE_AFTER_SIM_SECONDS` and the #943 WARN horizon of a
		 * train stalled on an ownership conflict: one horizon for "this holding is not being used".
		 * It is comfortably longer than a train on `vyhybna.xml` takes to enter a route it intends to
		 * travel, so a merely slow train is not mistaken for one that abandoned its route. It is read
		 * against simulation time only, never wall-clock time.
		 */
		const val DEFAULT_RESERVATION_TTL_SIM_SECONDS: Double = 60.0
	}

	init {
		require(reservationTtlSimSeconds > 0.0) {
			"reservationTtlSimSeconds must be positive, was $reservationTtlSimSeconds"
		}
	}

	// Contention and blocked-request tracking (Issue #612 follow-up): see [ReservationContentionTracker]
	// for why AllPathsBlocked is routine contention and only unresolved-at-end-of-run is reported.
	private val contention = ReservationContentionTracker(registry)

	/**
	 * The ids of the trains that currently have at least one blocked-path contention tracked.
	 *
	 * Exposed as `internal` purely so unit tests can observe the real tracker behind the release
	 * funnel's `clearBlockedTracking` wiring (the [RouteReleaser] constructed below is wired with
	 * `contention::clearBlockedTracking`); the destructive [flushUnresolvedConflicts] cannot serve
	 * that pin, because it empties the map and drops ownerless entries. The tracker itself stays
	 * private: tests may observe the wiring, never seed or clear contention beside it.
	 */
	internal fun blockedTrainIds(): Set<String> = contention.blockedTrainIds()

	// ── Cleared-signal ledger and route signal configuration (Issue #1169, SP12.3) ──
	//
	// Which semaphore is lit for which train, and every reset back to STOP, live in
	// ClearedSignalLedger; clearing the START and intermediate semaphores of a reserved route
	// lives in RouteSignalConfigurator, which records each aspect it clears in the ledger.
	private val signalLedger = ClearedSignalLedger()

	private val signalConfigurator = RouteSignalConfigurator(environment, signalLedger)

	/** Sets, locks, registers and releases a candidate's switches (Issue #1170, SP12.4). */
	private val switchConfigurator = SwitchConfigurator(environment, registry)

	/** Reservation target discovery (Issue #1171): what a route can be set to, and in which order. */
	private val targetDiscovery = ReservationTargetDiscovery(navigator, environment)

	/** Read-only candidate geometry screening: origin guards, rear-facing and divergent candidates (Issue #1210). */
	private val candidateScreen = RouteCandidateScreen(registry, environment, targetDiscovery, pathInfoBuilder)

	// Route release and rollback, including the approach-lock deferral window (Issue #1168).
	private val releaser =
		RouteReleaser(
			registry = registry,
			resetSemaphoresForReleasedBlocks = signalLedger::resetSemaphoresForReleasedBlocks,
			resetClearedSemaphores = signalLedger::resetClearedSemaphores,
			clearBlockedTracking = contention::clearBlockedTracking
		)

	/**
	 * The one kernel-side reservation expiry (Issue #1174): the TTL, the candidate query and the
	 * release policy live in [DefaultReservationExpiry]; the service stays the single facade and
	 * exposes the trigger through [ReservationExpiry] (the #1152 allow-list keeps `ShuntingLoop`
	 * free of any reservation-service handle). `internal`, like its sibling collaborators: tests
	 * reach the release seam and the counter through it, the real wiring included.
	 */
	internal val expiry =
		DefaultReservationExpiry(
			registry = registry,
			candidateScreen = candidateScreen,
			ttlSimSeconds = reservationTtlSimSeconds,
			initialRelease = releaser::releasePathDetailed
		)

	/**
	 * Candidate commit, finalize and rollback (Issue #1211, SP12.10); exposed so tests reach the
	 * committer through the service's real wiring.
	 */
	internal val candidateCommitter =
		CandidateCommitter(
			registry = registry,
			pathInfoBuilder = pathInfoBuilder,
			switchConfigurator = switchConfigurator,
			signalConfigurator = signalConfigurator,
			signalLedger = signalLedger,
			contention = contention,
			releaser = releaser,
			screen = candidateScreen
		)

	private fun findCandidatePaths(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int,
		topologicalPaths: Lazy<List<List<TrackSection>>>?
	): List<List<TrackSection>> {
		if (start is DynamicInOut && target is DynamicInOut) {
			val routes = routeFinder.findRoutes(start.staticRef, target.staticRef, environment)
			if (routes.isEmpty()) {
				logger.info { "reservePath: RouteFinder found no routes from $start to $target for $trainId" }
				return emptyList()
			}
			logger.debug {
				"reservePath: RouteFinder returned ${routes.size} route(s) for $trainId " +
					"(cheapest cost=${routes.first().cost})"
			}
			return routes.map { it.segments }
		}

		return topologicalPaths?.value ?: navigator.findAllTopologicalPaths(start, target, maxDepth)
	}

	/**
	 * Find and reserve a free path from start to target separator.
	 *
	 * ## Algorithm Implementation
	 *
	 * When both [start] and [target] are [DynamicInOut] elements, [RouteFinder] is used
	 * to obtain cost-sorted candidate routes. The lowest-cost route is tried first.
	 * For other separator types (e.g. semaphore-to-InOut), BFS topology paths from
	 * [TopologyNavigator] are used instead.
	 *
	 * 0. Reject a non-oriented [start] (a switch — Issue #1188, see
	 *    [RouteCandidateScreen.rejectNonOrientedStart]), a non-oriented [target] (Issue #1200, see
	 *    [RouteCandidateScreen.rejectNonOrientedEnd]) and a [start] that is not contiguous with
	 *    the train's own footprint (Issue #893) — see [RouteCandidateScreen.rejectNonContiguousStart]
	 * 1. Obtain candidate paths (RouteFinder for InOut↔InOut, TopologyNavigator otherwise)
	 * 2. For each path in priority order (cheapest cost first for InOut routes):
	 *    a. Extract unique DynamicTrackBlocks from TrackSections
	 *    b. Validate all blocks are FREE
	 *    c. Atomically reserve all blocks with rollback on failure
	 *    d. Register ownership in registry
	 *    e. Return Success if all steps succeed
	 * 3. If all paths fail, return appropriate failure result
	 *
	 * ## Error Handling
	 *
	 * - Start not contiguous with the train's footprint → return NonContiguousStart
	 * - Start or target is not an OrientedPathSeparator (a switch) → return GeometricallyImpossible
	 * - RouteFinder returns empty list → return NoPathExists (clear failure, no crash)
	 * - TrackOperationException during setUpPath() → rollback and try next path
	 * - Registry conflict → roll back only the forward blocks THIS call reserved (never the
	 *   blocks the train already held, Issue #1025) and return Conflict result
	 * - Empty paths list → return NoPathExists
	 * - All paths blocked → return AllPathsBlocked
	 */
	override fun reservePath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int
	): PathReservationService.ReservationResult {
		// Issue #1174: a stale holding of another train must not refuse this request.
		expireStaleReservations(exceptTrainId = trainId)
		return reservePath(trainId, start, target, maxDepth, topologicalPaths = null)
	}

	/**
	 * [reservePath] with the topological candidate paths supplied by the caller (Issue #966).
	 *
	 * [topologicalPaths] must be `navigator.findAllTopologicalPaths(start, target, maxDepth)`; the
	 * `reservePathToAny` attempt loop takes it from [TargetAttempt.topologicalPaths]. It
	 * replaces that enumeration in the non-InOut↔InOut branch of candidate discovery and is read
	 * only there, so a caller that already enumerated the paths (for example to sort targets)
	 * does not enumerate them again. InOut↔InOut requests still go to [RouteFinder]. `null`
	 * means "enumerate here", exactly as the public overload does.
	 *
	 * The expiry-trigger cadence (Issue #1174) makes this form load-bearing: it runs NO expiry.
	 * The entry-point overloads ([reservePath], [reservePathToAny]) run the trigger at their top;
	 * the attempt loops call this one so no expiry can move the before/after snapshots their
	 * bypass rollback relies on. Do not "simplify" a `DEFAULT_MAX_PATH_DEPTH,
	 * topologicalPaths = null` call site back to the short overload — that would re-enable
	 * per-attempt expiry.
	 */
	internal fun reservePath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int,
		topologicalPaths: Lazy<List<List<TrackSection>>>?
	): PathReservationService.ReservationResult {
		// Step 0 (Issue #1188): a route runs signal-to-signal, so a non-oriented start (a switch)
		// is refused here, before any block is reserved or registered -- see
		// [RouteCandidateScreen.rejectNonOrientedStart] for the leak this prevents.
		candidateScreen.rejectNonOrientedStart(trainId, start, "reservePath")?.let { return it }
		// Step 0 (Issue #1200): the END-side twin -- a route can only end at a semaphore or an InOut.
		candidateScreen.rejectNonOrientedEnd(trainId, target, "reservePath")?.let { return it }

		// Step 0 (Issue #893, task A-R1): a route may only start where the train actually is.
		// Checked BEFORE candidate-path discovery so it also governs the already-owned
		// early-return branch further down, which would otherwise report Success for a route
		// this train can never reach.
		candidateScreen.rejectNonContiguousStart(trainId, start)?.let { return it }

		val candidatePaths = findCandidatePaths(trainId, start, target, maxDepth, topologicalPaths)

		if (candidatePaths.isEmpty()) {
			return PathReservationService.ReservationResult.NoPathExists
		}

		// Tracks the first blocked (block, owningTrain) pair across all candidate paths.
		// Used to emit ReservationConflictDetected when AllPathsBlocked is about to be returned.
		var firstBlockedConflict: Pair<DynamicTrackBlock, String>? = null

		// Issue #903/#937: the permanent per-candidate geometric impossibilities (rear-facing START or
		// END, or unconfigurable switch) seen across candidates — first reason kept for reporting, count
		// kept so classifyExhaustedAttempt can tell "every candidate is impossible" (permanent)
		// from "one is, another is merely busy" (retryable).
		//
		// Step 1.5 (Issue #1064, G8): a route must END at a signal facing the train. Screened before
		// the loop — so before the already-owned early return and before any block, switch or signal
		// is touched — which leaves a refused candidate nothing to roll back. Screened-out candidates
		// are recorded like G4, so a request whose every candidate ends rear-facing is reported as a
		// permanent GeometricallyImpossible.
		val (rearFacingEnds, rearFacingUsable) = candidateScreen.screenRearFacingEnds(start, target, candidatePaths)
		var geometricFailures = rearFacingEnds

		// Step 1.6 (Issue #1066): a candidate that does not continue the route the train already
		// holds can never merge, so it is skipped here -- before anything is reserved, thrown or
		// cleared -- instead of at Step 2i after all of that (same first-reason / count shape as
		// geometricFailures).
		val (divergentFailures, usableCandidates) =
			candidateScreen.screenDivergentCandidates(trainId, start, target, rearFacingUsable)

		// Step 2: Try each candidate path until we find a free one
		for ((index, path) in usableCandidates) {
			// Step 2a: Extract unique DynamicTrackBlocks from TrackSections
			val blocks = extractUniqueBlocks(path)
			logger.trace { "reservePath: Path has ${blocks.size} unique block(s)" }

			// Step 2a.5: Bug fix (Issue #296) - Filter out blocks already owned by this train
			// Blocks already owned (RESERVED or OCCUPIED) by this train should be excluded because:
			// 1. OCCUPIED: Train has already been there (blocks behind the train)
			// 2. RESERVED: Already reserved from a (possibly different) separator
			// Including owned blocks causes TOCTOU conflicts when trying to re-reserve from different separator.
			// The same form is shared with the Step 1.6 probe, see [excludingOwnedBy].
			val forwardBlocks = blocks.excludingOwnedBy(trainId)

			if (forwardBlocks.isEmpty()) {
				return reserveAlreadyOwnedPath(trainId, start, target, blocks, path)
			}

			// Step 2b: Check if all forward blocks are available (FREE or RESERVED by THIS train)
			if (!forwardBlocks.areAllFreeOrOwnedBy(trainId)) {
				// Capture a blocked (block, owner) pair for the AllPathsBlocked conflict warning.
				firstBlockedConflict = contention.findFirstBlockedConflict(forwardBlocks, trainId)
				continue
			}

			// TOCTOU Note: Small window between areAllBlocksFree() check and
			// candidateCommitter.tryAtomicReservation() use.
			// This is acceptable because:
			// - Single-threaded access to simulation state (documented in class KDoc)
			// - candidateCommitter.tryAtomicReservation() has rollback logic if state changed
			// - Worst case: false positive on free check, caught during reservation, try next path
			//
			// Step 2c: Attempt atomic reservation with rollback (only forward blocks)
			val reservationResult = candidateCommitter.tryAtomicReservation(trainId, start, forwardBlocks)
			if (reservationResult != null) {
				// Reservation failed, try next path
				if (reservationResult is PathReservationService.ReservationResult.Conflict) {
					// Conflict indicates serious error, don't try other paths
					return reservationResult
				}
				continue
			}

			// Step 2d: Register ownership in registry (atomic operation, only forward blocks).
			// A `null` outcome rejects this candidate and the loop tries the next one, exactly
			// like the former in-arm `continue`s: nothing follows this call in the loop body.
			val (updatedGeometricFailures, candidateOutcome) =
				candidateCommitter.registerAndFinalizeCandidate(
					CandidateRequest(trainId, start, target, path, blocks, forwardBlocks, index),
					geometricFailures
				)
			geometricFailures = updatedGeometricFailures
			candidateOutcome?.let { return it }
		}

		// All paths tried, all were blocked or impossible.
		// Issue #903/#937: a permanent geometric impossibility on EVERY candidate takes priority
		// over AllPathsBlocked — the permanence gate is an AND over candidates, not an OR (a single
		// impossible candidate alongside a merely-busy one is contention, not a dead end). Issue
		// #903's first-hit-wins still governs *which* reason is reported, not whether the request is
		// unsatisfiable. Extracted into [classifyExhaustedAttempt] so this branching stays out of
		// reservePath's own cyclomatic complexity count.
		return classifyExhaustedAttempt(
			trainId,
			geometricFailures,
			divergentFailures,
			firstBlockedConflict,
			candidatePaths.size
		)
	}

	/**
	 * Step 2a.5's already-owned early-return branch of [reservePath]'s candidate loop: every
	 * block of the candidate is already owned by this train, so the branch only (re-)configures
	 * the START signal and registers/merges the PathInfo, and every exit reports
	 * [PathReservationService.ReservationResult.Success] (the redundant re-request no-op and
	 * the merge-abort signal reset are Goal 10 SP2b.9 / Issue #904; the PathInfo registration
	 * itself is Issue #296). Extracted (SonarCloud S3776, PR #1109) so this branching stays out
	 * of [reservePath]'s own cognitive complexity.
	 */
	private fun reserveAlreadyOwnedPath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		blocks: List<DynamicTrackBlock>,
		path: List<TrackSection>
	): PathReservationService.ReservationResult {
		// All blocks in this path are already owned by this train
		// Configure START semaphore before returning (may be from different position)
		// configureSemaphoreSignal is idempotent, safe to call multiple times
		// Issue #904: snapshot BEFORE configureAlreadyOwnedStartSignal so a merge abort
		// below can reset exactly what THIS call cleared, not a pre-existing reservation's
		// signal (mirrors Step 2g/2i's own clearedBeforeStart snapshots).
		val clearedBeforeStart = signalLedger.snapshotClearedSemaphores(trainId)
		if (blocks.isNotEmpty()) {
			signalConfigurator.configureAlreadyOwnedStartSignal(trainId, start, blocks)
		}
		// FIX (Goal 10 SP2b.9 follow-up): a redundant re-request for a route this train
		// already holds to the same target (e.g. a stateless per-cycle LLM dispatcher
		// re-issuing request_route for a train it already granted a route to, since it
		// has no memory of its own prior tool calls) must be a no-op here. Re-registering
		// an identical PathInfo would duplicate every separator in the merge
		// (PathReservationRegistry.registerPathInfo / PathInfoMerger.merge), and a further
		// redundant call can hit the registry's 3rd-occurrence cycle-abort — silently
		// discarding the merge while this method still reports Success, an invisible
		// PathInfo/reality divergence that can strand the train permanently once it
		// reaches whatever depends on the discarded segment.
		val existingPathInfo = registry.getPathInfo(trainId)
		if (existingPathInfo != null && existingPathInfo.target == target) {
			contention.clearBlockedTracking(trainId)
			return PathReservationService.ReservationResult.Success(blocks)
		}

		// FIX (Issue #296): Register PathInfo for already-owned blocks
		val pathInfo = pathInfoBuilder.buildPathInfo(start, target, path)
		val mergeOutcome = registry.registerPathInfo(trainId, pathInfo)
		// Issue #904: this branch acquires no NEW blocks or switches -- the whole point is
		// that the train already owns everything -- so an abort has nothing to roll back on
		// that side. The only discrepancy a merge abort can leave here is a signal this call
		// just (re-)cleared for a merge that never happened; reset it back to STOP (G1),
		// still report Success since the train's actual block ownership is correct and
		// unchanged (traffic-simulation-expert ruling, Issue #904).
		signalLedger.resetSignalsIfMergeAborted(mergeOutcome, trainId, start, clearedBeforeStart)

		// Resolved -- this train is no longer contending for any block.
		contention.clearBlockedTracking(trainId)
		return PathReservationService.ReservationResult.Success(blocks)
	}

	/**
	 * Classifies the outcome of [reservePath] once every candidate path has been tried and none
	 * succeeded. Extracted so this branching stays out of [reservePath]'s own cyclomatic
	 * complexity count (Issue #903 review follow-up).
	 *
	 * Reports `GeometricallyImpossible` only when **every** candidate failed geometrically
	 * (Issue #937). Permanence is a property of the whole request, not of one candidate: if any
	 * candidate was merely blocked it can free up, so the request is ordinary contention and the
	 * caller must wait and retry rather than give up. The distinction is load-bearing —
	 * [cz.vutbr.fit.interlockSim.sim.InOutWorker] throws `SimulationException` on
	 * `GeometricallyImpossible` instead of waiting, and the dispatcher's perception tells the model
	 * not to retry the request, so a mixed attempt misreported as permanent parks a train for the
	 * rest of the run.
	 *
	 * Issue #903's first-hit-wins still governs *which reason is reported* — the earliest geometric
	 * reason is the most informative. What changed is the permanence gate, which is an AND over
	 * candidates rather than an OR. Candidates that fail for other reasons (atomic-reservation
	 * failure, a merge abort past Step 0a) record nothing, so the `count == attemptedPaths` equality
	 * treats them as non-geometric, which is the safe direction: an unproven candidate must not
	 * contribute to a verdict of permanent impossibility.
	 *
	 * Issue #1066: a candidate screened out at Step 1.6 because it does not continue the route the
	 * train holds is counted in [divergentFailures] and contributes to the same permanence gate.
	 * When every candidate is geometric-or-divergent, the result is `DivergesFromHeldRoute` (the
	 * actionable one: the divergent candidate may work once the held route is extended or
	 * cancelled). Before this, that precondition failed only at Step 2i (after the candidate had
	 * reserved and thrown things), recorded nothing here, and the outcome collapsed to
	 * `AllPathsBlocked` -- "track busy" -- although no block was busy.
	 *
	 * Otherwise: no [BlockEvent.ReservationConflictDetected] emission here (Goal 3) -- a
	 * blocked-path outcome is routine "path busy, will retry" contention for the
	 * collision-warning layer. The contention is recorded so [flushUnresolvedConflicts] can
	 * surface it if it never resolves before the run ends (see
	 * [ReservationContentionTracker]'s class KDoc). [ConflictDetectedEvent] (Goal 9 SP1) IS emitted mid-run (it does not trigger an
	 * automatic simulation pause, so it can be delivered without the false-positive-pause problem
	 * that led to the Goal 3 restriction) -- but only the FIRST time a given (trainId, block)
	 * contention is observed; see [ReservationContentionTracker.recordContentionAndEmitIfNew].
	 */
	private fun classifyExhaustedAttempt(
		trainId: String,
		geometricFailures: GeometricFailures,
		divergentFailures: GeometricFailures,
		firstBlockedConflict: Pair<DynamicTrackBlock, String>?,
		attemptedPaths: Int
	): PathReservationService.ReservationResult {
		// Issue #1066: a divergent candidate (it does not continue the held route) is as permanent as
		// a geometric one, so the two together satisfy the permanence gate. When any candidate is
		// divergent the actionable reason is "extend from the held target or cancel the route
		// first" -- that candidate may well work once the held route is gone, whereas a
		// geometric reason cannot say so. A mix with a merely busy candidate stays ordinary
		// contention (below).
		if (divergentFailures.reason != null &&
			divergentFailures.count + geometricFailures.count == attemptedPaths
		) {
			return PathReservationService.ReservationResult.DivergesFromHeldRoute(
				heldTarget =
					registry.getPathInfo(trainId)?.let { separatorLabel(it.target) }
						?: "unknown",
				reason = divergentFailures.reason
			)
		}
		if (geometricFailures.reason != null && geometricFailures.count == attemptedPaths) {
			return PathReservationService.ReservationResult.GeometricallyImpossible(geometricFailures.reason)
		}
		firstBlockedConflict?.let { (blockedBlock, owningTrain) ->
			contention.recordContentionAndEmitIfNew(trainId, blockedBlock, owningTrain)
		}
		return PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths)
	}

	/** Delegates to [ReservationContentionTracker.flushUnresolvedConflicts] (Issue #612, #1167). */
	override fun flushUnresolvedConflicts(simulationEndTime: Double): List<BlockEvent.ReservationConflictDetected> =
		contention.flushUnresolvedConflicts(simulationEndTime)

	/** See [RouteReleaser.releasePath]. */
	override fun releasePath(trainId: String): List<DynamicTrackBlock> = releaser.releasePath(trainId)

	/** See [RouteReleaser.releasePathDetailed] (Issue #1050 approach locking). */
	override fun releasePathDetailed(trainId: String): PathRelease = releaser.releasePathDetailed(trainId)

	/** See [PathReservationService.hasClearedSignals]; answered by [ClearedSignalLedger.hasClearedSignals]. */
	override fun hasClearedSignals(trainId: String): Boolean = signalLedger.hasClearedSignals(trainId)

	/**
	 * See [PathReservationService.recordExternalClearedSemaphore]. Delegates directly to the
	 * same [ClearedSignalLedger.recordClearedSemaphore] used internally by [reservePath], so an external caller's
	 * write is folded into the single ledger this service already maintains -- see the interface
	 * KDoc for why this is the fix for Issue #893's task A6 (G6 single signal ledger).
	 */
	override fun recordExternalClearedSemaphore(
		trainId: String,
		semaphore: DynamicRailSemaphore
	) {
		signalLedger.recordClearedSemaphore(trainId, semaphore)
	}

	/**
	 * Check if a path is currently available.
	 *
	 * ## Implementation
	 *
	 * 1. Find all topological paths
	 * 2. For each path, check if all blocks are FREE
	 * 3. Return true if at least one free path exists
	 *
	 * For the switch-CONSTRAINED semantics (Issue #797 follow-up) see
	 * [ReservationTargetDiscovery.isPathAvailableFor], which carries the full rationale.
	 */
	override fun isPathAvailable(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int
	): Boolean = targetDiscovery.isPathAvailableFor(start, target, maxDepth, ownerTrainId = null)

	override fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: DynamicPathSeparator,
		next: TrackSection
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAnyNextSemaphore: ENTRY POINT for $trainId - " +
				"start=$start (${start::class.simpleName}), " +
				"next=$next (${next::class.simpleName})"
		}

		// Find ALL reachable semaphores via this track section
		val semaphores = targetDiscovery.findNextSemaphoresVia(start, next)
		if (semaphores.isEmpty()) {
			logger.debug {
				"reservePathToAnyNextSemaphore: No semaphores found from $start via $next"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.debug {
			"reservePathToAnyNextSemaphore: Found ${semaphores.size} semaphore(s): $semaphores"
		}

		// Extract the DynamicTrackBlock from the 'next' parameter for validation
		val nextBlock = next.getTrackBlock() as? DynamicTrackBlock
		if (nextBlock == null) {
			logger.warn {
				"reservePathToAnyNextSemaphore: next parameter is not a DynamicTrackBlock: $next"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		// Try to reserve path to each semaphore until one succeeds
		var lastResult: PathReservationService.ReservationResult? = null
		var attemptCount = 0
		// Issues #937, #1066: geometric or divergent targets; see classifyExhaustedSemaphores.
		var permanentAttempts = 0
		for (semaphore in semaphores) {
			attemptCount++
			logger.trace {
				"reservePathToAnyNextSemaphore: Attempting reservation to $semaphore (attempt $attemptCount/${semaphores.size})"
			}

			// Capture the semaphores this train already has cleared before this attempt, so a
			// bypass-rollback can reset only what THIS candidate cleared (the before/after delta)
			// and not a pre-existing reservation's signals.
			val clearedBefore = signalLedger.snapshotClearedSemaphores(trainId)
			// Capture the blocks this train already owns before this attempt, for the same reason
			// on the block side: a bypass candidate's alternative route may reuse blocks the train
			// already holds from a live reservation, so result.reservedBlocks can overlap that
			// reservation. Releasing all of it would unregister the live reservation. The rollback
			// must release only the candidate's NEW blocks (the before/after ownership delta),
			// mirroring the semaphore delta above. registerAtomic is idempotent for already-owned
			// blocks (it only updates ownership maps), so the snapshot is stable across the attempt.
			val blocksBefore = registry.getBlocks(trainId).toSet()
			// Same reason on the switch and PathInfo sides (PR #901 review): a successful bypass
			// candidate has already locked/registered its switches (CandidateCommitter's
			// configureAndRegisterSwitches, inside reservePath's Step 2f) and merged its PathInfo
			// (Step 2i) before the bypass check below can reject it. The snapshots let the
			// rollback undo exactly what THIS candidate added on those two sides too, mirroring
			// the semaphore/block deltas above.
			val switchesBefore = registry.getSwitches(trainId).toSet()
			val pathInfoBefore = registry.getPathInfo(trainId)

			val result = reservePath(trainId, start, semaphore, DEFAULT_MAX_PATH_DEPTH, topologicalPaths = null)

			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					// FIX: validate the reserved path goes through the required 'next' block
					if (!result.reservedBlocks.contains(nextBlock)) {
						logger.debug {
							"reservePathToAnyNextSemaphore: Path to $semaphore rejected (doesn't use required next block)"
						}
						// reservePath already cleared this candidate's semaphores
						// (configureStartSignal / configureIntermediateSemaphores). Releasing the
						// blocks without resetting them would leave a proceed aspect standing over
						// freed track -- the evergreen-aspect defect #847 fixes on the other release
						// paths. Reset exactly the semaphores this candidate cleared (the delta),
						// never the train's whole set, which may still hold a live reservation.
						val clearedByThisCandidate =
							signalLedger.snapshotClearedSemaphores(trainId) - clearedBefore
						signalLedger.resetSemaphoreSet(trainId, clearedByThisCandidate)
						// Release the wrongly reserved path — only the blocks THIS candidate newly
						// reserved (the ownership delta vs [blocksBefore]); blocks the train already
						// held from a live reservation must stay registered, or the rollback would
						// strand a mid-journey train.
						releaseBypassRollbackBlocks(trainId, result.reservedBlocks, blocksBefore)
						// The transaction must also be complete on the switch and PathInfo sides
						// (PR #901 review): a successful bypass candidate locked/registered its
						// switches and merged its PathInfo inside reservePath. Leaving either behind
						// keeps track locked the train never reserved (blocking other trains) and
						// navigation metadata pointing through a route that was just released
						// (steering THIS train onto the rejected path).
						val candidateSwitches = registry.getSwitches(trainId) - switchesBefore
						switchConfigurator.releaseCandidateSwitches(trainId, candidateSwitches)
						// PathInfo cannot be un-merged (merge has cycle-guard abort semantics), so
						// restore the pre-attempt snapshot registered value instead.
						registry.restorePathInfo(trainId, pathInfoBefore)
						lastResult = recordBlocked(lastResult, attemptCount)
						continue
					}

					// Success! Path reserved and validated to use the required 'next' block
					signalConfigurator.configureBypassStartSignal(trainId, start, result.reservedBlocks)

					logger.debug {
						"reservePathToAnyNextSemaphore: Successfully reserved path to $semaphore via required next block"
					}
					return result
				}
				is PathReservationService.ReservationResult.AllPathsBlocked -> {
					// This semaphore blocked, try next one
					logger.trace { "reservePathToAnyNextSemaphore: Path to $semaphore blocked, trying next" }
					lastResult = recordBlocked(lastResult, attemptCount)
					continue
				}
				is PathReservationService.ReservationResult.Conflict -> {
					// Conflict indicates serious error, don't try other paths
					logger.warn {
						"reservePathToAnyNextSemaphore: Conflict detected, aborting: $result"
					}
					return result
				}
				is PathReservationService.ReservationResult.NoPathExists -> {
					// Should not happen (we found the semaphore), but handle it
					logger.warn {
						"reservePathToAnyNextSemaphore: No topological path to $semaphore (unexpected)"
					}
					lastResult = recordAttemptResult(lastResult, PathReservationService.ReservationResult.NoPathExists)
					continue
				}
				is PathReservationService.ReservationResult.NonContiguousStart -> {
					// The start itself is unusable for this train (Issue #893). Every remaining
					// candidate shares that same start, so trying them would produce the identical
					// rejection -- abort and surface the reason to the caller.
					logger.warn {
						"reservePathToAnyNextSemaphore: aborting, start is not contiguous with " +
							"'$trainId': ${result.reason}"
					}
					return result
				}
				// Issue #903: candidate-specific (unlike NonContiguousStart, which is about
				// `start`), so keep trying; recordAttemptResult preserves the geometric reason
				// over a later AllPathsBlocked if every candidate exhausts.
				is PathReservationService.ReservationResult.GeometricallyImpossible -> {
					logger.trace {
						"reservePathToAnyNextSemaphore: Path to $semaphore geometrically impossible " +
							"(${result.reason}), trying next"
					}
					permanentAttempts++
					lastResult = recordAttemptResult(lastResult, result)
				}
				// Issue #1066: counted because recordAttemptResult keeps only the LAST result, so
				// classifyExhaustedSemaphores needs the count to spot a mix with a busy semaphore.
				is PathReservationService.ReservationResult.DivergesFromHeldRoute -> {
					permanentAttempts++
					lastResult = recordAttemptResult(lastResult, result)
				}
			}
		}

		// All semaphores tried, none reservable.
		return classifyExhaustedSemaphores(lastResult, permanentAttempts, attemptCount)
	}

	/**
	 * Final classification for [reservePathToAnyNextSemaphore] once every target semaphore has been
	 * tried, applying Issue #937's permanence gate to the outer aggregation.
	 *
	 * A permanent verdict — geometric, or (Issue #1066) divergent from the held route — may stand
	 * only if **every** attempted semaphore was permanent ([permanentAttempts] ==
	 * [attemptCount]). If even one was merely blocked it can free up, so the
	 * attempt is contention and the caller must wait rather than throw — the same rule
	 * [classifyExhaustedAttempt] applies to a single `reservePath`'s candidates, and its KDoc
	 * carries the full rationale.
	 *
	 * Extracted so [reservePathToAnyNextSemaphore] stays within its method-length budget.
	 * `internal` so tests can pin the all-divergent branch directly: since Issue #1188 no legal
	 * (oriented, contiguous) origin on `vyhybna.xml` reaches it through the real scan.
	 */
	internal fun classifyExhaustedSemaphores(
		lastResult: PathReservationService.ReservationResult?,
		permanentAttempts: Int,
		attemptCount: Int
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAnyNextSemaphore: all $attemptCount path(s) exhausted " +
				"($permanentAttempts permanently refused)"
		}
		val permanentVerdict =
			lastResult is PathReservationService.ReservationResult.GeometricallyImpossible ||
				lastResult is PathReservationService.ReservationResult.DivergesFromHeldRoute
		if (permanentVerdict && permanentAttempts < attemptCount) {
			return PathReservationService.ReservationResult.AllPathsBlocked(attemptCount)
		}
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(attemptCount)
	}

	/**
	 * Release the blocks of a bypass-rollback candidate (the wrongly reserved path that did not
	 * use the required next block). Each block goes through [rollbackBlock], like
	 * [CandidateCommitter.rollbackUnconfigurableCandidate]'s: `cancelPathSetup` from the block's own
	 * `reservedFrom` (not the caller's `start`) under a try, then the registry entry, the approach-lock deferral and any
	 * stale switch lock. Unlike that rollback, each block that leaves the registry is then announced as
	 * released, because the candidate's successful [reservePath] already announced it as reserved
	 * (Issue #961).
	 *
	 * Only the candidate's NEW blocks — those not in [blocksBefore] (the train's ownership
	 * snapshot taken before this candidate's [reservePath]) — are released. A bypass candidate's
	 * alternative route may reuse blocks the train already holds from a live reservation; releasing
	 * those would unregister the live reservation and strand a mid-journey train. This mirrors the
	 * caller's semaphore delta reset (`clearedByThisCandidate = clearedAfter - clearedBefore`).
	 * Blocks already owned are skipped entirely — both `cancelPathSetup` (which would drive a live
	 * RESERVED block to FREE) and `unregisterBlock` — so the pre-existing reservation is undisturbed.
	 */
	private fun releaseBypassRollbackBlocks(
		trainId: String,
		reservedBlocks: List<DynamicTrackBlock>,
		blocksBefore: Set<DynamicTrackBlock>
	) {
		val simTime = currentSimulationTime()
		reservedBlocks.forEach { block ->
			// Skip blocks the train already owned before this candidate: they belong to a live
			// reservation and must not be cancelled or unregistered.
			if (block in blocksBefore) {
				return@forEach
			}
			// Unlike every other rollback, this candidate's reservePath SUCCEEDED and announced each
			// new block as reserved, so each block it rolls back is announced as released, exactly
			// once, or the event-driven reservation counters never return to zero (Issue #961, #1081).
			if (rollbackBlock(trainId, block)) {
				emitBlockReleased(block, trainId, simTime)
			}
		}
	}

	override fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: OrientedPathSeparator
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAnyNextSemaphore: Finding path from oriented separator $start for $trainId"
		}

		// Steps 1–3: resolve the forward track section from the separator's orientation
		val (dynamicStart, next) =
			targetDiscovery.resolveForwardSection(start, "reservePathToAnyNextSemaphore")
				?: return PathReservationService.ReservationResult.NoPathExists

		logger.info {
			"reservePathToAnyNextSemaphore: Selected next track section: $next"
		}

		// Step 4: Delegate to existing overload
		logger.debug {
			"reservePathToAnyNextSemaphore: Delegating to existing overload with next=$next"
		}
		return reservePathToAnyNextSemaphore(trainId, dynamicStart, next)
	}

	override fun findNextReservationTarget(
		start: OrientedPathSeparator,
		ownerTrainId: String?
	): DynamicPathSeparator? = findReservationTargetCandidates(start, ownerTrainId).firstOrNull { it.available }?.separator

	override fun findReservationTargetCandidates(
		start: OrientedPathSeparator,
		ownerTrainId: String?
	): List<ReservationTargetCandidate> = targetDiscovery.findReservationTargetCandidates(start, ownerTrainId)

	override fun isPathToAnyNextSemaphoreAvailable(
		start: PathSeparator,
		next: TrackSection?
	): Boolean = targetDiscovery.isAnyNextTargetAvailable(start, next)

	/**
	 * Get all blocks currently reserved by a train.
	 */
	override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> = registry.getBlocks(trainId)

	/**
	 * Get all blocks currently physically occupied by a train.
	 */
	override fun getOccupiedBlocks(trainId: String): List<DynamicTrackBlock> = registry.getOccupiedBlocks(trainId)

	/**
	 * Implementation of reservePathToAny: try every whole-network target in priority order until
	 * one reservation succeeds.
	 *
	 * The targets and their order — InOuts before semaphores, opposite-side InOuts before same-side
	 * ones, shortest first — come from [ReservationTargetDiscovery.reservePathToAnyTargets], whose
	 * KDoc carries the ordering rationale. This method is the attempt loop:
	 * - try each target in priority order and return the first Success;
	 * - return immediately on Conflict (serious error) or NonContiguousStart (the start is
	 *   unusable for every target);
	 * - if all fail: NoPathExists when there was no target, else the last failure.
	 *
	 * Called rarely (when a train needs a new path, ~once per train per network traversal), so the
	 * target list is recomputed per call rather than cached: each call's targets carry lazy path
	 * lists ([TargetAttempt], Issue #966) valid for exactly that one call. The order itself
	 * carries no dynamic state: it follows from the network's endpoint set and the static topology;
	 * occupancy is only evaluated later, per attempt.
	 *
	 * @param trainId Unique identifier for the train
	 * @param start Starting path separator (typically a semaphore)
	 * @return ReservationResult indicating success or failure reason
	 */
	override fun reservePathToAny(
		trainId: String,
		start: DynamicPathSeparator
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAny: Searching for available targets from $start for $trainId"
		}
		// Issue #1174: once per request, not per target -- the attempts below call the internal overload.
		expireStaleReservations(exceptTrainId = trainId)

		// Issue #1188: a non-oriented start (a switch) is refused once, here. reservePath would
		// refuse it too, before its candidate-path enumeration, but only after STEP 1 had already
		// discovered every target, and then once per target with the same reason and warning.
		// Refusing up front skips the target discovery and the repeated calls.
		candidateScreen.rejectNonOrientedStart(trainId, start, "reservePathToAny")?.let { return it }
		check(start is OrientedPathSeparator) {
			"reservePathToAny: start is not oriented, which the Issue #1188 guard above makes impossible"
		}

		// Steps 1-3: target discovery and prioritization (see ReservationTargetDiscovery)
		val attempts = targetDiscovery.reservePathToAnyTargets(start)

		logger.debug {
			"reservePathToAny: Target order (InOuts first): " +
				attempts.joinToString(", ") { it.target.toString() }
		}

		// ========================================
		// STEP 4: Reservation Attempts
		// ========================================
		// Try each target in priority order until a reservation succeeds.
		// This is a greedy algorithm: return first success, don't try to find "optimal" path.
		//
		// **Why greedy is correct:**
		// - Targets are already sorted by preference (opposite-side, short paths first)
		// - First success is guaranteed to be the best available option
		// - Trying all paths to find "optimal" would be wasteful (dynamic state changes anyway)
		var lastResult: PathReservationService.ReservationResult? = null

		for (attempt in attempts) {
			val target = attempt.target
			val result = reservePath(trainId, start, target, DEFAULT_MAX_PATH_DEPTH, attempt.topologicalPaths)

			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					logger.debug {
						"reservePathToAny: Successfully reserved path from $start to $target for $trainId"
					}
					return result
				}
				is PathReservationService.ReservationResult.Conflict -> {
					// Conflict indicates serious error (race condition or registry corruption)
					// Abort immediately - don't try other targets
					logger.warn {
						"reservePathToAny: Conflict detected at ${result.conflictingBlock}, aborting"
					}
					return result
				}
				is PathReservationService.ReservationResult.NonContiguousStart -> {
					// The start is unusable for this train (Issue #893); every remaining target
					// shares it, so continuing would repeat the same rejection once per target
					// and each repetition costs a full candidate-path enumeration.
					logger.warn {
						"reservePathToAny: aborting, start is not contiguous with '$trainId': ${result.reason}"
					}
					return result
				}
				else -> {
					// Path not available (blocked, occupied, or no path exists)
					// Try next target
					logger.trace { "reservePathToAny: Path to $target not available, trying next target" }
					lastResult = result
				}
			}
		}

		// ========================================
		// STEP 5: All Targets Failed
		// ========================================
		// No available path found after trying all targets.
		if (attempts.isEmpty()) {
			logger.warn { "reservePathToAny: No targets found from $start" }
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.warn {
			"reservePathToAny: No available path from $start for $trainId " +
				"(tried ${attempts.size} targets, all blocked or unreachable)"
		}

		// Return last failure result (or AllPathsBlocked if no result available)
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(attempts.size)
	}

	// ========== Private helper methods ==========

	/**
	 * Single write path for [reservePathToAnyNextSemaphore]'s `lastResult` tracker (Issue #903).
	 *
	 * `GeometricallyImpossible` is a PERMANENT impossibility (rear-facing START or END, unconfigurable
	 * switch, or a non-oriented route origin; every target here is a semaphore, so a non-oriented
	 * end cannot arise), not ordinary contention: once seen for one
	 * candidate semaphore, it must win over any later contention result (`AllPathsBlocked`,
	 * `NoPathExists`, or the bypass-rollback branch's `AllPathsBlocked`) so the caller --
	 * [cz.vutbr.fit.interlockSim.sim.InOutWorker] on the train-navigation path -- throws
	 * [cz.vutbr.fit.interlockSim.exceptions.SimulationException]
	 * instead of spinning on `waitUntil(pathFree)` retrying a path that will never become
	 * available. Before Issue #903's fix, the `AllPathsBlocked` / `NoPathExists` / bypass-rollback
	 * branches wrote `lastResult` directly and silently overwrote an earlier geometric result.
	 *
	 * Also implements first-hit-wins among multiple geometric candidates: the first reason is
	 * kept as the most informative for diagnostics.
	 *
	 * Extracted so every `lastResult` assignment in [reservePathToAnyNextSemaphore] routes through
	 * one helper, making the priority self-enforcing and keeping each call site a single line
	 * (the loop body stays under its line-count threshold).
	 */
	private fun recordAttemptResult(
		current: PathReservationService.ReservationResult?,
		candidate: PathReservationService.ReservationResult
	): PathReservationService.ReservationResult =
		if (current is PathReservationService.ReservationResult.GeometricallyImpossible) current else candidate

	/**
	 * Convenience for [recordAttemptResult] wrapping the common
	 * [PathReservationService.ReservationResult.AllPathsBlocked] case, keeping the two
	 * `lastResult` call sites in [reservePathToAnyNextSemaphore] single-line (the fully
	 * qualified `AllPathsBlocked(attemptCount)` argument would otherwise exceed the line
	 * length limit and force a multi-line form that pushes the method over its length cap).
	 */
	private fun recordBlocked(
		current: PathReservationService.ReservationResult?,
		attemptCount: Int
	): PathReservationService.ReservationResult =
		recordAttemptResult(current, PathReservationService.ReservationResult.AllPathsBlocked(attemptCount))

	/**
	 * Kernel-side reservation expiry (Issue #1174, absorbs #975): the loop tick, judging every
	 * train. The policy and the containment live in [DefaultReservationExpiry]; the release goes
	 * only through [RouteReleaser.releasePathDetailed] -- never [releasePath] or a raw registry
	 * removal -- and nothing that occupies track is ever released.
	 */
	override fun expireStaleReservations(): Int = expiry.expireStale(exceptTrainId = null)

	/**
	 * [expireStaleReservations] at the top of a request by [exceptTrainId], which is exempt (Issue
	 * #1174 ruling D8): its new claim restarts its clock, as it restarts the #847 sweeper's. Expiring
	 * it first would empty its footprint, so `rejectNonContiguousStart` would pass vacuously and a
	 * mid-station extension would be granted as an orphan route. [reservePath] and
	 * [reservePathToAny] call this at their top; if the request fails, the next loop tick expires
	 * the holding. The expiry runs before the Step 0 guards, so a request that is then refused
	 * (`GeometricallyImpossible`, `NonContiguousStart`) can still expire other trains' stale
	 * holdings -- correct, as a stale holding is stale regardless and the loop tick would do the
	 * same about a second later.
	 */
	internal fun expireStaleReservations(exceptTrainId: String): Int = expiry.expireStale(exceptTrainId)

	/** See [RouteReleaser.unregister]. */
	override fun unregister(trainId: String): List<DynamicTrackBlock> = releaser.unregister(trainId)

	/** See [RouteReleaser.unregisterBlock]. */
	override fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean = releaser.unregisterBlock(trainId, block)

	override fun dropFreedBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean = releaser.dropFreedBlock(trainId, block)

	override fun releaseBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean = releaser.releaseBlock(trainId, block)

	override fun rollbackBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean = releaser.rollbackBlock(trainId, block)

	/**
	 * See [PathReservationService.resetSemaphoresForReleasedBlocks]; delegates to
	 * [ClearedSignalLedger.resetSemaphoresForReleasedBlocks] (Issue #1169).
	 *
	 * @since Issue #893 (phase alpha, task A3)
	 */
	override fun resetSemaphoresForReleasedBlocks(
		trainId: String,
		blocks: Collection<DynamicTrackBlock>
	) {
		signalLedger.resetSemaphoresForReleasedBlocks(trainId, blocks)
	}

	override fun addBlockOccupancyListener(listener: BlockOccupancyListener) {
		registry.addBlockOccupancyListener(listener)
	}

	override fun removeBlockOccupancyListener(listener: BlockOccupancyListener) {
		registry.removeBlockOccupancyListener(listener)
	}

	/** See [RouteReleaser.emitBlockReleased]. */
	private fun emitBlockReleased(
		block: DynamicTrackBlock,
		trainId: String,
		simTime: Double
	) = releaser.emitBlockReleased(block, trainId, simTime)
}
