/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Sim-thread applier that drains [ActuatorCommandQueue] at each simulation control
 * step and applies every [DispatchDecision] through the SP0.6 actuator ports.
 *
 * Registered as a [ControlStepListener] on [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop]
 * before the simulation starts.  [onControlStep] is called synchronously by
 * [ShuntingLoop.iteration][cz.vutbr.fit.interlockSim.sim.ShuntingLoop] at the
 * beginning of every control step, which is always on the single kDisco simulation
 * thread.  All simulation-state mutation therefore originates on the sim thread
 * regardless of which thread posted the decisions into the queue.
 *
 * ## Decision routing
 *
 * | Decision | Applied via |
 * |---|---|
 * | [DispatchDecision.ApproveTrain] | [onApproveTrain] callback (into [ShuntingLoop.approveQueuedTrain][cz.vutbr.fit.interlockSim.sim.ShuntingLoop.approveQueuedTrain]) |
 * | [DispatchDecision.ReservePath] | [NetworkActuatorPort.requestRoute] |
 * | [DispatchDecision.NoAction] | no-op |
 *
 * Decisions are applied in FIFO order matching the posting order on the driver thread.
 *
 * ## Wiring (before `context.run()`)
 *
 * ```kotlin
 * val queue          = ActuatorCommandQueue()
 * val networkActuator = DefaultNetworkActuatorPort(context)
 * val loop           = ShuntingLoop(context, endTime)
 * val applier        = DispatchDecisionApplier(queue, networkActuator, loop::approveQueuedTrain)
 * loop.controlStepListener = applier
 * context.run()
 * ```
 *
 * @param queue           The command queue to drain on each control step.
 * @param networkActuator Network actuator port used to apply path-reservation commands.
 * @param onApproveTrain  Callback invoked (on the sim thread) to admit a queued train.
 *   Typically [ShuntingLoop.approveQueuedTrain][cz.vutbr.fit.interlockSim.sim.ShuntingLoop.approveQueuedTrain].
 *
 *   **Design note (SP0.9 review, Minor #4):** train admission (unapproved → approved
 *   + kDisco `activate`) is a `ShuntingLoop`-specific lifecycle step, not a generic
 *   train-actuator command — [TrainActuatorPort][cz.vutbr.fit.interlockSim.ports.TrainActuatorPort]
 *   only exposes `setTargetSpeed`. Routing `ApproveTrain` through a `(String) -> Unit`
 *   callback is therefore an intentional, minimal coupling for SP0.9. If SP2b (#556)
 *   adds more train-lifecycle commands (e.g. `HoldTrain`), extract a dedicated
 *   `TrainAdmissionPort` and route all train commands through it — symmetric with
 *   [NetworkActuatorPort] — rather than growing this constructor-arg list.
 *
 * @since Issue #731 (SP0.9 — Goal 10)
 */
class DispatchDecisionApplier(
	private val queue: ActuatorCommandQueue,
	private val networkActuator: NetworkActuatorPort,
	private val onApproveTrain: (trainId: String) -> Unit
) : ControlStepListener {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Drains [queue] and applies each pending [DispatchDecision] via the actuator ports.
	 *
	 * Always called on the kDisco simulation thread from
	 * [ShuntingLoop.iteration][cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
	 * All simulation-state mutations performed here are serialised with all other
	 * sim-thread operations by kDisco's single-threaded scheduler.
	 */
	override fun onControlStep() {
		val decisions = queue.drain()
		if (decisions.isEmpty()) return
		logger.debug { "onControlStep: applying ${decisions.size} pending decision(s)" }
		for (decision in decisions) {
			applyDecision(decision)
		}
	}

	// Expression-body `when` so the compiler enforces exhaustiveness over the sealed
	// DispatchDecision type — adding a future subtype (e.g. HoldTrain in SP2b, #556)
	// becomes a compile error here rather than a silently-dropped decision.
	private fun applyDecision(decision: DispatchDecision) =
		when (decision) {
			is DispatchDecision.ApproveTrain -> {
				logger.debug { "Applying ApproveTrain: trainId=${decision.trainId}" }
				onApproveTrain(decision.trainId)
			}
			is DispatchDecision.ReservePath -> applyReservePath(decision)
			DispatchDecision.NoAction -> Unit
		}

	private fun applyReservePath(decision: DispatchDecision.ReservePath) {
		logger.debug {
			"Applying ReservePath: trainId=${decision.trainId} " +
				"${decision.fromSemaphoreName} → ${decision.toSeparatorName}"
		}
		val result =
			networkActuator.requestRoute(
				decision.trainId,
				decision.fromSemaphoreName,
				decision.toSeparatorName
			)
		// Exhaustive `when` *expression* over the sealed RouteRequestResult type —
		// returning the `when` forces the compiler to enforce coverage, so a future
		// subtype addition fails to compile here instead of being silently ignored
		// (matches the pattern in DefaultNetworkActuatorPort.requestRoute).
		return when (result) {
			is RouteRequestResult.Reserved ->
				logger.debug {
					"ReservePath: reserved ${result.blocksCount} block(s) for ${decision.trainId}"
				}
			is RouteRequestResult.AllPathsBlocked ->
				logger.warn {
					"ReservePath: all paths blocked for ${decision.trainId} " +
						"(${decision.fromSemaphoreName} → ${decision.toSeparatorName}); " +
						"attempted: ${result.attemptedPaths}"
				}
			is RouteRequestResult.Conflict ->
				logger.warn {
					"ReservePath: conflict for ${decision.trainId} — " +
						"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
				}
			is RouteRequestResult.NoRouteExists ->
				logger.warn {
					"ReservePath: no route exists " +
						"${decision.fromSemaphoreName} → ${decision.toSeparatorName} " +
						"for ${decision.trainId}"
				}
		}
	}
}
