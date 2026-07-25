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

/**
 * Sim-to-driver pacing signal: wakes [AgentLoopDriver] exactly once per simulation
 * tick rather than on a wall-clock poll.
 *
 * ## Protocol
 *
 * 1. Sim thread: `captureSnapshot()` → `signal()`
 * 2. Driver thread: `await()` (blocks) → `perceptionPort.snapshot()` → decide → post
 *
 * [signal] uses "at-most-one-pending" semantics (drain-before-release): if the driver
 * is slow and has not yet consumed the previous signal, the stale permit is discarded
 * and replaced with a fresh one.  The driver therefore always reads the latest
 * snapshot rather than queuing up a backlog of stale cycles.
 *
 * ## Why this eliminates the ~4 % determinism failure (Issue #746 / SP0.11c)
 *
 * The polling path in [AgentLoopDriver] used [Thread.sleep] (1 ms) to wait for a
 * fresh snapshot.  Inside the lock-step handshake used by
 * [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedDispatcherDeterminismTest], the sim
 * thread releases `driverTurn` once per tick and waits on `simTurn` for the driver to
 * finish.  If the driver's `simTime == prevSimTime` early-return fired inside the
 * handshake (because kDisco can schedule two events at `simTime = 0.0`), the driver
 * returned *without* posting any decisions and released `simTurn` in its `finally`
 * block — the sim continued without the first admission decision, which caused
 * `trainsExited = 0`.
 *
 * With signal-based pacing the driver blocks on [await] until the sim calls
 * [signal] after each `captureSnapshot()`, and then processes the snapshot
 * unconditionally (the `simTime == prevSimTime` guard is skipped in signal mode).
 * One signal per tick → one driver cycle per tick → no missed admission tick.
 *
 * @since Issue #746 (SP0.11c — Goal 10)
 */
interface SnapshotSignal {
	/**
	 * Called by the sim thread immediately after each
	 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.captureSnapshot].
	 *
	 * Wakes one blocked [await] call; drops any unconsumed previous signal so that
	 * at most one permit is ever pending.
	 */
	fun signal()

	/**
	 * Called by the driver thread at the top of each [AgentLoopDriver.runCycle].
	 *
	 * Blocks until [signal] is called, then returns so the driver can read the fresh
	 * snapshot.  The driver runs on a dedicated daemon thread (inside `runBlocking`),
	 * so blocking this thread is intentional and does not stall the kDisco kernel.
	 */
	suspend fun await()
}

/**
 * Default [SnapshotSignal] implementation backed by a [Semaphore].
 *
 * Thread-safety: [signal] and [await] may be called concurrently from different threads.
 *
 * [signal] drains all accumulated permits before releasing one, so the semaphore count
 * is always 0 or 1.  [await] acquires one permit, blocking if none is available.
 *
 * @since Issue #746 (SP0.11c — Goal 10)
 */
class DefaultSnapshotSignal : SnapshotSignal {
	private val semaphore = Semaphore(0)

	override fun signal() {
		semaphore.drainPermits()
		semaphore.release()
	}

	override suspend fun await() {
		semaphore.acquire()
	}
}
