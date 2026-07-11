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
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Wires a [ShuntingLoop] to a [RuleBasedDispatcher] that decides **and applies
 * synchronously on the kDisco sim thread**, once per iteration.
 *
 * ## Why this exists (SP0.11 follow-up)
 *
 * The SP0.11 thin-shell refactor (Issue #733) removed all admission and
 * route-reservation policy from [ShuntingLoop]. The full asynchronous stack
 * (queue + applier + off-kernel driver thread, wired by `:desktop-ui`'s
 * `ExampleRegistry.wireDispatcherAgent`) is JVM-only and lives in
 * `:dispatcher-agent`. Consumers that cannot or need not run the async stack —
 * the `:fast-sim` native CLI and every test that runs a bare `ShuntingLoop` —
 * use this synchronous wiring instead; without it a bare loop admits zero trains.
 *
 * ## Semantics
 *
 * Identical decision logic to production ([RuleBasedDispatcher]). Each
 * [ShuntingLoop.iteration] publishes one fresh observation snapshot, then the
 * control-step listener decides and applies synchronously on the kDisco sim
 * thread in the same tick — admission and path-advancement together, pre-hold
 * (the SP0.11 single-observation-per-tick model, Issue #733). Because
 * observation and application are never decoupled across threads, the
 * duplicate-decision races the async driver needs guards for (PR #740,
 * Issue #742) cannot occur here by construction, and runs are fully deterministic.
 *
 * Usage (before `context.run()`):
 * ```kotlin
 * val loop = ShuntingLoop(context, endTime)
 * wireSynchronousDispatcher(context, loop)
 * context.setMainProcess(loop)
 * context.run()
 * ```
 *
 * @param env The simulation environment backing the ports
 * @param loop The loop to wire; its [ShuntingLoop.snapshotCaptureHook] and
 *   [ShuntingLoop.controlStepListener] are overwritten
 * @param maxConcurrentTrains Admission cap, defaults to production's
 *   [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS]
 * @since Issue #733 / #742 follow-up (SP0.11 green-up)
 */
fun wireSynchronousDispatcher(
	env: SimulationEnvironment,
	loop: ShuntingLoop,
	maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
) {
	val perceptionPort =
		DefaultNetworkPerceptionPort(
			env = env,
			activeTrains = loop::getApprovedTrains
		)
	val actuatorPort = DefaultNetworkActuatorPort(env = env)
	val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = maxConcurrentTrains)

	loop.snapshotCaptureHook = perceptionPort::captureSnapshot
	loop.controlStepListener =
		ControlStepListener {
			val observation =
				DispatchObservation(
					snapshot = perceptionPort.snapshot(),
					unapprovedTrains = loop.getQueuedTrains(),
					innerBlockInputs = loop.getInnerBlockInputs(),
					outerBlockInputs = loop.getOuterBlockInputs()
				)
			dispatcher.decide(observation).forEach { decision ->
				applyDecision(decision, loop, actuatorPort)
			}
		}
}

private fun applyDecision(
	decision: DispatchDecision,
	loop: ShuntingLoop,
	actuatorPort: DefaultNetworkActuatorPort
) {
	when (decision) {
		is DispatchDecision.ApproveTrain -> loop.approveQueuedTrain(decision.trainId)
		is DispatchDecision.ReservePath -> {
			val result =
				actuatorPort.requestRoute(
					decision.trainId,
					decision.fromSemaphoreName,
					decision.toSeparatorName
				)
			when (result) {
				is RouteRequestResult.Reserved -> loop.incrementBlockTransition(decision.trainId)
				else -> {
					// Blocked/conflict outcomes are routine "wait and retry next tick" contention.
					logger.debug {
						"wireSynchronousDispatcher: ReservePath ${decision.fromSemaphoreName} → " +
							"${decision.toSeparatorName} for ${decision.trainId} not applied: $result"
					}
					loop.incrementFailedReservation()
				}
			}
		}
		is DispatchDecision.NoAction -> Unit
	}
}
