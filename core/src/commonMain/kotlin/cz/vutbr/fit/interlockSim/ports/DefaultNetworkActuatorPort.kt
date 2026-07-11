/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Simulation-backed implementation of [NetworkActuatorPort].
 *
 * Translates string-based dispatcher commands into actual simulation operations via
 * [SimulationEnvironment] and [PathReservationService].  This is the **act** side of the
 * agent's sense → decide → act loop, symmetric to [DefaultNetworkPerceptionPort] on the
 * sense side.
 *
 * ## Design
 *
 * Network elements (InOuts, semaphores, switches) are indexed by name at construction
 * time.  This makes per-call lookups O(1) and mirrors the approach in
 * [DefaultNetworkPerceptionPort].  All cached objects are stable for the lifetime of a
 * simulation context; no element is added or removed at runtime.
 *
 * The `requestRoute` method maps directly to
 * [PathReservationService.reservePath] with the located [DynamicInOut] objects as
 * endpoints.  `releaseRoute` maps to [PathReservationService.releasePath].  Switch and
 * signal commands mutate the respective dynamic wrapper's property directly (same pattern
 * used by the interlocking and dispatcher logic in [ShuntingLoop]).
 *
 * ## String-based identifiers
 *
 * All methods identify elements by their string names (same as [NetworkActuatorPort]
 * contract).  Unknown names produce well-defined failure responses as documented on each
 * method — no live domain objects are ever leaked to callers.
 *
 * ## Thread-safety
 *
 * Not thread-safe.  All calls must originate from the single kDisco simulation thread.
 *
 * @param env The simulation environment providing element lookups and routing services.
 * @param pathReservationService Path reservation service (defaults to
 *   `env.getRoutingServices().getPathReservationService()`); injectable for testing.
 *
 * @see NetworkActuatorPort
 * @see DefaultNetworkPerceptionPort
 * @since Issue #545 (SP0.6 — Goal 10)
 */
class DefaultNetworkActuatorPort(
	private val env: SimulationEnvironment,
	private val pathReservationService: PathReservationService =
		env.getRoutingServices().getPathReservationService()
) : NetworkActuatorPort {
	// ── Caches built once at construction ─────────────────────────────────

	/** All InOut wrappers indexed by their name for O(1) lookup. */
	private val inOutByName: Map<String, DynamicInOut> =
		env.getInOuts().associateBy { it.name }

	/** All semaphore cells indexed by name for O(1) lookup. */
	private val semaphoreByName: Map<String, DynamicRailSemaphore> =
		buildSemaphoreCache()

	/** All rail-switch cells indexed by name for O(1) lookup. */
	private val switchByName: Map<String, DynamicRailSwitch> =
		buildSwitchCache()

	// ── NetworkActuatorPort ───────────────────────────────────────────────

	override fun requestRoute(
		trainName: String,
		fromEndpointName: String,
		toEndpointName: String
	): RouteRequestResult {
		require(trainName.isNotBlank()) { "trainName must be non-blank" }
		val from = requireEndpoint(fromEndpointName)
		val to = requireEndpoint(toEndpointName)

		return when (val result = pathReservationService.reservePath(trainName, from, to)) {
			is PathReservationService.ReservationResult.Success ->
				RouteRequestResult.Reserved(
					trainName = trainName,
					blocksCount = result.reservedBlocks.size
				)
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.debug { "requestRoute: no topology path $fromEndpointName → $toEndpointName" }
				RouteRequestResult.NoRouteExists(fromEndpointName, toEndpointName)
			}
			is PathReservationService.ReservationResult.AllPathsBlocked -> {
				logger.debug {
					"requestRoute: all paths blocked $fromEndpointName → $toEndpointName " +
						"(attempted: ${result.attemptedPaths})"
				}
				RouteRequestResult.AllPathsBlocked(result.attemptedPaths)
			}
			is PathReservationService.ReservationResult.Conflict -> {
				// A path exists but a block along it is already owned by another train.
				// Surface who is blocking so a dispatcher can wait for that specific train
				// rather than collapsing this into AllPathsBlocked (which would discard the
				// owner/block and report attemptedPaths=0, contradicting its own contract).
				logger.warn {
					"requestRoute: conflict reserving $fromEndpointName → $toEndpointName for " +
						"$trainName — block '${result.conflictingBlock.name ?: "unnamed"}' " +
						"owned by '${result.existingOwner}'"
				}
				RouteRequestResult.Conflict(
					blockName = result.conflictingBlock.name,
					existingOwner = result.existingOwner
				)
			}
		}
	}

	override fun releaseRoute(trainName: String): Boolean {
		require(trainName.isNotBlank()) { "trainName must be non-blank" }
		val released = pathReservationService.releasePath(trainName)
		return released.isNotEmpty()
	}

	override fun setSwitchPosition(
		switchName: String,
		position: RailSwitch.Conf
	): Boolean {
		val sw =
			switchByName[switchName] ?: run {
				logger.debug { "setSwitchPosition: unknown switch '$switchName'" }
				return false
			}
		if (sw.conf == position) return true
		if (sw.locked) {
			// Switch is locked by a route reservation — cannot be moved while a path is active.
			logger.debug { "setSwitchPosition: switch '$switchName' is locked by route reservation" }
			return false
		}
		return try {
			sw.changeConf()
			sw.conf == position
		} catch (e: IllegalStateException) {
			logger.warn { "setSwitchPosition: cannot change '$switchName': ${e.message}" }
			false
		}
	}

	override fun setSignalAspect(
		semaphoreName: String,
		signal: Signal
	): Boolean {
		val sem =
			semaphoreByName[semaphoreName] ?: run {
				logger.debug { "setSignalAspect: unknown semaphore '$semaphoreName'" }
				return false
			}
		// Both upgrades and downgrades are permitted on a dynamic semaphore (downgrades are
		// physically hard for a train to reach due to braking, but still allowed).  A
		// *constant* semaphore (predzvěst / narážník / rychlostník) ignores writes, so the
		// post-condition below reports failure when a change to a different aspect is
		// requested — constant semaphores must stay constant.  Setting the semaphore to its
		// current aspect is a no-op that returns true.
		sem.signal = signal
		return sem.signal == signal
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	/**
	 * Returns the [DynamicPathSeparator] for [name], searching both [inOutByName] and
	 * [semaphoreByName].  Throws [IllegalArgumentException] if the name is not recognised
	 * as either an InOut or a Semaphore in this network.
	 *
	 * Partial paths (InOut → Semaphore or Semaphore → InOut) are valid in addition to the
	 * full end-to-end (InOut → InOut) form, so both element types are accepted here.
	 */
	private fun requireEndpoint(name: String): DynamicPathSeparator =
		(inOutByName[name] ?: semaphoreByName[name])
			?: throw IllegalArgumentException(
				"Unknown endpoint '$name' in network " +
					"(known InOuts: ${inOutByName.keys.sorted()}, " +
					"known Semaphores: ${semaphoreByName.keys.sorted()})"
			)

	/**
	 * Scans the grid once to build a name→semaphore index.  Mirrors the strategy in
	 * [DefaultNetworkPerceptionPort.buildSemaphoreCache].
	 */
	private fun buildSemaphoreCache(): Map<String, DynamicRailSemaphore> =
		env
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSemaphore>()
			.filter { it.name.isNotBlank() }
			.associateBy { it.name }

	/**
	 * Scans the grid once to build a name→switch index.
	 */
	private fun buildSwitchCache(): Map<String, DynamicRailSwitch> =
		env
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSwitch>()
			.filter { it.name.isNotBlank() }
			.associateBy { it.name }
}
