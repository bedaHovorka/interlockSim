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

import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Drives the dispatcher sense→decide→act loop from outside the kDisco kernel,
 * paced by [SimulationController.awaitIfPaused] / [SimulationController.throttle].
 *
 * Each call to [runCycle] executes one complete iteration:
 * 1. **SENSE**: reads a [SimulationSnapshot] from [perceptionPort]
 * 2. **DECIDE**: calls [Dispatcher.decide] with a [DispatchObservation] built from
 *    the snapshot
 * 3. **ACT**: posts the returned [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s to
 *    [commandQueue] — a thread-safe handoff; the sim-thread
 *    [DispatchDecisionApplier] drains and applies them on the kDisco thread
 * 4. **PACE**: calls [SimulationController.awaitIfPaused] to honour pause/step
 *    requests, then [SimulationController.throttle] with the simulation-time delta
 *    since the previous cycle
 *
 * ## Threading
 *
 * The driver runs on its own thread or coroutine; the kDisco kernel never blocks
 * on it (A6-safe from the SP0.5 design spec). The driver thread reads
 * [perceptionPort] (read-only, safe for off-thread access) and posts to
 * [commandQueue] (thread-safe). It never touches live simulation state.
 *
 * ## SimulationController invariant
 *
 * [controller] is injected into the driver **only** — it is never passed to
 * [SimulationController][cz.vutbr.fit.interlockSim.context.SimulationEnvironment],
 * [DispatchObservation], or [Dispatcher] implementations (locked invariant 3
 * from the SP0.5 design spec, docs/specs/2026-07-08-544-sp05-drive-loop-design.md).
 *
 * ## DispatchObservation construction
 *
 * For the initial SP0.10 slice the observation contains only the general-purpose
 * [SimulationSnapshot] (signals, block occupancy, train positions, timetables).
 * The [DispatchObservation.unapprovedTrains] list and the block-input lists are
 * populated in SP0.11 (#733) when [perceptionPort] is extended and the
 * [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop] shell is wired to
 * expose them.  Until then the driver creates observations with empty auxiliary
 * lists; [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] handles this case
 * gracefully (it returns [cz.vutbr.fit.interlockSim.sim.DispatchDecision.NoAction]).
 *
 * @param perceptionPort Read-only sense port for the railway network state (SP0.4, #543)
 * @param dispatcher     Pure decision function (SP0.7, #729); must not retain the
 *   observation beyond the call or mutate simulation state
 * @param commandQueue   Thread-safe queue to which decisions are posted (SP0.8, #730);
 *   drained and applied by the sim-thread applier (SP0.9, #731)
 * @param controller     Pacing controller; injected into the driver **only** —
 *   never exposed to [cz.vutbr.fit.interlockSim.context.SimulationEnvironment] or
 *   policy implementations (SP0.5 invariant 3)
 *
 * @since Issue #732 (SP0.10 — Goal 10)
 */
class AgentLoopDriver(
	private val perceptionPort: NetworkPerceptionPort,
	private val dispatcher: Dispatcher,
	private val commandQueue: ActuatorCommandQueue,
	private val controller: SimulationController
) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/** Simulation time at the end of the most recently completed cycle; 0.0 before the first cycle. */
	private var prevSimTime: Double = 0.0

	/**
	 * Executes one complete sense→decide→act→pace cycle.
	 *
	 * This is a `suspend` function so that [SimulationController.awaitIfPaused] (which
	 * is itself `suspend`) can co-operatively yield the driver coroutine while the
	 * simulation is paused, without blocking the underlying OS thread.
	 *
	 * Cycle steps:
	 * 1. **SENSE** — [NetworkPerceptionPort.snapshot] captures a consistent, frozen
	 *    picture of the current network state.
	 * 2. **DECIDE** — the [dispatcher] is called with an observation built from the
	 *    snapshot; [DispatchObservation.unapprovedTrains] and block-input lists are
	 *    empty in this SP0.10 slice (populated in SP0.11).
	 * 3. **ACT** — all returned decisions are posted to [commandQueue] in a single
	 *    atomic [ActuatorCommandQueue.postAll] call.  The sim-thread
	 *    [DispatchDecisionApplier] applies them; no simulation state is mutated on
	 *    the driver thread.
	 * 4. **PACE** — [SimulationController.awaitIfPaused] is called first (honours
	 *    pause/step requests), then [SimulationController.throttle] with the
	 *    simulation-time delta since the previous cycle (wall-clock pacing).
	 *
	 * The simulation-time delta passed to [SimulationController.throttle] is the
	 * simulation time elapsed since the previous cycle.  There is no previous
	 * cycle on the first call, so that delta is taken from the loop's start
	 * baseline — the same initial-delta convention used by the controlled event
	 * loop in [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext].
	 */
	suspend fun runCycle() {
		// 1. SENSE — read a consistent frozen snapshot off the perception port.
		val snapshot = perceptionPort.snapshot()
		logger.debug { "AgentLoopDriver: sensed snapshot at simTime=${snapshot.simTime}" }

		// 2. DECIDE — call the pure dispatcher with a read-only observation.
		// unapprovedTrains and block-input lists are populated in SP0.11 (#733).
		val observation =
			DispatchObservation(
				snapshot = snapshot,
				unapprovedTrains = emptyList()
			)
		val decisions = dispatcher.decide(observation)
		logger.debug { "AgentLoopDriver: decided ${decisions.size} decision(s)" }

		// 3. ACT — post decisions to the thread-safe handoff queue.
		// The sim-thread DispatchDecisionApplier drains and applies them; this
		// call never mutates simulation state on the driver thread.
		val posted = commandQueue.postAll(decisions)
		if (!posted) {
			logger.warn {
				"AgentLoopDriver: commandQueue rejected ${decisions.size} decision(s) " +
					"(backpressure limit reached); decisions discarded for this cycle"
			}
		}

		// 4. PACE — honour pause/step requests, then throttle wall-clock time.
		controller.awaitIfPaused()
		val simDelta = snapshot.simTime - prevSimTime
		controller.throttle(simDelta)
		prevSimTime = snapshot.simTime
	}
}
