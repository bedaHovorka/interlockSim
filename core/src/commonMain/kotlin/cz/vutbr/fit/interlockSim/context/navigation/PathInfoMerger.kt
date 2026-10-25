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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.MergeOutcome
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KLogger

private val logger: KLogger = registryLogger

/**
 * The PathInfo extension algorithm of [PathReservationRegistry.registerPathInfo]: how a newly
 * granted route is appended to the route a train already holds, and the four fail-safe aborts that
 * keep the stored route unchanged instead. Split out of the registry by Issue #1172.
 *
 * Stateless: it reads and writes no registry map; [PathInfoStore] stores what it returns.
 *
 * @param context Simulation context, needed to build the merged [ArrayPath]
 * @since Issue #1172 (the algorithm since Issue #296 Phase 8, #316, #834, #904, #944)
 */
internal class PathInfoMerger(
	private val context: SimulationContext
) {
	/**
	 * Rejects a new route that reverses the train over the section it has just travelled.
	 *
	 * A train arrives at `old.target` over the last [TrackSection] of the stored route and must
	 * leave it over a *different* one. When the new route's first section is that same section,
	 * the merged path doubles back on itself: the train would have to reverse direction mid-route,
	 * which is not a route extension at all but a distinct shunting movement the interlocking
	 * never authorised.
	 *
	 * **Why the occurrence counter in [addElementWithCycleDetection] cannot catch this.** That
	 * guard counts how often a separator appears and only rejects the 3rd occurrence, treating a
	 * 2nd as a legitimate circular route. Both a genuine loop and a reversal revisit separators
	 * twice, so counting cannot tell them apart. Direction can: a train continuing around a loop
	 * leaves a separator by a different section than it arrived on, a reversing train by the same
	 * one.
	 *
	 * **The measured deadlock (Issue #944).** In `exampleGui shuntingLoopAI 333`, Train #4
	 * (running B → A) was granted `zB → doA2`, then `doA2 → zB`. Each of `doB2`, `vB` and `zB`
	 * reached only two occurrences, so the merge was accepted and logged as a "LEGITIMATE
	 * CIRCULAR ROUTE", storing an out-and-back path that retraces section `k2` in the opposite
	 * direction. Nothing can follow such a path:
	 * [DefaultTrainNavigationService.buildPathWithDirection] walks the reservation, revisits a
	 * `(separator, previous)` pair, finds the cycle exit rear-facing and returns `null` — surfaced
	 * as [PathResult.OwnershipConflict], on which the train waits while holding its blocks. That
	 * wait is now bounded (Issue #943: one WARN after
	 * [cz.vutbr.fit.interlockSim.sim.Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS], then
	 * `errorStop`), so the same defect would now be named in the log instead of stalling silently —
	 * but it was unbounded when this was measured. Train #4 stalled at `zB` holding the blocks
	 * Train #5 needed to leave `doB1`, and the
	 * layout deadlocked: the dispatcher observed "no reservable sections ahead" for both trains
	 * and correctly no-opped for the rest of the run.
	 *
	 * Path elements are per-block [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock]
	 * singletons, so a retraced section is the same instance on both sides of the seam and `==`
	 * compares exactly (verified against `vyhybna.xml`: every forward/backward separator pair yields
	 * identical element identity).
	 *
	 * **Interaction with commanded reversal.** `Train.reverseDirection()` (GitHub #62) is a
	 * distinct operation — the train must be at a standstill and a delay models the driver
	 * changing cab — and it has no production call site today, so it never reaches this guard.
	 * Should it gain one, the train's stored authority must be **cleared** at that point and a
	 * fresh route requested from its current position, rather than this guard being relaxed: a
	 * reversal is a new movement requiring its own clearance, never an extension of the authority
	 * granted for the outbound journey. Retaining the outbound path across a reversal would make
	 * the first post-reversal route look exactly like the defect this guard exists to refuse.
	 *
	 * @return the abort reason, or `null` when the new route continues the stored one forward.
	 */
	private fun reversalAbortReason(
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): String? {
		val arrivedOver = old.reservedPath.filterIsInstance<TrackSection>().lastOrNull() ?: return null
		val departsOver = new.reservedPath.filterIsInstance<TrackSection>().firstOrNull() ?: return null
		if (arrivedOver != departsOver) return null
		return "merge for train $trainId would reverse it at ${old.target} back over $arrivedOver, " +
			"the section it just travelled"
	}

	private fun addElementWithCycleDetection(
		element: PathElement,
		mergedPath: ArrayPath,
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): String? {
		if (element is PathSeparator &&
			mergedPath.any { it == element }
		) {
			val occurrenceCount = mergedPath.count { it == element }
			if (occurrenceCount >= 2) {
				// 3rd occurrence — infinite loop detected (Issue #316)
				val reason =
					"merge for train $trainId would create cycle (separator $element at 3+ occurrences)"
				logger.warn {
					"mergePathInfo: $reason. Keeping existing valid PathInfo unchanged. " +
						"(old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
				}
				return reason
			}
			// 2nd occurrence — legitimate circular route
			logger.info {
				"mergePathInfo: LEGITIMATE CIRCULAR ROUTE - separator $element appears ${occurrenceCount + 1}x " +
					"in path (train '$trainId' progressing through circular shunting loop). " +
					"Allowing (old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
			}
		}
		mergedPath.add(element)
		return null
	}

	/**
	 * Merge two PathInfo instances by extending old path with new path
	 * ([PathReservationRegistry.registerPathInfo]'s `mergePathInfo` before Issue #1172).
	 *
	 * ## This method NEVER throws (Issue #834)
	 *
	 * Every rejection is a **fail-safe abort**: log a WARN and `return old` unchanged. There are
	 * four of them — non-contiguous start, duplicated new start, the direction reversal guard
	 * ([reversalAbortReason], Issue #944), and the 3rd-occurrence cycle guard — and they all
	 * behave identically from the caller's point of view.
	 *
	 * This is not stylistic. `registerPathInfo` is called from
	 * [DefaultPathReservationService.reservePath] Step 2i and from its already-owned early return,
	 * in both cases **after** blocks are reserved, switches locked and a START signal cleared, on
	 * the kDisco simulation thread, with no rollback and nothing catching a throw
	 * (`DispatchDecisionApplier.onControlStep` catches only `IllegalArgumentException`). An
	 * exception here therefore did not fail a run loudly — it killed the simulation thread while
	 * the run still wrote a well-formed result file, i.e. it fabricated a measurement.
	 *
	 * **Transactional completion at abort time (Issue #904).** An abort here no longer leaves an
	 * orphaned RESERVED tail: [DefaultPathReservationService.reservePath] inspects the returned
	 * [MergeOutcome] at both of its call sites (Step 2i, and the already-owned early return) and,
	 * on [MergeOutcome.Aborted], releases exactly what *that* `reservePath` invocation acquired
	 * for the rejected candidate — blocks, switch locks, and the signal it cleared, driven back
	 * to STOP (G1) — using the same scoped-rollback helpers Step 2g's own signal-failure rollback
	 * already uses. This applies the PR #901 standard (no exit from `reservePath` leaves resources
	 * unreleased without rollback) to the one exit that previously didn't meet it, and it holds in
	 * every host — `:fast-sim` and bare-`:core` included — because it no longer depends on
	 * anything outside this call.
	 *
	 * **`OrphanReservationSweeper`'s role is narrower now, not gone.** Before this fix, the sweeper
	 * (in `dispatcher-agent`, wired only in `desktop-ui`/`dispatcher-agent` via `ExampleRegistry`)
	 * was the *only* mechanism that ever reclaimed a merge-abort's orphaned tail — and only in the
	 * hosts that wire it; `:fast-sim` and bare-`:core` never reclaimed it at all. The sweeper still
	 * has an independent, legitimate job: reclaiming a route held un-travelled past its staleness
	 * threshold and phantom-owner routes (a train that exited without releasing). It is simply no
	 * longer relied on for *this* residue, because there is none left to reclaim.
	 *
	 * ## Algorithm
	 *
	 * 0. Abort unless the new path continues the stored one (new.start == old.target)
	 * 1. Abort unless new.start appears exactly once in new.reservedPath
	 * 1b. Abort if the new route reverses the train back over the section it just travelled
	 * 2. Create new ArrayPath and add all elements from old path
	 * 3. Find overlap point (old.target == new.start)
	 * 4. Add elements from new path, skipping first occurrence if it overlaps
	 * 5. Distinguish legitimate circular routes from infinite loop bugs
	 * 6. Merge entry directions (new overwrites old for same blocks)
	 *
	 * ## Example
	 *
	 * ```
	 * old: B → zB → vB → doB1  (Tail at B, Front at doB1)
	 * new: doB1 → k1 → A       (Front reserves forward to A)
	 * merged: B → zB → vB → doB1 → k1 → A  (complete path for both Front and Tail)
	 * ```
	 *
	 * ## Preconditions (each one aborts the merge rather than throwing)
	 *
	 * - `new.start == old.target` — the new path must CONTINUE the stored one. A path that starts
	 *   elsewhere is not merged: concatenating it yields two adjacent `PathSeparator`s, which every
	 *   navigation reader handles safely (`ArrayPath.getNext` returns `deque[i+2]` only when it
	 *   `is TrackSection`, so it returns `null`; `DefaultTrainNavigationService`'s
	 *   `determineNextFromPathInfo` hits its `is PathSeparator -> null` arm) — so there is no
	 *   teleport and no weakening of the `allowingSignal` gate, navigation simply truncates at
	 *   `old.target`. The damage is the orphaned RESERVED tail described above, not a safety
	 *   violation. Note this is a *merge* precondition only: it is unrelated to
	 *   `RouteCandidateScreen.rejectNonContiguousStart`, which tests the route origin
	 *   against the train's physical block footprint and must stay permissive enough for entry,
	 *   bidirectional reversal (PR #356) and post-partial-release re-reservation.
	 * - `new.start` appears exactly ONCE in `new.reservedPath` (at the beginning)
	 * - Circular routes are ALLOWED if train completes one full loop back to original start
	 * - Repeated cycles (>1 loop) are REJECTED to prevent infinite loops
	 *
	 * ## Circular Route Handling
	 *
	 * **Shunting Loop Scenario (LEGITIMATE):**
	 * - Train starts at A, travels A → B → C → A (one complete loop)
	 * - old.start = A, new path returns to A
	 * - This is ALLOWED: train completes circular shunting operation
	 *
	 * **Infinite Loop Bug (REJECTED):**
	 * - Train oscillates: A → B → A → B → A (repeated back-and-forth)
	 * - Separator would appear 3+ times in merged path
	 * - The entire merge is ABORTED and the original `old` PathInfo is returned unchanged
	 *   (Issue #316 fix: a truncated PathInfo ending mid-path is worse than keeping the valid original)
	 *
	 * ## Entry Direction Merging
	 *
	 * When old and new have the same block with different entry directions,
	 * the NEW direction overwrites the old (most recent direction is used).
	 *
	 * ## Resource Safety
	 *
	 * `merge()` itself is still a **pure data-structure operation** — it does not acquire
	 * or release track blocks or switches. All resource locking happens in
	 * `DefaultPathReservationService.reservePath()` via `registerAtomic()` and
	 * `registerSwitches()`, which use their own tracking maps (`trainToBlocks` /
	 * `trainToSwitches`), independent of PathInfo.
	 *
	 * **PathInfo / block divergence — resolved by the caller (Issue #904):** When this method
	 * returns [MergeOutcome.Aborted], the train's `trainToBlocks` entry momentarily contains the
	 * newly reserved blocks (step 2d in `reservePath()`) while `trainToPathInfo` still holds the
	 * pre-merge value — but `reservePath` itself resolves that divergence before returning to its
	 * own caller: it inspects the [MergeOutcome], sees the abort, and releases exactly those newly
	 * reserved blocks (and any switches locked, and any signal cleared) for the rejected candidate.
	 * The divergence therefore never outlives the `reservePath` call that created it — there is no
	 * window in which `trainToBlocks` and `trainToPathInfo` disagree from any other caller's point
	 * of view, and nothing is left for a later sweep to reclaim.
	 *
	 * @param trainId Owner of the PathInfo being merged (used in the abort WARNs)
	 * @param old Previous PathInfo (Tail may still be navigating through this)
	 * @param new New PathInfo (Front just reserved this)
	 * @return [MergeOutcome.Merged] wrapping the merged PathInfo, or [MergeOutcome.Aborted]
	 *         wrapping [old] unchanged if the merge was aborted: [new] does not start at
	 *         `old.target`, `new.start` is duplicated inside [new], or the merge would create a
	 *         third occurrence of a separator (cycle guard). Never throws.
	 * @since Issue #296 Phase 8; never-throw abort semantics from Issue #834 (SP2c.11); return
	 *   type widened from `PathInfo` to [MergeOutcome] by Issue #904.
	 */
	fun merge(
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): MergeOutcome {
		logger.trace {
			"mergePathInfo: merging old path ${old.start}->${old.target} " +
				"with new ${new.start}->${new.target} for '$trainId'"
		}

		val preconditionReason = preconditionAbortReason(trainId, old, new)
		if (preconditionReason != null) {
			logger.warn {
				"mergePathInfo: aborting $preconditionReason. Keeping existing valid PathInfo unchanged. " +
					"(old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
			}
			return MergeOutcome.Aborted(old, preconditionReason)
		}

		// Steps 1-4: old path, then new path without the shared separator, cycle-checked
		val mergedPath = ArrayPath(context)
		val cycleAbortReason = concatenate(trainId, old, new, mergedPath)
		if (cycleAbortReason != null) return MergeOutcome.Aborted(old, cycleAbortReason)

		// Step 5: Merge entry directions (new overwrites old for conflicts)
		val mergedDirections = old.entryDirections.toMutableMap()
		mergedDirections.putAll(new.entryDirections)

		logger.trace {
			"mergePathInfo: merged ${old.reservedPath.size} + ${new.reservedPath.size} " +
				"elements into ${mergedPath.size} elements " +
				"(overlap: ${if (new.start == old.target) "yes" else "no"})"
		}

		return MergeOutcome.Merged(
			PathInfo(
				start = old.start, // Keep original start (where Tail might still be)
				target = new.target,
				reservedPath = mergedPath,
				entryDirections = mergedDirections
			)
		)
	}

	/**
	 * Steps 0a-0c of [merge]: the fail-safe checks that run before anything is concatenated.
	 *
	 * @return the first abort reason, or `null` when [new] may be merged onto [old]
	 */
	private fun preconditionAbortReason(
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): String? {
		// Step 0a (Issue #834): the new path must CONTINUE the stored one. Concatenating a path
		// that starts somewhere else produces two adjacent separators; navigation truncates there
		// safely, so the train never teleports, but everything past the seam becomes an orphaned
		// RESERVED tail behind a cleared aspect the train will never reach. Abort fail-safe.
		// Issue #904: the caller now releases exactly what it acquired for this attempt, so the
		// tail is no longer orphaned -- it never gets a chance to persist.
		nonContiguousReason(trainId, old, new)?.let { return it }

		// Step 0b: new.start must occur exactly once in its own path (see "Preconditions" above).
		// Until Issue #834 this threw IllegalStateException — from reservePath Step 2i, i.e. after
		// blocks were reserved and a signal was already cleared, on the kDisco simulation thread,
		// with nothing catching it. Abort the same fail-safe way instead; never throw from here.
		val occurrences = new.reservedPath.count { it == new.start }
		if (occurrences != 1) {
			return "merge with duplicated new start for train $trainId — ${new.start} appears " +
				"$occurrences times in the new path, expected exactly 1 at its beginning"
		}

		// Step 0c: the new route must not send the train back over the section it just
		// travelled (Issue #944). See [reversalAbortReason] for why occurrence counting alone
		// cannot catch this.
		return reversalAbortReason(trainId, old, new)
	}

	/**
	 * Steps 1-4 of [merge]: append [old], then [new] without its first separator (already
	 * `old.target`), into [mergedPath].
	 *
	 * @return the cycle abort reason, or `null` when every element was appended
	 */
	private fun concatenate(
		trainId: String,
		old: PathInfo,
		new: PathInfo,
		mergedPath: ArrayPath
	): String? {
		// Step 2: Add all elements from old path
		old.reservedPath.forEach { mergedPath.add(it) }

		// Step 3: Find overlap point (old.target == new.start). Always true past Step 0a; kept as an
		// explicit predicate so the skip below reads on its own terms rather than on the guard's.
		val skipFirst = (new.start == old.target)
		var skipped = false

		// Step 4: Add elements from new path (skip first separator if overlapping, detect cycles)
		for (element in new.reservedPath) {
			if (skipFirst && !skipped && element == new.start) {
				skipped = true // Skip this occurrence (already in old path)
				logger.trace {
					"mergePathInfo: skipping overlap element $element (old.target == new.start)"
				}
			} else {
				val cycleAbortReason = addElementWithCycleDetection(element, mergedPath, trainId, old, new)
				if (cycleAbortReason != null) return cycleAbortReason
			}
		}
		return null
	}

	/**
	 * Step 0a's predicate and wording, shared by [merge] and
	 * [PathReservationRegistry.nonContinuingCandidateReason].
	 */
	fun nonContiguousReason(
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): String? =
		if (new.start != old.target) {
			"non-contiguous merge for train $trainId — new path starts at ${new.start} but " +
				"the stored path ends at ${old.target}"
		} else {
			null
		}
}
