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
import cz.vutbr.fit.interlockSim.objects.core.Cell
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
		fromInOutName: String,
		toInOutName: String
	): RouteRequestResult {
		require(trainName.isNotBlank()) { "trainName must be non-blank" }
		val from = requireInOut(fromInOutName)
		val to = requireInOut(toInOutName)

		return when (val result = pathReservationService.reservePath(trainName, from, to)) {
			is PathReservationService.ReservationResult.Success ->
				RouteRequestResult.Reserved(
					trainName = trainName,
					blocksCount = result.reservedBlocks.size
				)
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.debug { "requestRoute: no topology path $fromInOutName → $toInOutName" }
				RouteRequestResult.NoRouteExists(fromInOutName, toInOutName)
			}
			is PathReservationService.ReservationResult.AllPathsBlocked -> {
				logger.debug {
					"requestRoute: all paths blocked $fromInOutName → $toInOutName " +
						"(attempted: ${result.attemptedPaths})"
				}
				RouteRequestResult.AllPathsBlocked(result.attemptedPaths)
			}
			is PathReservationService.ReservationResult.Conflict -> {
				// Treat a reservation conflict like AllPathsBlocked(0): the path
				// technically exists but cannot be reserved right now.
				logger.warn {
					"requestRoute: conflict reserving $fromInOutName → $toInOutName for " +
						"$trainName — block '${result.conflictingBlock.name ?: "unnamed"}' " +
						"owned by '${result.existingOwner}'"
				}
				RouteRequestResult.AllPathsBlocked(0)
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
		if (sw.locked) {
			logger.debug { "setSwitchPosition: switch '$switchName' is locked" }
			return false
		}
		if (sw.conf == position) return true
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
		sem.signal = signal
		return true
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	/**
	 * Returns the [DynamicInOut] for [name], throwing [IllegalArgumentException] if the
	 * name is not recognised in this network.
	 */
	private fun requireInOut(name: String): DynamicInOut =
		inOutByName[name]
			?: throw IllegalArgumentException(
				"Unknown InOut '$name' in network (known: ${inOutByName.keys.sorted()})"
			)

	/**
	 * Scans the grid once to build a name→semaphore index.  Mirrors the strategy in
	 * [DefaultNetworkPerceptionPort.buildSemaphoreCache].
	 */
	private fun buildSemaphoreCache(): Map<String, DynamicRailSemaphore> {
		val grid = env.getRailWayNetGrid()
		val result = mutableMapOf<String, DynamicRailSemaphore>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell: Cell? = grid.getCellAt(x, y)
				if (cell is DynamicRailSemaphore && cell.name.isNotBlank()) {
					result[cell.name] = cell
				}
			}
		}
		return result
	}

	/**
	 * Scans the grid once to build a name→switch index.
	 */
	private fun buildSwitchCache(): Map<String, DynamicRailSwitch> {
		val grid = env.getRailWayNetGrid()
		val result = mutableMapOf<String, DynamicRailSwitch>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell: Cell? = grid.getCellAt(x, y)
				if (cell is DynamicRailSwitch && cell.name.isNotBlank()) {
					result[cell.name] = cell
				}
			}
		}
		return result
	}
}
