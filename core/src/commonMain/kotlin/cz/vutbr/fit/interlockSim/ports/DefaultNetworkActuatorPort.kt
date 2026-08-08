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
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
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
 * @param interlockingFacade SP3.5 (Issue #573): when non-null, [requestRoute] is routed
 *   through the interlocking safety kernel as the **single chokepoint** protecting all
 *   callers (Koog tools arriving via [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue],
 *   [cz.vutbr.fit.interlockSim.sim.SynchronousDispatcherWiring],
 *   [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]).
 *   When `null` (default), falls back to the legacy direct
 *   [PathReservationService.reservePath] path — used by `:fast-sim` and tests that run
 *   without Koin DI. Production wiring always provides the facade.
 *
 * @see NetworkActuatorPort
 * @see DefaultNetworkPerceptionPort
 * @since Issue #545 (SP0.6 — Goal 10)
 */
class DefaultNetworkActuatorPort(
	private val env: SimulationEnvironment,
	private val pathReservationService: PathReservationService =
		env.getRoutingServices().getPathReservationService(),
	private val interlockingFacade: InterlockingFacade? = null
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

		// SP3.5: route through the interlocking safety kernel when wired in production.
		// Endpoint existence is validated first (preserving the IllegalArgumentException
		// contract for unknown names), then the facade handles C1/C3/C4 safety checks.
		val facade = interlockingFacade
		if (facade != null) {
			requireEndpoint(fromEndpointName)
			requireEndpoint(toEndpointName)
			return when (val response = facade.requestRouteByEndpoints(trainName, fromEndpointName, toEndpointName)) {
				is InterlockingFacade.RouteResponse.Granted ->
					RouteRequestResult.Reserved(
						trainName = trainName,
						blocksCount = response.lockedRoute.blocks.size
					)
				is InterlockingFacade.RouteResponse.Denied -> {
					logger.debug {
						"requestRoute: denied by interlocking for $trainName " +
							"($fromEndpointName → $toEndpointName): ${response.reason}"
					}
					if (response.originNotContiguous) {
						// Issue #893 (task A-R1b): the kernel identified this specifically as a
						// non-contiguous-origin rejection (ReservationResult.NonContiguousStart),
						// surfaced by DefaultInterlockingFacade.requestRouteByEndpoints via the
						// originNotContiguous flag. Map it the same way the legacy/no-facade branch
						// below does, reason string preserved verbatim.
						logger.warn {
							"requestRoute: non-contiguous origin for $trainName " +
								"($fromEndpointName → $toEndpointName): ${response.reason}"
						}
						RouteRequestResult.OriginNotContiguous(fromEndpointName, response.reason)
					} else {
						// Every other interlocking denial still collapses to AllPathsBlocked(0);
						// callers retry on the next tick. The specific reason is logged above and
						// in the facade. Widening this to other RouteResponse.Denied causes is a
						// spin-off candidate, not part of task A-R1b.
						RouteRequestResult.AllPathsBlocked(0)
					}
				}
			}
		}

		// Legacy path (no facade): direct PathReservationService.reservePath.
		// Used by :fast-sim native binary and tests that run without Koin DI.
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
			is PathReservationService.ReservationResult.NonContiguousStart -> {
				// The train is nowhere near fromEndpointName; reserving would lock track it can
				// never reach. Surfaced as its own result rather than folded into AllPathsBlocked
				// so the caller can tell "ask again later" from "ask for a different origin".
				logger.warn {
					"requestRoute: non-contiguous origin for $trainName " +
						"($fromEndpointName → $toEndpointName): ${result.reason}"
				}
				RouteRequestResult.OriginNotContiguous(fromEndpointName, result.reason)
			}
		}
	}

	override fun releaseRoute(trainName: String): Boolean {
		require(trainName.isNotBlank()) { "trainName must be non-blank" }
		// Issue #893 task A7: read BEFORE releasePath, which purges this bookkeeping as a side
		// effect of resetting the signals it recorded -- see PathReservationService.hasClearedSignals.
		val hadClearedSignals = pathReservationService.hasClearedSignals(trainName)
		val releasedBlocks = pathReservationService.releasePath(trainName)
		// Truthful per the traffic-simulation-expert R5 ruling: "the train's route state is now
		// clear" is true whenever EITHER blocks or signals were actually released. Before this fix,
		// a train holding cleared signals but zero blocks (reachable after a partial release
		// reclaimed its un-travelled tail, tasks A3/A4) had its signals genuinely reset here while
		// this method reported `false` -- OrphanReservationSweeper then never counted the reclaim
		// and, worse, treated the still-visible holding as unresolved on every subsequent sweep.
		// Deliberately NOT forced to `true` when NEITHER blocks nor signals existed: that case is
		// "trainName held no reservation at all", which DispatchDecisionApplier surfaces to the LLM
		// dispatcher as a distinct NO_RESERVATION outcome (AppliedOutcomeChannel) -- collapsing it
		// into the same `true` as a genuine release would erase that diagnostic signal for no gain,
		// since OrphanReservationSweeper never calls this method for a train with zero footprint.
		return releasedBlocks.isNotEmpty() || hadClearedSignals
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
	): Boolean = setSignalAspect(semaphoreName, signal, trainName = null)

	/**
	 * Attributed overload of [setSignalAspect]: identical write, but the caller supplies the
	 * [trainName] the write is made on behalf of. A successful write to a proceed aspect is
	 * then recorded with [PathReservationService.recordExternalClearedSemaphore] -- closing the
	 * tracking-contract hole an untracked [DispatchDecision.SetSignalAspect] write would
	 * otherwise leave (G5, Issue #893 task A6): without this, a later
	 * [releaseRoute] for [trainName] would have no way to know this semaphore needs resetting.
	 *
	 * [trainName] `null` (equivalent to the plain 2-arg [setSignalAspect] above) intentionally
	 * records nothing -- there is no train to attribute the write to.
	 *
	 * @param semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @param signal Target signal aspect.
	 * @param trainName The train this write is made on behalf of, or `null` for an unattributed
	 *   write.
	 * @return `true` if the semaphore now displays [signal]; `false` if no semaphore with that
	 *   name exists, or if the semaphore is constant and [signal] differs from its fixed aspect.
	 * @since Issue #893 (phase alpha, task A6 -- G5 attribution slice)
	 */
	override fun setSignalAspect(
		semaphoreName: String,
		signal: Signal,
		trainName: String?
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
		val success = sem.signal == signal
		if (success && trainName != null) {
			pathReservationService.recordExternalClearedSemaphore(trainName, sem)
		}
		return success
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
