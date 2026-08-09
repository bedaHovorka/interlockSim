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

import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val toolDrivenLogger = KotlinLogging.logger {}

/**
 * Applies one of the four SP1.7 tool-driven [DispatchDecision] subtypes
 * ([SetSignalAspect], [SetSwitchPosition], [ReleaseRoute], [RequestRoute]) to an
 * actuator port — the single source of truth for their actuator call + logging.
 *
 * ## Why this exists (SP1.7, Issue #774 follow-up)
 *
 * Before this helper, the four tool-driven branches were copy-pasted between the
 * asynchronous applier (`:dispatcher-agent`'s `DispatchDecisionApplier`) and the
 * synchronous wiring ([wireSynchronousDispatcher] / [applyDecision] in `:core`).
 * That duplication was the exact hazard that let the first SP1.7 commit add the four
 * subtypes to one call site and miss the other, breaking `:core` compilation until a
 * follow-up fix. Both consumers now delegate to this function, so a future subtype
 * addition only touches this file (plus the call-site `when` branches, which the
 * compiler enforces via the exhaustive `when` in `DispatchDecisionApplier.applyDecision`).
 *
 * ## Scope
 *
 * Only the four tool-driven subtypes are handled here. [DispatchDecision.ApproveTrain]
 * and [DispatchDecision.ReservePath] stay per-call-site: the asynchronous applier owns
 * a duplicate-suppression guard ([DispatchDecisionApplier] `appliedReservations`) and
 * counter callbacks (`onBlockTransition` / `onFailedReservation`) that the synchronous
 * wiring intentionally omits (its decide-and-apply-in-one-tick model makes the
 * duplicate-decision race impossible by construction — see [wireSynchronousDispatcher]
 * KDoc). Those divergences are real, so `ReservePath`/`ApproveTrain` are not shared.
 *
 * ## Threading
 *
 * Must be called on the kDisco simulation thread — both call sites do
 * ([DispatchDecisionApplier.onControlStep] drains the queue on the sim thread;
 * [wireSynchronousDispatcher]'s control-step listener runs inline on the sim thread).
 *
 * @param actuator The actuator port to apply the decision through.
 * @param logPrefix Short prefix identifying the call site, included in every log line
 *   so the two consumers are distinguishable in logs (e.g. `"wireSynchronousDispatcher"`,
 *   `"DispatchDecisionApplier"`).
 * @since Issue #774 (SP1.7 — Goal 10 threading contract); helper extracted to remove
 *   the cross-path duplication surfaced by the SP1.7 code review.
 */
fun DispatchDecision.applyToolDrivenToActuator(
	actuator: NetworkActuatorPort,
	logPrefix: String
) {
	// Exhaustive `when` *expression* over the sealed DispatchDecision — the compiler
	// enforces coverage, so adding a future subtype fails to compile here until it
	// is handled. The non-tool-driven subtypes must never reach this helper (the
	// caller handles them); reaching them is a programming error.
	when (this) {
		is DispatchDecision.SetSignalAspect -> {
			toolDrivenLogger.debug {
				"$logPrefix: applying SetSignalAspect semaphoreName=$semaphoreName, signal=$signal" +
					rationale.toRationaleLogSuffix()
			}
			val success = actuator.setSignalAspect(semaphoreName, signal, trainName)
			if (!success) {
				toolDrivenLogger.warn {
					"$logPrefix: SetSignalAspect semaphore '$semaphoreName' does not exist or is constant — " +
						"signal $signal not applied"
				}
			}
		}
		is DispatchDecision.SetSwitchPosition -> {
			toolDrivenLogger.debug {
				"$logPrefix: applying SetSwitchPosition switchName=$switchName, position=$position" +
					rationale.toRationaleLogSuffix()
			}
			val success = actuator.setSwitchPosition(switchName, position)
			if (!success) {
				toolDrivenLogger.warn {
					"$logPrefix: SetSwitchPosition switch '$switchName' does not exist or is locked — " +
						"position $position not applied"
				}
			}
		}
		is DispatchDecision.ReleaseRoute -> {
			toolDrivenLogger.debug {
				"$logPrefix: applying ReleaseRoute trainName=$trainName" +
					rationale.toRationaleLogSuffix()
			}
			val released = actuator.releaseRoute(trainName)
			if (!released) {
				toolDrivenLogger.debug {
					"$logPrefix: ReleaseRoute train '$trainName' held no reservation (no-op)"
				}
			}
		}
		is DispatchDecision.RequestRoute -> {
			toolDrivenLogger.debug {
				"$logPrefix: applying RequestRoute trainName=$trainName, from=$fromEndpointName, to=$toEndpointName" +
					rationale.toRationaleLogSuffix()
			}
			// Exhaustive `when` over the sealed RouteRequestResult — returning it forces the
			// compiler to enforce coverage (matches the pattern in DefaultNetworkActuatorPort).
			//
			// Design note: a successful RequestRoute does NOT bump the block-transition counter
			// (unlike ReservePath, whose `Reserved` branch calls incrementBlockTransition /
			// onBlockTransition at the call site). The counter is test-observability only (#365);
			// trains navigate the reserved route via PathReservationRegistry, not the counter.
			// See DispatchDecision.RequestRoute KDoc for the full rationale.
			return when (
				val result = actuator.requestRoute(trainName, fromEndpointName, toEndpointName)
			) {
				is RouteRequestResult.Reserved ->
					toolDrivenLogger.debug {
						"$logPrefix: RequestRoute reserved ${result.blocksCount} block(s) for $trainName"
					}
				is RouteRequestResult.AllPathsBlocked ->
					toolDrivenLogger.warn {
						"$logPrefix: RequestRoute all paths blocked for $trainName " +
							"($fromEndpointName → $toEndpointName); attempted: ${result.attemptedPaths}"
					}
				is RouteRequestResult.Conflict ->
					toolDrivenLogger.warn {
						"$logPrefix: RequestRoute conflict for $trainName — " +
							"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
					}
				is RouteRequestResult.NoRouteExists ->
					toolDrivenLogger.warn {
						"$logPrefix: RequestRoute no route exists $fromEndpointName → $toEndpointName for $trainName"
					}
				is RouteRequestResult.OriginNotContiguous ->
					toolDrivenLogger.warn {
						"$logPrefix: RequestRoute origin not contiguous for $trainName — ${result.reason}"
					}
			}
		}
		DispatchDecision.NoAction,
		is DispatchDecision.ApproveTrain,
		is DispatchDecision.ReservePath,
		is DispatchDecision.HoldTrain ->
			error(
				"applyToolDrivenToActuator must not be called for $this — " +
					"ApproveTrain/ReservePath/NoAction/HoldTrain are handled by the caller, not this helper."
			)
	}
}
