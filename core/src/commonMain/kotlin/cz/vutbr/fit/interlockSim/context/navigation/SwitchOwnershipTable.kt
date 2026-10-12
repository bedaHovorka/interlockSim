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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KLogger

private val logger: KLogger = registryLogger

/**
 * Switch ownership of [PathReservationRegistry] (Tier 2): which train holds which
 * [DynamicRailSwitch] locked, and why ([SwitchClaim]). Split out of the registry by Issue #1172; the
 * registry delegates every switch-ownership operation here and keeps the public contract and its KDoc.
 *
 * ## The claim's purpose
 *
 * [SwitchClaim.isFlank] marks FLANK protection (Issue #1076 review). A facade `lockRouteAtomic`
 * grant locks `route.flank` switches that protect a route the switch is NOT adjacent to (see
 * `docs/INTERLOCKING_SCOPE_LIMITATIONS.md` §B2). The "owner holds no block bounded by it" staleness
 * rule of [isStaleSwitchOwnership] is unsound for such a switch -- a live flank grant has no adjacent
 * block by definition -- so a flank claim is never stale while it stands. Owner and purpose live in
 * one record (Issue #1103), so every removal ([unregisterSwitch], [unregisterSwitches], [clear])
 * drops the purpose with the claim. The purpose is per switch, not per route: if the same train
 * later also registers the switch as a running switch, the claim stays a flank claim until release
 * -- the table cannot tell whether the earlier flank route is still live, so it keeps the
 * conservative answer.
 *
 * **NOT thread-safe**, like the registry.
 *
 * @param heldBlocks the blocks a train holds (empty when it has no block entry) -- the registry
 *   passes [BlockOwnershipTable.getBlocks]. Only the staleness rule reads it.
 * @since Issue #1172 (the logic since Issue #291 / #1065 / #1076 / #1103)
 */
internal class SwitchOwnershipTable(
	private val heldBlocks: (String) -> List<DynamicTrackBlock>
) {
	/** Train ID -> reserved switches. */
	private val trainToSwitches = mutableMapOf<String, MutableList<DynamicRailSwitch>>()

	/** Switch -> the owning train's [SwitchClaim]. */
	private val switchToTrain = mutableMapOf<DynamicRailSwitch, SwitchClaim>()

	/** [PathReservationRegistry.registerSwitches]: plain claims, foreign ownership rejected atomically. */
	fun registerSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		registerClaims(trainId, switches, isFlank = false)
	}

	/** [PathReservationRegistry.registerFlankSwitches]: flank claims; an empty list registers nothing. */
	fun registerFlankSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		if (switches.isEmpty()) return
		registerClaims(trainId, switches, isFlank = true)
	}

	/**
	 * [registerSwitches] with the claim's purpose. A plain registration over the train's own flank
	 * claim keeps it a flank claim -- see the class KDoc.
	 */
	private fun registerClaims(
		trainId: String,
		switches: List<DynamicRailSwitch>,
		isFlank: Boolean
	) {
		switches.forEach { switch ->
			check(!isOwnedByOtherTrain(switch, trainId)) {
				"registerSwitches: Switch ${switch.staticRef.getName()} is already registered to " +
					"'${getSwitchOwner(switch)}' and cannot be registered to '$trainId' (Issue #1076); " +
					"nothing was registered"
			}
		}
		val switchList = trainToSwitches.getOrPut(trainId) { mutableListOf() }
		switches.forEach { switch ->
			if (switch !in switchList) {
				switchList.add(switch)
				// Lock switch when first registered to this train
				switch.lock()
				logger.info {
					"registerSwitches: Locked switch ${switch.hashCode()} for '$trainId', locked=${switch.locked}"
				}
			}
			switchToTrain[switch] = SwitchClaim(trainId, isFlank || switchToTrain[switch]?.isFlank == true)
		}

		logger.info {
			"registerSwitches: Registered ${switches.size} switches for '$trainId'"
		}
	}

	/** Whether [switch] is currently registered as flank protection. */
	fun isFlankProtected(switch: DynamicRailSwitch): Boolean = switchToTrain[switch]?.isFlank == true

	/** Whether [switch] is registered to a train OTHER than [trainId]. */
	fun isOwnedByOtherTrain(
		switch: DynamicRailSwitch,
		trainId: String
	): Boolean {
		val owner = getSwitchOwner(switch)
		return owner != null && owner != trainId
	}

	/**
	 * Stale iff the switch has an owner, its claim is not a flank claim, and the owner holds no block
	 * bounded by it -- see [PathReservationRegistry.isStaleSwitchOwnership].
	 */
	fun isStaleSwitchOwnership(switch: DynamicRailSwitch): Boolean {
		val claim = switchToTrain[switch] ?: return false
		if (claim.isFlank) return false
		return heldBlocks(claim.trainId).none { switch in it.ends() }
	}

	/**
	 * Release [switch]'s ownership if [isStaleSwitchOwnership] says it protects no live route, through
	 * [unregisterSwitch]; a throw from the unlock is contained and logged -- see
	 * [PathReservationRegistry.reclaimStaleSwitchOwnership].
	 *
	 * @return the owner whose stale claim was released, or `null` when nothing was released or the
	 *   release failed and was contained
	 */
	fun reclaimStaleSwitchOwnership(switch: DynamicRailSwitch): String? {
		val switchName = switch.staticRef.getName()
		val claim = getSwitchClaim(switch)
		if (claim == null || !isStaleSwitchOwnership(switch)) return null
		val owner = claim.trainId
		try {
			unregisterSwitch(owner, switch)
		} catch (e: Exception) {
			logger.warn(e) {
				"reclaimStaleSwitchOwnership: Failed to release stale ownership of switch $switchName " +
					"held by '$owner' -- the claim stays in the ownership maps and the switch stays " +
					"unlocked; a later reclaim cleans it"
			}
			return null
		}
		logger.info {
			"reclaimStaleSwitchOwnership: Released stale ownership of switch $switchName held by " +
				"'$owner' -- the owner holds no block bounded by it"
		}
		return owner
	}

	/** Unlock and release every switch [trainId] holds; returns them (empty if none). */
	fun unregisterSwitches(trainId: String): List<DynamicRailSwitch> {
		val switches: List<DynamicRailSwitch> = trainToSwitches[trainId] ?: return emptyList()

		// Unlock and remove all switches from mappings
		switches.forEach { switch ->
			switch.unlock()
			switchToTrain.remove(switch)
			logger.info {
				"unregisterSwitches: Unlocked switch ${switch.hashCode()} for '$trainId', locked=${switch.locked}"
			}
		}

		// Remove train entry
		trainToSwitches.remove(trainId)

		logger.info {
			"unregisterSwitches: Released ${switches.size} switches for '$trainId'"
		}

		return switches.toList()
	}

	/**
	 * Unlock and release ONE switch if [trainId] owns it. The map entries are removed only after
	 * `unlock()` returns.
	 *
	 * @return `true` if released, `false` if not owned by [trainId]
	 */
	fun unregisterSwitch(
		trainId: String,
		switch: DynamicRailSwitch
	): Boolean {
		if (getSwitchOwner(switch) != trainId) {
			logger.debug {
				"unregisterSwitch: Switch ${switch.hashCode()} not owned by '$trainId' " +
					"(owner='${getSwitchOwner(switch)}')"
			}
			return false
		}
		switch.unlock()
		switchToTrain.remove(switch)
		trainToSwitches[trainId]?.remove(switch)
		if (trainToSwitches[trainId]?.isEmpty() == true) {
			trainToSwitches.remove(trainId)
		}
		logger.debug {
			"unregisterSwitch: Released switch ${switch.hashCode()} for '$trainId'"
		}
		return true
	}

	/** A copy of the switches [trainId] holds (empty if none). */
	fun getSwitches(trainId: String): List<DynamicRailSwitch> = trainToSwitches[trainId]?.toList() ?: emptyList()

	/** The train that owns [switch], or `null`. */
	fun getSwitchOwner(switch: DynamicRailSwitch): String? = switchToTrain[switch]?.trainId

	/** The [SwitchClaim] on [switch], or `null` when no train owns it. */
	fun getSwitchClaim(switch: DynamicRailSwitch): SwitchClaim? = switchToTrain[switch]

	/** Drop every claim (with its purpose) without unlocking any switch -- as the registry's `clear()` always did. */
	fun clear() {
		trainToSwitches.clear()
		switchToTrain.clear()
	}
}
