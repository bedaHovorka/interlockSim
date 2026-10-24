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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The cleared-signal ledger of [DefaultPathReservationService]: which semaphore shows a proceed
 * aspect on which train's behalf, and the resets that return those aspects to [Signal.STOP]
 * when a route (or part of one) is released or a candidate is rolled back.
 *
 * Extracted from [DefaultPathReservationService] by Issue #1169 (SP12.3) as a pure move: the
 * bookkeeping, its ownership semantics and the order of every reset are unchanged. The service
 * owns exactly one instance per simulation context, and its writes come only from inside the
 * kernel: the service itself, the [RouteReleaser] release/rollback callbacks the service binds
 * to it, and [RouteSignalConfigurator], which records the aspects it clears here.
 *
 * **NOT thread-safe**, like the service it belongs to.
 *
 * @since Issue #1169 (moved out of DefaultPathReservationService)
 */
internal class ClearedSignalLedger {
	// ── Cleared-signal ownership, so a proceed aspect cannot outlive its route ──
	//
	// reservePath() clears the START separator (configureStartSignal) and every semaphore
	// between two consecutive reserved blocks (configureIntermediateSemaphores). Nothing used
	// to undo that: releasePath()/unregister() cancelled blocks and unlocked switches but never
	// touched a semaphore, so an aspect cleared for a route that was later released stayed lit
	// for the rest of the run -- authorising an opposing train onto track the interlocking
	// believed free. Seen live on `exampleGui shuntingLoopAI 333`: the A→B route granted at
	// t=26.0 cleared zA/doA1/doB1, the OrphanReservationSweeper cancelled it at t=88.0, and all
	// three still showed S80 when the run ended.
	//
	// Only the SUCCESS path records here. Two kinds of rollback need to reach a semaphore
	// nonetheless:
	// - `rollbackReservation` and the switch-config-failure call to `rollbackUnconfigurableCandidate`
	//   (from `configureAndRegisterSwitches`) run strictly BEFORE step 2g signal configuration --
	//   a candidate rolled back for either reason has cleared nothing and needs no reset.
	// - The signal-config-failure call to `rollbackUnconfigurableCandidate` (step 2g's own `!signalConfigured` branch)
	//   runs AFTER `configureStartSignal` was attempted, and a PARTIAL aspect write is possible: the underlying config
	//   call can set the physical aspect and still throw before `recordClearedSemaphore` runs (Issue #893, task A5).
	//   That branch resets the before/after delta of this map via `resetSemaphoreSet` (for any semaphore that WAS
	//   recorded) and separately drives the candidate START itself back to STOP when it is left showing proceed with no
	//   recorded owner -- see [DefaultPathReservationService.reservePath] step 2g and the reset helper it calls for the
	//   unrecorded case.
	// A third case, the bypass-rollback in `reservePathToAnyNextSemaphore`, runs AFTER a fully
	// successful `reservePath` has already configured the candidate's signals; it resets exactly
	// the semaphores that candidate cleared (the before/after delta of this map) via
	// `resetSemaphoreSet`, never the whole per-train set, so a pre-existing reservation's signals
	// stay lit.
	//
	// Ownership is last-writer-wins -- unlike block ownership, where registerAtomic rejects a
	// foreign reservation instead of reassigning it (the same contrast
	// PathReservationService.recordExternalClearedSemaphore states): if a semaphore is re-cleared
	// for another train, that train becomes its owner and the earlier train's release leaves it
	// alone. Without this a stale release could drop a signal to STOP under a train actively
	// running against it.
	private val clearedSemaphores: MutableMap<String, MutableSet<DynamicRailSemaphore>> = mutableMapOf()

	private val semaphoreClearedFor: MutableMap<DynamicRailSemaphore, String> = mutableMapOf()

	/**
	 * Record that [semaphore] now shows a proceed aspect on [trainId]'s behalf, so
	 * [resetClearedSemaphores] can return it to [Signal.STOP] when the route is released.
	 *
	 * A semaphore that did not actually end up allowing is not recorded: `configureSemaphoreSignal`
	 * swallows configuration failures (logging at WARN), and a `ConstantSemaphore` (an InOut's
	 * `outSemaphore`, predzvěst, narážník) has a no-op setter that never changes. "Is it lit as a
	 * result of this call?" is therefore the only reliable test that something needs undoing.
	 */
	fun recordClearedSemaphore(
		trainId: String,
		semaphore: DynamicRailSemaphore
	) {
		if (!semaphore.signal.isAllowing()) return
		clearedSemaphores.getOrPut(trainId) { mutableSetOf() }.add(semaphore)
		semaphoreClearedFor[semaphore] = trainId
	}

	/**
	 * Return every semaphore still owned by [trainId] to [Signal.STOP] and forget them.
	 *
	 * [Signal.STOP] is always the fail-safe direction -- it authorises nothing -- so resetting
	 * a semaphore the train no longer needs can only ever be over-restrictive. Semaphores since
	 * re-cleared for another train are skipped (see [semaphoreClearedFor]).
	 */
	fun resetClearedSemaphores(trainId: String) {
		val owned: Set<DynamicRailSemaphore> = clearedSemaphores.remove(trainId) ?: return
		owned.forEach { semaphore ->
			if (semaphoreClearedFor[semaphore] != trainId) {
				logger.debug {
					"resetClearedSemaphores: ${semaphore.name} was re-cleared for " +
						"'${semaphoreClearedFor[semaphore]}'; leaving it lit for that train"
				}
				return@forEach
			}
			semaphoreClearedFor.remove(semaphore)
			try {
				semaphore.signal = Signal.STOP
			} catch (e: Exception) {
				logger.warn(e) {
					"resetClearedSemaphores: Failed to reset semaphore ${semaphore.name} for '$trainId'"
				}
			}
		}
		logger.debug {
			"resetClearedSemaphores: Returned ${owned.size} semaphore(s) to STOP for '$trainId'"
		}
	}

	/**
	 * Return only the semaphores in [toReset] still owned by [trainId] to [Signal.STOP] and forget
	 * them, leaving every other semaphore the train still holds cleared untouched.
	 *
	 * Subset analogue of [resetClearedSemaphores]. Used by the bypass-rollback in
	 * [DefaultPathReservationService.reservePathToAnyNextSemaphore]: that rollback runs *after* a successful
	 * [DefaultPathReservationService.reservePath] has already cleared the candidate's semaphores, so resetting the
	 * whole per-train set would also drop signals the train still needs for a live reservation. The caller passes the
	 * before/after delta of [clearedSemaphores] -- exactly what this candidate cleared -- so a pre-existing
	 * reservation's signals are preserved.
	 *
	 * Same ownership semantics as [resetClearedSemaphores]: a semaphore since re-cleared for another
	 * train is skipped (see [semaphoreClearedFor]).
	 */
	fun resetSemaphoreSet(
		trainId: String,
		toReset: Set<DynamicRailSemaphore>
	) {
		if (toReset.isEmpty()) return
		val owned = clearedSemaphores[trainId] ?: return
		var resetCount = 0
		toReset.forEach { semaphore ->
			// Forget this semaphore in the CALLER's ledger entry regardless of which branch
			// runs below -- ownership having moved on means trainId doesn't hold it any more
			// either way, so leaving it in `owned` would strand a stale entry that keeps
			// hasClearedSignals(trainId) reporting true forever (Issue #893 final-review F2).
			owned.remove(semaphore)
			if (semaphoreClearedFor[semaphore] != trainId) {
				logger.debug {
					"resetSemaphoreSet: ${semaphore.name} was re-cleared for " +
						"'${semaphoreClearedFor[semaphore]}'; leaving it lit for that train"
				}
				return@forEach
			}
			semaphoreClearedFor.remove(semaphore)
			try {
				semaphore.signal = Signal.STOP
				resetCount++
			} catch (e: Exception) {
				logger.warn(e) {
					"resetSemaphoreSet: Failed to reset semaphore ${semaphore.name} for '$trainId'"
				}
			}
		}
		if (owned.isEmpty()) clearedSemaphores.remove(trainId)
		logger.debug {
			"resetSemaphoreSet: Returned $resetCount of ${toReset.size} semaphore(s) to STOP for '$trainId'"
		}
	}

	/**
	 * Return a candidate route's START separator to [Signal.STOP] when
	 * [RouteSignalConfigurator.configureStartSignal] wrote a partial aspect that [recordClearedSemaphore]
	 * never got a chance to record (Issue #893, task A5).
	 *
	 * `DefaultSimulationContext.configureSemaphoreSignal` can set the physical aspect and still
	 * throw before returning -- `configureStartSignal`'s try/catch then reports failure with the
	 * semaphore already lit, but `recordClearedSemaphore` sits AFTER that call and never runs, so
	 * [resetSemaphoreSet]'s before/after delta is empty and cannot find it. This is the fallback
	 * that catches exactly that unrecorded case.
	 *
	 * ## Why [clearedBeforeStart] is required (review fix, Issue #893 task A5)
	 *
	 * A route EXTENSION re-invokes [DefaultPathReservationService.reservePath] with the train's ORIGINAL start. If that
	 * start was already legitimately cleared and recorded for THIS SAME train by an earlier successful call,
	 * `semaphoreClearedFor[semaphore] == trainId` already -- the plain ownership check alone cannot distinguish
	 * "already lit for me from an earlier hop" from "just written by THIS failed attempt". Without
	 * [clearedBeforeStart], a failed extension attempt would drive an earlier, still-governing signal back to STOP and
	 * purge its bookkeeping, stranding the train behind its own signal while its earlier blocks stay registered. The
	 * snapshot -- taken before this attempt's own [RouteSignalConfigurator.configureStartSignal] call -- lets this
	 * function reset ONLY a semaphore that is genuinely new to this attempt.
	 *
	 * A no-op when [start] is not a semaphore-bearing separator, when it is not showing proceed,
	 * when it was already present in [clearedBeforeStart] (this attempt did not write it), or
	 * when it is owned by a DIFFERENT train (same ownership semantics as
	 * [resetClearedSemaphores]/[resetSemaphoreSet] -- never drop a signal a live reservation
	 * still needs).
	 *
	 * @param trainId The train whose failed candidate is being rolled back
	 * @param start The candidate's start separator (semaphore or InOut)
	 * @param clearedBeforeStart Snapshot of `clearedSemaphores[trainId]` taken before this
	 *   attempt's [RouteSignalConfigurator.configureStartSignal] call -- a semaphore already present here predates this
	 *   attempt and must be left alone
	 */
	fun resetUnrecordedStartSignal(
		trainId: String,
		start: DynamicPathSeparator,
		clearedBeforeStart: Set<DynamicRailSemaphore>
	) {
		val semaphore =
			when (start) {
				is DynamicRailSemaphore -> start
				is DynamicInOut -> start.inSemaphore
				else -> return
			}
		if (!semaphore.signal.isAllowing()) return
		if (semaphore in clearedBeforeStart) {
			logger.debug {
				"resetUnrecordedStartSignal: ${semaphore.name} was already cleared for '$trainId' " +
					"before this attempt (route extension reusing its original START); leaving it lit"
			}
			return
		}
		val owner = semaphoreClearedFor[semaphore]
		if (owner != null && owner != trainId) {
			logger.debug {
				"resetUnrecordedStartSignal: ${semaphore.name} is owned by '$owner', not '$trainId'; " +
					"leaving it lit for that train"
			}
			return
		}
		try {
			semaphore.signal = Signal.STOP
			semaphoreClearedFor.remove(semaphore)
			val owned = clearedSemaphores[trainId]
			owned?.remove(semaphore)
			if (owned != null && owned.isEmpty()) clearedSemaphores.remove(trainId)
			logger.debug {
				"resetUnrecordedStartSignal: Reset partially-written START semaphore " +
					"${semaphore.name} to STOP for '$trainId'"
			}
		} catch (e: Exception) {
			logger.warn(e) {
				"resetUnrecordedStartSignal: Failed to reset semaphore ${semaphore.name} for '$trainId'"
			}
		}
	}

	/**
	 * `clearedSemaphores[trainId]`, defaulting to an empty set. Extracted so every snapshot call site in
	 * [DefaultPathReservationService.reservePath] stays branch-free (a bare elvis `?:` at the call site would otherwise
	 * count against that method's own cyclomatic complexity).
	 */
	fun snapshotClearedSemaphores(trainId: String): Set<DynamicRailSemaphore> =
		clearedSemaphores[trainId]?.toSet() ?: emptySet()

	/**
	 * Reset exactly the semaphores THIS candidate cleared since [clearedBeforeCandidate] (Issue #893 task A5 / Issue
	 * #904): the before/after delta of [clearedSemaphores] (covers anything recorded), then [start] itself for the
	 * unrecorded partial-write case [resetSemaphoreSet]'s delta cannot see. Extracted so every rollback call site in
	 * [DefaultPathReservationService.reservePath] stays a single line.
	 */
	fun resetCandidateSignals(
		trainId: String,
		start: DynamicPathSeparator,
		clearedBeforeCandidate: Set<DynamicRailSemaphore>
	) {
		resetSemaphoreSet(trainId, snapshotClearedSemaphores(trainId) - clearedBeforeCandidate)
		resetUnrecordedStartSignal(trainId, start, clearedBeforeCandidate)
	}

	/**
	 * The already-owned early-return branch's merge-abort handling (Issue #904, traffic-simulation-expert ruling Q2):
	 * this branch acquires no new blocks or switches, so an abort has nothing to roll back on that side. The only
	 * discrepancy is a signal this call may have just (re-)cleared for a merge that never happened; reset it back to
	 * STOP (G1) if the merge aborted, otherwise do nothing. Extracted so the call site in
	 * [DefaultPathReservationService.reservePath] stays branch-free.
	 */
	fun resetSignalsIfMergeAborted(
		mergeOutcome: PathReservationRegistry.MergeOutcome,
		trainId: String,
		start: DynamicPathSeparator,
		clearedBeforeCandidate: Set<DynamicRailSemaphore>
	) {
		if (mergeOutcome !is PathReservationRegistry.MergeOutcome.Aborted) return
		resetCandidateSignals(trainId, start, clearedBeforeCandidate)
	}

	/**
	 * `true` when [trainId] still owns at least one cleared semaphore. `clearedSemaphores` never
	 * holds an empty set for a key -- every removal path ([resetClearedSemaphores],
	 * [resetSemaphoreSet], [resetUnrecordedStartSignal]) deletes the map entry once its set
	 * empties -- so a key-presence check alone is exact.
	 */
	fun hasClearedSignals(trainId: String): Boolean = clearedSemaphores.containsKey(trainId)

	/**
	 * Reset every semaphore this service recorded as cleared for [trainId] that governs one of
	 * [blocks]. See the interface KDoc for why candidates come from both `ends()` and
	 * `reservedFrom` -- the two sources cover, respectively, an INTERMEDIATE boundary between two
	 * blocks of the same reservation, and the route's START (including its InOut form), neither of
	 * which the other source can recover on its own for a multi-block route.
	 *
	 * Delegates the actual reset to [resetSemaphoreSet], so ownership (last-writer-wins) is identical to
	 * [DefaultPathReservationService.releasePath]/[DefaultPathReservationService.unregister]: a semaphore since
	 * re-cleared for another train is left alone.
	 *
	 * See the interface KDoc for this call's proven-safe scope (suffix/rearmost releases on a
	 * non-revisiting route) -- this method performs no route-position validation of its own, so a
	 * caller outside that scope is responsible for the risk described there.
	 *
	 * @since Issue #893 (phase alpha, task A3)
	 */
	fun resetSemaphoresForReleasedBlocks(
		trainId: String,
		blocks: Collection<DynamicTrackBlock>
	) {
		if (blocks.isEmpty()) return
		val candidates = mutableSetOf<DynamicRailSemaphore>()
		blocks.forEach { block ->
			block.ends().forEach { end ->
				when (end) {
					is DynamicRailSemaphore -> candidates.add(end)
					is DynamicInOut -> candidates.add(end.inSemaphore)
					else -> Unit
				}
			}
			when (val reservedFrom = block.reservedFrom) {
				is DynamicRailSemaphore -> candidates.add(reservedFrom)
				is DynamicInOut -> candidates.add(reservedFrom.inSemaphore)
				else -> Unit
			}
		}
		resetSemaphoreSet(trainId, candidates)
	}
}
