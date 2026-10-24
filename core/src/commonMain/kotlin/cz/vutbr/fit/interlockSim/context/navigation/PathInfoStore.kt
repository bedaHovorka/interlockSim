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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.PathInfoEnd
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KLogger

private val logger: KLogger = registryLogger

/**
 * PathInfo bookkeeping of [PathReservationRegistry]: the route each train is driving
 * (Train ID -> [PathInfo] with its entry directions), how a new grant extends it (through
 * [PathInfoMerger]), and how it is cut back after a partial release. Split out of the registry by
 * Issue #1172; the registry delegates every PathInfo operation here and keeps the public contract.
 *
 * PathInfo is the train's **intended path** and is semantically separate from its **current
 * reservations** (Issue #301): releasing a block never removes or shortens it here. Only the trim
 * operations read the held blocks, through [heldBlocks].
 *
 * **NOT thread-safe**, like the registry.
 *
 * @param context Simulation context, needed to build trimmed [ArrayPath]s and to tell which way a
 *   semaphore faces
 * @param heldBlocks the blocks a train holds (empty if none) -- the registry passes
 *   [BlockOwnershipTable.getBlocks]
 * @since Issue #1172 (the logic since Issue #295/#296, #893, #1050, #1063, #1066, #1067)
 */
internal class PathInfoStore(
	private val context: SimulationContext,
	private val heldBlocks: (String) -> List<DynamicTrackBlock>
) {
	private val merger = PathInfoMerger(context)

	/** Train ID -> PathInfo metadata (Issue #295/#296 Phase 3). */
	private val trainToPathInfo = mutableMapOf<String, PathInfo>()

	/**
	 * Register PathInfo for a train.
	 *
	 * ## PathInfo Extension (Issue #296 Phase 8)
	 *
	 * Instead of overwriting the old PathInfo, this method **extends** it by merging:
	 * - Appends new path elements to old path (preserves Tail navigation)
	 * - Updates target to new target (allows Front to progress)
	 * - Merges entry directions (new overwrites old for conflicts)
	 *
	 * This fixes the "Train Tail Double-Leave Bug" where overwriting PathInfo caused
	 * the Tail to lose track of its position, leading to wrong-direction navigation.
	 *
	 * @param trainId The train identifier
	 * @param newPathInfo Complete path metadata to register or merge
	 * @return [MergeOutcome.Merged] on a genuine merge (or first registration); [MergeOutcome.Aborted]
	 *   when [PathInfoMerger.merge]'s fail-safe abort fired and the stored `PathInfo` is unchanged.
	 * @since Issue #295/#296 Phase 3
	 * @since Issue #296 Phase 8 (PathInfo extension fix)
	 * @since Issue #904 (return type widened from `Unit` to [MergeOutcome])
	 */
	fun register(
		trainId: String,
		newPathInfo: PathInfo
	): MergeOutcome {
		val oldPathInfo = trainToPathInfo[trainId]

		if (oldPathInfo == null) {
			// First registration, just store it
			logger.debug {
				"registerPathInfo: first registration for '$trainId', storing PathInfo " +
					"(start=${newPathInfo.start}, target=${newPathInfo.target}, " +
					"path length=${newPathInfo.reservedPath.size})"
			}
			trainToPathInfo[trainId] = newPathInfo
			return MergeOutcome.Merged(newPathInfo)
		}

		// Merge old and new PathInfo (trainId is used only in the abort WARNs)
		val outcome = merger.merge(trainId, oldPathInfo, newPathInfo)
		val mergedPathInfo = outcome.pathInfo
		trainToPathInfo[trainId] = mergedPathInfo

		logger.debug {
			"registerPathInfo: merged PathInfo for '$trainId' " +
				"(old: ${oldPathInfo.start}→${oldPathInfo.target}, " +
				"new: ${newPathInfo.start}→${newPathInfo.target}, " +
				"merged: ${mergedPathInfo.start}→${mergedPathInfo.target}, " +
				"path length: ${oldPathInfo.reservedPath.size} + ${newPathInfo.reservedPath.size} " +
				"= ${mergedPathInfo.reservedPath.size})"
		}

		// Log merged PathInfo (debug level for normal operation)
		logger.debug {
			"registerPathInfo: Created/merged PathInfo for '$trainId': " +
				"start=${mergedPathInfo.start}, target=${mergedPathInfo.target}, " +
				"path length=${mergedPathInfo.reservedPath.size}"
		}
		return outcome
	}

	/**
	 * Check if path extends beyond the given separator.
	 *
	 * Prevents redundant reservation attempts by dispatcher while allowing path extensions.
	 * This implements an idempotent check similar to the working tag's `isSetUpPath()` pattern.
	 *
	 * ## Usage Pattern
	 *
	 * ```kotlin
	 * // Before attempting path extension from a semaphore:
	 * if (registry.isPathExtendedBeyond(trainId, currentSemaphore)) {
	 *     // Path already extends beyond this semaphore, skip reservation
	 *     return true
	 * }
	 * // Otherwise, proceed with extension
	 * pathReservationService.reservePathToAnyNextSemaphore(trainId, currentSemaphore)
	 * ```
	 *
	 * ## Path Extension Check
	 *
	 * A path is considered extended beyond a separator if:
	 * 1. PathInfo exists for this train
	 * 2. The separator appears in the reserved path
	 * 3. There is at least one more element AFTER the separator in the path
	 *
	 * This allows the dispatcher to extend paths that END at a semaphore but prevents
	 * redundant reservations when the path already extends beyond it.
	 *
	 * ## Benefits
	 *
	 * - Prevents dispatcher from repeatedly calling reservePathToAnyNextSemaphore()
	 * - Allows path extensions when train is approaching a semaphore
	 * - Reduces cycle detection trigger rate
	 * - Matches working tag's idempotent behavior
	 * - Cheap O(n) check (linear scan of path elements)
	 *
	 * @param trainId Train identifier
	 * @param separator Semaphore/separator to check
	 * @return true if path already extends beyond this separator, false otherwise
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Dispatcher State Tracking
	 */
	fun isPathExtendedBeyond(
		trainId: String,
		separator: DynamicPathSeparator
	): Boolean {
		val pathInfo = trainToPathInfo[trainId] ?: return false

		// Find the separator in the path
		val separatorIndex = pathInfo.reservedPath.indexOfLast { it == separator }
		if (separatorIndex == -1) {
			// Separator not in path
			return false
		}

		// Path extends beyond if there are elements after the separator
		val extendsBeyond = separatorIndex < pathInfo.reservedPath.size - 1

		if (extendsBeyond) {
			logger.debug {
				"Path already extends beyond $separator for '$trainId' " +
					"(${pathInfo.reservedPath.size} elements, separator at index $separatorIndex)"
			}
		}

		return extendsBeyond
	}

	/** The PathInfo stored for [trainId], or `null`. */
	fun get(trainId: String): PathInfo? = trainToPathInfo[trainId]

	/** Drop the PathInfo of [trainId] (whole-route teardown). */
	fun remove(trainId: String) {
		trainToPathInfo.remove(trainId)
	}

	/**
	 * Read-only probe of [PathInfoMerger.merge]'s Step 0a precondition (Issue #1066): would [candidate]
	 * CONTINUE the route [trainId] currently holds?
	 *
	 * Lets `DefaultPathReservationService.reservePath` refuse a divergent candidate BEFORE it
	 * reserves blocks, throws switches or clears signals, instead of discovering the same
	 * condition at Step 2i and rolling everything back. Changes no state.
	 *
	 * @return `null` when [candidate] continues the stored path, or when the train has no stored
	 *   PathInfo yet (a first registration is always accepted); otherwise an English reason naming
	 *   the stored target and the candidate's start.
	 * @since Issue #1066
	 */
	fun nonContinuingCandidateReason(
		trainId: String,
		candidate: PathInfo
	): String? {
		val old = trainToPathInfo[trainId] ?: return null
		return merger.nonContiguousReason(trainId, old, candidate)
	}

	/**
	 * Restore a train's PathInfo to a previously snapshotted value, bypassing [register]'s
	 * merge logic.
	 *
	 * ## Purpose: transaction rollback only
	 *
	 * `registerPathInfo` is merge-only by design (Issue #296 Phase 8), and the merge is NOT
	 * invertible — it has cycle-guard abort semantics and entry-direction overwrites that make a
	 * "subtract the last registration" operation ill-defined. A caller that must undo a registration
	 * therefore snapshots the PathInfo via [get] BEFORE attempting the mutation, and passes
	 * that snapshot here on rollback:
	 *
	 * - Non-null snapshot → replaces the current entry with the snapshot object (exact restore,
	 *   not a merge).
	 * - Null snapshot → removes the entry entirely (the train had no PathInfo before the attempt).
	 *
	 * The sole production caller is the bypass-rollback in
	 * `DefaultPathReservationService.reservePathToAnyNextSemaphore`: a candidate that committed its
	 * reservation and merged its PathInfo is rejected after the fact, and its contribution must be
	 * removed exactly — a merged-through-the-rejected-route PathInfo would steer the train onto
	 * track that was just released.
	 *
	 * Do NOT use this for general PathInfo editing: route extensions belong in [register]
	 * so the merge invariants (overlap handling, cycle guard) keep applying.
	 *
	 * @param trainId The train identifier
	 * @param snapshot The value previously read via [get], or `null` when the train had
	 *   no PathInfo before the rolled-back attempt
	 * @since Issue #893 follow-up (PR #901 review — bypass-rollback transactional completion)
	 */
	fun restore(
		trainId: String,
		snapshot: PathInfo?
	) {
		if (snapshot == null) {
			trainToPathInfo.remove(trainId)
			logger.debug { "restorePathInfo: removed PathInfo entry for '$trainId' (no pre-attempt PathInfo)" }
		} else {
			trainToPathInfo[trainId] = snapshot
			logger.debug {
				"restorePathInfo: restored PathInfo for '$trainId' to snapshot " +
					"(start=${snapshot.start}, target=${snapshot.target}, path length=${snapshot.reservedPath.size})"
			}
		}
	}

	/**
	 * Cut a train's stored PathInfo back so that it ends at [boundary], after the blocks beyond that
	 * separator were released.
	 *
	 * ## Why
	 *
	 * [PathReservationRegistry.unregisterBlock] deliberately keeps the PathInfo (Issue #301): the
	 * train still needs it to navigate the blocks it holds. After a partial release of an
	 * un-travelled tail, however, the untrimmed PathInfo still describes the released track.
	 * [isPathExtendedBeyond] reads only the PathInfo, so it keeps reporting the route as extended
	 * beyond the head's exit signal: the rule engine skips the train, the LLM is told "route already
	 * set", and a new route from that signal aborts in [PathInfoMerger.merge] Step 0a because the
	 * stored path still ends further on. The train then stands for the rest of the run (Issue #1063,
	 * the permanent stall of Issue #1031).
	 *
	 * This is not the whole-route deletion of Issue #301: the head keeps its PathInfo, only the
	 * released tail goes.
	 *
	 * ## Contract
	 *
	 * The trimmed PathInfo keeps [PathInfo.start], ends at the first occurrence of [boundary] after
	 * the last block the train still holds, and keeps only the entry directions of blocks that
	 * remain on the trimmed path. Nothing is changed and `false` is returned when the train has no
	 * PathInfo, holds no block on it, or [boundary] does not follow its last held block — trimming
	 * there would drop track the train still owns. It is also refused when [boundary] is a switch:
	 * a route runs signal to signal and a switch is never its end (Issue #938), so a PathInfo ending
	 * at one would make every later extension fail the merge. It is likewise refused when [boundary]
	 * is a semaphore facing away from the train's direction of travel (Issue #1067 gap 2): the
	 * trimmed PathInfo's end is about to become the START of the train's next route request, and G4
	 * ([RouteSignalConfigurator.startFacesTravelDirection]) refuses every route starting at a
	 * rear-facing signal, which would strand the train exactly like the untrimmed PathInfo did.
	 *
	 * @param trainId The train identifier
	 * @param boundary The separator where the train's held track now ends
	 * @return `true` if the stored PathInfo now ends at [boundary], `false` if it was left unchanged
	 * @since Issue #1063
	 */
	fun trimPathInfoTo(
		trainId: String,
		boundary: DynamicPathSeparator
	): Boolean {
		val pathInfo = trainToPathInfo[trainId] ?: return false
		val elements = pathInfo.reservedPath.toList()
		val boundaryIndex = trimBoundaryIndex(trainId, pathInfo, elements, boundary) ?: return false
		if (!isValidPathInfoEnd(boundary, elements.getOrNull(boundaryIndex + 1)?.let(::blockOf))) {
			logger.warn {
				"trimPathInfoTo: not trimming the PathInfo of '$trainId' to $boundary — a route cannot end " +
					"there (a switch, or a signal facing away from the train), so the PathInfo is kept " +
					"(path ${pathInfo.start}→${pathInfo.target})"
			}
			return false
		}
		if (boundaryIndex == elements.lastIndex) return true

		trainToPathInfo[trainId] = pathInfo.truncatedAt(elements, boundaryIndex, boundary, context)
		logger.info {
			"trimPathInfoTo: trimmed the PathInfo of '$trainId' from ${pathInfo.start}→${pathInfo.target} " +
				"to ${pathInfo.start}→$boundary after its tail beyond $boundary was released"
		}
		return true
	}

	/**
	 * Whether [separator] may be the end of a stored PathInfo, the separator a train's next route
	 * request starts from.
	 *
	 * - A switch never may: a route runs signal to signal and a switch is never its end (Issue #938),
	 *   so a PathInfo ending at one would make every later extension fail the merge.
	 * - A semaphore facing away from [nextBlock] (the block the train would enter past it) never may
	 *   (Issue #1067 gap 2): G4 refuses every route starting at a rear-facing signal, so the train
	 *   would stand exactly as with an untrimmed PathInfo. Without a [nextBlock] the direction is
	 *   unknown and only the switch rule applies.
	 *
	 * [trimPathInfoTo] refuses such an end. A caller that must not change anything unless the trim
	 * will follow checks it first, with the end from [pathInfoEndAfter].
	 *
	 * @since Issue #1063
	 */
	fun isValidPathInfoEnd(
		separator: DynamicPathSeparator,
		nextBlock: DynamicTrackBlock? = null
	): Boolean =
		when {
			separator is DynamicRailSwitch -> false
			separator is DynamicRailSemaphore && nextBlock != null ->
				semaphoreFacesNextBlock(context, separator, nextBlock)
			else -> true
		}

	/**
	 * Where the stored PathInfo of [trainId] would end if the train kept only [kept]: the separator
	 * right after the last element of the path whose block is in [kept], and the block after that
	 * separator (`null` when the separator is the path's target). Pure query.
	 *
	 * @return `null` when the train has no PathInfo, no block of [kept] lies on it, or no separator
	 *   follows the last kept block
	 * @since Issue #1067
	 */
	fun pathInfoEndAfter(
		trainId: String,
		kept: Collection<DynamicTrackBlock>
	): PathInfoEnd? {
		val elements = trainToPathInfo[trainId]?.reservedPath?.toList() ?: return null
		val lastKeptIndex = lastIndexOfBlockIn(elements, kept)
		if (lastKeptIndex < 0) return null
		val boundary = elements.getOrNull(lastKeptIndex + 1) as? DynamicPathSeparator ?: return null
		return PathInfoEnd(boundary, elements.getOrNull(lastKeptIndex + 2)?.let(::blockOf))
	}

	/**
	 * The index of [boundary] in [elements] that [trimPathInfoTo] may cut at, or `null` (with a WARN)
	 * when [boundary] does not follow the last block the train still holds.
	 */
	private fun trimBoundaryIndex(
		trainId: String,
		pathInfo: PathInfo,
		elements: List<PathElement>,
		boundary: DynamicPathSeparator
	): Int? {
		val held = heldBlocks(trainId)
		val lastHeldIndex = lastIndexOfBlockIn(elements, held)
		val boundaryIndex =
			if (lastHeldIndex < 0) {
				null
			} else {
				(lastHeldIndex + 1 until elements.size).firstOrNull { elements[it] == boundary }
			}
		if (boundaryIndex == null) {
			logger.warn {
				"trimPathInfoTo: not trimming the PathInfo of '$trainId' to $boundary — the separator does " +
					"not follow the last block the train still holds, or the path passes one of those blocks twice " +
					"(path ${pathInfo.start}→${pathInfo.target}, ${held.size} block(s) held)"
			}
		}
		return boundaryIndex
	}

	/**
	 * [trimPathInfoTo], but finds the boundary itself: the separator immediately following the
	 * last block the train still holds in the stored PathInfo.
	 *
	 * ## Why (Issue #1067 gap 1)
	 *
	 * [RegistryPartialRouteReleaser] used to derive the boundary from the ends shared by the
	 * occupied head and the blocks it released **in that one call**. A tail released over two
	 * sweeps — one block refused or throwing on the first sweep, freed on a later retry — then
	 * finds no boundary on the sweep that finally frees it: that block is no longer adjacent to
	 * the occupied head, only to the block released earlier. The trim was skipped with a warning
	 * and never retried, reproducing the permanent stall [trimPathInfoTo] exists to prevent.
	 * Deriving the boundary from the blocks the train currently holds — rather than from what
	 * changed in the current call — makes the operation idempotent: whichever sweep frees the
	 * last remaining tail block computes the same boundary and the trim goes through.
	 *
	 * @param trainId The train identifier
	 * @return `true` if the stored PathInfo now ends at the boundary, `false` if there was nothing
	 *   to trim ([pathInfoEndAfter] found no end: no PathInfo, or no held block on it) or
	 *   [trimPathInfoTo] refused it (see [isValidPathInfoEnd])
	 * @since Issue #1067
	 */
	fun trimPathInfoToHeldBlocks(trainId: String): Boolean {
		val end = pathInfoEndAfter(trainId, heldBlocks(trainId))
		if (end == null) {
			logger.debug {
				"trimPathInfoToHeldBlocks: nothing to trim for '$trainId' — it has no PathInfo, or holds no " +
					"block on it"
			}
			return false
		}
		return trimPathInfoTo(trainId, end.boundary)
	}

	/**
	 * Whether freeing every block of [trainId] except [kept] would leave its stored PathInfo
	 * consistent: pure query, changes nothing.
	 *
	 * `true` when no trim is needed (no PathInfo, or [kept] reaches the path's end so nothing
	 * beyond it can go stale) or when the trim [trimPathInfoTo] would perform will follow --
	 * the boundary past the last kept block passes [isValidPathInfoEnd]. A caller that must
	 * not change anything unless the trim will follow ([DefaultPathReservationService]'s
	 * approach-locked release) checks this BEFORE freeing any block: a freed tail that
	 * survives in the PathInfo because the trim is refused later stalls the train with
	 * `OwnershipConflict` (Issue #1050 review round).
	 *
	 * `false` when [kept] holds no block on the path (the whole PathInfo would be a stale
	 * tail with no derivable boundary) or the boundary is one [trimPathInfoTo] refuses.
	 *
	 * @since Issue #1050
	 */
	fun canTrimPathInfoTo(
		trainId: String,
		kept: Collection<DynamicTrackBlock>
	): Boolean {
		val elements = trainToPathInfo[trainId]?.reservedPath?.toList() ?: return true
		val lastKeptIndex = lastIndexOfBlockIn(elements, kept)
		if (lastKeptIndex < 0) return false
		if (lastKeptIndex == elements.lastIndex) return true
		val boundary = elements[lastKeptIndex + 1] as? DynamicPathSeparator ?: return false
		val nextBlock = elements.getOrNull(lastKeptIndex + 2)?.let(::blockOf)
		return isValidPathInfoEnd(boundary, nextBlock)
	}

	/** Drop every stored PathInfo. */
	fun clear() {
		trainToPathInfo.clear()
	}
}

/** The block a [PathInfo.reservedPath] element belongs to, or `null` when the element is a separator. */
private fun blockOf(element: PathElement): DynamicTrackBlock? =
	(element as? TrackSection)?.getTrackBlock() as? DynamicTrackBlock

/**
 * The index of the last element of [elements] whose block is in [blocks], or `-1` when there is none.
 *
 * Also `-1` when a block of [blocks] appears in two separate places (a merged circular route may pass a
 * block twice). "The last occurrence" is then ambiguous — it can lie ahead of the train rather than
 * under it — and a trim there would drop track the train still holds (PR #1068 review), so the caller
 * finds no boundary and refuses.
 */
private fun lastIndexOfBlockIn(
	elements: List<PathElement>,
	blocks: Collection<DynamicTrackBlock>
): Int {
	val blockSet = blocks.toSet()
	val seen = mutableSetOf<DynamicTrackBlock>()
	var previous: DynamicTrackBlock? = null
	var lastIndex = -1
	elements.forEachIndexed { index, element ->
		val block = blockOf(element)
		if (block == null) {
			// A separator ends a place: a block right after it again (a route that turns back into
			// the block it came from) is a second place, not a continuation (PR #1068 review).
			if (element is DynamicPathSeparator) previous = null
			return@forEachIndexed
		}
		if (block in blockSet) {
			if (block != previous && !seen.add(block)) return -1
			lastIndex = index
		}
		previous = block
	}
	return lastIndex
}

/**
 * A copy of this PathInfo that ends at [boundary], the element at [boundaryIndex] of [elements] (this
 * PathInfo's reserved path). It keeps [PathInfo.start] and only the entry directions of blocks that
 * stay on the shorter path.
 */
private fun PathInfo.truncatedAt(
	elements: List<PathElement>,
	boundaryIndex: Int,
	boundary: DynamicPathSeparator,
	context: SimulationContext
): PathInfo {
	val kept = elements.subList(0, boundaryIndex + 1)
	val keptBlocks = kept.mapNotNull(::blockOf).toSet()
	return copy(
		target = boundary,
		reservedPath = ArrayPath(context).apply { kept.forEach { add(it) } },
		entryDirections = entryDirections.filterKeys { it in keptBlocks }
	)
}
