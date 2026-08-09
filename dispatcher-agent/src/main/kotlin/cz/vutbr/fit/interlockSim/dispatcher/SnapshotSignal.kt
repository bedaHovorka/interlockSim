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

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sim-to-driver pacing signal: wakes [AgentLoopDriver] exactly once per simulation
 * tick instead of the wall-clock `Thread.sleep(1)` poll it previously used.
 *
 * ## Protocol
 *
 * 1. Sim thread (inside [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.controlStepListener],
 *    AFTER `iteration()` has published the per-tick `latestObservation`/`TickObservation`):
 *    [signal]. **Must NOT be wired from `snapshotCaptureHook`** — `iteration()` calls
 *    that hook BEFORE it publishes `latestObservation`, so signalling from there wakes
 *    the driver to read the *previous* tick's observation (a mixed-tick read that was
 *    previously a real, confirmed bug). Both the production wiring
 *    ([cz.vutbr.fit.interlockSim.ExampleRegistry.wireDispatcherAgent]) and the
 *    determinism-test wiring fire from `controlStepListener` for exactly this reason.
 * 2. Driver thread (inside [AgentLoopDriver.runCycle]): [await] (blocks until step 1
 *    above runs, or until the bounded timeout — see below) → `perceptionPort.snapshot()`
 *    → decide → post
 *
 * [signal] uses "at-most-one-pending" semantics: if the driver has not yet consumed a
 * previous signal by the time a new one arrives, the stale, unconsumed permit is
 * discarded and replaced with a fresh one rather than accumulating a backlog. A driver
 * that falls behind the sim therefore always wakes to the *latest* captured snapshot
 * instead of working through a queue of stale ones — which is safe here because
 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] recomputes its decisions from
 * scratch on every call (state-based, not edge-triggered): reacting to the current
 * network state a tick later than it changed is a latency cost, not a correctness one.
 *
 * ## Why this replaces the wall-clock poll
 *
 * The previous pacing in [AgentLoopDriver] combined two wall-clock-coupled mechanisms:
 * a `Thread.sleep(1)` busy-poll, and a guard that skipped deciding whenever
 * `snapshot.simTime == prevSimTime`. Both were an attempt to approximate "has the sim
 * published a new tick yet?" without an actual signal from the sim thread — and the
 * `simTime` guard in particular could suppress a real decision opportunity on a tick
 * whose simulation time happened not to differ from the previous one it was allowed to
 * process, permanently stalling admission for the whole run in the reported failure
 * signature (`trainsExited = 0`).
 *
 * With [SnapshotSignal], the driver is woken directly and only by the sim thread that
 * just published a fresh snapshot; there is no polling and no guesswork about whether
 * the tick changed, so the `simTime` guard becomes both unnecessary and actively wrong
 * to keep — a caller opting into signal-based pacing must not re-derive staleness from
 * `simTime` on top of it.
 *
 * ## Why [await] is bounded, not an unconditional block
 *
 * [ShuntingLoop][cz.vutbr.fit.interlockSim.sim.ShuntingLoop] stops invoking
 * `iteration()` entirely the moment the simulation ends — its `interLoopSleep` sets
 * `isSimActive() == false` and simply never runs another iteration — so whatever hook
 * [signal] is wired into (`controlStepListener` in production; see the protocol above)
 * is never called again. [AgentLoopDriver]'s run loop is `while (isSimActive()) { runCycle() }`
 * (see [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.agentDriverAction]); if [await] were
 * an unconditional block, a driver thread that re-entered [AgentLoopDriver.runCycle]
 * for one last time in the (very narrow but real) window between the last real signal
 * being consumed and `isSimActive()` flipping to `false` would then wait on a signal
 * that is never coming again — a leaked, permanently-parked thread on every completed
 * simulation run. [await] returning `false` on a timeout lets that last cycle bail out
 * and re-check `isSimActive()` instead. Note this means shutdown detection is
 * **timeout-driven, not signal-driven**: the bounded [await] is the mechanism by which
 * a parked driver notices the sim has stopped. The timeout is a shutdown safety net
 * only: it is chosen large enough that it is not expected to fire during normal
 * operation (a fresh signal arrives every simulation tick), and even if system load
 * causes a spurious timeout mid-run, no signal is lost — the pending permit (if any) is
 * simply picked up by the next [await] call, since [signal] does not require an [await]
 * to be in progress to take effect.
 */
interface SnapshotSignal {
	/**
	 * Called by the sim thread from
	 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.controlStepListener], AFTER
	 * `iteration()` has published the per-tick `TickObservation` — NOT from
	 * `snapshotCaptureHook` (see the class KDoc protocol for why that ordering is
	 * broken).
	 *
	 * Wakes one blocked [await] call. Any signal not yet consumed by [await] is dropped
	 * first, so at most one permit is ever pending — see the class KDoc for why
	 * coalescing is safe here.
	 */
	fun signal()

	/**
	 * Called by the driver thread at the top of each
	 * [AgentLoopDriver.runCycle][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver.runCycle].
	 *
	 * Blocks the calling thread until [signal] is called, then returns `true` so the
	 * driver can read the freshly published snapshot. The driver runs on its own
	 * dedicated thread (started via
	 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.agentDriverAction]), so blocking here
	 * is intentional and never stalls the kDisco kernel thread.
	 *
	 * Returns `false` if no signal arrived within an implementation-defined bounded
	 * timeout — see the class KDoc ("Why await is bounded, not an unconditional block")
	 * for why this exists and why it is safe to treat as "nothing to do this cycle"
	 * rather than as a lost tick.
	 */
	fun await(): Boolean
}

/**
 * Default [SnapshotSignal] implementation backed by a [Semaphore].
 *
 * Thread-safety: [signal] and [await] may be called concurrently from different
 * threads — the sim thread and the driver thread respectively.
 *
 * [signal] drains any already-accumulated permits before releasing exactly one, so the
 * semaphore's permit count is always either 0 or 1 — never more. [await] acquires one
 * permit, blocking the calling thread for up to [awaitTimeoutMillis] if none is
 * currently available.
 *
 * @param awaitTimeoutMillis Upper bound on how long [await] blocks without a [signal]
 *   before returning `false`. Large relative to a normal simulation tick (ticks arrive
 *   far more often than this in practice) so it is not expected to fire during regular
 *   operation; small enough that a driver thread notices simulation shutdown promptly.
 *   In GUI 1× real-time mode `ShuntingLoop.iteration()` ends with `hold(1.0)`, so ticks
 *   arrive ~1 s apart wall-clock and this 50 ms timeout fires ~20 no-op cycles per tick
 *   — each no-op is just `awaitIfPaused` + `return false` (non-mutating, pause-aware,
 *   no determinism or correctness risk); under a headless `NoOpSimulationController`
 *   target it is genuinely signal-driven. Kept fixed rather than made adaptive: a
 *   longer/adaptive timeout would slow shutdown detection for no correctness gain.
 */
class DefaultSnapshotSignal(
	private val awaitTimeoutMillis: Long = DEFAULT_AWAIT_TIMEOUT_MILLIS
) : SnapshotSignal {
	private val semaphore = Semaphore(0)

	private val signals = AtomicLong(0L)
	private val coalesced = AtomicLong(0L)

	/** Total [signal] calls — one per control tick in the production wiring. */
	val signalCount: Long get() = signals.get()

	/**
	 * Signals discarded by the at-most-one-pending rule: ticks that fired while the driver was
	 * still busy with an earlier cycle and were overwritten before it could wake.
	 *
	 * The coalescing itself is correct — a dispatcher that recomputes from scratch each cycle
	 * gains nothing from a backlog of stale wake-ups. `signalCount - coalescedCount` is the
	 * number of ticks that actually reached the driver, so the gap between the two counts is a
	 * consequence of inference/decision latency, not a defect.
	 */
	val coalescedCount: Long get() = coalesced.get()

	override fun signal() {
		signals.incrementAndGet()
		coalesced.addAndGet(semaphore.drainPermits().toLong())
		semaphore.release()
	}

	override fun await(): Boolean =
		try {
			semaphore.tryAcquire(awaitTimeoutMillis, TimeUnit.MILLISECONDS)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}

	companion object {
		/** @see DefaultSnapshotSignal.awaitTimeoutMillis */
		const val DEFAULT_AWAIT_TIMEOUT_MILLIS: Long = 50L
	}
}
