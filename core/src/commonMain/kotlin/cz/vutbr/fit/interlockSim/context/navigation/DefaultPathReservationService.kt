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

import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackReservationException
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
 * @since Issue #294 (Phase 2 of Issue #292)
 */
@Suppress("LargeClass") // Core reservation service; splitting would obscure the algorithm
class DefaultPathReservationService(
	private val navigator: TopologyNavigator,
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry,
	private val pathInfoBuilder: PathInfoBuilder,
	private val routeFinder: RouteFinder
) : PathReservationService,
	ReservationTargetQuery,
	BlockRollbackStep {
	// Contention and blocked-request tracking (Issue #612 follow-up): see [ReservationContentionTracker]
	// for why AllPathsBlocked is routine contention and only unresolved-at-end-of-run is reported.
	private val contention = ReservationContentionTracker(registry)

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

	// Route release and rollback, including the approach-lock deferral window (Issue #1168).
	private val releaser =
		RouteReleaser(
			registry = registry,
			resetSemaphoresForReleasedBlocks = signalLedger::resetSemaphoresForReleasedBlocks,
			resetClearedSemaphores = signalLedger::resetClearedSemaphores,
			clearBlockedTracking = contention::clearBlockedTracking
		)

	/**
	 * Every [DynamicTrackBlock] edge in this context's graph, read once on first use.
	 *
	 * The graph's edges are static for the lifetime of a simulation context — blocks are never
	 * added or removed at runtime — so the list is built lazily once and never invalidated; only
	 * the live state read off those references ([DynamicTrackBlock.occupant]) changes. Mirrors
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]'s block cache.
	 *
	 * Lazy rather than eager: this service is constructed inside the per-context Koin scope, and
	 * touching the graph at construction time would couple service creation to graph readiness.
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

	/** All semaphores of this context's grid, cached by [targetDiscovery]; `internal` for tests. */
	internal fun allSemaphores(): List<DynamicRailSemaphore> = targetDiscovery.allSemaphores()

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
	private fun footprintOf(trainId: String): Set<DynamicTrackBlock> =
		registry.getBlocks(trainId).toSet() +
			allBlocksCache.filter { it.occupant?.name == trainId }

	/**
	 * Name of [separator] for diagnostics, falling back to its `toString()` when it has none.
	 */
	private fun separatorLabel(separator: PathSeparator): String =
		when (separator) {
			is DynamicRailSemaphore -> separator.name.takeIf { it.isNotBlank() }
			is DynamicInOut -> separator.name.takeIf { it.isNotBlank() }
			is DynamicRailSwitch -> separator.name.takeIf { it.isNotBlank() }
			else -> null
		} ?: separator.toString()

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
	private fun rejectNonContiguousStart(
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
	private fun rejectNonOrientedStart(
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
	 * 0. Reject a non-oriented [start] (a switch — Issue #1188, see [rejectNonOrientedStart]) and a
	 *    [start] that is not contiguous with the train's own footprint (Issue #893) —
	 *    see [rejectNonContiguousStart]
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
	 * - Start is not an OrientedPathSeparator (a switch) → return GeometricallyImpossible
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
	): PathReservationService.ReservationResult = reservePath(trainId, start, target, maxDepth, topologicalPaths = null)

	/**
	 * [reservePath] with the topological candidate paths supplied by the caller (Issue #966).
	 *
	 * [topologicalPaths] must be `navigator.findAllTopologicalPaths(start, target, maxDepth)`. It
	 * replaces that enumeration in the non-InOut↔InOut branch of candidate discovery and is read
	 * only there, so a caller that already enumerated the paths (for example to sort targets)
	 * does not enumerate them again. InOut↔InOut requests still go to [RouteFinder]. `null`
	 * means "enumerate here", exactly as the public overload does.
	 */
	@Suppress("LongMethod")
	internal fun reservePath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int,
		topologicalPaths: Lazy<List<List<TrackSection>>>?
	): PathReservationService.ReservationResult {
		// Step 0 (Issue #1188): a route runs signal-to-signal, so a non-oriented start (a switch)
		// is refused here, before any block is reserved or registered -- see
		// [rejectNonOrientedStart] for the leak this prevents.
		rejectNonOrientedStart(trainId, start, "reservePath")?.let { return it }

		// Step 0 (Issue #893, task A-R1): a route may only start where the train actually is.
		// Checked BEFORE candidate-path discovery so it also governs the already-owned
		// early-return branch further down, which would otherwise report Success for a route
		// this train can never reach.
		rejectNonContiguousStart(trainId, start)?.let { return it }

		val candidatePaths = findCandidatePaths(trainId, start, target, maxDepth, topologicalPaths)

		if (candidatePaths.isEmpty()) {
			return PathReservationService.ReservationResult.NoPathExists
		}

		// Tracks the first blocked (block, owningTrain) pair across all candidate paths.
		// Used to emit ReservationConflictDetected when AllPathsBlocked is about to be returned.
		var firstBlockedConflict: Pair<DynamicTrackBlock, String>? = null

		// Issue #903/#937: the permanent geometric impossibilities (rear-facing START or END, or
		// unconfigurable switch) seen across candidates — first reason kept for reporting, count
		// kept so classifyExhaustedAttempt can tell "every candidate is impossible" (permanent)
		// from "one is, another is merely busy" (retryable).
		//
		// Step 1.5 (Issue #1064, G8): a route must END at a signal facing the train. Screened before
		// the loop — so before the already-owned early return and before any block, switch or signal
		// is touched — which leaves a refused candidate nothing to roll back. Screened-out candidates
		// are recorded like G4, so a request whose every candidate ends rear-facing is reported as a
		// permanent GeometricallyImpossible.
		val (rearFacingEnds, rearFacingUsable) = screenRearFacingEnds(start, target, candidatePaths)
		var geometricFailures = rearFacingEnds

		// Step 1.6 (Issue #1066): a candidate that does not continue the route the train already
		// holds can never merge, so it is skipped here -- before anything is reserved, thrown or
		// cleared -- instead of at Step 2i after all of that (same first-reason / count shape as
		// geometricFailures).
		val (divergentFailures, usableCandidates) = screenDivergentCandidates(trainId, start, target, rearFacingUsable)

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
			val forwardBlocks =
				blocks.filterNot { block ->
					block.trainName == trainId
				}

			if (forwardBlocks.isEmpty()) {
				return reserveAlreadyOwnedPath(trainId, start, target, blocks, path)
			}

			// Step 2b: Check if all forward blocks are available (FREE or RESERVED by THIS train)
			if (!forwardBlocks.areAllFreeOrOwnedBy(trainId)) {
				// Capture a blocked (block, owner) pair for the AllPathsBlocked conflict warning.
				firstBlockedConflict = contention.findFirstBlockedConflict(forwardBlocks, trainId)
				continue
			}

			// TOCTOU Note: Small window between areAllBlocksFree() check and tryAtomicReservation() use.
			// This is acceptable because:
			// - Single-threaded access to simulation state (documented in class KDoc)
			// - tryAtomicReservation() has rollback logic if state changed
			// - Worst case: false positive on free check, caught during reservation, try next path
			//
			// Step 2c: Attempt atomic reservation with rollback (only forward blocks)
			val reservationResult = tryAtomicReservation(trainId, start, forwardBlocks)
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
				registerAndFinalizeCandidate(
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
	 * Step 2d of [reservePath]'s candidate loop: registers the atomically reserved candidate
	 * with the registry and finalizes it, or rolls the registry conflict back. Extracted
	 * (SonarCloud S3776, PR #1109) so both `when` arms' branching stay out of [reservePath]'s
	 * own cognitive complexity.
	 *
	 * @return the updated [GeometricFailures] (a rejected candidate may record one) and the
	 *   candidate's terminal result, or `null` when the candidate was rejected and the caller
	 *   must try the next one -- nothing follows this call in the loop body, so falling
	 *   through the iteration is exactly the former in-arm `continue`
	 */
	private fun registerAndFinalizeCandidate(
		candidate: CandidateRequest,
		geometricFailures: GeometricFailures
	): Pair<GeometricFailures, PathReservationService.ReservationResult?> =
		when (val result = registry.registerAtomic(candidate.trainId, candidate.forwardBlocks)) {
			is PathReservationRegistry.RegistrationResult.Success -> {
				val (failures, preparation) = prepareReservedCandidate(candidate, geometricFailures)
				if (preparation == null) {
					failures to null
				} else {
					failures to finalizePreparedCandidate(candidate, preparation)
				}
			}
			is PathReservationRegistry.RegistrationResult.Conflict -> {
				val conflict =
					rollbackRegistryConflict(
						candidate.trainId,
						candidate.start,
						candidate.forwardBlocks,
						candidate.blocks,
						result
					)
				geometricFailures to conflict
			}
		}

	/**
	 * Steps 2e-2g of [reservePath]'s candidate loop, on the
	 * [PathReservationRegistry.RegistrationResult.Success] arm of Step 2d: builds the
	 * candidate's [cz.vutbr.fit.interlockSim.objects.paths.PathInfo], releases orphan/stale
	 * switch ownership, configures the candidate's switches and START signal, and snapshots
	 * what a rollback of either would need. Extracted (SonarCloud S3776, PR #1109) so this
	 * branching stays out of [reservePath]'s own cognitive complexity.
	 *
	 * @return the updated [GeometricFailures] and the [CandidatePreparation] snapshots a
	 *   successful candidate needs for Steps 2h/2i, or `null` when the candidate was rejected
	 *   (unconfigurable switch, Issue #742/#903/#1065; failed START signal, Issue #893 task
	 *   A5 -- both fully rolled back here) and the caller must try the next candidate
	 */
	private fun prepareReservedCandidate(
		candidate: CandidateRequest,
		geometricFailures: GeometricFailures
	): Pair<GeometricFailures, CandidatePreparation?> {
		val (trainId, start, target, path, blocks, forwardBlocks, index) = candidate
		var failures = geometricFailures
		// Success - path reserved and registered

		// Step 2e: Build PathInfo with entry directions (Issue #295/#296 Phase 4)
		logger.debug {
			"reservePath: Building PathInfo for $trainId from $start to $target with ${path.size} track sections"
		}
		val pathInfo =
			pathInfoBuilder.buildPathInfo(
				start = start,
				target = target,
				trackSections = path // path is List<TrackSection> here
			)

		// Step 2e.5: Release any ORPHAN lock among this candidate's switches --
		// locked by a stray setUpPath call that never reached
		// registry.registerSwitches (Issue #1065; see releaseOrphanSwitchLocks).
		// Must run BEFORE configureSwitchesInPath below: a stale orphan lock would
		// otherwise masquerade as transient Locked contention and refuse an
		// otherwise valid candidate. Ordering relative to the priorSwitches snapshot
		// is irrelevant -- an orphan has no registered owner, so it can never appear
		// in registry.getSwitches(trainId) in the first place.
		val candidateSwitches = switchConfigurator.extractUniqueSwitches(pathInfo)
		switchConfigurator.releaseOrphanSwitchLocks(candidateSwitches)
		// Issue #1076: likewise reclaim STALE foreign ownership among them, so the
		// candidate never overwrites it; a foreign owner that survives this step
		// protects a live route and refuses the candidate in configureSwitchesInPath.
		switchConfigurator.reclaimStaleForeignSwitchOwnership(trainId, candidateSwitches)

		// Step 2f: Configure and register switches (Issue #300, #291, #742).
		// A candidate whose switches cannot be configured is physically impossible
		// and must fail the reservation — see configureAndRegisterSwitches.
		// Snapshot the switches the train already owns BEFORE this candidate so the
		// scoped rollback below (and the signal-config rollback in Step 2g) only
		// release THIS candidate's new switches, never the train's earlier hops.
		val priorSwitches = registry.getSwitches(trainId).toSet()
		// Snapshot the semaphore-clearing delta the same way, BEFORE any of this
		// candidate's Step 2g/2h/2i signal work runs, so a rollback at any of those
		// steps (including Step 2i's merge-abort rollback, Issue #904) can reset
		// exactly what THIS candidate cleared (Issue #893 task A5).
		val clearedBeforeCandidate = signalLedger.snapshotClearedSemaphores(trainId)
		val preparation = CandidatePreparation(pathInfo, candidateSwitches, priorSwitches, clearedBeforeCandidate)
		val switchOutcome = configureAndRegisterSwitches(trainId, pathInfo, forwardBlocks, priorSwitches)
		// The candidate has already been rolled back inside configureAndRegisterSwitches
		// for either failure kind, so try the remaining candidate paths like the other
		// failure modes (blocks-not-free, atomic-reservation-fail) rather than giving up
		// early -- SP0.11 review follow-up (was `return AllPathsBlocked(1)`).
		// recordSwitchConfigFailureIfGeometric only touches geometricFailures for
		// Unconfigurable (Issue #903: a permanent impossibility, first-hit-wins) -- a
		// Locked outcome (Issue #1065: SI-5, transient) must fall through to ordinary
		// contention instead, extracted to keep this branch out of reservePath's own
		// cyclomatic complexity count.
		if (switchOutcome != SwitchConfigOutcome.Configured) {
			failures = recordSwitchConfigFailureIfGeometric(switchOutcome, failures, trainId, index)
			return failures to null
		}

		// Step 2g: Configure semaphore signal after successful reservation.
		// A semaphore START is configured for blocks.first() (the block next to it, also on a
		// route extension, Issue #1062); forwardBlocks (just reserved) only drive an InOut START.
		if (forwardBlocks.isNotEmpty()) {
			val signalResult = signalConfigurator.configureStartSignal(trainId, start, forwardBlocks, blocks.first())

			// Rollback reservation if signal configuration failed
			// This prevents trains from waiting indefinitely at STOP signals.
			// SP0.11 review follow-up: use the scoped [rollbackUnconfigurableCandidate]
			// rather than a full registry.unregister(trainId), which would nuke the
			// train's ENTIRE pre-existing path on a mid-journey extension. Only this
			// candidate's forwardBlocks and new switches are
			// released; the train's earlier hops survive so it keeps waiting for its
			// through route. The candidate is then rolled back cleanly, so try the
			// remaining candidate paths like the other failure modes.
			if (signalResult != StartSignalResult.Configured) {
				failures =
					rollbackFailedStartSignalCandidate(
						trainId,
						start,
						forwardBlocks,
						signalResult,
						index,
						failures,
						preparation
					)
				return failures to null
			}
		}
		return failures to preparation
	}

	/**
	 * Step 2g's failed-START-signal rollback (Issue #893 task A5): records the G4 rejection
	 * (a genuine configuration exception stays ordinary contention, unchanged), resets the
	 * candidate's cleared signals, and releases the candidate's blocks and switches. Extracted
	 * (SonarCloud S3776, PR #1109) in the [recordSwitchConfigFailureIfGeometric] take-and-return
	 * shape so [prepareReservedCandidate] stays under its line threshold.
	 *
	 * @return the updated [GeometricFailures] after the G4 recording
	 */
	private fun rollbackFailedStartSignalCandidate(
		trainId: String,
		start: DynamicPathSeparator,
		forwardBlocks: List<DynamicTrackBlock>,
		signalResult: StartSignalResult,
		index: Int,
		geometricFailures: GeometricFailures,
		preparation: CandidatePreparation
	): GeometricFailures {
		// Issue #903: a G4 rejection is a PERMANENT impossibility, not ordinary
		// contention -- record it (first-hit-wins) so the fallthrough return can
		// classify it correctly. A genuine configuration exception stays folded
		// into ordinary contention/rollback, unchanged.
		val failures = recordG4FailureOnce(geometricFailures, signalResult, index)
		// Issue #893 task A5: configureStartSignal can leave a PARTIAL aspect
		// write behind -- the underlying config call sets the physical aspect
		// and still throws before ClearedSignalLedger.recordClearedSemaphore runs.
		// Reset the before/after delta of the ledger's clearedSemaphores (covers
		// anything that WAS recorded), then explicitly drive the candidate START
		// itself back to STOP for the unrecorded case the delta reset
		// (ClearedSignalLedger.resetSemaphoreSet) cannot see.
		signalLedger.resetCandidateSignals(trainId, start, preparation.clearedBeforeCandidate)
		rollbackUnconfigurableCandidate(
			trainId,
			forwardBlocks,
			preparation.candidateSwitches,
			preparation.priorSwitches
		)
		return failures
	}

	/**
	 * Steps 2h/2i of [reservePath]'s candidate loop: configures the intermediate semaphores,
	 * registers the forward-only PathInfo, emits the reservation events, and reports
	 * [PathReservationService.ReservationResult.Success]. Extracted (SonarCloud S3776, PR
	 * #1109) so this branching stays out of [reservePath]'s own cognitive complexity.
	 *
	 * @return the candidate's result, or `null` when the PathInfo merge aborted (Issue #904;
	 *   [rollbackIfMergeAborted] has already released exactly what this candidate acquired)
	 *   and the caller must try the next candidate
	 */
	private fun finalizePreparedCandidate(
		candidate: CandidateRequest,
		preparation: CandidatePreparation
	): PathReservationService.ReservationResult? {
		val (trainId, start, target, path, blocks, forwardBlocks) = candidate
		// Step 2h: Configure intermediate semaphore signals along the full path.
		// reservePath() is called with the entry (InOut/semaphore) and the exit
		// (InOut/semaphore) as end-points, so the reserved path may pass through
		// one or more intermediate semaphores.  Step 2g only sets the START
		// separator's signal; intermediate semaphores remain at STOP unless we
		// configure them here.  Without this, a train entering a multi-block path
		// will travel through the first block, stop at the intermediate semaphore
		// (signal=STOP) and wait forever.
		// forwardBlocks is passed separately so a route EXTENSION (blocks the train
		// already owns, plus new ones) only lights boundaries that lead into a new
		// block -- see [RouteSignalConfigurator.configureIntermediateSemaphores].
		signalConfigurator.configureIntermediateSemaphores(trainId, blocks, forwardBlocks.toSet())

		// Step 2i: Register PathInfo metadata (Issue #295/#296 Phase 4; moved here by
		// Issue #742). Registration happens only after switches AND signals configured
		// successfully, so no rollback path can leave a poisoned PathInfo behind —
		// a PathInfo pointing at an unusable route permanently stalls the train
		// (isPathExtendedBeyond suppresses the corrective re-reservation).
		// Issue #904: register the FORWARD-ONLY segment, not `pathInfo` (which starts
		// at the original `start` and would falsely non-contiguous-abort on every
		// route extension that reuses it, per forwardOnlyPathInfo's KDoc).
		// The candidate already passed the Step 0a contiguity probe (Step 1.6), so the abort
		// handling below now covers only Step 0b/0c and the cycle guard.
		val mergeCandidate = forwardOnlyPathInfo(start, target, path, forwardBlocks)
		val mergeOutcome = registry.registerPathInfo(trainId, mergeCandidate)
		// If the registry's merge fail-safe STILL aborted
		// (PathInfoMerger.merge's KDoc lists the four reasons) --
		// now only the genuinely pathological ones: duplicated new-start, the
		// direction reversal guard (Issue #944), or the cycle guard -- release
		// exactly what THIS candidate acquired --
		// transactionally complete, matching Step 2g's own rollback -- rather than
		// leaving an orphaned RESERVED tail for OrphanReservationSweeper (which is
		// not even wired in :fast-sim or bare-:core).
		if (rollbackIfMergeAborted(
				mergeOutcome,
				trainId,
				start,
				forwardBlocks,
				preparation.pathInfo,
				preparation.priorSwitches,
				preparation.clearedBeforeCandidate
			)
		) {
			return null
		}
		logger.debug {
			"reservePath: Registered PathInfo for $trainId with ${mergeCandidate.entryDirections.size} entry directions, " +
				"reserved path has ${mergeCandidate.reservedPath.length()} elements"
		}

		// Emit BlockReserved only for the blocks THIS call newly reserved (Issue #1081);
		// the full rationale moved with the code into [emitForwardBlockReservedEvents].
		emitForwardBlockReservedEvents(trainId, forwardBlocks)

		// Resolved -- this train is no longer contending for any block.
		contention.clearBlockedTracking(trainId)

		return PathReservationService.ReservationResult.Success(blocks)
	}

	/**
	 * Emits [BlockEvent.BlockReserved] (and the legacy [BlockOccupancyEvent]) for each block
	 * THIS call newly reserved. Extracted (SonarCloud S3776, PR #1109) from Step 2d's success
	 * arm; the Issue #1081 rationale in the body explains why only [forwardBlocks] -- never
	 * the already-owned prefix -- is announced.
	 */
	private fun emitForwardBlockReservedEvents(
		trainId: String,
		forwardBlocks: List<DynamicTrackBlock>
	) {
		// Emit BlockReserved only for the blocks THIS call newly reserved
		// (forwardBlocks). `blocks` also holds the train's already-owned prefix
		// (Step 2a.5), and each physical block is later released exactly once
		// (one BlockReleased per block), so a BlockReserved re-emitted for an
		// owned block left the per-event reservation counters permanently
		// off-balance: DefaultMetricsCollectionService.activeReservationCount
		// never returned to zero (the train was never counted as completed) and
		// DefaultCollisionDetectionService / TemporalConflictDetector over-counted
		// it the same way (Issue #1081 review; extensions over owned blocks
		// became dispatcher-reachable with Issue #1060).
		val simTime = currentSimulationTime()
		forwardBlocks.forEach { block ->
			emitCustom(BlockEvent.BlockReserved(block, trainId, simTime))
			// Also notify addBlockOccupancyListener subscribers (legacy API, works without run())
			registry.emit(
				BlockOccupancyEvent(
					block = block,
					type = BlockOccupancyEventType.BLOCK_RESERVED,
					trainId = trainId,
					occupant = null,
					previousState = TrackFacility.State.FREE,
					newState = TrackFacility.State.RESERVED,
					simulationTime = simTime
				)
			)
		}
	}

	/**
	 * Step 2d's [PathReservationRegistry.RegistrationResult.Conflict] arm: rolls back only
	 * the forward blocks THIS call reserved (Issue #1025 -- never the train's already-held
	 * blocks) and emits both conflict events. Extracted (SonarCloud S3776, PR #1109) so the
	 * arm's branching stays out of [reservePath]'s own cognitive complexity.
	 */
	private fun rollbackRegistryConflict(
		trainId: String,
		start: DynamicPathSeparator,
		forwardBlocks: List<DynamicTrackBlock>,
		blocks: List<DynamicTrackBlock>,
		result: PathReservationRegistry.RegistrationResult.Conflict
	): PathReservationService.ReservationResult.Conflict {
		// Registry conflict - rollback block reservations
		logger.warn {
			"reservePath: Registry conflict - ${result.conflictingBlock} owned by ${result.existingOwner}; " +
				"rolling back ${forwardBlocks.size} forward block(s) for $trainId " +
				"(${blocks.size - forwardBlocks.size} already-held block(s) untouched)"
		}
		// Issue #1025: roll back only the blocks THIS call physically reserved (Step 2c),
		// which are exactly `forwardBlocks`. `blocks` also holds the train's already-owned
		// blocks (excluded at Step 2a.5); when those were reserved from this same `start`
		// on an earlier route, cancelling them drove them RESERVED -> FREE while the
		// registry and the PathInfo still named this train. Navigation trusts the registry
		// only, so the train was later routed into the FREE block and
		// DynamicTrackBlock.enter threw "Wrong state: FREE , expected : RESERVED".
		// Nothing was registered (registerAtomic is all-or-nothing), so no registry
		// cleanup is needed.
		rollbackReservation(start, forwardBlocks)
		val simTime = currentSimulationTime()
		emitCustom(
			BlockEvent.ReservationConflictDetected(
				block = result.conflictingBlock,
				trainId = trainId,
				conflictingTrainId = result.existingOwner,
				time = simTime
			)
		)
		emitCustom(
			ConflictDetectedEvent(
				block = result.conflictingBlock,
				trainId = trainId,
				conflictingTrainId = result.existingOwner,
				time = simTime
			)
		)
		return PathReservationService.ReservationResult.Conflict(
			result.conflictingBlock,
			result.existingOwner
		)
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
	 */
	override fun isPathAvailable(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int
	): Boolean {
		// Switch-CONSTRAINED enumeration (Issue #797 follow-up): a target is available only if a
		// physically legal, all-free route reaches it. The switch-blind findAllTopologicalPaths
		// admits phantom routes that reverse through a switch to reach a target's back side while
		// bypassing the occupied block in front of it (e.g. reaching doB1 via the free k2 + a vB
		// reversal while k1 is occupied), producing a false positive that steers
		// findNextReservationTarget onto the blocked track and deadlocks opposing trains.
		return isPathAvailableFor(start, target, maxDepth, ownerTrainId = null)
	}

	/** [isPathAvailable], owner-aware (Issue #1060); see [ReservationTargetDiscovery.isPathAvailableFor]. */
	private fun isPathAvailableFor(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int,
		ownerTrainId: String?
	): Boolean = targetDiscovery.isPathAvailableFor(start, target, maxDepth, ownerTrainId)

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
			// candidate has already locked/registered its switches (configureAndRegisterSwitches,
			// inside reservePath's Step 2f) and merged its PathInfo (Step 2i) before the bypass
			// check below can reject it. The snapshots let the rollback undo exactly what THIS
			// candidate added on those two sides too, mirroring the semaphore/block deltas above.
			val switchesBefore = registry.getSwitches(trainId).toSet()
			val pathInfoBefore = registry.getPathInfo(trainId)

			val result = reservePath(trainId, start, semaphore)

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
	 * [rollbackUnconfigurableCandidate]'s: `cancelPathSetup` from the block's own `reservedFrom` (not
	 * the caller's `start`) under a try, then the registry entry, the approach-lock deferral and any
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
	 * target list is recomputed per call rather than cached: network state changes continuously.
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

		// Issue #1188: a non-oriented start (a switch) is refused once, here. reservePath would
		// refuse it too, before its candidate-path enumeration, but only after STEP 1 had already
		// discovered every target, and then once per target with the same reason and warning.
		// Refusing up front skips the target discovery and the repeated calls.
		rejectNonOrientedStart(trainId, start, "reservePathToAny")?.let { return it }

		// Steps 1-3: target discovery and prioritization (see ReservationTargetDiscovery)
		val sortedTargetsWithPaths = targetDiscovery.reservePathToAnyTargets(start)

		logger.debug {
			"reservePathToAny: Target order (InOuts first): " +
				sortedTargetsWithPaths.joinToString(", ") { it.first.toString() }
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

		for ((target, topologicalPaths) in sortedTargetsWithPaths) {
			val result = reservePath(trainId, start, target, DEFAULT_MAX_PATH_DEPTH, topologicalPaths)

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
		if (sortedTargetsWithPaths.isEmpty()) {
			logger.warn { "reservePathToAny: No targets found from $start" }
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.warn {
			"reservePathToAny: No available path from $start for $trainId " +
				"(tried ${sortedTargetsWithPaths.size} targets, all blocked or unreachable)"
		}

		// Return last failure result (or AllPathsBlocked if no result available)
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(sortedTargetsWithPaths.size)
	}

	// ========== Private helper methods ==========

	/**
	 * Attempt atomic reservation of all blocks in a path.
	 *
	 * ## Algorithm
	 *
	 * 1. Try to reserve all blocks via setUpPath()
	 * 2. If any block fails, rollback all previously reserved blocks
	 * 3. Return null on success, failure result on error
	 *
	 * ## Atomic Guarantee
	 *
	 * Either all blocks are reserved, or none are (all-or-nothing semantics).
	 *
	 * @param trainId Train identifier (for logging and error messages)
	 * @param separator Starting separator for reservation
	 * @param blocks List of blocks to reserve
	 * @return null on success, ReservationResult on failure
	 */
	private fun tryAtomicReservation(
		trainId: String,
		separator: DynamicPathSeparator,
		blocks: List<DynamicTrackBlock>
	): PathReservationService.ReservationResult? {
		val reservedSoFar = mutableListOf<DynamicTrackBlock>()

		try {
			for (block in blocks) {
				// Note: OCCUPIED blocks are filtered out before calling this method.
				// RESERVED blocks are handled idempotently by setUpPath() (if from same separator).
				block.setUpPath(separator, trainId)
				reservedSoFar.add(block)
			}
			// All blocks reserved successfully
			return null
		} catch (e: Exception) {
			// Partial failure - rollback all blocks reserved so far
			logger.debug(e) {
				"tryAtomicReservation: Reservation failed for $trainId, " +
					"rolling back ${reservedSoFar.size} block(s)"
			}
			rollbackReservation(separator, reservedSoFar)

			// Classify error using type-safe exception hierarchy
			return when (e) {
				is TrackReservationException.AlreadyReservedConflict -> {
					// TOCTOU race: block became reserved between check and reservation
					logger.warn {
						"tryAtomicReservation: TOCTOU race detected - " +
							"Block ${e.block} reserved by ${e.existingSeparator}"
					}
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
				is TrackReservationException.InvalidStateTransition -> {
					// State machine violation
					logger.error(e) {
						"tryAtomicReservation: State machine violation in ${e.block} - " +
							"${e.operation} from ${e.fromState}"
					}
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
				else -> {
					// Unexpected error (should not happen)
					logger.error(e) {
						"tryAtomicReservation: Unexpected error during reservation: ${e::class.simpleName}"
					}
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
			}
		}
	}

	/** See [RouteReleaser.rollbackReservation]. */
	private fun rollbackReservation(
		separator: PathSeparator,
		blocks: List<DynamicTrackBlock>
	) = releaser.rollbackReservation(separator, blocks)

	/**
	 * Permanent geometric impossibilities seen while trying a request's candidate paths.
	 *
	 * Two facts, deliberately kept apart (Issue #937):
	 * - [reason] is the **first** one hit, because a geometric reason is the most informative
	 *   thing to report and later candidates must not overwrite it (the Issue #903 ruling, which
	 *   still stands for reporting).
	 * - [count] is how many candidates hit one, because whether the *request* is permanently
	 *   unsatisfiable is an AND over candidates, not an OR. One impossible candidate alongside a
	 *   merely-busy one means "wait and retry", not "give up".
	 *
	 * @property reason First geometric failure reason, or `null` if no candidate hit one.
	 * @property count Number of candidates that failed geometrically.
	 */
	private data class GeometricFailures(
		val reason: String? = null,
		val count: Int = 0
	)

	/**
	 * The per-candidate snapshots Step 2f takes before configuring anything (Issue #893 task
	 * A5 / Issue #904 / Issue #1065): the candidate's [cz.vutbr.fit.interlockSim.objects.paths.PathInfo],
	 * its switches, the switches the train already owned, and the semaphores already cleared.
	 * Carried between [prepareReservedCandidate] and [finalizePreparedCandidate] (and into
	 * [rollbackFailedStartSignalCandidate]) so neither helper exceeds the parameter-count
	 * threshold. Built only from values that exist before [configureAndRegisterSwitches] runs,
	 * so building it has no side effects.
	 */
	private data class CandidatePreparation(
		val pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo,
		val candidateSwitches: List<DynamicRailSwitch>,
		val priorSwitches: Set<DynamicRailSwitch>,
		val clearedBeforeCandidate: Set<DynamicRailSemaphore>
	)

	/**
	 * The per-candidate slice of one [reservePath] request: the values Step 2d's extracted
	 * helpers need, unpacked once at each helper's head so the moved candidate-loop body can
	 * stay verbatim. Built once per candidate iteration (no side effects) and carried so
	 * [registerAndFinalizeCandidate], [prepareReservedCandidate] and [finalizePreparedCandidate]
	 * stay under the parameter-count threshold (SonarCloud S3776, PR #1109).
	 */
	private data class CandidateRequest(
		val trainId: String,
		val start: DynamicPathSeparator,
		val target: DynamicPathSeparator,
		val path: List<TrackSection>,
		val blocks: List<DynamicTrackBlock>,
		val forwardBlocks: List<DynamicTrackBlock>,
		val index: Int
	)

	/**
	 * Records one geometric candidate failure: keeps the first [reason], increments the count
	 * (Issue #903 for the reason, Issue #937 for the count). Extracted so the call site in
	 * [reservePath]'s candidate loop stays a single line, keeping that method's cyclomatic
	 * complexity under threshold.
	 */
	private fun recordGeometricFailureOnce(
		current: GeometricFailures,
		reason: String
	): GeometricFailures = GeometricFailures(current.reason ?: reason, current.count + 1)

	/**
	 * Step 2f's post-[configureAndRegisterSwitches] classification (Issue #1065): a failed
	 * [SwitchConfigOutcome] is recorded as a permanent geometric impossibility only for
	 * [SwitchConfigOutcome.Unconfigurable] (Issue #742/#903) via [recordGeometricFailureOnce].
	 * [SwitchConfigOutcome.Locked] (Issue #1065, safety property SI-5) is TRANSIENT contention --
	 * the switch frees once its holder's route releases it -- so it must fall through to ordinary
	 * contention (`AllPathsBlocked`) instead: recording it as geometric would make
	 * [cz.vutbr.fit.interlockSim.sim.InOutWorker] throw instead of letting the train wait.
	 * [SwitchConfigOutcome.Configured] never reaches here (the caller only calls this for a
	 * failed outcome), so it is a no-op passthrough for exhaustiveness.
	 *
	 * Extracted so this classification adds no branch to [reservePath]'s own cyclomatic
	 * complexity -- the caller's single `if (switchOutcome != Configured)` covers both failure
	 * kinds, differing only in what this helper does with [geometricFailures].
	 */
	private fun recordSwitchConfigFailureIfGeometric(
		switchOutcome: SwitchConfigOutcome,
		geometricFailures: GeometricFailures,
		trainId: String,
		index: Int
	): GeometricFailures =
		when (switchOutcome) {
			is SwitchConfigOutcome.Unconfigurable ->
				recordGeometricFailureOnce(
					geometricFailures,
					"Switch along candidate $index could not be configured for the requested route"
				)
			is SwitchConfigOutcome.Locked -> {
				logger.debug {
					"reservePath: candidate $index needs switch ${switchOutcome.switch.staticRef.getName()} " +
						"held ${switchOutcome.held} by '${switchOutcome.holder}' (train $trainId needs " +
						"${switchOutcome.required}) -- ordinary contention, not geometric (Issue #1065)"
				}
				geometricFailures
			}
			SwitchConfigOutcome.Configured -> geometricFailures
		}

	/**
	 * G8 screening of [candidatePaths] (Issue #1064): splits off every candidate whose end faces away
	 * from the train ([rearFacingEndReason]) and records each one as a geometric failure. Extracted so
	 * the screening adds no branch to [reservePath]'s own cyclomatic complexity.
	 *
	 * @return the geometric failures of the screened-out candidates, and the remaining candidates
	 *   with their original index, which later denial reasons use to name a candidate
	 */
	private fun screenRearFacingEnds(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidatePaths: List<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>
	): Pair<GeometricFailures, List<IndexedValue<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>>> {
		var failures = GeometricFailures()
		val usable = mutableListOf<IndexedValue<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>>()
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
	private fun screenDivergentCandidates(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidates: List<IndexedValue<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>>
	): Pair<GeometricFailures, List<IndexedValue<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>>> {
		var failures = GeometricFailures()
		val usable = mutableListOf<IndexedValue<List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>>>()
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
	 * @return the English denial reason when [target] faces away from the train at the end of
	 *   [path]; `null` when the end is usable — an InOut (bidirectional), a separator without an
	 *   orientation, or a signal facing the direction of travel.
	 */
	private fun rearFacingEndReason(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>,
		candidateIndex: Int
	): String? {
		if (target is DynamicInOut || target !is OrientedPathSeparator || path.isEmpty()) return null
		val arrival = arrivalSegmentAt(target, path.last()) ?: return null
		if (target.direction() ==
			cz.vutbr.fit.interlockSim.objects.core
				.anti(arrival)
		) {
			return null
		}
		val facingEnds =
			runCatching { targetDiscovery.findNextSemaphoresVia(start, path.first()) }
				.getOrDefault(emptyList())
				.joinToString(", ") { separatorLabel(it) }
		return "Route end '${separatorLabel(target)}' faces away from the direction of travel on candidate " +
			"$candidateIndex; a route must end at a signal facing the train. The next signals facing the " +
			"train from '${separatorLabel(start)}' are: $facingEnds."
	}

	/** The segment of [target]'s cell through which [lastSection] reaches it, or `null` if they are not adjacent. */
	private fun arrivalSegmentAt(
		target: PathSeparator,
		lastSection: cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
	): cz.vutbr.fit.interlockSim.objects.core.Cell.Segment? {
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
	 * and properly extends PathInfo instead of aborting -- leaving [rollbackIfMergeAborted]'s
	 * rollback to fire only for genuinely pathological aborts (duplicated new-start, the
	 * 3rd-occurrence cycle guard), not for this routine extension shape.
	 *
	 * @param path The FULL candidate path (including any already-owned prefix).
	 * @param forwardBlocks The blocks this candidate newly reserves (excludes any already-owned
	 *   prefix by construction, see [reservePath] Step 2a.5).
	 */
	private fun forwardOnlyPathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		path: List<TrackSection>,
		forwardBlocks: List<DynamicTrackBlock>
	): cz.vutbr.fit.interlockSim.objects.paths.PathInfo {
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

	/**
	 * Step 2i's merge-abort handling (Issue #904): if [mergeOutcome] is
	 * [PathReservationRegistry.MergeOutcome.Aborted], release exactly what THIS candidate
	 * acquired -- blocks, switches and cleared signal -- transactionally complete, matching Step
	 * 2g's own rollback, rather than leaving an orphaned RESERVED tail for
	 * `OrphanReservationSweeper` (not wired in `:fast-sim` or bare-`:core`). Extracted so the
	 * `is Aborted` check and its rollback body stay out of [reservePath]'s own cyclomatic
	 * complexity count; the caller still needs its own `if (...) continue` since a non-local
	 * `continue` cannot live inside this helper.
	 *
	 * @return `true` if the merge aborted (and the candidate was rolled back); `false` if it merged.
	 */
	private fun rollbackIfMergeAborted(
		mergeOutcome: PathReservationRegistry.MergeOutcome,
		trainId: String,
		start: DynamicPathSeparator,
		forwardBlocks: List<DynamicTrackBlock>,
		pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo,
		priorSwitches: Set<DynamicRailSwitch>,
		clearedBeforeCandidate: Set<DynamicRailSemaphore>
	): Boolean {
		if (mergeOutcome !is PathReservationRegistry.MergeOutcome.Aborted) return false
		logger.warn {
			"reservePath: merge aborted for $trainId (${mergeOutcome.reason}) -- releasing " +
				"this candidate's blocks, switches and signal instead of leaving an orphaned tail"
		}
		signalLedger.resetCandidateSignals(trainId, start, clearedBeforeCandidate)
		rollbackUnconfigurableCandidate(
			trainId,
			forwardBlocks,
			switchConfigurator.extractUniqueSwitches(pathInfo),
			priorSwitches
		)
		return true
	}

	/**
	 * Single write path for [reservePathToAnyNextSemaphore]'s `lastResult` tracker (Issue #903).
	 *
	 * `GeometricallyImpossible` is a PERMANENT impossibility (rear-facing START or unconfigurable
	 * switch), not ordinary contention: once seen for one candidate semaphore, it must win over
	 * any later contention result (`AllPathsBlocked`, `NoPathExists`, or the bypass-rollback
	 * branch's `AllPathsBlocked`) so the caller -- [cz.vutbr.fit.interlockSim.sim.InOutWorker] on
	 * the train-navigation path -- throws [cz.vutbr.fit.interlockSim.exceptions.SimulationException]
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
	 * [recordGeometricFailureOnce], specialised for the G4 rejection case: only records a reason
	 * when [signalResult] is [StartSignalResult.RejectedG4] (a genuine configuration exception —
	 * [StartSignalResult.ConfigFailed] — must not be recorded as a geometric impossibility).
	 */
	private fun recordG4FailureOnce(
		current: GeometricFailures,
		signalResult: StartSignalResult,
		candidateIndex: Int
	): GeometricFailures =
		if (signalResult == StartSignalResult.RejectedG4) {
			recordGeometricFailureOnce(
				current,
				"START semaphore for candidate $candidateIndex faces away from the requested direction of travel"
			)
		} else {
			current
		}

	/**
	 * Configure the candidate path's switches and register them on success (Issue #742).
	 *
	 * ## Ordering (Issue #300, #291, #742)
	 *
	 * Switches must be configured BEFORE semaphore signals are set up, so they are in the
	 * correct position (MAIN/BRANCH) for the reserved route. Configuration also runs BEFORE
	 * [PathReservationRegistry.registerSwitches] and [PathReservationRegistry.registerPathInfo]
	 * so a rejected candidate leaves no registry state and the rollback only needs to undo
	 * this candidate's blocks and switch locks.
	 *
	 * ## Why an unconfigurable switch fails the reservation (Issue #742)
	 *
	 * A switch the route genuinely traverses that CANNOT be configured (no switch
	 * configuration joins the route's entry/exit segments — e.g. the physically impossible
	 * sibling-branch "diversion" doB1→doB2 through vB) makes the whole candidate unusable.
	 * Returning Success anyway committed trains to untraversable routes and gridlocked the
	 * network. Failing instead means the train keeps waiting at its current semaphore and
	 * the through route is reserved at a future simulation time once it frees.
	 *
	 * @param trainId The train identifier
	 * @param pathInfo The candidate's PathInfo (switches are extracted from its path)
	 * @param forwardBlocks The candidate's freshly reserved blocks (for rollback on failure)
	 * @param priorSwitches Switches the train already owned before this candidate (snapshot
	 *   by the caller before this step), forwarded to [rollbackUnconfigurableCandidate] so
	 *   only this candidate's new switches are released on failure
	 * @return [SwitchConfigOutcome.Configured] when the candidate's switches are configured
	 *   and registered (or the path has none); a failure variant when the candidate was rolled
	 *   back and the reservation must fail -- the rollback is identical for both failure
	 *   variants, only the caller's classification of the result differs (Issue #1065)
	 */
	private fun configureAndRegisterSwitches(
		trainId: String,
		pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo,
		forwardBlocks: List<DynamicTrackBlock>,
		priorSwitches: Set<DynamicRailSwitch>
	): SwitchConfigOutcome {
		val switches = switchConfigurator.extractUniqueSwitches(pathInfo)
		if (switches.isEmpty()) {
			return SwitchConfigOutcome.Configured
		}
		val outcome = switchConfigurator.configureSwitchesInPath(trainId, pathInfo)
		if (outcome != SwitchConfigOutcome.Configured) {
			rollbackUnconfigurableCandidate(trainId, forwardBlocks, switches, priorSwitches)
			return outcome
		}
		switchConfigurator.registerSwitches(trainId, switches)
		return SwitchConfigOutcome.Configured
	}

	/**
	 * Roll back a candidate path whose switches cannot be configured (Issue #742), or whose
	 * signal configuration failed after the switches were already registered (SP0.11 review
	 * follow-up).
	 *
	 * Scoped strictly to THIS candidate's mutations — it must not touch the train's
	 * pre-existing blocks, switches or PathInfo (no `registry.unregister(trainId)`): an
	 * extension attempt can fail mid-journey while the train is still running on its
	 * earlier reserved path, and that path must survive so the train simply keeps waiting
	 * for its through route.
	 *
	 * - Cancels path setup and unregisters ONLY the freshly reserved [forwardBlocks], each through
	 *   [rollbackBlock] (which also reclaims a switch lock left stale and publishes no release
	 *   event, since every caller runs before [reservePath] announces the blocks -- Issue #961)
	 * - Unlocks AND unregisters ONLY this candidate's switches that are not part of the
	 *   train's pre-existing registered switches (switches locked/registered by the train's
	 *   earlier hops stay locked and registered). [PathReservationRegistry.unregisterSwitch]
	 *   is a no-op for switches not registered to the train, so this is safe whether or not
	 *   [PathReservationRegistry.registerSwitches] has run yet (switch-config failure runs
	 *   before registration; signal-config failure runs after it). A switch registered to
	 *   ANOTHER train is never released either (Issue #1065 review — see
	 *   [SwitchConfigurator.releaseCandidateSwitches]).
	 *
	 * PathInfo needs no rollback: Issue #742 moved [PathReservationRegistry.registerPathInfo]
	 * after switch and signal configuration, so nothing has been registered yet.
	 *
	 * @param trainId The train identifier
	 * @param forwardBlocks The freshly reserved blocks of the rejected candidate
	 * @param switches The rejected candidate's switches (possibly locked/registered)
	 * @param priorSwitches Switches the train already owned BEFORE this candidate was
	 *   attempted (snapshot by the caller before Step 2f). Only candidate switches NOT in
	 *   this set are released; switches shared with earlier hops stay locked/registered.
	 */
	internal fun rollbackUnconfigurableCandidate(
		trainId: String,
		forwardBlocks: List<DynamicTrackBlock>,
		switches: List<DynamicRailSwitch>,
		priorSwitches: Set<DynamicRailSwitch>
	) {
		switchConfigurator.releaseCandidateSwitches(trainId, switches.filterNot { it in priorSwitches })
		// No release event: these blocks were rolled back before reservePath announced them (Issue #961).
		forwardBlocks.forEach { rollbackBlock(trainId, it) }
		logger.debug {
			"rollbackUnconfigurableCandidate: Rolled back unconfigurable candidate for $trainId " +
				"(${forwardBlocks.size} block(s), ${switches.size} switch(es) checked)"
		}
	}

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

/** Path-search depth bound shared with the interface defaults of [PathReservationService]. */
internal const val DEFAULT_MAX_PATH_DEPTH = 100
