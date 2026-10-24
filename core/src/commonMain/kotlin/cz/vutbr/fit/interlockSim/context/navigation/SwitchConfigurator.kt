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
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.SwitchLockedException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Outcome of [SwitchConfigurator.configureSwitchesInPath] /
 * [DefaultPathReservationService.configureAndRegisterSwitches], distinguishing a permanent
 * geometric impossibility (Issue #742: no [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf]
 * joins the route's segments) from a transient SI-5 refusal (Issue #1065: the switch is
 * locked in a different position by a live route) so the caller records only the former as
 * a geometric failure. Folding the latter into [PathReservationService.ReservationResult.GeometricallyImpossible]
 * makes [cz.vutbr.fit.interlockSim.sim.InOutWorker] throw instead of letting the train wait
 * for the switch to free -- the exact regression this distinction prevents.
 *
 * @since Issue #1065; widened from `Boolean`
 */
internal sealed interface SwitchConfigOutcome {
	/** Every genuinely traversed switch was configured. */
	data object Configured : SwitchConfigOutcome

	/**
	 * Issue #742: a genuinely traversed switch has NO configuration joining its segments --
	 * physically impossible, permanent.
	 */
	data object Unconfigurable : SwitchConfigOutcome

	/**
	 * Issue #1065: a genuinely traversed switch IS locked, in a different position, by
	 * [holder] (`null` if the lock is an orphan -- see [PathReservationRegistry.getSwitchOwner]).
	 * Issue #1076: also reported when the switch is REGISTERED to a different train even in
	 * the SAME position (a known required configuration only) -- traversing it would steal
	 * the ownership entry from its live owner.
	 * Transient: resolves once [holder]'s route releases the switch.
	 */
	data class Locked(
		val switch: DynamicRailSwitch,
		val holder: String?,
		val held: RailSwitch.Conf,
		val required: RailSwitch.Conf
	) : SwitchConfigOutcome
}

/**
 * Switch configuration for a reserved route candidate (Issue #1170, SP12.4): set and lock the
 * switches a candidate traverses, register them to the train, and release them again when the
 * candidate is rolled back. Extracted verbatim from [DefaultPathReservationService], which owns
 * the orchestration (Step 2e.5 clean-up, Step 2f configure-and-register, scoped rollback).
 *
 * **NOT thread-safe**, like its owner: single-threaded simulation access only.
 *
 * @property environment Simulation environment; must be a
 *   [cz.vutbr.fit.interlockSim.context.SimulationContext] for `getSegment()`
 * @property registry Ownership registry the switch claims are recorded in
 * @since Issue #1170
 */
internal class SwitchConfigurator(
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry
) {
	/**
	 * Extract unique railway switches from a reserved path (Tier 2).
	 *
	 * Iterates through all PathElements in the path and collects DynamicRailSwitch instances.
	 * Switches are deduplicated to ensure each switch appears only once in the result.
	 *
	 * ## Algorithm
	 *
	 * 1. Iterate through path.reservedPath elements
	 * 2. Filter for DynamicPathSeparator elements that are switches (isSwitch() == true)
	 * 3. Cast to DynamicRailSwitch
	 * 4. Return unique switches
	 *
	 * @param pathInfo The PathInfo containing the reserved path
	 * @return List of unique DynamicRailSwitch instances in the path
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun extractUniqueSwitches(pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo): List<DynamicRailSwitch> {
		val seen = mutableSetOf<DynamicRailSwitch>()
		return pathInfo.reservedPath.mapNotNull { element ->
			when {
				element is DynamicPathSeparator && element.isSwitch() && element is DynamicRailSwitch -> {
					if (seen.add(element)) element else null
				}
				else -> null
			}
		}
	}

	/**
	 * Configure switches in the reserved path based on topology.
	 *
	 * For each switch in the path, determines the correct configuration (MAIN or BRANCH)
	 * based on the path topology (from/to track segments). Calls DynamicRailSwitch.setUpPath()
	 * which:
	 * 1. Calculates correct Conf based on from/to segments
	 * 2. Updates switch.conf property
	 * 3. Fires PropertyChangeSupport event for animation
	 * 4. Locks the switch
	 *
	 * ## Algorithm
	 *
	 * 1. Convert Path to indexed list for neighbor access
	 * 2. Iterate through each element with index
	 * 3. For each DynamicRailSwitch:
	 *    a. Get previous element (track or null)
	 *    b. Get next element (track or null)
	 *    c. Calculate from/to segments using environment.getSegment()
	 *    d. Call switch.setUpPath(from, to, allowedSpeed, trainOccupant)
	 *
	 * ## Railway Safety
	 *
	 * Configuration happens BEFORE semaphore signals are set, following railway
	 * interlocking principles: switches must be positioned and locked before
	 * authorizing train movement.
	 *
	 * ## Error Handling (Issue #742)
	 *
	 * Two failure classes are distinguished:
	 *
	 * - **Undeterminable segments** (`from`/`to` is null, or no next track): the path does not
	 *   geometrically traverse the switch, so it is skipped leniently — the pre-#742 behavior.
	 * - **Genuinely traversed switch that no configuration joins**
	 *   ([PathSeparatorChangeException] with both segments known): the candidate route is
	 *   physically impossible (e.g. the sibling-branch "diversion" doB1→doB2 through vB, whose
	 *   legs no configuration of vB connects). This now FAILS the whole configuration —
	 *   returning [SwitchConfigOutcome.Unconfigurable] — instead of being silently skipped. Silently skipping reserved
	 *   untraversable routes and permanently stalled trains (captured in failing
	 *   `RuleBasedDispatcherDeterminismTest` runs).
	 *
	 * ## Error Handling (Issue #1065)
	 *
	 * A third failure class, distinct from both above: a switch the path genuinely traverses
	 * that IS locked, in the OTHER position, by a live route (this train's own earlier hop, or
	 * another train's). Unlike the #742 case this is TRANSIENT contention -- the switch frees
	 * once that route releases it -- so it is reported separately
	 * ([SwitchConfigOutcome.Locked]) and must never be folded into a permanent geometric
	 * impossibility (that regression is exactly what corrupted a surviving route's switch
	 * position in Issue #1031).
	 *
	 * @param trainId Train identifier for logging and occupant creation
	 * @param pathInfo PathInfo containing the reserved path with switches
	 * @return [SwitchConfigOutcome.Configured] when every genuinely traversed switch was
	 *   configured; a failure variant otherwise, in which case the caller must roll the
	 *   candidate back
	 * @since Issue #300 Fix switch animation regression; widened from `Boolean` to
	 *   [SwitchConfigOutcome] by Issue #1065
	 */
	fun configureSwitchesInPath(
		trainId: String,
		pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo
	): SwitchConfigOutcome {
		// Convert Path to list for indexed access
		val pathElements = pathInfo.reservedPath.toList()

		// Safe cast: environment is always SimulationContext in practice (provides getSegment())
		// Note: getSegment() is in SimulationContext interface, not SimulationEnvironment
		val context =
			environment as? cz.vutbr.fit.interlockSim.context.SimulationContext
				?: throw IllegalStateException(
					"configureSwitchesInPath requires SimulationContext for getSegment() access, " +
						"but got ${environment::class.simpleName}"
				)

		// Track count of successfully configured switches
		var configuredCount = 0

		// Issue #1065: a Locked switch does NOT bail out of the loop -- unlike Unconfigurable
		// (permanent, so no later switch's classification can matter), a Locked switch is only
		// TRANSIENT contention, and a LATER switch further along the SAME candidate might be
		// genuinely Unconfigurable (#742, permanent). Bailing on the first Locked hit would let
		// it mask that more severe, more informative classification for as long as the lock
		// holds -- first-hit-wins, kept as the first one seen, but Unconfigurable always wins
		// over it once found.
		var pendingLocked: SwitchConfigOutcome.Locked? = null

		// Iterate through path elements with index for neighbor access
		pathElements.forEachIndexed { index, element ->
			// Only process switches
			if (element !is DynamicRailSwitch) return@forEachIndexed

			val outcome = configureSwitchAt(trainId, context, pathElements, index, element) ?: return@forEachIndexed
			when (outcome) {
				SwitchConfigOutcome.Configured -> configuredCount++
				SwitchConfigOutcome.Unconfigurable -> return outcome
				is SwitchConfigOutcome.Locked -> pendingLocked = pendingLocked ?: outcome
			}
		}

		logger.debug {
			"configureSwitchesInPath: Configured $configuredCount switch(es) for train $trainId"
		}
		return pendingLocked ?: SwitchConfigOutcome.Configured
	}

	/**
	 * The per-switch step of [configureSwitchesInPath] for [element], the switch sitting at
	 * [index] in [pathElements] (issue #1170 review). Determines the neighbours, classifies the
	 * configuration outcome for this one element; the caller keeps the loop-level bookkeeping
	 * (first-hit-wins `Locked`, immediate bail on `Unconfigurable`).
	 *
	 * @return the outcome for the caller's loop, or `null` when the path does not genuinely
	 *   traverse [element] (no next track, or no segment joins it) — skip, contributes nothing
	 */
	private fun configureSwitchAt(
		trainId: String,
		context: cz.vutbr.fit.interlockSim.context.SimulationContext,
		pathElements: List<PathElement>,
		index: Int,
		element: DynamicRailSwitch
	): SwitchConfigOutcome? {
		// Find previous and next Track (skip over separators)
		val previous = nearestTrackBefore(pathElements, index)
		val next = nearestTrackAfter(pathElements, index)

		// Skip if we don't have a next track (required for configuration)
		if (next == null) {
			logger.warn {
				"configureSwitchesInPath: Switch ${element.staticRef.getName()} " +
					"has no next track, skipping configuration"
			}
			return null
		}

		// Calculate from/to segments using context.getSegment()
		// from = segment the train is coming FROM
		// to = segment the train is going TO
		val from = context.getSegment(element, previous, next)
		val to = context.getSegment(element, next, previous)

		// Lenient skip (pre-#742 behavior): segments undeterminable means the path does
		// not geometrically traverse this switch, so there is nothing to configure.
		if (from == null || to == null) {
			logger.info {
				"configureSwitchesInPath: Skipped switch ${element.staticRef.getName()} " +
					"for train $trainId - segments undeterminable, path does not traverse it " +
					"(from=${from?.hashCode()}, to=${to?.hashCode()})"
			}
			return null
		}

		// Issues #1065/#1076: non-throwing pre-check of ownership/lock contention
		// (see switchOwnershipPreCheck for the classification rules).
		switchOwnershipPreCheck(trainId, element, element.pathConf(from, to))?.let { return it }

		return setUpTraversedSwitch(trainId, element, from, to)
	}

	/**
	 * Nearest [Track] before [index] in [pathElements], skipping over separators.
	 *
	 * @return the track, or null if the path starts with the switch (no track before it)
	 */
	private fun nearestTrackBefore(
		pathElements: List<PathElement>,
		index: Int
	): Track? = (index - 1 downTo 0).firstNotNullOfOrNull { i -> pathElements[i] as? Track }

	/**
	 * Nearest [Track] after [index] in [pathElements], skipping over separators.
	 *
	 * @return the track, or null if the path ends with the switch (no track after it)
	 */
	private fun nearestTrackAfter(
		pathElements: List<PathElement>,
		index: Int
	): Track? = ((index + 1) until pathElements.size).firstNotNullOfOrNull { i -> pathElements[i] as? Track }

	/**
	 * Configure [element], which the candidate for [trainId] genuinely traverses from segment
	 * [from] to segment [to], through [DynamicRailSwitch.setUpPath] (sets conf, fires the
	 * PropertyChange event for animation, locks).
	 *
	 * @return [SwitchConfigOutcome.Configured] on success; [SwitchConfigOutcome.Locked] when
	 *   setUpPath refuses a reposition while locked; [SwitchConfigOutcome.Unconfigurable] when
	 *   no configuration joins the two segments
	 */
	private fun setUpTraversedSwitch(
		trainId: String,
		element: DynamicRailSwitch,
		from: Cell.Segment,
		to: Cell.Segment
	): SwitchConfigOutcome =
		try {
			// MinimalTrackOccupant: the switch uses the occupant for logging only.
			element.setUpPath(from, to, element.allowedSpeed(), MinimalTrackOccupant(trainId))
			logger.info {
				"configureSwitchesInPath: Switch ${element.staticRef.getName()} " +
					"configured to ${element.conf} for train $trainId " +
					"(from=${from.hashCode()}, to=${to.hashCode()})"
			}
			SwitchConfigOutcome.Configured
		} catch (e: SwitchLockedException) {
			// Issue #1065: defence-in-depth. The pre-check (switchOwnershipPreCheck) should always
			// catch this first; this only fires if the switch's lock state changed between the
			// pre-check and this call (not possible in the current single-threaded kDisco
			// context, but the guard in DynamicRailSwitch.setUpPath is authoritative, so this
			// call site must not silently mis-classify its refusal as #742).
			logger.warn {
				"configureSwitchesInPath: Switch ${element.staticRef.getName()} refused a " +
					"reposition while locked for train $trainId (Issue #1065): ${e.message}"
			}
			SwitchConfigOutcome.Locked(element, registry.getSwitchOwner(element), e.heldConf, e.requiredConf)
		} catch (e: PathSeparatorChangeException) {
			// Issue #742: the route genuinely traverses this switch (both segments known) but NO
			// switch configuration joins them — the candidate route is physically impossible.
			// Reject the configuration so reservePath rolls the candidate back; silently skipping
			// here reserved untraversable routes and permanently stalled trains.
			logger.warn {
				"configureSwitchesInPath: Switch ${element.staticRef.getName()} cannot join " +
					"the route's segments for train $trainId - rejecting candidate route " +
					"(from=${from.hashCode()}, to=${to.hashCode()}, Issue #742)"
			}
			logger.debug(e) { "Exception details: ${e.message}" }
			SwitchConfigOutcome.Unconfigurable
		}

	/**
	 * Non-throwing pre-check whether the candidate for [trainId] may traverse [element] that
	 * needs [requiredConf] (null = the segments admit no switch configuration).
	 *
	 * Reported as TRANSIENT contention, never as a permanent geometric impossibility:
	 *
	 * - Issue #1065 (safety SI-5): the switch is locked by ANY train (including this one) in
	 *   the OTHER position — the exact shape that silently corrupted a surviving route's
	 *   switch position in Issue #1031.
	 * - Issue #1076: the switch is REGISTERED to a DIFFERENT train, even in the SAME position
	 *   (stale foreign ownership was already reclaimed in reservePath Step 2e.5, so this owner
	 *   protects a live route).
	 *
	 * Neither fires when [requiredConf] is null: the switch then admits no configuration at
	 * all, and [DynamicRailSwitch.setUpPath]'s geometric check must classify it as
	 * `Unconfigurable` (#742, permanent) -- contention a live foreign owner would never
	 * resolve must not mask it (Issue #1076 review).
	 *
	 * @return the contention to report (first-hit-wins in the caller), or null when the
	 *   candidate may proceed to [DynamicRailSwitch.setUpPath]
	 */
	private fun switchOwnershipPreCheck(
		trainId: String,
		element: DynamicRailSwitch,
		requiredConf: RailSwitch.Conf?
	): SwitchConfigOutcome.Locked? {
		val required = requiredConf ?: return null
		val repositionWhileLocked = element.locked && required != element.conf
		if (!repositionWhileLocked && !registry.isOwnedByOtherTrain(element, trainId)) {
			return null
		}
		val holder = registry.getSwitchOwner(element)
		logger.warn {
			val reason =
				if (repositionWhileLocked) {
					"is locked in ${element.conf} by '$holder' but train $trainId's candidate needs " +
						"$required (safety SI-5, Issue #1065)"
				} else {
					"is registered to '$holder'; train $trainId's candidate must not take it over " +
						"(Issue #1076)"
				}
			"configureSwitchesInPath: Switch ${element.staticRef.getName()} $reason -- transient " +
				"contention, continuing to check the rest of the candidate for a permanent impossibility"
		}
		return SwitchConfigOutcome.Locked(element, holder, element.conf, required)
	}

	/**
	 * Register [switches] (all of a configured candidate's switches, from [extractUniqueSwitches])
	 * to [trainId], skipping any switch registered to another train.
	 *
	 * Behaviour pinned by `SwitchConfiguratorTest` (Issue #1170): a listed switch the candidate does
	 * not genuinely traverse is registered (and locked) too, even when [trainId] holds no block
	 * bounded by it -- a claim born stale. Issue #1173 changes this on purpose.
	 *
	 * Correct only inside Step 2f, after Step 2e.5 and after [configureSwitchesInPath] returned
	 * `Configured`; called alone it registers a switch the candidate never touched (born stale).
	 *
	 * @since Issue #1170 (extracted from `DefaultPathReservationService.configureAndRegisterSwitches`)
	 */
	fun registerSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		// Issue #1076: extractUniqueSwitches includes switches the path does NOT genuinely
		// traverse (configureSwitchesInPath skips them leniently when their segments are
		// undeterminable). A non-traversed switch registered to ANOTHER train must not be
		// registered here -- registerSwitches throws on a foreign owner. Every genuinely
		// TRAVERSED switch passes this filter: a live foreign owner was refused as Locked
		// by configureSwitchesInPath (the caller's previous step) and stale foreign ownership was reclaimed in reservePath Step 2e.5.
		val registrable = switches.filterNot { registry.isOwnedByOtherTrain(it, trainId) }
		registry.registerSwitches(trainId, registrable)
		logger.debug {
			"reservePath: Registered ${registrable.size} switches for $trainId"
		}
	}

	/**
	 * Issue #1065: release any ORPHAN lock among [switches] before a candidate touches them.
	 *
	 * An orphan is `switch.locked == true` with `registry.getSwitchOwner(switch) == null` --
	 * locked by [DynamicRailSwitch.setUpPath] but never reaching
	 * [PathReservationRegistry.registerSwitches], so no granted route protects it. Reachable
	 * only in the narrow window of a single [configureSwitchesInPath] call where a switch is
	 * locked and a LATER switch in the same candidate throws (a non-[PathSeparatorChangeException]
	 * exception escapes with no rollback -- a known, separately tracked gap). An unowned lock
	 * protects nothing, so it is always safe to clear.
	 *
	 * @since Issue #1065
	 */
	fun releaseOrphanSwitchLocks(switches: List<DynamicRailSwitch>) {
		switches.forEach { switch ->
			if (switch.locked && registry.getSwitchOwner(switch) == null) {
				switch.unlock()
				logger.warn {
					"releaseOrphanSwitchLocks: unlocked orphan switch ${switch.staticRef.getName()} " +
						"(locked, but owned by no train -- Issue #1065)"
				}
			}
		}
	}

	/**
	 * Issue #1076: reclaim STALE foreign ownership among [switches] before a candidate for
	 * [trainId] touches them, so the candidate never has to overwrite it (see
	 * [PathReservationRegistry.registerSwitches]). No per-block release leaves ownership stale any
	 * more: [PathReservationRegistry.unregisterBlock] reclaims at the freed block's ends, including
	 * a direct registry call (Issue #1103 item 1); whole-route cleanup (`registry.unregister` +
	 * `unregisterSwitches`) unlocks wholesale instead. This step stays for the
	 * one remaining path: the registry is public, and [PathReservationRegistry.registerSwitches]
	 * accepts a switch the train holds no adjacent block for -- a claim born stale, which no block
	 * release ever visits. Without this step the G10 pre-check would refuse a same-position
	 * candidate for such a switch forever (the Issue #1065 trap).
	 *
	 * A foreign owner that survives this step is live and refuses the candidate in
	 * [configureSwitchesInPath] instead.
	 *
	 * @since Issue #1076
	 */
	fun reclaimStaleForeignSwitchOwnership(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		switches
			.filter { registry.isOwnedByOtherTrain(it, trainId) }
			.forEach { registry.reclaimStaleSwitchOwnership(it) }
	}

	/**
	 * Unlock and unregister THIS candidate's switches, shared by all scoped rollback paths
	 * ([DefaultPathReservationService.rollbackUnconfigurableCandidate], the bypass rollback in
	 * [DefaultPathReservationService.reservePathToAnyNextSemaphore]).
	 *
	 * [PathReservationRegistry.unregisterSwitch] unlocks + removes the switch from the registry's
	 * switch-ownership maps ([SwitchOwnershipTable]) when the switch is registered to this train
	 * (after [PathReservationRegistry.registerSwitches]). When the switch was locked by `setUpPath` but NOT
	 * yet registered (switch-config-failure path, before registration), unregisterSwitch is a no-op —
	 * so fall back to an explicit unlock to release the physical lock.
	 *
	 * The fallback is restricted to switches with NO registered owner (Issue #1065 review): a
	 * candidate can traverse a switch owned by ANOTHER train and still fail later, and while
	 * [PathReservationRegistry.unregisterSwitch] correctly refuses to release it, an unconditional
	 * explicit unlock would strip that train's physical lock while the registry still records it as
	 * the holder — reopening exactly the #1065 corruption under the foreign route. A switch
	 * registered to another train is left completely untouched here.
	 *
	 * @param trainId The train identifier
	 * @param candidateSwitches Only the switches newly locked/registered by the rolled-back
	 *   candidate — callers must have already excluded the train's pre-existing switches
	 */
	fun releaseCandidateSwitches(
		trainId: String,
		candidateSwitches: Collection<DynamicRailSwitch>
	) {
		candidateSwitches.forEach { switch ->
			try {
				if (!registry.unregisterSwitch(trainId, switch) && switch.locked) {
					// Only an OWNERLESS lock can belong to this candidate (locked by its
					// setUpPath, never registered). A registered switch that refused
					// unregisterSwitch belongs to another train and must keep both its lock
					// and its registry entry (Issue #1065 review).
					if (registry.getSwitchOwner(switch) == null) {
						switch.unlock()
					} else {
						logger.debug {
							"releaseCandidateSwitches: Switch ${switch.staticRef.getName()} is owned " +
								"by '${registry.getSwitchOwner(switch)}' - left locked and registered " +
								"(not $trainId's to release)"
						}
					}
				}
			} catch (e: Exception) {
				logger.warn(e) { "releaseCandidateSwitches: Failed to release switch $switch for $trainId" }
			}
		}
	}

	/**
	 * Minimal TrackOccupant implementation for switch configuration.
	 *
	 * Switches only use the `name` property for logging during setUpPath().
	 * The distance and semaphore methods are not used during configuration,
	 * so they return placeholder values.
	 *
	 * @property trainId The train identifier for logging
	 */
	private class MinimalTrackOccupant(
		private val trainId: String
	) : TrackOccupant {
		override val name: String get() = trainId

		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}
}
