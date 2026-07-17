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
import cz.vutbr.fit.interlockSim.lang.toSignal
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
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
 * 2. Switch positions (running and flank switches are not locked by another train)
 * 3. Route lock (all elements are locked atomically, rolled back on partial failure)
 * 4. Conflict exclusion (blocks: enforced via [registry]; switches: not-locked check above)
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
 * - **Switch position matching:** Only lock-ownership is checked, not [SwitchSetting.position]
 *   against the switch's actual [DynamicRailSwitch.conf]. Deferred to SP3.5.
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

		// Condition 1: Check route freedom (all blocks must be FREE)
		val freedomCheckReason = checkRouteFreedom(route)
		if (freedomCheckReason != null) {
			logger.info { "Route denied for trainId=$trainId: $freedomCheckReason" }
			return InterlockingFacade.RouteResponse.Denied(freedomCheckReason)
		}

		// Condition 2: Check switch positions (running and flank switches)
		val switchCheckReason = checkSwitchPositions(route)
		if (switchCheckReason != null) {
			logger.info { "Route denied for trainId=$trainId: $switchCheckReason" }
			return InterlockingFacade.RouteResponse.Denied(switchCheckReason)
		}

		// Conditions 3 & 4: Lock the route atomically (blocks via registry, switches via lock())
		val lockReason = lockRouteAtomic(trainId, route)
		if (lockReason != null) {
			logger.info { "Route denied for trainId=$trainId: $lockReason" }
			return InterlockingFacade.RouteResponse.Denied(lockReason)
		}

		clearSignal(entrySignal, clearedAspect, trainId)

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
		logger.debug { "releaseRoute: trainId=$trainId, exitSignal=${exitSignal.name}" }

		try {
			// Release all blocks and switches reserved for this train (shared registry — see
			// the class KDoc — so this correctly clears everything locked by requestRoute()).
			val releasedBlocks = env.getRoutingServices().getPathReservationService().releasePath(trainId)
			logger.info { "Released ${releasedBlocks.size} blocks for trainId=$trainId" }

			val signal = semaphoreByName[exitSignal.name]
			if (signal != null) {
				signal.signal = Signal.STOP
				logger.info { "Signal ${exitSignal.name} reset to STOP after route release for trainId=$trainId" }
			} else {
				logger.warn { "Signal ${exitSignal.name} not found in network; nothing to reset" }
			}
		} catch (e: Exception) {
			logger.error(e) { "Error releasing route for trainId=$trainId: ${e.message}" }
		}
	}

	/**
	 * Condition 1: Check that all blocks in the route are FREE — neither physically occupied
	 * nor reserved for another train (via [PathReservationRegistry.isBlockAvailable]).
	 *
	 * @return null if all blocks are free, otherwise a Czech denial reason.
	 */
	private fun checkRouteFreedom(route: TrainRoute): String? {
		for (blockId in route.blocks) {
			val block = blockByName[blockId.name] ?: return "Neznámý úsek ${blockId.name}"
			if (!registry.isBlockAvailable(block)) {
				val owner = block.trainName ?: block.occupant?.name ?: "neznámý vlak"
				return "Úsek ${blockId.name} obsazen vlakem $owner"
			}
		}
		return null // All blocks are free
	}

	/**
	 * Condition 2: Check that all running and flank switches are not locked by another train.
	 *
	 * @return null if all switches are available, otherwise a Czech denial reason.
	 */
	private fun checkSwitchPositions(route: TrainRoute): String? {
		for (switchSetting in route.running) {
			checkSwitchAvailable(switchSetting, "Výhybka")?.let { return it }
		}
		for (switchSetting in route.flank) {
			checkSwitchAvailable(switchSetting, "Odvratná výhybka")?.let { return it }
		}
		return null // All switches are available for locking
	}

	private fun checkSwitchAvailable(
		switchSetting: SwitchSetting,
		label: String
	): String? {
		val switch = switchByName[switchSetting.switch.name] ?: return "Neznámá výhybka ${switchSetting.switch.name}"
		if (switch.locked) {
			return "$label ${switchSetting.switch.name} je zafixovaná"
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
	 * @return null if all locks acquired successfully, otherwise a Czech denial reason.
	 */
	private fun lockRouteAtomic(
		trainId: String,
		route: TrainRoute
	): String? {
		val blocks = route.blocks.map { blockByName.getValue(it.name) }
		val switches = (route.running + route.flank).map { switchByName.getValue(it.switch.name) }

		val fromSeparator = if (blocks.isEmpty()) null else semaphoreByName[route.from.name]
		if (blocks.isNotEmpty() && fromSeparator == null) {
			return "Neznámé návěstidlo ${route.from.name}"
		}

		if (fromSeparator != null) {
			val blockReason = registerBlocks(trainId, blocks, fromSeparator)
			if (blockReason != null) return blockReason
		}

		val switchReason = lockSwitches(trainId, switches)
		if (switchReason != null) {
			if (fromSeparator != null) rollbackBlocks(trainId, blocks, fromSeparator)
			return switchReason
		}

		return null // All locks acquired successfully
	}

	/**
	 * Registers and physically reserves [blocks] for [trainId], rolling back on partial failure.
	 *
	 * @return null on success, otherwise a Czech denial reason (nothing left reserved).
	 */
	private fun registerBlocks(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		fromSeparator: DynamicPathSeparator
	): String? {
		when (val result = registry.registerAtomic(trainId, blocks)) {
			is PathReservationRegistry.RegistrationResult.Conflict ->
				return "Úsek ${result.conflictingBlock.name ?: "?"} obsazen vlakem ${result.existingOwner}"
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
			return "Úsek nelze zamknout"
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
	 * @return null on success, otherwise a Czech denial reason (nothing left locked).
	 */
	private fun lockSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	): String? {
		for (switch in switches) {
			if (switch.locked && registry.getSwitchOwner(switch) != trainId) {
				return "Výhybka ${switch.name} je zafixovaná"
			}
		}
		registry.registerSwitches(trainId, switches)
		return null
	}

	/** Clears [entrySignal] to [clearedAspect] if the signal is known; safety fallback otherwise. */
	private fun clearSignal(
		entrySignal: SignalId,
		clearedAspect: Aspect,
		trainId: String
	) {
		val signal = semaphoreByName[entrySignal.name]
		if (signal == null) {
			logger.warn { "Signal ${entrySignal.name} not found in network; proceeding anyway" }
			return
		}
		val targetSignalState = clearedAspect.toSignal()
		if (targetSignalState == null) {
			logger.warn {
				"Aspect ${clearedAspect.humanLabel()} has no Signal equivalent; " +
					"signal remains unchanged (safety fallback)"
			}
			return
		}
		signal.signal = targetSignalState
		logger.info { "Signal ${entrySignal.name} cleared to ${clearedAspect.humanLabel()} for trainId=$trainId" }
	}
}
