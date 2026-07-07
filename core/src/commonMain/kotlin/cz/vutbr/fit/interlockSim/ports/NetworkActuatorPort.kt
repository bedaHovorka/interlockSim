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
	 * [RouteRequestResult.NoRouteExists] is returned only when both endpoints are valid but
	 * no topological path connects them.
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
	 * Release all track blocks reserved for [trainName].
	 *
	 * The symmetric counterpart of [requestRoute]: when a train completes its journey or
	 * reverses, the dispatcher releases the route so the blocks return to the free pool.
	 * The operation is **idempotent** — calling it when [trainName] holds no reservation is
	 * a no-op and returns `false`.  Blocks transition RESERVED → FREE.
	 *
	 * @param trainName Name of the train whose route should be released (non-blank).
	 * @return `true` if at least one block was released; `false` if the train held no
	 *   reservation.
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
	 * Calling this method with the currently-displayed aspect is a no-op and still
	 * returns `true`.
	 *
	 * A semaphore whose signal is currently an allowing aspect has been set by the
	 * interlocking as part of an active route.  Such a semaphore is considered **locked**
	 * and this method will return `false` without changing anything — the same safety
	 * principle that prevents changing a locked switch.
	 *
	 * @param semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @param signal        Target signal aspect (e.g. [Signal.STOP], [Signal.FREE]).
	 * @return `true` if the signal was set successfully; `false` if no semaphore with
	 *   that name exists, or if the semaphore is locked by an active route (its current
	 *   signal is an allowing aspect).  Per the class-level safety guarantee, a
	 *   clear-to-FREE is never honoured while a route is active.
	 */
	fun setSignalAspect(
		semaphoreName: String,
		signal: Signal
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
	 * No topological path exists between the requested endpoints.
	 *
	 * Both endpoints are valid InOuts or Semaphores in the network, but the topology graph
	 * connects no path between them (e.g. disconnected sub-networks).  The dispatcher should
	 * log this as an error; retrying the same request will always yield the same result.
	 * Note: an unknown (non-existent) endpoint name is **not** this result — it throws
	 * [IllegalArgumentException] from [NetworkActuatorPort.requestRoute].
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
}
