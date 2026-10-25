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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackReservationException
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Candidate commit, finalize and rollback (Issue #1211, SP12.10): Steps 2c to 2i of
 * [DefaultPathReservationService.reservePath]'s candidate loop (2c, 2d, 2e to 2g including 2e.5,
 * 2h, 2i), i.e. everything that mutates state for one screened candidate path and undoes exactly
 * that candidate when a step fails.
 *
 * In step order:
 * - **2c** [tryAtomicReservation]: reserve the candidate's forward blocks all-or-nothing.
 * - **2d** [registerAndFinalizeCandidate]: register the blocks with the registry, or roll the
 *   registry conflict back ([rollbackRegistryConflict]).
 * - **2e-2g** [prepareReservedCandidate]: build the PathInfo, reclaim orphan/stale switch
 *   ownership, configure and register the switches ([configureAndRegisterSwitches]) and light
 *   the START signal; a failed switch or START configuration rolls the candidate back
 *   ([rollbackUnconfigurableCandidate], [rollbackFailedStartSignalCandidate]).
 * - **2h/2i** [finalizePreparedCandidate]: light the intermediate semaphores, register the
 *   forward-only PathInfo (a merge abort rolls the candidate back, [rollbackIfMergeAborted]) and
 *   announce the reserved blocks ([emitForwardBlockReservedEvents]).
 *
 * Every rollback is scoped to THIS candidate: the snapshots it needs (`priorSwitches`,
 * `clearedBeforeCandidate`) are taken in [prepareReservedCandidate] before the first mutation they
 * guard, so the train's earlier hops (blocks, switches, signals, PathInfo) always survive.
 *
 * Extracted from [DefaultPathReservationService] without behaviour change: every side effect keeps
 * its order, and the log lines and denial reasons are byte-identical. It is wired by the service
 * to the service's own collaborators, so the service-level rollback tests exercise the real wiring.
 *
 * **NOT thread-safe**, like its owner: single-threaded simulation access only.
 *
 * @property registry Ownership registry; block, switch and PathInfo registration
 * @property pathInfoBuilder Builder for the candidate's PathInfo
 * @property switchConfigurator Sets, locks, registers and releases a candidate's switches
 * @property signalConfigurator Clears the START and intermediate semaphores of a reserved route
 * @property signalLedger Records which semaphores are lit for which train; resets them on rollback
 * @property contention Blocked-request tracking, cleared once a train is resolved
 * @property releaser Block rollback and its release bookkeeping
 * @property screen Shared forward-only PathInfo trimming rule for the merge candidate
 * @since Issue #1211 (extracted from [DefaultPathReservationService])
 */
internal class CandidateCommitter(
	private val registry: PathReservationRegistry,
	private val pathInfoBuilder: PathInfoBuilder,
	private val switchConfigurator: SwitchConfigurator,
	private val signalConfigurator: RouteSignalConfigurator,
	private val signalLedger: ClearedSignalLedger,
	private val contention: ReservationContentionTracker,
	private val releaser: RouteReleaser,
	private val screen: RouteCandidateScreen
) {
	/**
	 * Step 2d of [DefaultPathReservationService.reservePath]'s candidate loop: registers the
	 * atomically reserved candidate with the registry and finalizes it, or rolls the registry
	 * conflict back. Extracted (SonarCloud S3776, PR #1109) so both `when` arms' branching stay out of
	 * [DefaultPathReservationService.reservePath]'s own cognitive complexity.
	 *
	 * @return the updated [GeometricFailures] (a rejected candidate may record one) and the
	 *   candidate's terminal result, or `null` when the candidate was rejected and the caller
	 *   must try the next one -- nothing follows this call in the loop body, so falling
	 *   through the iteration is exactly the former in-arm `continue`
	 */
	internal fun registerAndFinalizeCandidate(
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
	 * Steps 2e-2g of [DefaultPathReservationService.reservePath]'s candidate loop, on the
	 * [PathReservationRegistry.RegistrationResult.Success] arm of Step 2d: builds the
	 * candidate's [PathInfo], releases orphan/stale
	 * switch ownership, configures the candidate's switches and START signal, and snapshots
	 * what a rollback of either would need. Extracted (SonarCloud S3776, PR #1109) so this
	 * branching stays out of [DefaultPathReservationService.reservePath]'s own cognitive complexity.
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
	 * Steps 2h/2i of [DefaultPathReservationService.reservePath]'s candidate loop: configures the
	 * intermediate semaphores,
	 * registers the forward-only PathInfo, emits the reservation events, and reports
	 * [PathReservationService.ReservationResult.Success]. Extracted (SonarCloud S3776, PR
	 * #1109) so this branching stays out of [DefaultPathReservationService.reservePath]'s own cognitive complexity.
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
		val mergeCandidate = screen.forwardOnlyPathInfo(start, target, path, forwardBlocks)
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
	 * arm's branching stays out of [DefaultPathReservationService.reservePath]'s own cognitive complexity.
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
		releaser.rollbackReservation(start, forwardBlocks)
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
	internal fun tryAtomicReservation(
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
			releaser.rollbackReservation(separator, reservedSoFar)

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
	 * Extracted so this classification adds no branch to [DefaultPathReservationService.reservePath]'s own cyclomatic
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
	 * Step 2i's merge-abort handling (Issue #904): if [mergeOutcome] is
	 * [PathReservationRegistry.MergeOutcome.Aborted], release exactly what THIS candidate
	 * acquired -- blocks, switches and cleared signal -- transactionally complete, matching Step
	 * 2g's own rollback, rather than leaving an orphaned RESERVED tail for
	 * `OrphanReservationSweeper` (not wired in `:fast-sim` or bare-`:core`). Extracted so the
	 * `is Aborted` check and its rollback body stay out of [DefaultPathReservationService.reservePath]'s own cyclomatic
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
		pathInfo: PathInfo,
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
		pathInfo: PathInfo,
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
	 *   [RouteReleaser.rollbackBlock] (which also reclaims a switch lock left stale and publishes no release
	 *   event, since every caller runs before [DefaultPathReservationService.reservePath] announces
	 *   the blocks -- Issue #961)
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
		forwardBlocks.forEach { releaser.rollbackBlock(trainId, it) }
		logger.debug {
			"rollbackUnconfigurableCandidate: Rolled back unconfigurable candidate for $trainId " +
				"(${forwardBlocks.size} block(s), ${switches.size} switch(es) checked)"
		}
	}
}

/**
 * The per-candidate snapshots Step 2f takes before configuring anything (Issue #893 task
 * A5 / Issue #904 / Issue #1065): the candidate's [PathInfo],
 * its switches, the switches the train already owned, and the semaphores already cleared.
 * Carried between [prepareReservedCandidate] and [finalizePreparedCandidate] (and into
 * [rollbackFailedStartSignalCandidate]) so neither helper exceeds the parameter-count
 * threshold. Built only from values that exist before [configureAndRegisterSwitches] runs,
 * so building it has no side effects.
 */
private data class CandidatePreparation(
	val pathInfo: PathInfo,
	val candidateSwitches: List<DynamicRailSwitch>,
	val priorSwitches: Set<DynamicRailSwitch>,
	val clearedBeforeCandidate: Set<DynamicRailSemaphore>
)

/**
 * The per-candidate slice of one [DefaultPathReservationService.reservePath] request: the values Step 2d's extracted
 * helpers need, unpacked once at each helper's head so the moved candidate-loop body can
 * stay verbatim. Built once per candidate iteration (no side effects) and carried so
 * [registerAndFinalizeCandidate], [prepareReservedCandidate] and [finalizePreparedCandidate]
 * stay under the parameter-count threshold (SonarCloud S3776, PR #1109).
 */
internal data class CandidateRequest(
	val trainId: String,
	val start: DynamicPathSeparator,
	val target: DynamicPathSeparator,
	val path: List<TrackSection>,
	val blocks: List<DynamicTrackBlock>,
	val forwardBlocks: List<DynamicTrackBlock>,
	val index: Int
)
