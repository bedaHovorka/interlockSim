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
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerTickListener
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.planner.toActionAuthor
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

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
 * from `docs/specs/2026-07-08-544-sp05-drive-loop-design.md`).
 *
 * ## DispatchObservation construction
 *
 * The observation combines the general-purpose [SimulationSnapshot] (signals, block
 * occupancy, train positions, timetables) with the ShuntingLoop-specific dispatch
 * inputs read from [dispatchLoopSensorPort]: the [DispatchObservation.unapprovedTrains]
 * list and the block-input lists all come from one atomic [DispatchLoopSensorPort.snapshot]
 * read. With the default (empty) sensor port, [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]
 * (via [cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter]) handles
 * this case gracefully (it returns [cz.vutbr.fit.interlockSim.sim.DispatchDecision.NoAction]).
 *
 * @param perceptionPort Read-only sense port for the railway network state
 * @param planner        Pluggable planning function; must not retain the
 *   observation beyond the call or mutate simulation state
 * @param commandQueue   Thread-safe queue to which decisions are posted;
 *   drained and applied by the sim-thread applier
 * @param controller     Pacing controller; injected into the driver **only** —
 *   never exposed to [cz.vutbr.fit.interlockSim.context.SimulationEnvironment] or
 *   policy implementations
 * @param snapshotSignal Optional sim-to-driver pacing signal. When non-null,
 *   [runCycle] blocks on [SnapshotSignal.await] (bounded — see its KDoc) at the top
 *   of each cycle instead of polling with [Thread.sleep], and — because a real
 *   signal already guarantees the resulting snapshot is fresh and has not been
 *   processed before — the `snapshot.simTime == prevSimTime` stale-tick guard is
 *   skipped too. The caller must arrange for [SnapshotSignal.signal] to be called on
 *   the sim thread from
 *   [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.controlStepListener], AFTER
 *   `iteration()` publishes the per-tick `TickObservation` — NOT from
 *   [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.snapshotCaptureHook], which
 *   `iteration()` calls BEFORE the publish and would wake the driver to read the
 *   previous tick's observation.
 *   `null` by default: preserves the original polling behaviour for callers that do
 *   not supply a signal (e.g. tests exercising [runCycle] directly against a mocked
 *   [perceptionPort] with no real sim thread to signal from).
 */
class AgentLoopDriver(
	private val perceptionPort: NetworkPerceptionPort,
	private val planner: DispatcherPlanner,
	private val commandQueue: ActuatorCommandQueue,
	private val controller: SimulationController,
	/**
	 * Sensor port for the current per-tick dispatch-loop observation bundle
	 * (unapproved-train queue plus inner/outer block inputs) — the "sense" seam onto
	 * ShuntingLoop-specific inputs. Its [DispatchLoopSensorPort.snapshot] is invoked
	 * ONCE per [runCycle] on the driver thread during SENSE; the default
	 * implementation reads the single [ShuntingLoop.getLatestObservation] @Volatile
	 * reference published atomically by the sim thread. Defaults to a port over an
	 * empty bundle.
	 *
	 * [DispatchLoopSensorPort.snapshot] must be called at most once per cycle: it
	 * bundles all three fields specifically so a reader never mixes fields from two
	 * different sim ticks — reading the three fields via the per-field accessors
	 * would defeat that guarantee.
	 */
	private val dispatchLoopSensorPort: DispatchLoopSensorPort =
		DefaultDispatchLoopSensorPort {
			ShuntingLoop.TickObservation(emptyList(), emptyList(), emptyList())
		},
	private val snapshotSignal: SnapshotSignal? = null,
	/**
	 * The [KoogAgentPlanAdapter] driving [planner], when [planner] is (possibly wrapped)
	 * LLM-backed — supplied explicitly by the wiring layer rather than detected via `is` on a
	 * `by`-delegated wrapper such as [MeasuringPlanAdapter].
	 *
	 * When non-null, this driver installs a [PlannerTickListener] on it and attributes each
	 * cycle's `commandQueue.postAll` call via [TickOutcome.toActionAuthor] on the tick
	 * outcome most recently reported. When `null` (the default — a pure deterministic
	 * planner such as [RuleBasedPlanAdapter]), every cycle is attributed
	 * [ActionAuthor.RULE_BASED] — safe because a synchronous rule-based planner has no
	 * internal LLM/fallback blend to distinguish.
	 */
	private val plannerTickSource: KoogAgentPlanAdapter? = null,
	/**
	 * Optional observer receiving every [TickRecord] produced by [plannerTickSource].
	 *
	 * Exists because this class *overwrites* `plannerTickSource.tickListener` in its `init` block,
	 * so a caller that installed its own listener beforehand had it silently discarded — there was
	 * no seam at all onto which a run recorder could be wired, which is why
	 * `DispatcherRunRecorder.onTick` had no production caller and every per-run JSON reported
	 * `totalTicks = 0`.
	 *
	 * Invoked on the dispatcher-agent driver thread, synchronously inside `planner.plan()`.
	 * Implementations must be cheap and non-blocking; `DefaultDispatcherRunRecorder.onTick` is a
	 * counter increment.
	 *
	 * `null` for wirings with no recorder — the rule-based baseline included.
	 */
	private val onTickRecord: ((TickRecord) -> Unit)? = null,
	/**
	 * Minimum wall-clock spacing between the end of one cycle and the end of the next, in
	 * milliseconds. `0` (the default) imposes nothing and reproduces the original cadence.
	 *
	 * See [awaitMinimumCyclePeriod] for why this is a wall-clock floor rather than the
	 * simulated-time period #822 §5.5 describes.
	 */
	private val tickPeriodMs: Long = DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		private const val NANOS_PER_MILLI: Long = 1_000_000L
	}

	/** Simulation time at the end of the most recently completed cycle; 0.0 before the first cycle. */
	private var prevSimTime: Double = 0.0
	private var hasProcessedSnapshot: Boolean = false

	/**
	 * Wall clock at the end of the most recently completed cycle, or `null` before the first.
	 * Driver-thread-confined, like [prevSimTime].
	 */
	private var lastCycleEndNanos: Long? = null

	/**
	 * Most recent [TickOutcome] reported by [plannerTickSource]'s [PlannerTickListener], if any.
	 * `@Volatile` even though both writer (the listener callback) and reader (this class) run
	 * on the same driver thread/coroutine — matches this class's existing `@Volatile` usage
	 * for defensive safe publication.
	 */
	@Volatile
	private var lastTickOutcome: TickOutcome? = null

	init {
		// The listener fires synchronously inside plannerTickSource.plan()'s suspend body — all
		// four call sites (LLM success, EMPTY_NO_TOOLS/TIMEOUT/EXCEPTION fallback) are inline in
		// KoogAgentPlanAdapter.plan's try/catch, not in a spawned coroutine — so lastTickOutcome
		// is always fresh by the time plan() returns below in runCycle().
		plannerTickSource?.tickListener =
			PlannerTickListener { record ->
				lastTickOutcome = record.outcome
				onTickRecord?.invoke(record)
			}
	}

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
	 * 0. **WAIT** (signal-based pacing only, [snapshotSignal] non-null) — blocks on
	 *    [SnapshotSignal.await] until the sim thread has published a fresh snapshot for
	 *    this tick. Skipped entirely in polling mode ([snapshotSignal] `null`).
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
	 *
	 * @return `true` if a full cycle actually ran (a decision batch — possibly empty —
	 *   was computed and posted, and [SimulationController.throttle] was called);
	 *   `false` if this call was a no-op short-circuit (nothing sensed, decided, posted,
	 *   or throttled) — an [SimulationSnapshot.EMPTY] / stale-tick / signal-timeout
	 *   case. Callers that need to know whether a cycle's decisions were actually
	 *   posted before proceeding (e.g. a caller enforcing strict per-tick ordering
	 *   against the sim thread) must gate on this return value rather than assume
	 *   every call did real work — see
	 *   [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedDispatcherDeterminismTest] for
	 *   why: releasing such a barrier unconditionally after a no-op call lets the sim
	 *   thread proceed before the corresponding decision is actually posted.
	 */
	suspend fun runCycle(): Boolean {
		// 0. WAIT (signal-based pacing only) — block until the sim thread publishes a
		// fresh snapshot for this tick. Replaces the Thread.sleep(1) poll and the
		// simTime-equality guard below with an explicit wake-up.
		// SnapshotSignal.await's bounded timeout is a shutdown safety net (see its KDoc):
		// if it returns false, no signal arrived — most commonly because the simulation
		// has just stopped calling the hook that signals — so this cycle does nothing and
		// lets the caller's `while (isSimActive())` loop notice and exit instead of
		// blocking here forever. No decision opportunity is lost: any signal that does
		// eventually arrive is still picked up by the next await() call.
		if (snapshotSignal != null && !snapshotSignal.await()) {
			controller.awaitIfPaused()
			return false
		}

		// 1. SENSE — read a consistent frozen snapshot off the perception port.
		val snapshot = perceptionPort.snapshot()
		logger.debug { "AgentLoopDriver: sensed snapshot at simTime=${snapshot.simTime}" }
		if (snapshotSignal == null && snapshot === SimulationSnapshot.EMPTY) {
			controller.awaitIfPaused()
			pauseUntilNextSnapshot()
			return false
		}
		if (snapshotSignal == null && hasProcessedSnapshot && snapshot.simTime == prevSimTime) {
			// Polling-mode-only stale-tick guard. Not applicable in signal-based pacing:
			// SnapshotSignal.await already guarantees this snapshot was published for THIS
			// tick and has not been processed before, so re-deriving staleness from
			// simTime here would be redundant at best and actively wrong: it is exactly
			// this guard firing on a tick it should not have skipped that produced the
			// ~4% `trainsExited = 0` admission-stall residue that this parameter was
			// introduced to eliminate.
			controller.awaitIfPaused()
			pauseUntilNextSnapshot()
			return false
		}

		// 2. DECIDE — call the pure dispatcher with a read-only observation.
		// unapprovedTrains and block-input lists are populated via a single
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

		// Attribute this cycle's decisions correctly instead of silently defaulting to
		// ActionAuthor.LLM (postAll's pre-fix default) regardless of which planner
		// actually produced them.
		val author =
			if (plannerTickSource == null) {
				ActionAuthor.RULE_BASED
			} else {
				lastTickOutcome?.toActionAuthor ?: ActionAuthor.LLM
			}
		val reason = lastTickOutcome?.name ?: ""

		// 3. ACT — post decisions to the thread-safe handoff queue.
		// The sim-thread DispatchDecisionApplier drains and applies them; this
		// call never mutates simulation state on the driver thread.
		val posted = commandQueue.postAll(decisions, author, reason)
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
		awaitMinimumCyclePeriod()
		prevSimTime = snapshot.simTime
		hasProcessedSnapshot = true
		return true
	}

	/**
	 * Holds the driver until at least [tickPeriodMs] of wall clock has passed since the previous
	 * cycle ended. No-op at the default `0`.
	 *
	 * ## Why wall clock and not simulated time
	 *
	 * #822 §5.5 specifies a tick period in *simulated* seconds, and on the synchronous
	 * [DispatchTickLoop] path that is what it means. This driver is the asynchronous path, and its
	 * cadence is not its own to choose: ticks arrive from [SnapshotSignal], which the sim thread
	 * fires once per `ShuntingLoop` control step. Reinterpreting the parameter as a simulated-time
	 * period here would mean changing that control step — which lives in `core/` and is off limits
	 * (C10). The only period this driver can actually impose is a wall-clock floor between cycles,
	 * so that is what it imposes, and the run JSON records it as such.
	 *
	 * ## What it can and cannot do
	 *
	 * It can only ever make the loop *slower*. At the 10–25 s inference latency measured on
	 * `qwen2.5:7b-instruct`, any value below p95 latency is inert — the cycle already took longer
	 * than the floor. It is honoured, recorded and sweepable; whether it is *useful* at a given
	 * model's latency is exactly the sort of thing the #847 grid exists to answer, and the answer
	 * for a 7B on this station is "not below ~25 s".
	 *
	 * [kotlinx.coroutines.delay] rather than `Thread.sleep`: the driver runs inside `runBlocking`
	 * on a daemon thread shared with nothing else, but suspending keeps this consistent with
	 * [SimulationController.awaitIfPaused] above and leaves the thread interruptible.
	 */
	private suspend fun awaitMinimumCyclePeriod() {
		if (tickPeriodMs <= 0L) return
		val now = System.nanoTime()
		val previous = lastCycleEndNanos
		lastCycleEndNanos = now
		if (previous == null) return
		val elapsedMs = (now - previous) / NANOS_PER_MILLI
		val remainingMs = tickPeriodMs - elapsedMs
		if (remainingMs > 0) {
			logger.debug { "AgentLoopDriver: holding ${remainingMs}ms to honour tickPeriodMs=$tickPeriodMs" }
			delay(remainingMs)
			lastCycleEndNanos = System.nanoTime()
		}
	}
}
