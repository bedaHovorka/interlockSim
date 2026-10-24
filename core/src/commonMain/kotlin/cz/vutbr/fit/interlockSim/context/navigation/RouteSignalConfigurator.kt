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
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Outcome of [RouteSignalConfigurator.configureStartSignal], distinguishing a permanent G4 rear-facing rejection
 * (Issue #903) from any other configuration failure (unsupported START type, or a genuine
 * exception from the underlying signal-configuration call) so the caller can classify the
 * candidate correctly instead of folding both into ordinary contention.
 *
 * @since Issue #903
 */
internal enum class StartSignalResult {
	/** The start signal/inSemaphore was configured (or treated as already configured). */
	Configured,

	/** G4: an adjacent semaphore START faces away from the requested direction of travel. */
	RejectedG4,

	/** Any other failure: unsupported START type, or a genuine configuration exception. */
	ConfigFailed
}

/**
 * Signal configuration for a route [DefaultPathReservationService] has just reserved: the START
 * separator's aspect (fresh candidate, already-owned early return, bypass re-light) and every
 * facing intermediate semaphore between two consecutive reserved blocks. Each aspect it clears
 * is recorded in [ledger], so a release can return it to STOP.
 *
 * Extracted from [DefaultPathReservationService] by Issue #1169 (SP12.3) as a pure move: which
 * semaphores are cleared, the direction guards, and the order of every signal write are
 * unchanged. The service stays the only caller.
 *
 * **Review round (2026-10-24):** [configureStartSignal]'s two when-case bodies moved verbatim
 * into [configureSemaphoreStartSignal] and [configureInOutStartSignal] so the when stays a
 * dispatch table. Write order and log messages are unchanged.
 *
 * **NOT thread-safe**, like the service it belongs to.
 *
 * @property environment Simulation environment the signal writes go through
 * @property ledger Cleared-signal ledger every aspect cleared here is recorded in
 * @since Issue #1169 (moved out of DefaultPathReservationService)
 */
internal class RouteSignalConfigurator(
	private val environment: SimulationEnvironment,
	private val ledger: ClearedSignalLedger
) {
	/**
	 * Configure (or skip) the START separator's signal for the already-owned early-return branch of
	 * [DefaultPathReservationService.reservePath]: every block in the requested path is already owned by [trainId], so
	 * there is nothing to reserve, only the START signal to (re-)confirm.
	 *
	 * Extracted from [DefaultPathReservationService.reservePath] so the candidate-loop body stays under the cyclomatic
	 * complexity threshold (mirrors [configureStartSignal]'s extraction for the same reason).
	 *
	 * - START is a [DynamicRailSemaphore] that faces away from [blocks]' first entry (Issue
	 *   #893 task A1, G4): SKIP -- the grant already exists and there is nothing to roll back,
	 *   so unlike [configureStartSignal]'s rejection this only leaves the semaphore un-lit.
	 * - START is a [DynamicRailSemaphore] facing the travel direction: configure it, idempotent.
	 * - START is a [DynamicInOut]: configure its embedded `inSemaphore`, idempotent.
	 * - Any other START type: nothing to configure.
	 *
	 * @param start The candidate's start separator (semaphore or InOut).
	 * @param blocks The already-owned path's full block list (first one drives the signal).
	 * @since Issue #893 task A1 (extracted alongside the G4 rear-facing-START guard)
	 */
	fun configureAlreadyOwnedStartSignal(
		trainId: String,
		start: DynamicPathSeparator,
		blocks: List<DynamicTrackBlock>
	) {
		when {
			start is DynamicRailSemaphore && !startFacesTravelDirection(start, blocks.first()) -> {
				logger.debug {
					"reservePath: Left already-owned START semaphore ${start.name} at STOP for " +
						"$trainId - the route passes it from behind"
				}
			}
			start is DynamicRailSemaphore -> {
				environment.configureSemaphoreSignal(start, blocks.first())
				ledger.recordClearedSemaphore(trainId, start)
			}
			start is DynamicInOut -> {
				val firstBlock = blocks.first()
				val maxSpeed = firstBlock.maxSpeed(start)
				start.inSemaphore.setUpSpeed(
					from = start.direction(),
					to =
						cz.vutbr.fit.interlockSim.objects.core
							.anti(start.direction()),
					allowedSpeed = maxSpeed
				)
				ledger.recordClearedSemaphore(trainId, start.inSemaphore)
			}
		}
	}

	/**
	 * Configure the START separator's signal for a freshly reserved candidate (Step 2g).
	 *
	 * Extracted from [DefaultPathReservationService.reservePath] so the candidate-loop body stays under the cyclomatic
	 * complexity threshold. Returns [StartSignalResult.Configured] when the start signal/inSemaphore was configured for
	 * the reserved candidate, and a specific failure variant otherwise — in which case the caller rolls the candidate
	 * back via [DefaultPathReservationService.rollbackUnconfigurableCandidate] and continues to the next candidate.
	 *
	 * - START is a [DynamicRailSemaphore]: configure it for [startBlock], the path's first block --
	 *   the one the semaphore actually bounds. The first *forward* block is not that block on a route
	 *   extension (every block next to the start is already owned), and the block rejects a
	 *   separator it does not end, which left the start at STOP (Issue #1062).
	 * - START is a [DynamicInOut]: configure its embedded `inSemaphore` (train entering from
	 *   the external network). `inSemaphore.direction() == anti(InOut.direction())` per
	 *   `InOut.kt`, so `from = InOut.direction()` and `to = anti(InOut.direction())`.
	 * - Any other START type: no signal to configure → [StartSignalResult.ConfigFailed] (rolls
	 *   the candidate back).
	 *
	 * @param start The candidate's start separator (semaphore or InOut).
	 * @param forwardBlocks The blocks just reserved for this candidate (first one drives an
	 *   InOut START's allowed speed).
	 * @param startBlock The first block of the whole candidate path, adjacent to [start]; drives a
	 *   semaphore START's direction check and allowed speed.
	 * @return [StartSignalResult.Configured] on success; a failure variant otherwise.
	 * @since Issue #742 SP0.11 review follow-up (extracted from reservePath Step 2g); return
	 *   type widened from `Boolean` to [StartSignalResult] by Issue #903.
	 */
	fun configureStartSignal(
		trainId: String,
		start: DynamicPathSeparator,
		forwardBlocks: List<DynamicTrackBlock>,
		startBlock: DynamicTrackBlock
	): StartSignalResult =
		when {
			start is DynamicRailSemaphore -> configureSemaphoreStartSignal(trainId, start, startBlock)
			start is DynamicInOut -> configureInOutStartSignal(trainId, start, forwardBlocks)
			else -> StartSignalResult.ConfigFailed
		}

	/**
	 * Semaphore branch of [configureStartSignal]: the START is a [DynamicRailSemaphore] the train
	 * departs from. A START facing away from the travel direction is rejected as
	 * [StartSignalResult.RejectedG4] before anything is configured or recorded (G4, Issue #893
	 * task A1), and any configuration exception maps to [StartSignalResult.ConfigFailed].
	 *
	 * Extracted from [configureStartSignal] in the 2026-10-24 review round so the when stays a
	 * dispatch table; body moved verbatim, write order and log messages unchanged.
	 */
	private fun configureSemaphoreStartSignal(
		trainId: String,
		start: DynamicRailSemaphore,
		startBlock: DynamicTrackBlock
	): StartSignalResult {
		// Issue #893 task A1 (G4): a START that faces away from the requested direction
		// of travel has no proceed authority to give -- the block the train needs to
		// enter lies behind the semaphore's facing, not ahead of it. Unlike an
		// intermediate semaphore (configureIntermediateSemaphores, PR #892), the START
		// is authority-defining: granting the route anyway would strand the train with
		// no signal it is entitled to obey, a #566-class stall. Reject before
		// configuring/recording anything, so the caller's rollback has nothing to undo.
		return if (!startFacesTravelDirection(start, startBlock)) {
			logger.debug {
				"reservePath: Rejected START semaphore ${start.name} for $trainId - it faces " +
					"away from the requested direction of travel toward $startBlock"
			}
			StartSignalResult.RejectedG4
		} else {
			try {
				environment.configureSemaphoreSignal(start, startBlock)
				ledger.recordClearedSemaphore(trainId, start)
				logger.debug {
					"reservePath: Configured START semaphore ${start.name} to ${start.signal}"
				}
				StartSignalResult.Configured
			} catch (e: Exception) {
				logger.warn(e) {
					"reservePath: Semaphore signal configuration failed - rolling back reservation"
				}
				StartSignalResult.ConfigFailed
			}
		}
	}

	/**
	 * InOut branch of [configureStartSignal]: the START is a [DynamicInOut]; its embedded
	 * inSemaphore is configured (`from` = the InOut's direction, `to` = its anti) for a train
	 * entering from the external network, at [firstBlock]'s max speed. A boundary the InOut
	 * does not literally bound (route extension, Issue #911) is treated as already configured,
	 * and any configuration exception maps to [StartSignalResult.ConfigFailed].
	 *
	 * Extracted from [configureStartSignal] in the 2026-10-24 review round so the when stays a
	 * dispatch table; body moved verbatim, write order and log messages unchanged.
	 */
	private fun configureInOutStartSignal(
		trainId: String,
		start: DynamicInOut,
		forwardBlocks: List<DynamicTrackBlock>
	): StartSignalResult {
		val firstBlock = forwardBlocks.first()
		// Issue #911: mirror startFacesTravelDirection's fail-open tolerance for a
		// route extension's non-adjacent start (Issue #893 task A1). A route extension
		// re-invokes reservePath with the ORIGINAL start; once every block adjacent to
		// it is already owned, forwardBlocks.first() is several hops away and is not
		// literally an end of this InOut's track, so maxSpeed(start) throws
		// SimulationException[FATAL] instead of returning a value. There is nothing new
		// to configure at a boundary this InOut does not bound -- the adjacent block
		// already carries the speed set by the original grant
		// (ClearedSignalLedger.resetUnrecordedStartSignal's KDoc documents this
		// exact steady state) -- so treat the START as already configured rather than
		// failing the whole candidate. A genuine configuration failure below (e.g.
		// setUpSpeed itself throwing) still fails closed, unchanged.
		val maxSpeed =
			try {
				firstBlock.maxSpeed(start)
			} catch (e: SimulationException) {
				logger.debug(e) {
					"reservePath: Could not resolve maxSpeed for InOut START ${start.name} " +
						"toward $firstBlock (likely non-adjacent, e.g. a route extension); " +
						"treating as already configured"
				}
				return StartSignalResult.Configured
			}
		return try {
			// Call setUpSpeed directly - inSemaphore is not a track end, it's embedded
			// For valid direction: from=anti(inSem.dir), to=inSem.dir
			// Since inSem.dir=anti(InOut.dir), this becomes: from=InOut.dir, to=anti(InOut.dir)
			start.inSemaphore.setUpSpeed(
				from = start.direction(), // InOut's direction
				to =
					cz.vutbr.fit.interlockSim.objects.core
						.anti(start.direction()),
				// Anti = inSemaphore's direction
				allowedSpeed = maxSpeed
			)
			ledger.recordClearedSemaphore(trainId, start.inSemaphore)
			logger.debug {
				"reservePath: Configured InOut ${start.name} inSemaphore to ${start.inSemaphore.signal}"
			}
			StartSignalResult.Configured
		} catch (e: Exception) {
			logger.warn(e) {
				"reservePath: InOut inSemaphore configuration failed - rolling back reservation"
			}
			StartSignalResult.ConfigFailed
		}
	}

	/**
	 * Re-configure a [DynamicRailSemaphore] START after [DefaultPathReservationService.reservePathToAnyNextSemaphore]
	 * has validated that the inner [DefaultPathReservationService.reservePath] call's [reservedBlocks] actually
	 * traverse the required `next` block.
	 *
	 * Extracted from [DefaultPathReservationService.reservePathToAnyNextSemaphore] to keep that method under the
	 * cyclomatic/length complexity thresholds (mirrors [configureStartSignal]'s own extraction for the same reason).
	 *
	 * Issue #893 task A5 (documented TDD exception -- see the approved SDD plan for phase alpha gap G3; no reachable
	 * failing test exists for this branch, since it only ever repeats a decision the inner
	 * [DefaultPathReservationService.reservePath] call above already made). Fixed anyway: re-lighting here without
	 * [ClearedSignalLedger.recordClearedSemaphore] leaves the signal outside `clearedSemaphores` /
	 * `semaphoreClearedFor` forever, so no release path can ever find it to reset. Guarded with
	 * [facesDirectionOfTravel] (not the tolerant [startFacesTravelDirection] -- `reservedBlocks.first()` is always the
	 * path's genuine first hop from [start], so it is always structurally adjacent and `getSegment` cannot throw here),
	 * for symmetry with the A1 rear-facing-START rejection: re-lighting blindly would resurrect a signal
	 * [configureStartSignal] or [configureAlreadyOwnedStartSignal] deliberately left at STOP.
	 *
	 * A no-op for a non-semaphore [start] (InOut semaphores are constant) or an empty
	 * [reservedBlocks] list.
	 */
	fun configureBypassStartSignal(
		trainId: String,
		start: DynamicPathSeparator,
		reservedBlocks: List<DynamicTrackBlock>
	) {
		if (start !is DynamicRailSemaphore || reservedBlocks.isEmpty()) return
		val firstBlock = reservedBlocks.first()
		if (!facesDirectionOfTravel(start, firstBlock)) {
			logger.debug {
				"reservePathToAnyNextSemaphore: Left START semaphore ${start.name} at STOP for " +
					"$trainId - it faces away from the requested direction of travel"
			}
			return
		}
		environment.configureSemaphoreSignal(start, firstBlock)
		ledger.recordClearedSemaphore(trainId, start)
		logger.debug {
			"reservePathToAnyNextSemaphore: Configured START semaphore ${start.name} to ${start.signal}"
		}
	}

	/**
	 * `true` when a train moving into [nextBlock] passes [semaphore] head-on (the semaphore
	 * faces the movement), `false` when it passes it from behind.
	 *
	 * ## Why a rear-passed semaphore must not be cleared (the `doB` defect)
	 *
	 * `vyhybna.xml` is bidirectional, so a reserved route routinely runs past semaphores that
	 * face the other way. An `A → B` route is governed by `zA` and `doB1`/`doB2` and runs past
	 * `doA1`/`doA2` and `zB`; a `B → A` route is the mirror image. Clearing the rear-passed ones
	 * is wrong twice over:
	 *
	 * - **The train never reads them.** `Train.separatorAction` only calls `semaphoreAction`
	 *   when `isSeparatorInDirection()` holds, so a rear-passed semaphore contributes nothing
	 *   to the movement it was cleared for.
	 * - **It authorises the opposing movement.** A train approaching from the other side reads
	 *   `signal.isAllowing()` directly (it is the reservation holder, so it deliberately does
	 *   not consult [DynamicRailSemaphore.isAllowingFor]) and takes a proceed aspect meant for
	 *   nobody. That aspect is also the one that never returns to danger, because the reset at
	 *   the end of `semaphoreAction` sits on exactly the branch that was skipped.
	 *
	 * ## Relationship to Issue #566
	 *
	 * [DynamicRailSemaphore.checkPathSegments] deliberately accepts both segment orderings, so
	 * a rear-side pairing is a *valid* thing to ask for; this guard decides only that the
	 * reservation flow does not ask for it. The Issue #566 stall it was protecting against — a
	 * granted route no train could drive — cannot come back, precisely because the train does
	 * not wait on a semaphore it passes from behind.
	 *
	 * Falls back to `true` (clear it, the pre-existing behaviour) when the environment cannot
	 * resolve segments, so an unexpected environment type degrades to the old semantics rather
	 * than silently stranding routes at STOP.
	 */
	private fun facesDirectionOfTravel(
		semaphore: DynamicRailSemaphore,
		nextBlock: DynamicTrackBlock
	): Boolean {
		val context =
			environment as? cz.vutbr.fit.interlockSim.context.SimulationContext
				?: return true
		return semaphoreFacesNextBlock(context, semaphore, nextBlock)
	}

	/**
	 * [facesDirectionOfTravel], tolerant of a [nextBlock] that cannot be resolved (Issue #893
	 * task A1, Issue #1062).
	 *
	 * Both START-signal call sites ([configureStartSignal] and the already-owned early-return branch of
	 * [DefaultPathReservationService.reservePath]) hand this the FIRST block of the whole requested path, which is the
	 * block next to [semaphore]. That holds on a route **extension** too: the extension re-invokes `reservePath` with
	 * the ORIGINAL start separator and every block next to it is already owned, but the path's first block is still the
	 * one the separator bounds. So the direction check runs on extensions and a rear-facing START is rejected there
	 * (G4), like on a fresh route. (Before Issue #1062 the call sites passed the first *forward* block, which is not
	 * next to the start on an extension; [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.getSegment] threw
	 * for it and this guard fell open.)
	 *
	 * The `catch` stays as a fail-open safety net ("cannot resolve -> proceed, never strand the route") for a pair that
	 * is unexpectedly not adjacent, without changing [facesDirectionOfTravel]'s contract or its callers within
	 * [configureIntermediateSemaphores] (which only ever pass a genuinely adjacent pair). A START that does not touch
	 * the requesting train's footprint at all is refused earlier by the A-R1 contiguity predicate
	 * ([DefaultPathReservationService.rejectNonContiguousStart]).
	 */
	private fun startFacesTravelDirection(
		semaphore: DynamicRailSemaphore,
		nextBlock: DynamicTrackBlock
	): Boolean =
		try {
			facesDirectionOfTravel(semaphore, nextBlock)
		} catch (e: Exception) {
			logger.debug(e) {
				"reservePath: Could not resolve whether START semaphore ${semaphore.name} faces " +
					"$nextBlock (unexpectedly not adjacent); treating as facing " +
					"the travel direction"
			}
			true
		}

	/**
	 * Configure the signal for every semaphore that lies at the junction between
	 * two consecutive blocks in the reserved path **and faces the direction of travel**.
	 *
	 * When [DefaultPathReservationService.reservePath] reserves a path spanning multiple blocks (e.g. InOut A →
	 * semaphore → InOut B), step 2g only sets the signal for the *start* separator. Any intermediate semaphore keeps
	 * its default STOP signal, causing the train to halt at that separator and never reach its destination.
	 *
	 * This method walks the ordered list of reserved blocks and, for each
	 * consecutive pair, finds the common end-point separator.  If that separator is
	 * a [DynamicRailSemaphore] it is configured to ALLOW in the direction of the
	 * *next* block (i.e. the block the train will enter after passing the
	 * semaphore).
	 *
	 * The call is idempotent — re-setting a signal that is already ALLOW is safe.
	 * Failures are non-fatal and are logged at WARN level by
	 * [SimulationEnvironment.configureSemaphoreSignal].
	 *
	 * A semaphore the route passes from behind is deliberately left at STOP — see
	 * [facesDirectionOfTravel].
	 *
	 * A route **extension** (this train already owns a prefix of [blocks] from an earlier
	 * [DefaultPathReservationService.reservePath] call, and this call adds new blocks beyond it) must not touch a
	 * boundary strictly between two blocks the train already owns: the train may already have passed that semaphore and
	 * returned it to STOP itself ([cz.vutbr.fit.interlockSim.sim.Train]'s `semaphoreAction` does exactly that on facing
	 * passage). Re-lighting it here would resurrect a proceed aspect behind the train's head — authorising nobody,
	 * since the train never reads a semaphore it has already passed, while an opposing movement might. Only a boundary
	 * whose next block is still part of the *new* portion of the route is eligible for configuration; [forwardBlocks]
	 * is exactly that new portion, as computed by [DefaultPathReservationService.reservePath] (blocks not already owned
	 * by [trainId]).
	 *
	 * @param trainId Train the aspects are cleared on behalf of, recorded so
	 *                [ClearedSignalLedger.resetClearedSemaphores] can return them to STOP when the route is released.
	 * @param blocks Ordered list of [DynamicTrackBlock] objects from path start to target.
	 *               Must be in traversal order (the order produced by
	 *               [DefaultPathReservationService.extractUniqueBlocks] from a BFS/DFS path).
	 * @param forwardBlocks The subset of [blocks] not already owned by [trainId] (the newly
	 *                      reserved portion of the route). A boundary is only configured when
	 *                      the block it leads into is a member of this set.
	 */
	fun configureIntermediateSemaphores(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		forwardBlocks: Set<DynamicTrackBlock>
	) {
		if (blocks.size < 2) return
		for (i in 0 until blocks.size - 1) {
			val currentBlock = blocks[i]
			val nextBlock = blocks[i + 1]
			// The boundary leads into a block the train already owns -- it sits strictly
			// between two blocks this train already holds, so it must be left exactly as it
			// is (the train may already have passed it and returned it to STOP itself).
			if (nextBlock !in forwardBlocks) {
				continue
			}
			// Find the shared end-point between the two consecutive blocks.
			// DynamicTrackBlock.ends() returns Array<PathSeparator> whose elements
			// are the DynamicPathSeparator instances shared across the graph.
			for (end in currentBlock.ends()) {
				if (end is DynamicRailSemaphore && nextBlock.ends().contains(end)) {
					// A semaphore facing against the direction of travel governs the OPPOSING
					// movement, not this one. Clearing it authorises nobody useful and is
					// actively unsafe -- see [facesDirectionOfTravel].
					if (!facesDirectionOfTravel(end, nextBlock)) {
						logger.debug {
							"reservePath: Left intermediate semaphore ${end.name} at STOP for $trainId - " +
								"the route passes it from behind (between block $i and block ${i + 1})"
						}
						continue
					}
					// `end` sits between currentBlock and nextBlock; configure it so
					// the train can pass from currentBlock into nextBlock.
					environment.configureSemaphoreSignal(end, nextBlock)
					ledger.recordClearedSemaphore(trainId, end)
					logger.debug {
						"reservePath: Configured intermediate semaphore ${end.name} to ALLOW " +
							"(between block $i and block ${i + 1})"
					}
				}
			}
		}
	}
}
