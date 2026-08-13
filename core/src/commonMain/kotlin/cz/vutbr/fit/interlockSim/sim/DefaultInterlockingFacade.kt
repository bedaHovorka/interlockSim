/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.lang.toSignal
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchPosition
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of the ESA-11 four-condition interlocking kernel.
 *
 * Implements atomic route locking with verification of:
 * 1. Route freedom (blocks are FREE — unoccupied and unreserved)
 * 2. Switch positions (each switch's [DynamicRailSwitch.conf] matches its required
 *    [SwitchSetting.position] via the canonical `PLUS ↔ MAIN` / `MINUS ↔ BRANCH` mapping)
 * 3. Route lock (all elements are locked atomically, rolled back on partial failure)
 * 4. Conflict exclusion (blocks: enforced via [registry]; switches: lock-ownership check in
 *    [lockSwitches])
 *
 * **Design Notes:**
 *
 * - **Block/Switch/Signal lookup:** Named elements are indexed once at construction time
 *   (mirrors [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort]). An unknown name
 *   anywhere in the requested route is treated as a denial (fail closed), not as "assumed free".
 *
 * - **Progressive lock release:** The current MVP releases the entire route atomically.
 *   SP3.5 will implement progressive release (block-by-block as the train physically clears).
 *
 * - **Switch position mapping:** [SwitchPosition] (PLUS/MINUS, route-spec layer) and
 *   [RailSwitch.Conf] (MAIN/BRANCH, domain object layer) are disjoint in the codebase;
 *   [SwitchPosition.toConf] below is the canonical mapping (first introduced here).
 *
 * - **Cleared-signal tracking:** The kernel remembers which entry signal it cleared per train
 *   ([clearedSignals]) so [releaseRoute] resets exactly that signal, regardless of the
 *   [exitSignal] argument supplied by the caller (which is audit-only). Since Issue #893 task A6,
 *   every clear is ALSO recorded with the [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]
 *   (single signal ledger — see [clearSignal]), so a release routed directly through the service
 *   (bypassing this facade) resets the signal too. [clearedSignals] is kept only so THIS facade's
 *   own [releaseRoute] stays a correct, idempotent no-op no matter which side releases first.
 *
 * @property env The simulation environment providing access to network elements
 *              (blocks, switches, signals, and the block graph).
 * @property registry The [PathReservationRegistry] SHARED with the context's
 *              `PathReservationService` (same scoped instance — see `CoreModule`). Using the
 *              same registry means a route granted here is correctly released later by
 *              `PathReservationService.releasePath()` in [releaseRoute].
 *
 * @since Issue #572 (SP3.4 — Goal 10)
 */
class DefaultInterlockingFacade(
	private val env: SimulationEnvironment,
	private val registry: PathReservationRegistry
) : InterlockingFacade {
	/** All track-block edges indexed by name for O(1) lookup (unnamed blocks are excluded). */
	private val blockByName: Map<String, DynamicTrackBlock> =
		env
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.filter { !it.name.isNullOrBlank() }
			.associateBy { it.name!! }

	/** All rail-switch cells indexed by name for O(1) lookup. */
	private val switchByName: Map<String, DynamicRailSwitch> =
		env
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSwitch>()
			.filter { it.name.isNotBlank() }
			.associateBy { it.name }

	/** All semaphore cells indexed by name for O(1) lookup. */
	private val semaphoreByName: Map<String, DynamicRailSemaphore> =
		env
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSemaphore>()
			.filter { it.name.isNotBlank() }
			.associateBy { it.name }

	/** All InOut endpoint cells indexed by name for O(1) lookup (SP3.5). */
	private val inOutByName: Map<String, DynamicInOut> =
		env.getInOuts().associateBy { it.name }

	/**
	 * The entry signal the kernel actually cleared per train in [requestRoute] (C4/I4).
	 * [releaseRoute] resets exactly this signal, ignoring the caller-supplied [exitSignal]
	 * (audit-only). A train with no entry here has no active route and no signal is reset.
	 */
	private val clearedSignals: MutableMap<String, DynamicRailSemaphore> = mutableMapOf()

	/**
	 * A four-condition [requestRoute] denial: the human-readable [reason] plus the [retryable]
	 * flag forwarded as `DenialCause.ConditionFailed(retryable)`. The helpers below return this
	 * instead of a bare `String?` so the cause is accurate *per underlying reason* — several
	 * helpers can produce both a permanent output defect (unknown name) and a transient
	 * contention (occupied/locked) from one check, and collapsing them to a single site-level
	 * retryable would mislabel one of the two (review finding #2; ruling sanity-checked with
	 * gemma4).
	 *
	 * `retryable = true` = transient track contention (another train holds a resource; clears on
	 * its own); `retryable = false` = a permanent dispatcher output defect (unknown name, empty
	 * route, mismatched signal, un-clearable signal) — an identical retry fails identically.
	 *
	 * @since Issue #834 (SP2c.11 — Goal 10, review finding #2)
	 */
	private data class ConditionDenial(
		val reason: String,
		val retryable: Boolean
	)

	override fun requestRoute(
		trainId: String,
		entrySignal: SignalId,
		route: TrainRoute,
		clearedAspect: Aspect
	): InterlockingFacade.RouteResponse {
		logger.debug {
			"requestRoute: trainId=$trainId, entrySignal=${entrySignal.name}, " +
				"from=${route.from.name} to=${route.to.name}, " +
				"blocks=${route.blocks.map { it.name }}, clearedAspect=${clearedAspect.humanLabel()}"
		}

		// Precondition (C1): a route with no blocks protects nothing — never clear a signal for it.
		// Permanent output defect: an identical retry has the same empty route.
		if (route.blocks.isEmpty()) {
			logger.info { "Route denied for trainId=$trainId: empty route" }
			return InterlockingFacade.RouteResponse.Denied(
				"Empty route — no track sections",
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = false)
			)
		}

		// Precondition (I5): the signal to clear must be the route's entry separator.
		// Permanent output defect: the dispatcher named a signal that is not the route's origin.
		if (entrySignal.name != route.from.name) {
			logger.info {
				"Route denied for trainId=$trainId: signal ${entrySignal.name} does not match route origin ${route.from.name}"
			}
			return InterlockingFacade.RouteResponse.Denied(
				"Signal ${entrySignal.name} does not match route origin ${route.from.name}",
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = false)
			)
		}

		// Condition 1: Check route freedom (all blocks must be FREE)
		checkRouteFreedom(route)?.let { denial ->
			logger.info { "Route denied for trainId=$trainId: ${denial.reason}" }
			return InterlockingFacade.RouteResponse.Denied(
				denial.reason,
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = denial.retryable)
			)
		}

		// Condition 2: Check switch positions (running and flank switches)
		checkSwitchPositions(route)?.let { denial ->
			logger.info { "Route denied for trainId=$trainId: ${denial.reason}" }
			return InterlockingFacade.RouteResponse.Denied(
				denial.reason,
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = denial.retryable)
			)
		}

		// Conditions 3 & 4: Lock the route atomically (blocks via registry, switches via lock())
		lockRouteAtomic(trainId, route)?.let { denial ->
			logger.info { "Route denied for trainId=$trainId: ${denial.reason}" }
			return InterlockingFacade.RouteResponse.Denied(
				denial.reason,
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = denial.retryable)
			)
		}

		// C2: never return Granted unless the signal actually shows clearedAspect. If the signal
		// is unknown or the aspect is unmappable, clearSignal returns null and we roll back the
		// locks just acquired so no state leaks. Both sub-cases are permanent (output/map defect),
		// not contention.
		val clearedSemaphore = clearSignal(entrySignal, clearedAspect, trainId)
		if (clearedSemaphore == null) {
			env.getRoutingServices().getPathReservationService().releasePath(trainId)
			logger.info { "Route denied for trainId=$trainId: signal ${entrySignal.name} cannot be cleared" }
			return InterlockingFacade.RouteResponse.Denied(
				"Signal ${entrySignal.name} cannot be cleared (unknown signal or invalid signal aspect)",
				InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = false)
			)
		}

		// C4: remember which entry signal we cleared so releaseRoute resets exactly this one.
		clearedSignals[trainId] = clearedSemaphore

		logger.info {
			"Route GRANTED for trainId=$trainId: " +
				"${route.blocks.size} blocks locked, ${route.running.size + route.flank.size} switches locked"
		}
		return InterlockingFacade.RouteResponse.Granted(
			clearedAspect = clearedAspect,
			lockedRoute = route
		)
	}

	override fun releaseRoute(
		trainId: String,
		exitSignal: SignalId
	) {
		logger.debug { "releaseRoute: trainId=$trainId, exitSignal=${exitSignal.name} (audit only)" }

		// Release all blocks and switches reserved for this train (shared registry — see the
		// class KDoc — so this clears everything locked by requestRoute()). releasePath is
		// idempotent: an unknown trainId yields an empty list and touches nothing.
		val releasedBlocks = env.getRoutingServices().getPathReservationService().releasePath(trainId)
		logger.info { "Released ${releasedBlocks.size} blocks for trainId=$trainId" }

		// C4/I4: reset exactly the entry signal the kernel cleared for this train. The caller's
		// exitSignal is audit-only and never selects a signal to reset, so a caller error (wrong
		// trainId or wrong exitSignal) cannot disrupt another train's cleared signal.
		val cleared = clearedSignals.remove(trainId)
		if (cleared != null) {
			cleared.signal = Signal.STOP
			logger.info { "Cleared entry signal reset to STOP for trainId=$trainId" }
		} else {
			logger.info { "No tracked cleared signal for trainId=$trainId; nothing to reset" }
		}
	}

	override fun requestRouteByEndpoints(
		trainId: String,
		fromEndpointName: String,
		toEndpointName: String
	): InterlockingFacade.RouteResponse {
		logger.debug {
			"requestRouteByEndpoints: trainId=$trainId, $fromEndpointName → $toEndpointName"
		}

		// No reservation is attempted for an unresolvable endpoint, so there is no candidate-path
		// count and no owning train to report: these two denials keep the default residual cause
		// (DenialCause.Other). Classifying them as contention would invent a count that does not
		// exist and tell the caller to retry a request that can never succeed (Issue #834).
		val fromEndpoint =
			resolveEndpoint(fromEndpointName)
				?: return InterlockingFacade.RouteResponse.Denied(
					"Unknown route endpoint: $fromEndpointName"
				)
		val toEndpoint =
			resolveEndpoint(toEndpointName)
				?: return InterlockingFacade.RouteResponse.Denied(
					"Unknown route endpoint: $toEndpointName"
				)

		return when (
			val result =
				env
					.getRoutingServices()
					.getPathReservationService()
					.reservePath(trainId, fromEndpoint, toEndpoint)
		) {
			is PathReservationService.ReservationResult.Success -> {
				// Every physically reserved block must be represented here, even if unnamed —
				// silently dropping unnamed blocks (via mapNotNull on the name) would undercount
				// blocksCount downstream in DefaultNetworkActuatorPort.requestRoute's
				// RouteRequestResult.Reserved, which is what the dispatcher/tool caller observes.
				val blocks = result.reservedBlocks.mapIndexed { index, b -> BlockId(b.name ?: "unnamed-$index") }
				val route =
					TrainRoute(
						from = SignalId(fromEndpointName),
						to = SignalId(toEndpointName),
						running = emptyList(),
						blocks = blocks
					)
				logger.info {
					"Route GRANTED (by endpoints) for trainId=$trainId: " +
						"${blocks.size} blocks reserved ($fromEndpointName → $toEndpointName)"
				}
				InterlockingFacade.RouteResponse.Granted(Aspect.Volno, route)
			}
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.info {
					"Route DENIED for trainId=$trainId: no path exists " +
						"$fromEndpointName → $toEndpointName"
				}
				InterlockingFacade.RouteResponse.Denied(
					"No path exists: $fromEndpointName → $toEndpointName",
					InterlockingFacade.RouteResponse.DenialCause.NoPath
				)
			}
			is PathReservationService.ReservationResult.AllPathsBlocked -> {
				logger.info {
					"Route DENIED for trainId=$trainId: all paths blocked " +
						"(attempts: ${result.attemptedPaths}, $fromEndpointName → $toEndpointName)"
				}
				InterlockingFacade.RouteResponse.Denied(
					"All paths blocked ($fromEndpointName → $toEndpointName, " +
						"attempts: ${result.attemptedPaths})",
					// The same count that is formatted into the reason above, now also carried
					// machine-readably: before Issue #834 task alpha-7a the facade branch of
					// DefaultNetworkActuatorPort reported attemptedPaths=0 for every denial,
					// contradicting RouteRequestResult.AllPathsBlocked's own contract.
					InterlockingFacade.RouteResponse.DenialCause.AllPathsBlocked(result.attemptedPaths)
				)
			}
			is PathReservationService.ReservationResult.Conflict -> {
				val blockName = result.conflictingBlock.name ?: "?"
				logger.info {
					"Route DENIED for trainId=$trainId: conflict at block $blockName " +
						"(train ${result.existingOwner})"
				}
				InterlockingFacade.RouteResponse.Denied(
					"Block $blockName occupied by train ${result.existingOwner}",
					// The cause carries the block's REAL name (null when unnamed), not the "?"
					// placeholder the human-readable reason substitutes, so a caller can identify
					// the block rather than re-parse the text.
					InterlockingFacade.RouteResponse.DenialCause.Conflict(
						blockName = result.conflictingBlock.name,
						existingOwner = result.existingOwner
					)
				)
			}
			is PathReservationService.ReservationResult.NonContiguousStart -> {
				// Issue #893 (task A-R1b): the requested origin is nowhere near this train. The
				// reason string already names the origin and the legal alternatives, so it is
				// forwarded as-is; the NonContiguousStart cause lets DefaultNetworkActuatorPort
				// map this denial to RouteRequestResult.OriginNotContiguous.
				logger.info {
					"Route DENIED for trainId=$trainId: non-contiguous origin ($fromEndpointName): ${result.reason}"
				}
				InterlockingFacade.RouteResponse.Denied(
					result.reason,
					InterlockingFacade.RouteResponse.DenialCause.NonContiguousStart
				)
			}
		}
	}

	/**
	 * Resolves a named endpoint to its [DynamicPathSeparator], checking [inOutByName]
	 * first then [semaphoreByName].  Returns `null` if no element matches the name.
	 */
	private fun resolveEndpoint(name: String): DynamicPathSeparator? = inOutByName[name] ?: semaphoreByName[name]

	/**
	 * Condition 1: Check that all blocks in the route are FREE — neither physically occupied
	 * nor reserved for another train (via [PathReservationRegistry.isBlockAvailable]).
	 *
	 * @return null if all blocks are free, otherwise a [ConditionDenial]. An *unknown* block name
	 *   is a permanent output defect (`retryable = false`); a block occupied/reserved by another
	 *   train is transient contention (`retryable = true`).
	 */
	private fun checkRouteFreedom(route: TrainRoute): ConditionDenial? {
		for (blockId in route.blocks) {
			val block =
				blockByName[blockId.name] ?: return ConditionDenial("Unknown track section ${blockId.name}", retryable = false)
			if (!registry.isBlockAvailable(block)) {
				val owner = block.trainName ?: block.occupant?.name ?: "unknown train"
				return ConditionDenial("Track section ${blockId.name} occupied by train $owner", retryable = true)
			}
		}
		return null // All blocks are free
	}

	/**
	 * Condition 2: Check that all running and flank switches are in their required position.
	 *
	 * The switch's physical position ([DynamicRailSwitch.conf]) is compared against the route's
	 * [SwitchSetting.position] via the canonical `PLUS ↔ MAIN` / `MINUS ↔ BRANCH` mapping.
	 * Lock-ownership is NOT checked here — it is the authoritative conflict check in
	 * [lockSwitches] (condition 3/4), which rolls back blocks if a switch turns out to be locked
	 * by another train.
	 *
	 * @return null if all switches are in the required position, otherwise a [ConditionDenial]. An
	 *   *unknown* switch name is a permanent output defect (`retryable = false`); a switch not in
	 *   the required position is treated as transient (`retryable = true`): another train may be
	 *   holding/locking it in a different position, which clears when that train releases it.
	 */
	private fun checkSwitchPositions(route: TrainRoute): ConditionDenial? {
		for (switchSetting in route.running) {
			checkSwitchAvailable(switchSetting, "Switch")?.let { return it }
		}
		for (switchSetting in route.flank) {
			checkSwitchAvailable(switchSetting, "Flank switch")?.let { return it }
		}
		return null // All switches are in the required position
	}

	private fun checkSwitchAvailable(
		switchSetting: SwitchSetting,
		label: String
	): ConditionDenial? {
		val switch =
			switchByName[switchSetting.switch.name]
				?: return ConditionDenial("Unknown switch ${switchSetting.switch.name}", retryable = false)
		val requiredConf = switchSetting.position.toConf()
		if (switch.conf != requiredConf) {
			return ConditionDenial(
				"$label ${switchSetting.switch.name} is not in position ${switchSetting.position} " +
					"(required ${requiredConf.name}, actual ${switch.conf.name})",
				retryable = true
			)
		}
		return null
	}

	/**
	 * Conditions 3 & 4: Atomically lock all blocks and switches in the route via [registry].
	 *
	 * Blocks are registered with [PathReservationRegistry.registerAtomic] (which itself detects
	 * condition-4 conflicts against other trains) and then physically reserved with
	 * [DynamicTrackBlock.setUpPath]. Switches are locked via [PathReservationRegistry.registerSwitches]
	 * once verified free. Any failure rolls back everything acquired so far.
	 *
	 * @return null if all locks acquired successfully, otherwise a [ConditionDenial]. An *unknown*
	 *   entry-signal name is a permanent output defect (`retryable = false`); a registration
	 *   conflict, a low-level lock failure, or a switch locked by another train are all transient
	 *   contention (`retryable = true`).
	 */
	private fun lockRouteAtomic(
		trainId: String,
		route: TrainRoute
	): ConditionDenial? {
		val blocks = route.blocks.map { blockByName.getValue(it.name) }
		val switches = (route.running + route.flank).map { switchByName.getValue(it.switch.name) }

		val fromSeparator = if (blocks.isEmpty()) null else semaphoreByName[route.from.name]
		if (blocks.isNotEmpty() && fromSeparator == null) {
			return ConditionDenial("Unknown signal ${route.from.name}", retryable = false)
		}

		if (fromSeparator != null) {
			registerBlocks(trainId, blocks, fromSeparator)?.let { return it }
		}

		lockSwitches(trainId, switches)?.let { switchDenial ->
			if (fromSeparator != null) rollbackBlocks(trainId, blocks, fromSeparator)
			return switchDenial
		}

		return null // All locks acquired successfully
	}

	/**
	 * Registers and physically reserves [blocks] for [trainId], rolling back on partial failure.
	 *
	 * @return null on success, otherwise a [ConditionDenial] (transient contention; nothing left
	 *   reserved).
	 */
	private fun registerBlocks(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		fromSeparator: DynamicPathSeparator
	): ConditionDenial? {
		when (val result = registry.registerAtomic(trainId, blocks)) {
			is PathReservationRegistry.RegistrationResult.Conflict ->
				return ConditionDenial(
					"Track section ${result.conflictingBlock.name ?: "?"} occupied by train ${result.existingOwner}",
					retryable = true
				)
			PathReservationRegistry.RegistrationResult.Success -> Unit
		}

		val reservedSoFar = mutableListOf<DynamicTrackBlock>()
		try {
			for (block in blocks) {
				block.setUpPath(fromSeparator, trainId)
				reservedSoFar.add(block)
			}
		} catch (e: Exception) {
			logger.error(e) { "Failed to reserve blocks for trainId=$trainId: ${e.message}" }
			reservedSoFar.forEach { runCatching { it.cancelPathSetup(fromSeparator) } }
			registry.unregister(trainId)
			return ConditionDenial("Track section cannot be locked", retryable = true)
		}
		return null
	}

	private fun rollbackBlocks(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		fromSeparator: DynamicPathSeparator
	) {
		blocks.forEach { runCatching { it.cancelPathSetup(fromSeparator) } }
		registry.unregister(trainId)
	}

	/**
	 * Locks [switches] for [trainId] via [PathReservationRegistry.registerSwitches], after
	 * verifying none are already locked by a different train.
	 *
	 * @return null on success, otherwise a [ConditionDenial] (transient contention — the switch
	 *   is locked by another train; nothing left locked).
	 */
	private fun lockSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	): ConditionDenial? {
		// Authoritative switch lock-conflict check (M6). Condition 2 above only verifies position;
		// lock-ownership is checked here, immediately before registration. This is the single
		// point that decides a switch is free to lock, so [lockRouteAtomic] rolls back any blocks
		// already reserved when this fails. The check is defence-in-depth: in a single-threaded
		// kDisco context a switch that passed condition 2 will not change between here and
		// [registerSwitches], but under a future concurrent model the re-check guards the TOCTOU
		// window between the position check and the lock.
		for (switch in switches) {
			if (switch.locked && registry.getSwitchOwner(switch) != trainId) {
				return ConditionDenial("Switch ${switch.name} is locked", retryable = true)
			}
		}
		registry.registerSwitches(trainId, switches)
		return null
	}

	/**
	 * Clears [entrySignal] to [clearedAspect] (C2). Returns the [DynamicRailSemaphore] that was
	 * cleared, or null if the signal is unknown or the aspect has no [Signal] equivalent — in
	 * both cases the signal is left untouched and the caller MUST roll back the locks it just
	 * acquired and deny the route (never return Granted without a cleared signal).
	 *
	 * ## Single signal ledger (Issue #893, task A6 -- G6)
	 *
	 * A successful clear is ALSO recorded with the [PathReservationService] via
	 * [PathReservationService.recordExternalClearedSemaphore], so a release routed through the
	 * service directly (the `OrphanReservationSweeper`, via
	 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.releaseRoute]) sees this grant and
	 * resets the signal, instead of only [releaseRoute] on THIS facade knowing about it. [clearedSignals]
	 * is still populated below (see [releaseRoute]) so this facade's own release stays a correct,
	 * idempotent no-op regardless of which side released first — the service is now the single
	 * source of truth; this facade's own map exists solely for that idempotency.
	 */
	private fun clearSignal(
		entrySignal: SignalId,
		clearedAspect: Aspect,
		trainId: String
	): DynamicRailSemaphore? {
		val signal = semaphoreByName[entrySignal.name]
		if (signal == null) {
			logger.warn { "Signal ${entrySignal.name} not found in network; route will be denied" }
			return null
		}
		val targetSignalState = clearedAspect.toSignal()
		if (targetSignalState == null) {
			logger.warn {
				"Aspect ${clearedAspect.humanLabel()} has no Signal equivalent; " +
					"route will be denied (signal unchanged)"
			}
			return null
		}
		signal.signal = targetSignalState
		env.getRoutingServices().getPathReservationService().recordExternalClearedSemaphore(trainId, signal)
		logger.info { "Signal ${entrySignal.name} cleared to ${clearedAspect.humanLabel()} for trainId=$trainId" }
		return signal
	}
}

/**
 * Canonical mapping from the route-spec [SwitchPosition] (PLUS/MINUS) to the domain-object
 * [RailSwitch.Conf] (MAIN/BRANCH). First such mapping in the codebase — the two layers were
 * previously disjoint. `PLUS ↔ MAIN` (straight/main direction), `MINUS ↔ BRANCH` (diverging).
 */
private fun SwitchPosition.toConf(): RailSwitch.Conf =
	when (this) {
		SwitchPosition.PLUS -> RailSwitch.Conf.MAIN
		SwitchPosition.MINUS -> RailSwitch.Conf.BRANCH
	}
