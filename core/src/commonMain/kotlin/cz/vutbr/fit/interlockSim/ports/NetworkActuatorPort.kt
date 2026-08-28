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

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Actuator port for the dispatcher agent's network-control commands.
 *
 * This is one of two actuator port interfaces introduced by SP0.3 (Issue #542) to give
 * agents a stable, kernel-independent surface for effecting changes in the simulation.
 * The port forms the **"act"** side of the dispatcher agent's sense → decide → act loop.
 *
 * ## Responsibility
 *
 * The dispatcher acts on the railway *infrastructure* rather than on individual trains:
 *
 * 1. **Route requests** — ask the interlocking to find and atomically reserve a free
 *    end-to-end path from one InOut to another for a named train.
 * 2. **Route release** — free the blocks a train holds when its journey ends or it
 *    reverses (the symmetric counterpart of route requests).
 * 3. **Switch commands** — directly set a named rail switch to [RailSwitch.Conf.MAIN]
 *    or [RailSwitch.Conf.BRANCH] position.
 * 4. **Signal commands** — directly set a named semaphore to a given [Signal] aspect.
 *
 * ## String-based identifiers
 *
 * All methods identify network elements by their string names rather than by live domain
 * objects.  This design choice:
 * - Makes the interface naturally serialisable for LLM tool-call JSON payloads.
 * - Keeps the `:dispatcher-agent` module free of compile-time dependencies on
 *   internal wrapper types (`DynamicRailSwitch`, `DynamicRailSemaphore`, …).
 * - Lets test doubles and LLM adapters be written without instantiating simulation
 *   objects.
 *
 * ## Safety guarantee
 *
 * Implementations **must** route all commands through the interlocking's safety logic
 * (lock checks, path compatibility, signal-direction validation).  The interface
 * contracts only the *request*; whether it is honoured is decided by the interlocking.
 *
 * ## Usage example
 *
 * ```kotlin
 * class RuleBasedDispatcherAdapter(private val actuator: NetworkActuatorPort) {
 *     fun setUpRouteForTrain(trainId: String, entry: String, exit: String) {
 *         when (val result = actuator.requestRoute(trainId, entry, exit)) {
 *             is RouteRequestResult.Reserved    -> logger.info { "$trainId: route reserved" }
 *             is RouteRequestResult.AllPathsBlocked -> logger.warn { "$trainId: all paths blocked" }
 *             is RouteRequestResult.Conflict   -> logger.warn { "$trainId: blocked by ${result.existingOwner} at ${result.blockName}" }
 *             is RouteRequestResult.NoRouteExists   -> logger.error { "$trainId: no topology route" }
 *         }
 *     }
 * }
 * ```
 *
 * @see TrainActuatorPort
 * @see RouteRequestResult
 * @since Issue #542 (SP0.3 — Goal 10)
 */
interface NetworkActuatorPort {
	/**
	 * Request a route reservation from [fromEndpointName] to [toEndpointName] for train [trainName].
	 *
	 * The interlocking finds a topologically valid path between the two named endpoints
	 * and atomically reserves all blocks along it if they are free.  If multiple paths exist,
	 * the implementation chooses one (typically by shortest distance or first-found).
	 *
	 * Endpoints may be either **InOut** names (full end-to-end route) or **Semaphore** names
	 * (partial route from/to an intermediate signal).  Partial routes are required when a
	 * train needs to reach a specific semaphore rather than a network boundary (e.g. Trains
	 * #4 and #5 in the ShuntingLoop scenario).
	 *
	 * The call is **synchronous** — it returns only after the reservation attempt has
	 * completed (success or failure).
	 *
	 * ## Invalid input
	 *
	 * Invalid input is a programmer/agent error and throws rather than being surfaced as a
	 * [RouteRequestResult]:
	 * - A blank [trainName] throws [IllegalArgumentException].
	 * - A [fromEndpointName] or [toEndpointName] that does not match any InOut or Semaphore in
	 *   the network throws [IllegalArgumentException] (unknown names must fail fast — they are
	 *   not "no route").
	 *
	 * [RouteRequestResult.NoRouteExists] is returned when no topological path connects the
	 * requested endpoints, and also for a residual kernel refusal that never reached
	 * pathfinding — see that type's own KDoc.  [RouteRequestResult.Conflict] is returned when a
	 * path exists but a block along it is already owned by another train — it carries the
	 * conflicting block name and the owning train name so a dispatcher can wait for that
	 * specific train rather than retrying blindly.
	 *
	 * @param trainName        Identifier of the train that will use the reserved route.
	 *   Must be non-blank; matched against the train registry in the simulation.
	 * @param fromEndpointName Name of the entry InOut or Semaphore (must exist in the network).
	 * @param toEndpointName   Name of the exit InOut or Semaphore (must exist in the network).
	 * @return [RouteRequestResult] indicating outcome; never `null`.
	 * @throws IllegalArgumentException if [trainName] is blank, or if [fromEndpointName] or
	 *   [toEndpointName] does not name an InOut or Semaphore in the network.
	 */
	fun requestRoute(
		trainName: String,
		fromEndpointName: String,
		toEndpointName: String
	): RouteRequestResult

	/**
	 * Release all track blocks -- and any signal still cleared on its behalf -- reserved for
	 * [trainName].
	 *
	 * The symmetric counterpart of [requestRoute]: when a train completes its journey or
	 * reverses, the dispatcher releases the route so the blocks return to the free pool.
	 * The operation is **idempotent** — calling it when [trainName] holds no reservation and no
	 * cleared signal is a no-op and returns `false`.  Blocks transition RESERVED → FREE.
	 *
	 * ## Truthful return value (Issue #893, task A7 -- binding traffic-simulation-expert R5 ruling)
	 *
	 * `true` covers every case where this call actually changed [trainName]'s registry/signal
	 * state, including a **signals-only** release: a train can hold a cleared signal with zero
	 * blocks reserved (e.g. after a partial release reclaimed its un-travelled tail), and that
	 * reset is just as real a state change as a block returning to FREE. `false` is reserved for
	 * the genuine no-op — [trainName] held neither a block nor a cleared signal — which callers
	 * may use to distinguish "nothing to release" from "released".
	 *
	 * Two guarantees callers may rely on:
	 *
	 * 1. **`false` is never a failure indicator, and no caller may retry on it.** It reports one
	 *    thing only: [trainName] had no block and no cleared signal to give back *at the moment
	 *    this method was called*. It never means "the release attempt failed" -- there is no
	 *    failure mode this method can report via its return value (see guarantee 2). A caller
	 *    that retries `releaseRoute` because it saw `false`, hoping a later call will succeed
	 *    where this one "failed", is retrying on a state that will not change on its own: if
	 *    nothing changes between calls, [trainName] still holds nothing and `false` recurs
	 *    forever. Treat `false` purely as **information** ("there was nothing here"), never as a
	 *    **command to retry**.
	 * 2. **`true` reports a registry/signal STATE CHANGE, not per-element success.** The
	 *    reference implementation ([cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.releasePath])
	 *    catches, logs, and swallows individual element failures internally -- a block whose
	 *    `cancelPathSetup` throws, or a switch whose `unlock` throws, is logged at WARN and
	 *    skipped, never surfaced to the caller. `releaseRoute` still reports `true` in that case,
	 *    because the registry/signal bookkeeping this train owned was still cleared (or attempted
	 *    to be cleared) as a whole -- the return value answers "did I have something to release
	 *    and did I act on it", not "did every underlying element operation individually succeed".
	 *
	 * @param trainName Name of the train whose route should be released (non-blank).
	 * @return `true` if at least one block or one cleared signal was released; `false` if the
	 *   train held neither. Never a failure signal either way -- see the two guarantees above.
	 * @throws IllegalArgumentException if [trainName] is blank.
	 */
	fun releaseRoute(trainName: String): Boolean

	/**
	 * Command a named rail switch to the given position.
	 *
	 * The switch is set only if it is not currently locked (i.e. no train is occupying
	 * or reserved through it).  If the switch is already in the requested position the
	 * call is a no-op and still returns `true`.
	 *
	 * @param switchName Name of the switch (must exist in the network; case-sensitive).
	 * @param position   Target position: [RailSwitch.Conf.MAIN] or [RailSwitch.Conf.BRANCH].
	 * @return `true` if the switch is now in [position]; `false` if it is locked, or if
	 *   no switch with that name exists.
	 */
	fun setSwitchPosition(
		switchName: String,
		position: RailSwitch.Conf
	): Boolean

	/**
	 * Command a named semaphore to display the given signal aspect.
	 *
	 * Both **upgrades** (e.g. STOP → FREE) and **downgrades** (e.g. FREE → S30) are
	 * permitted on a dynamic semaphore.  Downgrades are physically hard for a train to
	 * reach in time due to braking distance, but they are allowed at the command
	 * surface — the dispatcher/interlocking decides whether to issue one.
	 *
	 * A **constant** semaphore (predzvěst / narážník / rychlostnik — a fixed-aspect
	 * signal whose value must not change) ignores writes.  Requesting a *different*
	 * aspect on a constant semaphore returns `false` (constant semaphores must stay
	 * constant); requesting its current aspect returns `true` as an idempotent no-op.
	 *
	 * Calling this method with the currently-displayed aspect on a dynamic semaphore is
	 * likewise a no-op and returns `true`.
	 *
	 * This is the unattributed convenience form: the default implementation delegates to the
	 * attributed 3-arg overload with `trainName = null`, so an implementation only needs to
	 * override the 3-arg form. Override this 2-arg form only to break the delegation cycle if
	 * the 3-arg override itself delegates back here (see [setSignalAspect] below).
	 *
	 * @param semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @param signal        Target signal aspect (e.g. [Signal.STOP], [Signal.FREE]).
	 * @return `true` if the semaphore now displays [signal]; `false` if no semaphore with
	 *   that name exists, or if the semaphore is constant and [signal] differs from its
	 *   fixed aspect.
	 */
	fun setSignalAspect(
		semaphoreName: String,
		signal: Signal
	): Boolean = setSignalAspect(semaphoreName, signal, trainName = null)

	/**
	 * Attributed [setSignalAspect]: identical write, but the caller supplies the [trainName]
	 * the write is made on behalf of, enabling G5 cleared-signal ownership tracking so that a
	 * later [releaseRoute] for [trainName] can reset this semaphore.
	 *
	 * This is the **abstract contract method** — every implementation must provide it so that
	 * attribution cannot be silently dropped (which would re-open the G5 hole of #898). A
	 * test fake that does not care about attribution may delegate to the 2-arg form, but it
	 * must then also override the 2-arg form to break the delegation cycle (the 2-arg default
	 * calls this 3-arg form; a 3-arg override that calls the 2-arg default would recurse).
	 *
	 * @param semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @param signal        Target signal aspect.
	 * @param trainName     The train this write is made on behalf of, or `null` for an
	 *   unattributed write (equivalent to the 2-arg overload).
	 * @return `true` if the semaphore now displays [signal]; `false` if no semaphore with
	 *   that name exists, or if the semaphore is constant and [signal] differs from its
	 *   fixed aspect.
	 * @since Issue #898 (follow-up to #893 task A6 — G5 attribution slice)
	 */
	fun setSignalAspect(
		semaphoreName: String,
		signal: Signal,
		trainName: String?
	): Boolean
}

/**
 * Result of a [NetworkActuatorPort.requestRoute] call.
 *
 * The sealed hierarchy lets dispatcher implementations (including LLM-based ones) react
 * to each outcome without inspecting numeric codes or string messages.
 *
 * @since Issue #542 (SP0.3 — Goal 10)
 */
sealed class RouteRequestResult {
	/**
	 * The route was found and all blocks along it were atomically reserved for [trainName].
	 *
	 * @property trainName   The train for which the route was reserved.
	 * @property blocksCount Number of track blocks that were reserved.
	 */
	data class Reserved(
		val trainName: String,
		val blocksCount: Int
	) : RouteRequestResult()

	/**
	 * No route was established between the requested endpoints, and retrying the identical
	 * request will always yield the same result.  The dispatcher should log this as an error.
	 *
	 * Two kernel outcomes produce it:
	 *
	 * - **No topological path** — the endpoints are as requested, but the topology graph connects
	 *   no path between them (e.g. disconnected sub-networks).  Maps from
	 *   [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NoPathExists].
	 * - **A residual interlocking refusal that never reached pathfinding** — the kernel denied
	 *   before attempting a reservation, so no candidate-path count and no owning train exist to
	 *   report.  Maps from
	 *   [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.Other]
	 *   (Issue #834): it is reported here rather than as [AllPathsBlocked] because there is no
	 *   count to report and the refusal is not contention.
	 *
	 * Note: an unknown (non-existent) endpoint name is **not** this result — it throws
	 * [IllegalArgumentException] from [NetworkActuatorPort.requestRoute], which validates endpoint
	 * names before consulting the kernel.
	 *
	 * @property fromEndpointName The requested entry point name.
	 * @property toEndpointName   The requested exit point name.
	 */
	data class NoRouteExists(
		val fromEndpointName: String,
		val toEndpointName: String
	) : RouteRequestResult()

	/**
	 * A topological path exists but all candidate paths are currently blocked
	 * (OCCUPIED or RESERVED by other trains).
	 *
	 * The dispatcher may retry after waiting for a block to become free.
	 *
	 * @property attemptedPaths Number of topological candidate paths that were checked
	 *   (always `≥ 0`).
	 */
	data class AllPathsBlocked(
		val attemptedPaths: Int
	) : RouteRequestResult()

	/**
	 * A topological path exists but could not be reserved because a block along it is
	 * already owned by another train.
	 *
	 * Unlike [AllPathsBlocked] (no candidate path was reservable right now) and
	 * [NoRouteExists] (no topology path at all), this result identifies *who* is
	 * blocking, so a dispatcher can wait for that specific train rather than retrying
	 * blindly.  Maps from
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict].
	 *
	 * Only string identifiers are carried (no live domain object) to honour this port's
	 * "no live domain objects leaked to callers" contract.
	 *
	 * @property blockName     Name of the conflicting block, or `null` if the block is
	 *   unnamed.
	 * @property existingOwner Name of the train that already owns the conflicting block.
	 */
	data class Conflict(
		val blockName: String?,
		val existingOwner: String
	) : RouteRequestResult()

	/**
	 * The requested origin is not contiguous with the train's current position: it bounds none
	 * of the blocks the train holds or occupies, so the train could never reach the route.
	 *
	 * Maps from
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NonContiguousStart].
	 *
	 * Distinct from [AllPathsBlocked] on purpose. `AllPathsBlocked` says "not right now, try
	 * later"; this says "not from there, ever" — retrying the identical request while the train
	 * stays put will always fail, and counting it as contention would hide a dispatcher-output
	 * defect inside a routine-traffic metric (Issue #893).
	 *
	 * ## Produced on both the facade and the legacy/no-facade path (task A-R1b)
	 *
	 * [DefaultNetworkActuatorPort][cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort]
	 * constructs this on the legacy/no-facade path directly from
	 * `ReservationResult.NonContiguousStart`, and on the facade path (production dispatcher-agent
	 * runs) from
	 * [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.NonContiguousStart]
	 * -- the discriminant [cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade] threads through
	 * from the same kernel result. Since Issue #834 (task alpha-7a) the facade path classifies
	 * **every** denial on that exhaustive `DenialCause`, so both paths yield the same
	 * [RouteRequestResult] for the same kernel outcome (invariant I4).
	 *
	 * @property fromEndpointName The rejected origin name, as requested.
	 * @property reason English explanation naming the origin and the legal origins for this
	 *   train, suitable for dispatcher display and for feeding back to an LLM dispatcher.
	 * @since Issue #893 (phase alpha, task A-R1)
	 */
	data class OriginNotContiguous(
		val fromEndpointName: String,
		val reason: String
	) : RouteRequestResult()

	/**
	 * One of the four ESA-11 route conditions refused the route (the four-condition
	 * [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.requestRoute] path). Maps from
	 * [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.ConditionFailed].
	 *
	 * Distinct from [NoRouteExists] (which carries the endpoint-resolution residual
	 * [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.Other]) and
	 * from [AllPathsBlocked]/[Conflict] (which carry a candidate-path count / a blocking owner
	 * from the reservation service): a four-condition refusal does not go through pathfinding, so
	 * neither a count nor an owner exists to report. The [retryable] flag is the only
	 * machine-readable signal a caller gets — it preserves the transient-vs-permanent split so a
	 * caller does not collapse contention (a block occupied by another train) onto the permanent
	 * [NoRouteExists] side. This result exists because #834 stopped inventing a count/owner for
	 * denials that have neither (review finding #2).
	 *
	 * @property reason The kernel's human-readable denial reason, forwarded verbatim from
	 *   [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.Denied.reason]. Suitable for
	 *   dispatcher display and LLM feedback (the same role [OriginNotContiguous.reason] plays).
	 * @property retryable `true` when the underlying reason is transient contention (another
	 *   train holds a resource; retrying the same request later can succeed); `false` when it is
	 *   a permanent dispatcher output defect (unknown name, empty route, mismatched signal,
	 *   un-clearable signal) — an identical retry fails identically. Forwarded unchanged from
	 *   [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.ConditionFailed.retryable].
	 * @since Issue #834 (SP2c.11 — Goal 10, review finding #2)
	 */
	data class ConditionFailed(
		val reason: String,
		val retryable: Boolean
	) : RouteRequestResult()

	/**
	 * The candidate that came closest was **permanently** impossible, not merely busy: a
	 * candidate's START semaphore faced away from the requested direction of travel (G4), or a
	 * switch along a candidate could not be set to the position the route required. Maps from
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.GeometricallyImpossible]
	 * via
	 * [cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause.GeometricallyImpossible].
	 *
	 * Distinct from [AllPathsBlocked] on purpose, exactly as [OriginNotContiguous] is: retrying
	 * the identical request while the network topology and signal facings stay as they are will
	 * always fail, and counting it as contention would hide a dispatcher-output defect inside a
	 * routine-traffic metric (Issue #903, same reasoning as Issue #893).
	 *
	 * ## Produced on both the facade and the legacy/no-facade path
	 *
	 * [DefaultNetworkActuatorPort] constructs this on the legacy/no-facade path directly from
	 * `ReservationResult.GeometricallyImpossible`, and on the facade path (production
	 * dispatcher-agent runs) from `DenialCause.GeometricallyImpossible` — the discriminant
	 * [cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade] threads through from the same
	 * kernel result, so both branches yield the same [RouteRequestResult] for the same kernel
	 * outcome (invariant I4, same as [OriginNotContiguous]).
	 *
	 * @property reason English explanation of which permanent-impossibility class fired.
	 * @since Issue #903
	 */
	data class GeometricallyImpossible(
		val reason: String
	) : RouteRequestResult()
}
