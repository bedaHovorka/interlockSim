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
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Drives the dispatcher sense→decide→act loop from outside the kDisco kernel,
 * paced by [SimulationController.awaitIfPaused] / [SimulationController.throttle].
 *
 * Each call to [runCycle] executes one complete iteration:
 * 1. **SENSE**: reads a [SimulationSnapshot] from [perceptionPort]
 * 2. **DECIDE**: calls [DispatcherPlanner.plan] with a [DispatchObservation] built from
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
 * [DispatchObservation], or [DispatcherPlanner] implementations (locked invariant 3
 * from the SP0.5 design spec, docs/specs/2026-07-08-544-sp05-drive-loop-design.md).
 *
 * ## DispatchObservation construction
 *
 * The observation combines the general-purpose [SimulationSnapshot] (signals, block
 * occupancy, train positions, timetables) with the ShuntingLoop-specific dispatch
 * inputs read from [dispatchLoopSensorPort] — the SP4.1 sensor seam (Issue #563):
 * the [DispatchObservation.unapprovedTrains] list and the block-input lists all come
 * from one atomic [DispatchLoopSensorPort.snapshot] read. With the default (empty)
 * sensor port, [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] (via
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter]) handles
 * this case gracefully (it returns [cz.vutbr.fit.interlockSim.sim.DispatchDecision.NoAction]).
 *
 * @param perceptionPort Read-only sense port for the railway network state (SP0.4, #543)
 * @param planner        Pluggable planning function (SP3.6, #574); must not retain the
 *   observation beyond the call or mutate simulation state
 * @param commandQueue   Thread-safe queue to which decisions are posted (SP0.8, #730);
 *   drained and applied by the sim-thread applier (SP0.9, #731)
 * @param controller     Pacing controller; injected into the driver **only** —
 *   never exposed to [cz.vutbr.fit.interlockSim.context.SimulationEnvironment] or
 *   policy implementations (SP0.5 invariant 3)
 * @param snapshotSignal Optional sim-to-driver pacing signal (SP0.11c, Issue #746).
 *   When non-null, the driver blocks on [SnapshotSignal.await] at the top of each
 *   cycle rather than polling with [Thread.sleep].  The sim thread must call
 *   [SnapshotSignal.signal] immediately after each
 *   [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.captureSnapshot].
 *   When null (the default), the original polling behaviour is preserved for backward
 *   compatibility with callers that do not supply a signal.
 *
 * @since Issue #732 (SP0.10 — Goal 10); [snapshotSignal] added in Issue #746 (SP0.11c)
 */
class AgentLoopDriver(
	private val perceptionPort: NetworkPerceptionPort,
	private val planner: DispatcherPlanner,
	private val commandQueue: ActuatorCommandQueue,
	private val controller: SimulationController,
	/**
	 * Sensor port for the current per-tick dispatch-loop observation bundle
	 * (unapproved-train queue plus inner/outer block inputs) — the SP4.1 "sense" seam
	 * (Issue #563). Its [DispatchLoopSensorPort.snapshot] is invoked ONCE per
	 * [runCycle] on the driver thread during SENSE; the default implementation reads
	 * the single [ShuntingLoop.getLatestObservation] @Volatile reference published
	 * atomically by the sim thread. Defaults to a port over an empty bundle
	 * (SP0.10 compatibility).
	 *
	 * [DispatchLoopSensorPort.snapshot] must be called at most once per cycle: it
	 * bundles all three fields specifically so a reader never mixes fields from two
	 * different sim ticks — reading the three fields via the per-field accessors
	 * would defeat that guarantee.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); collapsed to a single provider by the
	 *   SP0.11 review follow-up (tearing fix); lifted to the SP4.1
	 *   [DispatchLoopSensorPort] seam in SP4.2 (Issue #564)
	 */
	private val dispatchLoopSensorPort: DispatchLoopSensorPort =
		DefaultDispatchLoopSensorPort {
			ShuntingLoop.TickObservation(emptyList(), emptyList(), emptyList())
		},
	/**
	 * SP0.11c: Optional sim-to-driver pacing signal (Issue #746).
	 *
	 * When non-null the driver blocks on [SnapshotSignal.await] at the top of each
	 * [runCycle] instead of sleeping 1 ms and polling.  The `simTime == prevSimTime`
	 * early-return guard is also skipped in this mode because the signal already
	 * guarantees a fresh tick — eliminating the ~4 % `trainsExited = 0` failure that
	 * occurred when the guard fired inside the lock-step handshake on the first tick.
	 *
	 * `null` by default: callers that do not supply a signal retain the original
	 * polling behaviour ([stagnantSimTimeSkipsCycle][AgentLoopDriverTest] contract).
	 *
	 * @since Issue #746 (SP0.11c — Goal 10)
	 */
	private val snapshotSignal: SnapshotSignal? = null
) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/** Simulation time at the end of the most recently completed cycle; 0.0 before the first cycle. */
	private var prevSimTime: Double = 0.0
	private var hasProcessedSnapshot: Boolean = false

	private fun pauseUntilNextSnapshot() {
		try {
			Thread.sleep(1)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

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
	 * 2. **DECIDE** — the [planner] is called with an observation built from the
	 *    snapshot; [DispatchObservation.unapprovedTrains] and block-input lists come
	 *    from one atomic [DispatchLoopSensorPort.snapshot] read (SP4.1/SP4.2).
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
		// 0. WAIT (signal-based mode): block until the sim publishes a fresh snapshot.
		// Replaces Thread.sleep polling and eliminates the simTime stale-skip race (Issue #746).
		// In polling mode (snapshotSignal == null) this step is skipped.
		snapshotSignal?.await()

		// 1. SENSE — read a consistent frozen snapshot off the perception port.
		val snapshot = perceptionPort.snapshot()
		logger.debug { "AgentLoopDriver: sensed snapshot at simTime=${snapshot.simTime}" }
		if (snapshot === SimulationSnapshot.EMPTY) {
			controller.awaitIfPaused()
			// In polling mode: sleep and retry on the next call.
			// In signal mode: EMPTY is unexpected after a signal — return without decisions;
			// the driver will block on the next await() for the next tick's signal.
			if (snapshotSignal == null) pauseUntilNextSnapshot()
			return
		}
		// Stale-snapshot guard (polling mode only): skip re-deciding on the same sim tick.
		// In signal mode the signal already guarantees a fresh tick, so this guard is omitted —
		// it is the guard's firing inside the lock-step handshake that caused the ~4 %
		// trainsExited=0 failure (Issue #746 / SP0.11c root cause).
		if (snapshotSignal == null && hasProcessedSnapshot && snapshot.simTime == prevSimTime) {
			controller.awaitIfPaused()
			pauseUntilNextSnapshot()
			return
		}

		// 2. DECIDE — call the pure dispatcher with a read-only observation.
		// SP0.11/SP4.2: unapprovedTrains and block-input lists populated via a single
		// DispatchLoopSensorPort.snapshot() read — one atomic port call, so all three
		// fields come from the same sim tick (published as one @Volatile reference by
		// ShuntingLoop.iteration() on the sim thread).
		val tick = dispatchLoopSensorPort.snapshot()
		val observation =
			DispatchObservation(
				snapshot = snapshot,
				unapprovedTrains = tick.queuedTrains,
				innerBlockInputs = tick.innerBlockInputs,
				outerBlockInputs = tick.outerBlockInputs
			)
		val decisions = planner.plan(observation)
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
		hasProcessedSnapshot = true
	}
}
