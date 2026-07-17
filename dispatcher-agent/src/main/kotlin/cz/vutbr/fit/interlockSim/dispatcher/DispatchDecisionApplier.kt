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
	private val onApproveTrain: (trainId: String) -> Unit,
	/**
	 * SP0.11: Callback invoked on the sim thread when a path reservation succeeds.
	 * Increments [ShuntingLoop.incrementBlockTransition] (the counter previously
	 * updated inside the removed [ShuntingLoop.tryReservePath]).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	private val onBlockTransition: (trainId: String) -> Unit = {},
	/**
	 * SP0.11: Callback invoked on the sim thread when a path reservation fails
	 * (any of AllPathsBlocked, Conflict, or NoRouteExists).
	 * Increments [ShuntingLoop.incrementFailedReservation] (the counter previously
	 * updated inside the removed [ShuntingLoop.tryReservePath]).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	private val onFailedReservation: () -> Unit = {}
) : ControlStepListener {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Reservation triples (`trainId|fromSemaphoreName|toSeparatorName`) already
	 * successfully applied via [applyReservePath], for the duplicate-suppression
	 * guard documented on that function.
	 *
	 * Entries are evicted via [evictReservationsFor] once the wiring layer observes a
	 * block release for that train (see [evictReservationsFor] KDoc) — this set must
	 * NOT be permanent: interlockSim supports bidirectional train operation (a train
	 * can reverse and later legitimately need to reserve an identical hop it already
	 * traversed once), which would otherwise collide with an entry left over from the
	 * first pass and silently stall the train.
	 */
	private val appliedReservations: MutableSet<String> = mutableSetOf()

	/**
	 * Evicts every [appliedReservations] entry recorded for [trainId].
	 *
	 * ## Why eviction is needed (Goal 10 code-review fix)
	 *
	 * [appliedReservations] exists to suppress a *duplicate* [DispatchDecision.ReservePath]
	 * decided twice before its first application is reflected back (see [applyReservePath]).
	 * That in-flight race is only possible while the reservation is still active. Once ANY
	 * block release happens for [trainId] (e.g. after a reversal re-approaches a hop it
	 * already traversed once, or simply as it completes its journey and exits), a later
	 * identical [DispatchDecision.ReservePath] is by construction a fresh request, not a
	 * stale duplicate in flight — so it must not stay suppressed.
	 *
	 * This is intentionally coarse (it clears every recorded hop for [trainId], not just the
	 * one whose block was released): the dedup guard is a best-effort in-flight-race filter,
	 * not an ownership ledger, and re-issuing an already-owned reservation is already a
	 * harmless no-op at the [NetworkActuatorPort]/registry level. Callers should invoke this
	 * from a [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener] subscribed to
	 * [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType.BLOCK_RELEASED] events.
	 *
	 * @since Goal 10 code-review fix — latent bug found during the SP0.11 review
	 */
	fun evictReservationsFor(trainId: String) {
		val prefix = "$trainId|"
		appliedReservations.removeAll { it.startsWith(prefix) }
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
			is DispatchDecision.SetSignalAspect -> applySetSignalAspect(decision)
			is DispatchDecision.SetSwitchPosition -> applySetSwitchPosition(decision)
			is DispatchDecision.ReleaseRoute -> applyReleaseRoute(decision)
			is DispatchDecision.RequestRoute -> applyRequestRoute(decision)
		}

	/**
	 * Applies [decision] via [NetworkActuatorPort.requestRoute], guarding against
	 * duplicate application of an already-successfully-reserved hop.
	 *
	 * ## Duplicate-decision race (SP0.11 regression, Issue #733 follow-up)
	 *
	 * [AgentLoopDriver][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] runs
	 * on its own thread, decoupled from the sim thread that applies its decisions.
	 * Because [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop]'s own
	 * polling ticks are not wall-clock throttled (no `SimulationController`
	 * pacing in a headless run), several ShuntingLoop ticks can elapse before the
	 * driver thread gets scheduled again. The block-input observation it reads —
	 * in particular [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.pathAlreadyExtendedBeyond],
	 * the guard [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] relies on to
	 * avoid re-deciding an already-reserved hop — only updates once a decided
	 * [DispatchDecision.ReservePath] has actually been drained and applied here.
	 * This means the driver can legitimately decide the *same* `ReservePath` more
	 * than once before its first application is reflected back.
	 *
	 * Re-applying an already-owned hop is not a harmless no-op:
	 * `DefaultPathReservationService.reservePath`'s already-owned fast path calls
	 * `PathReservationRegistry.registerPathInfo` again, and that registry's merge
	 * logic assumes the incoming path's `start` overlaps the *current* registered
	 * `target`. Once the first (legitimate) application has already advanced that
	 * target past the duplicate's `start`, the overlap check fails, the duplicate
	 * segment is spliced back into the train's registered path, and the train's
	 * `Front` process (`Train.kt`) loses track of its position — permanently
	 * stalling (confirmed via `PathReservationRegistry` merge tracing during root
	 * cause analysis; the malformed splice is silently "allowed" as a false-positive
	 * "circular route" before a *third* occurrence trips the existing cycle guard).
	 *
	 * Skipping a triple already recorded in [appliedReservations] restores the
	 * "at most one real application per reservation need" invariant that held
	 * naturally under the pre-SP0.11 synchronous (decide-and-apply-in-one-step)
	 * architecture, without touching the shared navigation/registry code that
	 * other callers (e.g. `InOutWorker`) also depend on.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); duplicate-suppression guard added as a
	 *   regression fix for the resulting train-freeze/deadlock
	 */
	private fun applyReservePath(decision: DispatchDecision.ReservePath) {
		val reservationKey = "${decision.trainId}|${decision.fromSemaphoreName}|${decision.toSeparatorName}"
		if (reservationKey in appliedReservations) {
			logger.debug {
				"Skipping duplicate ReservePath: trainId=${decision.trainId} " +
					"${decision.fromSemaphoreName} → ${decision.toSeparatorName} (already applied)"
			}
			return
		}

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
			is RouteRequestResult.Reserved -> {
				logger.debug {
					"ReservePath: reserved ${result.blocksCount} block(s) for ${decision.trainId}"
				}
				appliedReservations.add(reservationKey)
				onBlockTransition(decision.trainId)
			}
			is RouteRequestResult.AllPathsBlocked -> {
				logger.warn {
					"ReservePath: all paths blocked for ${decision.trainId} " +
						"(${decision.fromSemaphoreName} → ${decision.toSeparatorName}); " +
						"attempted: ${result.attemptedPaths}"
				}
				onFailedReservation()
			}
			is RouteRequestResult.Conflict -> {
				logger.warn {
					"ReservePath: conflict for ${decision.trainId} — " +
						"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
				}
				onFailedReservation()
			}
			is RouteRequestResult.NoRouteExists -> {
				logger.warn {
					"ReservePath: no route exists " +
						"${decision.fromSemaphoreName} → ${decision.toSeparatorName} " +
						"for ${decision.trainId}"
				}
				onFailedReservation()
			}
		}
	}

	// ── SP1.7 tool-driven actuator handlers (Issue #774) ─────────────────────
	//
	// These methods apply the four tool-driven DispatchDecision subtypes introduced in
	// SP1.7 to satisfy the kDisco threading contract.  They are called from onControlStep()
	// on the kDisco simulation thread, so all NetworkActuatorPort calls are thread-safe.
	// Results are logged; the agent driver does not wait for synchronous confirmation
	// (fire-and-forget, matching the existing ReservePath/ApproveTrain pattern).

	private fun applySetSignalAspect(decision: DispatchDecision.SetSignalAspect) {
		logger.debug {
			"Applying SetSignalAspect: semaphoreName=${decision.semaphoreName}, signal=${decision.signal}"
		}
		val success = networkActuator.setSignalAspect(decision.semaphoreName, decision.signal)
		if (!success) {
			logger.warn {
				"SetSignalAspect: semaphore '${decision.semaphoreName}' does not exist or is constant — " +
					"signal ${decision.signal} not applied"
			}
		}
	}

	private fun applySetSwitchPosition(decision: DispatchDecision.SetSwitchPosition) {
		logger.debug {
			"Applying SetSwitchPosition: switchName=${decision.switchName}, position=${decision.position}"
		}
		val success = networkActuator.setSwitchPosition(decision.switchName, decision.position)
		if (!success) {
			logger.warn {
				"SetSwitchPosition: switch '${decision.switchName}' does not exist or is locked — " +
					"position ${decision.position} not applied"
			}
		}
	}

	private fun applyReleaseRoute(decision: DispatchDecision.ReleaseRoute) {
		logger.debug { "Applying ReleaseRoute: trainName=${decision.trainName}" }
		val released = networkActuator.releaseRoute(decision.trainName)
		if (!released) {
			logger.debug { "ReleaseRoute: train '${decision.trainName}' held no reservation (no-op)" }
		}
	}

	private fun applyRequestRoute(decision: DispatchDecision.RequestRoute) {
		logger.debug {
			"Applying RequestRoute: trainName=${decision.trainName}, " +
				"from=${decision.fromEndpointName}, to=${decision.toEndpointName}"
		}
		return when (
			val result =
				networkActuator.requestRoute(
					decision.trainName,
					decision.fromEndpointName,
					decision.toEndpointName
				)
		) {
			is RouteRequestResult.Reserved -> {
				logger.debug {
					"RequestRoute: reserved ${result.blocksCount} block(s) for ${decision.trainName}"
				}
			}
			is RouteRequestResult.AllPathsBlocked -> {
				logger.warn {
					"RequestRoute: all paths blocked for ${decision.trainName} " +
						"(${decision.fromEndpointName} → ${decision.toEndpointName}); " +
						"attempted: ${result.attemptedPaths}"
				}
			}
			is RouteRequestResult.Conflict -> {
				logger.warn {
					"RequestRoute: conflict for ${decision.trainName} — " +
						"block '${result.blockName ?: "unnamed"}' owned by '${result.existingOwner}'"
				}
			}
			is RouteRequestResult.NoRouteExists -> {
				logger.warn {
					"RequestRoute: no route exists " +
						"${decision.fromEndpointName} → ${decision.toEndpointName} " +
						"for ${decision.trainName}"
				}
			}
		}
	}
}
