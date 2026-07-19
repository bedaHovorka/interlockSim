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
 * @param interlockingFacade SP3.5 (Issue #573): when non-null, [DefaultNetworkActuatorPort.requestRoute]
 *   is routed through the interlocking safety kernel as the single chokepoint.
 *   Pass `null` (default) for `:fast-sim` native binary and legacy test wiring that runs
 *   without Koin DI; those callers fall back to the direct [PathReservationService] path.
 * @since Issue #733 / #742 follow-up (SP0.11 green-up)
 */
fun wireSynchronousDispatcher(
	env: SimulationEnvironment,
	loop: ShuntingLoop,
	maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
	interlockingFacade: InterlockingFacade? = null
) {
	val perceptionPort =
		DefaultNetworkPerceptionPort(
			env = env,
			activeTrains = loop::getApprovedTrains
		)
	val actuatorPort = DefaultNetworkActuatorPort(env = env, interlockingFacade = interlockingFacade)
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

internal fun applyDecision(
	decision: DispatchDecision,
	loop: ShuntingLoop,
	actuatorPort: DefaultNetworkActuatorPort
) = when (decision) {
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
	DispatchDecision.NoAction -> Unit
	// ── SP1.7 tool-driven actuator subtypes (Issue #774) ─────────────────
	// Delegated to the shared [DispatchDecision.applyToolDrivenToActuator] helper so the
	// synchronous path and DispatchDecisionApplier cannot drift apart (the duplication here
	// was the hazard that let the first SP1.7 commit miss this `when` and break :core).
	// Expression-body `when` so the compiler enforces exhaustiveness over the sealed type.
	//
	// Note: a successful RequestRoute (handled inside the helper) intentionally does NOT call
	// loop.incrementBlockTransition — see DispatchDecision.RequestRoute KDoc (counter is
	// test-observability only; trains navigate via PathReservationRegistry).
	is DispatchDecision.SetSignalAspect,
	is DispatchDecision.SetSwitchPosition,
	is DispatchDecision.ReleaseRoute,
	is DispatchDecision.RequestRoute ->
		decision.applyToolDrivenToActuator(actuatorPort, "wireSynchronousDispatcher")
	// ── SP2b.1 train-lifecycle subtypes (Issue #556) ─────────────────────
	// HoldTrain is not supported in the synchronous wiring path: wireSynchronousDispatcher
	// is used by fast-sim and headless tests which run without a TrainLifecyclePort.
	// Rule-based dispatchers (RuleBasedDispatcher) never emit HoldTrain, so this branch
	// is a defensive guard only.  LLM-backed dispatchers must use the async
	// DispatchDecisionApplier path (DispatcherAgentModule / ExampleRegistry), which
	// supports TrainLifecyclePort.
	is DispatchDecision.HoldTrain -> {
		logger.warn {
			"wireSynchronousDispatcher: HoldTrain not supported in synchronous path " +
				"(trainId=${decision.trainId}, duration=${decision.holdDurationSeconds}s) — ignored. " +
				"Use the async DispatchDecisionApplier path for LLM-backed dispatchers."
		}
	}
}
