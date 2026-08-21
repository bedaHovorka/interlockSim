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

import cz.ksimulantenbande.kdisco.Condition
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.paths.TransitionAwarePath
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of TrainNavigationService.
 *
 * ## Architecture
 *
 * This service ensures trains only navigate through blocks they have reserved by
 * following the PathInfo.reservedPath sequence instead of exploring topology.
 *
 * ## Dependencies
 *
 * - **SimulationContext**: Simulation environment for dynamic type conversion
 * - **PathReservationRegistry**: Provides block ownership information and PathInfo
 *
 * All dependencies are injected via constructor (Koin DI).
 *
 * ## Algorithm (Issue #297 - Fixed Navigation)
 *
 * 1. Get PathInfo for trainId from registry (contains reservedPath)
 * 2. Use PathInfo.reservedPath.getNext() to follow RESERVED blocks only
 * 3. Extract all DynamicTrackBlocks from the built path
 * 4. For each block, validate ownership via registry
 * 5. If ANY block is not owned by trainId, return null (train must wait)
 * 6. If ALL blocks are owned by trainId, return complete path
 *
 * **Key Change:** No topology exploration! Trains follow reservedPath faithfully,
 * eliminating wrong turns at switches (fixes Issue #291 root cause).
 *
 * ## Error Handling
 *
 * This service is **read-only** and does NOT modify state. It simply returns:
 * - **Path object**: All blocks are reserved for this train (success)
 * - **null**: Path doesn't exist, or blocks are reserved for different train (wait)
 *
 * No exceptions are thrown for ownership mismatches - null indicates "wait and retry".
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** Assumes single-threaded access to network state.
 *
 * @param context Simulation environment for dynamic type conversion
 * @param registry Registry for checking block ownership
 * @param verifyEveryEvaluation Debug switch for [createPathAvailableCondition]: when `true`, every
 *   condition test recomputes and compares against the cached answer, throwing on a mismatch.
 *   Off in production — see that method's KDoc.
 * @see TrainNavigationService
 * @since Issue #295 (Phase 3 of Issue #292)
 * @since Issue #296 Phase 5 (Removed indirect recursion)
 * @since Issue #297 (Fixed navigation to use reserved blocks only)
 */
class DefaultTrainNavigationService(
	private val context: SimulationContext,
	private val registry: PathReservationRegistry,
	private val verifyEveryEvaluation: Boolean = false
) : TrainNavigationService {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Epoch value meaning "this condition has never really evaluated".
		 *
		 * [PathReservationRegistry.mutationEpoch] starts at `0` and only grows, so a negative
		 * value can never compare equal to it and the first test always computes.
		 */
		private const val UNSEEN_EPOCH: Long = -1L
	}

	/** Times [createPathAvailableCondition]'s conditions were tested (Issue #931 f2). */
	private var conditionTests: Long = 0L

	/** Times such a test had to run a real [findReservedPathForTrain] (Issue #931 f2). */
	private var realEvaluations: Long = 0L

	override fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator
	): PathResult {
		logger.debug {
			"findReservedPathForTrain: train '$trainId' requesting path from $separator"
		}

		// Step 1: Get PathInfo for this train (Issue #295/#296 Phase 5)
		val pathInfo = registry.getPathInfo(trainId)
		if (pathInfo == null) {
			logger.debug {
				"findReservedPathForTrain: no PathInfo registered for train '$trainId'"
			}
			return if (hasTopologicalContinuation(separator)) {
				PathResult.OwnershipConflict
			} else {
				PathResult.NoTopologicalPath
			}
		}

		// Step 2: Determine next track section from PathInfo
		val dynamicSeparator = context.toDynamic(separator)
		val nextTrackSection = determineNextFromPathInfo(dynamicSeparator, pathInfo)

		// If separator not in PathInfo, return OwnershipConflict (train should wait for proper reservation)
		// Issue #296 Phase 8: Removed fallback mechanism - it returned wrong-direction blocks
		if (nextTrackSection == null) {
			logger.debug {
				"findReservedPathForTrain: separator $separator not in PathInfo, " +
					"returning OwnershipConflict (train should wait for new path reservation)"
			}
			return PathResult.OwnershipConflict
		}

		logger.trace {
			"findReservedPathForTrain: determined next track section from PathInfo: $nextTrackSection"
		}

		// Step 3: Build path using known direction (based on working solution)
		val candidatePath = buildPathWithDirection(dynamicSeparator, nextTrackSection, pathInfo)
		if (candidatePath == null) {
			logger.debug {
				"findReservedPathForTrain: no path found from $separator with direction $nextTrackSection" +
					" — the reserved path does not reach a forward-facing separator yet, so the train waits"
			}
			// Reaching here means the train HAS a PathInfo and a next section, so topology is not in
			// question -- the only genuine topology verdict is the hasTopologicalContinuation check
			// above. What failed is building a movement path out of the CURRENT RESERVATION STATE,
			// which the dispatcher can change at any moment. That is ordinary "wait for my route to
			// be extended", not a permanent fault.
			//
			// The measured case: a train running A -> B is granted `A -> doA1`. `doA1` faces west, so
			// for an eastbound train it is a rear-facing terminus that buildPathWithDirection can
			// never accept; on reaching `zA` the walk exhausts and returns null. Reported as
			// NoTopologicalPath, Train.actions() took its unbounded `hold(5.0)` poll and logged
			// "No topological path exists ... train reached dead-end" every 5 s -- on a network that
			// has no dead end at all, for a train whose route was simply not extended yet. Reported
			// as OwnershipConflict it waits on createPathAvailableCondition and resumes the moment
			// the dispatcher extends the route.
			return PathResult.OwnershipConflict
		}

		// Step 3.5: Handle path transitions (Issue #296 Phase 9)
		// When PathInfo instances are merged, train's current TrackSection may be from the old
		// segment and not present in the new path built from the separator forward.
		// Determine the previous track section (the one train just left to arrive at separator)
		val currentTrackSection = getCurrentTrackSection(dynamicSeparator, pathInfo)
		val finalPath =
			if (currentTrackSection != null &&
				currentTrackSection != nextTrackSection &&
				!candidatePath.contains(currentTrackSection)
			) {
				// Train is transitioning from old PathInfo to new merged PathInfo
				// Wrap path to handle getNext(currentTrackSection) correctly
				logger.debug {
					"findReservedPathForTrain: wrapping path for transition from $currentTrackSection"
				}
				TransitionAwarePath(candidatePath, currentTrackSection, nextTrackSection)
			} else {
				candidatePath
			}

		// Step 4: Extract all track blocks from the path
		val blocks = extractDynamicTrackBlocks(finalPath)
		logger.trace {
			"findReservedPathForTrain: candidate path has ${blocks.size} blocks: ${blocks.map { it.toString() }}"
		}

		// Step 5: Validate ALL blocks are reserved for this train
		for (block in blocks) {
			val owner = registry.getOwner(block)
			if (owner != trainId) {
				// Issue #931 f1 demoted this class of per-evaluation log; this one was missed. It
				// fires on every failed evaluation of every waiting train — the single most common
				// outcome for a parked train — so at INFO it dominated the log volume.
				logger.debug {
					"findReservedPathForTrain: block $block is not reserved for train '$trainId' " +
						"(owner: ${owner ?: "none"}), path not available"
				}
				// Block not owned by this train, return OwnershipConflict (train waits)
				return PathResult.OwnershipConflict
			}
		}

		// Step 6: All blocks owned by this train, return complete path
		logger.debug {
			"findReservedPathForTrain: train '$trainId' owns all ${blocks.size} blocks in path, " +
				"path length ${finalPath.length()}"
		}
		return PathResult.Available(finalPath)
	}

	override fun isPathReservedForTrain(
		trainId: String,
		separator: PathSeparator
	): Boolean {
		logger.trace {
			"isPathReservedForTrain: checking availability for train '$trainId' from $separator"
		}

		// Delegate to findReservedPathForTrain and check result type
		val result = findReservedPathForTrain(trainId, separator)
		val isAvailable = result is PathResult.Available

		logger.trace {
			val string = if (isAvailable) "IS" else "IS NOT"
			"isPathReservedForTrain: path $string available for train '$trainId' (result: ${result::class.simpleName})"
		}

		return isAvailable
	}

	override fun reservedSeparatorsAhead(
		trainId: String,
		separator: PathSeparator,
		limit: Int
	): List<OrientedPathSeparator> {
		require(limit >= 0) { "limit must be >= 0: $limit" }
		if (limit == 0) return emptyList()

		val reservedPath = registry.getPathInfo(trainId)?.reservedPath ?: return emptyList()

		// Find the anchor separator on the reserved route (same element == separator matching
		// as determineNextFromPathInfo), then collect subsequent OrientedPathSeparators in order.
		val anchorIndex = reservedPath.indexOfFirst { it == separator }
		if (anchorIndex < 0) {
			logger.trace {
				"reservedSeparatorsAhead: anchor $separator not on reserved path of train '$trainId'"
			}
			return emptyList()
		}

		val result = mutableListOf<OrientedPathSeparator>()
		for (i in (anchorIndex + 1) until reservedPath.size) {
			val element = reservedPath.elementAt(i)
			if (element is OrientedPathSeparator) {
				result.add(element)
				if (result.size == limit) break
			}
		}
		logger.trace {
			"reservedSeparatorsAhead: ${result.size} separator(s) ahead of $separator for train '$trainId'"
		}
		return result
	}

	/**
	 * Determine the next track section from PathInfo based on current separator position.
	 *
	 * ## Algorithm (Issue #295/#296 Phase 5)
	 *
	 * Based on working `pathToNextSemaphore()` (commit 18108fa), which uses:
	 * ```kotlin
	 * var next: TrackSection? = initialNext  // THIS IS THE CRITICAL PARAMETER!
	 * ```
	 *
	 * The PathInfo.reservedPath contains the complete path with track sections in order.
	 * We need to:
	 * 1. Find current separator in the reserved path
	 * 2. Get the NEXT TrackSection element after it
	 * 3. Validate using entryDirections map (cross-check)
	 *
	 * ## Example
	 *
	 * ```
	 * PathInfo.reservedPath: [semaphore1] -> [track_A] -> [separator_X] -> [track_B] -> [semaphore2]
	 *
	 * If separator == separator_X:
	 *   - Find separator_X at index 2
	 *   - Return track_B at index 3
	 * ```
	 *
	 * @param separator Current position (where train is now)
	 * @param pathInfo Complete path metadata with entry directions
	 * @return Next TrackSection to follow, or null if separator not in path or at end
	 */
	private fun determineNextFromPathInfo(
		separator: PathSeparator,
		pathInfo: PathInfo
	): TrackSection? {
		val reservedPath = pathInfo.reservedPath

		logger.trace {
			"determineNextFromPathInfo: searching for $separator in path with ${reservedPath.size} elements"
		}

		// Walk path elements to find separator
		for (i in 0 until reservedPath.size) {
			val element = reservedPath.elementAt(i)

			// Check if this element matches our separator
			if (element == separator) {
				// Found separator! Get next element
				if (i + 1 < reservedPath.size) {
					val nextElement = reservedPath.elementAt(i + 1)
					return when (nextElement) {
						is TrackSection -> {
							logger.trace {
								"determineNextFromPathInfo: found separator at index $i, " +
									"next track section at index ${i + 1}: $nextElement"
							}
							nextElement
						}
						is PathSeparator -> {
							// Target separator reached - this is the end of the reserved path
							logger.debug {
								"determineNextFromPathInfo: Reached target separator $nextElement, " +
									"no more tracks in reserved path for train at $separator"
							}
							null // Expected: train has reached destination
						}
						else -> {
							// Unexpected element type - path structure error
							logger.warn {
								"determineNextFromPathInfo: Unexpected element type after separator: " +
									"${nextElement::class.simpleName}, path may be malformed"
							}
							null // Unexpected structure
						}
					}
				} else {
					// Current separator is the last element in the path
					logger.debug {
						"determineNextFromPathInfo: Current separator $separator is last element " +
							"in reserved path, destination reached"
					}
					return null
				}
			}
		}

		logger.debug {
			"determineNextFromPathInfo: separator $separator not found in reserved path " +
				"(train may have passed this separator already)"
		}
		return null
	}

	/**
	 * Get the TrackSection that comes BEFORE the given separator in PathInfo.
	 *
	 * ## Purpose (Issue #296 Phase 9)
	 *
	 * When PathInfo instances are merged (old + new), the train's current position
	 * might be in the old segment. To detect transition cases, we need to know which
	 * TrackSection the train just left to arrive at the current separator.
	 *
	 * ## Algorithm
	 *
	 * Walk backward through the reservedPath from the separator:
	 * 1. Find the separator in the path
	 * 2. Look at the previous element (index - 1)
	 * 3. If it's a TrackSection, return it (this is the "current" the train is coming from)
	 * 4. Otherwise, return null (separator is first element, or path structure unexpected)
	 *
	 * ## Example
	 *
	 * ```
	 * PathInfo.reservedPath: [start] -> [trackA] -> [separator] -> [trackB] -> [end]
	 *                                      ^          ^
	 *                                      |          |
	 *                                  current    separator (where train is now)
	 *
	 * getCurrentTrackSection(separator, pathInfo) returns trackA
	 * ```
	 *
	 * @param separator Current separator where train is positioned
	 * @param pathInfo Complete path metadata
	 * @return TrackSection before separator, or null if separator is first element
	 */
	private fun getCurrentTrackSection(
		separator: PathSeparator,
		pathInfo: PathInfo
	): TrackSection? {
		val reservedPath = pathInfo.reservedPath

		// Find separator in path
		for (i in 0 until reservedPath.size) {
			val element = reservedPath.elementAt(i)
			if (element == separator) {
				// Found separator! Look at previous element
				if (i > 0) {
					val previous = reservedPath.elementAt(i - 1)
					if (previous is TrackSection) {
						logger.trace {
							"getCurrentTrackSection: found previous track section at index ${i - 1}: $previous"
						}
						return previous
					} else {
						logger.trace {
							"getCurrentTrackSection: element before separator is not TrackSection: " +
								"${previous::class.simpleName}"
						}
						return null
					}
				} else {
					logger.trace {
						"getCurrentTrackSection: separator is first element in path (no previous section)"
					}
					return null
				}
			}
		}

		logger.trace {
			"getCurrentTrackSection: separator $separator not found in reserved path"
		}
		return null
	}

	/**
	 * Build path to next semaphore using reserved blocks from PathInfo.
	 *
	 * ## Algorithm (Issue #297 - Fixed Navigation)
	 *
	 * Instead of exploring ALL topological possibilities at switches (which could
	 * choose wrong branches), this method follows ONLY the reserved blocks in PathInfo.
	 *
	 * Key change: Uses `pathInfo.reservedPath.getNext(current)` to get the next
	 * TrackSection in the RESERVED path sequence, rather than exploring topology.
	 *
	 * Path structure: [Separator] → [TrackSection] → [Separator] → [TrackSection]
	 * - When current == null: returns first TrackSection (index 1)
	 * - When current != null: returns next TrackSection (index+2, skipping separator)
	 *
	 * ## Benefits
	 *
	 * - Trains follow reserved blocks faithfully (no wrong turns at switches)
	 * - No topology exploration (eliminates Issue #291 workaround)
	 * - Simpler logic (path structure defines navigation)
	 *
	 * @param startSeparator Starting position
	 * @param initialNext Initial direction (from PathInfo!)
	 * @param pathInfo Complete path metadata with reservedPath
	 * @return Path to next semaphore, or null if path cannot be built
	 */
	private fun buildPathWithDirection(
		startSeparator: PathSeparator,
		initialNext: TrackSection,
		pathInfo: PathInfo
	): Path? {
		logger.debug {
			"buildPathWithDirection: building path from $startSeparator via $initialNext"
		}

		val reservedPath = pathInfo.reservedPath
		var separator = startSeparator
		var previous: TrackSection? = null
		var current: TrackSection? = initialNext
		val path = ArrayPath(context)

		// Track visited (separator, direction) pairs for cycle detection
		// Direction = which track we came from (previous)
		val visited = mutableSetOf<Pair<PathSeparator, TrackSection?>>()

		do {
			// Cycle detection: Check if we've visited this separator from this direction before
			val directedPosition = Pair(separator, previous)
			if (directedPosition in visited) {
				logger.warn {
					"buildPathWithDirection: cycle detected at $separator from $previous, " +
						"path length ${path.length()} elements"
				}
				// Cycle detected - check if we're at a valid stopping point
				if (separator is OrientedPathSeparator) {
					if (context.isSeparatorInDirection(separator, current, previous)) {
						path.add(separator)
						logger.debug {
							"buildPathWithDirection: completed circular route, " +
								"final path length ${path.length()} elements"
						}
						return path
					}
				}
				// Cycle but not at valid exit point - path incomplete
				logger.error {
					"buildPathWithDirection: cycle detected but not at oriented semaphore, " +
						"path incomplete (${path.length()} elements)"
				}
				return null
			}
			visited.add(directedPosition)

			path.add(separator)
			if (current != null) {
				path.add(current)
				val staticResult = current.getSecondEnd(separator)
				separator = context.toDynamic(staticResult)
				previous = current
				// NEW: Use PathInfo.reservedPath instead of topology exploration
				current = reservedPath.getNext(current)
			} else {
				break
			}

			// Check if we've reached an oriented semaphore
			if (separator is OrientedPathSeparator) {
				if (context.isSeparatorInDirection(separator, current, previous)) {
					path.add(separator)
					logger.debug {
						"buildPathWithDirection: found complete path with length ${path.length()}"
					}
					return path
				}
			}
		} while (current != null)

		logger.debug { "buildPathWithDirection: no path found (no oriented semaphore reached)" }
		return null
	}

	/**
	 * Extract all DynamicTrackBlock instances from a path.
	 *
	 * ## Implementation
	 *
	 * Paths contain PathElements which can be:
	 * - PathSeparator (semaphores, switches, InOuts)
	 * - TrackSection (track segments)
	 *
	 * We need to extract TrackSection instances that are also DynamicTrackBlocks,
	 * filtering out TrackBlockParts (which are not reservable).
	 *
	 * ## Deduplication
	 *
	 * Paths may contain the same block multiple times (e.g., switch "around" blocks).
	 * We use a LinkedHashSet to preserve order while eliminating duplicates.
	 *
	 * @param path Path to extract blocks from
	 * @return List of unique DynamicTrackBlocks in path order
	 */
	private fun extractDynamicTrackBlocks(path: Path): List<DynamicTrackBlock> {
		val seen = LinkedHashSet<DynamicTrackBlock>()

		for (element in path) {
			// Only process TrackSection instances
			if (element !is TrackSection) continue

			// Extract the track block from the section
			// TrackSection.getTrackBlock() returns the underlying TrackBlock
			val block = element.getTrackBlock()

			// Only include DynamicTrackBlock instances (not TrackBlockPart)
			if (block is DynamicTrackBlock) {
				seen.add(block) // LinkedHashSet ensures uniqueness
			}
		}

		logger.trace {
			"extractDynamicTrackBlocks: extracted ${seen.size} unique blocks from path with ${path.size} elements"
		}
		return seen.toList()
	}

	/**
	 * Epoch-cached path-available condition (Issue #931 f2).
	 *
	 * ## What is cached and why it is sound
	 *
	 * [findReservedPathForTrain] reads exactly two pieces of mutable state, and both live in
	 * [PathReservationRegistry]: `getPathInfo(trainId)` and `getOwner(block)`. `PathInfo` is an
	 * immutable data class, so it can only change by being replaced in the registry.
	 * [hasTopologicalContinuation] goes to `TopologyNavigator.getNextTrackBlock`, which reads the
	 * **static** grid and deliberately ignores switch configuration; nothing on the path reads a
	 * block's occupant. So while [PathReservationRegistry.mutationEpoch] is unchanged, the answer
	 * cannot have changed either, and the re-test can be served from memory.
	 *
	 * The cache holds a `Boolean`. It must not hold the [PathResult] — `Train.Site.separatorAction`
	 * calls `removeFirst()` on the path it is handed, which is safe only because every evaluation
	 * builds a fresh one.
	 *
	 * ## Lifetime
	 *
	 * One cache per returned condition, so it lives exactly as long as the `waitUntil` that holds
	 * it: no map, no eviction, nothing to leak. kDisco keeps the same `Condition` instance for the
	 * whole wait (`Process.waitUntil` stores it in a `WaitNotice`), which is what makes a
	 * per-instance cache worth having.
	 *
	 * ## No time-based refresh
	 *
	 * There is deliberately no "recompute anyway every N seconds" safety net. The epoch covers the
	 * whole read set, so such a window would change nothing; and if the read set were ever
	 * incomplete, a window would convert a reproducible staleness bug into a timing-dependent one
	 * and make the evaluation counts untestable. [verifyEveryEvaluation] is the safety net instead:
	 * it recomputes every time and fails loudly on a mismatch.
	 */
	override fun createPathAvailableCondition(
		trainId: String,
		separator: PathSeparator
	): Condition {
		var epochAtLastEval = UNSEEN_EPOCH
		var lastResult = false
		return Condition {
			conditionTests++
			val epoch = registry.mutationEpoch
			if (epoch == epochAtLastEval) {
				if (verifyEveryEvaluation) {
					verifyCachedAnswer(trainId, separator, lastResult)
				}
				lastResult
			} else {
				realEvaluations++
				lastResult = findReservedPathForTrain(trainId, separator) is PathResult.Available
				epochAtLastEval = epoch
				lastResult
			}
		}
	}

	override fun evaluationStats(): PathEvaluationStats =
		PathEvaluationStats(conditionTests = conditionTests, realEvaluations = realEvaluations)

	/**
	 * Recomputes and compares against [cached], for [verifyEveryEvaluation].
	 *
	 * A mismatch means the registry changed the answer without changing
	 * [PathReservationRegistry.mutationEpoch] — a missing `bumpEpoch()`. Left undetected that
	 * stalls a train until its #943 error horizon, at which point the cause is long gone from the
	 * log, so this fails immediately and names the train.
	 */
	private fun verifyCachedAnswer(
		trainId: String,
		separator: PathSeparator,
		cached: Boolean
	) {
		val fresh = findReservedPathForTrain(trainId, separator) is PathResult.Available
		check(fresh == cached) {
			"Path-available cache is stale for train '$trainId' at $separator: cached=$cached, " +
				"recomputed=$fresh, while PathReservationRegistry.mutationEpoch stayed at " +
				"${registry.mutationEpoch}. A registry mutation is missing its bumpEpoch()."
		}
	}

	/**
	 * Check if there is a topological continuation for the given separator.
	 *
	 * This method uses the TopologyNavigator to determine if there is a valid
	 * next track block in the topology, starting from the given separator.
	 *
	 * @param separator The separator to check for topological continuation
	 * @return True if there is a topological continuation, false otherwise
	 */
	private fun hasTopologicalContinuation(separator: PathSeparator): Boolean {
		val navigator = context.getRoutingServices().getTopologyNavigator()
		val nodeCell = (separator as? NodeCell) ?: CellUtilities.assertNodeCell(separator)
		return navigator.getNextTrackBlock(nodeCell, null) != null
	}
}
