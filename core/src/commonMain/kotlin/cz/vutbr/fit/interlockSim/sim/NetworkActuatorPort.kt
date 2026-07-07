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
 * 2. **Switch commands** — directly set a named rail switch to [RailSwitch.Conf.MAIN]
 *    or [RailSwitch.Conf.BRANCH] position.
 * 3. **Signal commands** — directly set a named semaphore to a given [Signal] aspect.
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
	 * Request a route reservation from [fromInOutName] to [toInOutName] for train [trainName].
	 *
	 * The interlocking finds a topologically valid path between the two named InOut points
	 * and atomically reserves all blocks along it if they are free.  If multiple paths exist,
	 * the implementation chooses one (typically by shortest distance or first-found).
	 *
	 * The call is **synchronous** — it returns only after the reservation attempt has
	 * completed (success or failure).
	 *
	 * @param trainName     Identifier of the train that will use the reserved route.
	 *   Must be non-blank; matched against the train registry in the simulation.
	 * @param fromInOutName Name of the InOut entry point (must exist in the network).
	 * @param toInOutName   Name of the InOut exit point (must exist in the network).
	 * @return [RouteRequestResult] indicating outcome; never `null`.
	 */
	fun requestRoute(
		trainName: String,
		fromInOutName: String,
		toInOutName: String
	): RouteRequestResult

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
	 * @param semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @param signal        Target signal aspect (e.g. [Signal.STOP], [Signal.FREE]).
	 * @return `true` if the signal was set successfully; `false` if no semaphore with
	 *   that name exists.
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
	 * No topological path exists between the requested InOut endpoints.
	 *
	 * This indicates a network-topology issue (disconnected graph, wrong names).  The
	 * dispatcher should log this as an error; retrying the same request will always yield
	 * the same result.
	 *
	 * @property fromInOutName The requested entry point name.
	 * @property toInOutName   The requested exit point name.
	 */
	data class NoRouteExists(
		val fromInOutName: String,
		val toInOutName: String
	) : RouteRequestResult()

	/**
	 * A topological path exists but all candidate paths are currently blocked
	 * (OCCUPIED or RESERVED by other trains).
	 *
	 * The dispatcher may retry after waiting for a block to become free.
	 *
	 * @property attemptedPaths Number of topological candidate paths that were checked.
	 */
	data class AllPathsBlocked(
		val attemptedPaths: Int
	) : RouteRequestResult()
}
