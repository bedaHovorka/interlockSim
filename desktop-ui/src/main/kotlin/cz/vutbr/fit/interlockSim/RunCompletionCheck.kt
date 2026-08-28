/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause

/**
 * How a headless simulation run ended, and the process exit code that reports it.
 *
 * @property exitCode Process exit status for this outcome.
 *
 * @since Issue #847 (round 3)
 */
enum class RunOutcome(
	val exitCode: Int
) {
	/** Simulated time reached the requested end time. */
	COMPLETED(0),

	/**
	 * The run stopped short of the requested end time — the kDisco event queue drained because
	 * every process terminated or blocked permanently. A deadlock is the usual cause.
	 *
	 * This is the only outcome that forces a non-zero process exit, because it is the only one that
	 * is silent otherwise: a usage error already prints its own diagnosis.
	 */
	TERMINATED_EARLY(1),

	/**
	 * No simulation ran: bad arguments, an unknown example name, or an exception during setup.
	 *
	 * Kept distinct from [TERMINATED_EARLY] because it is not a deadlock and must not be counted as
	 * one — and because `main` deliberately does **not** exit on it, preserving the pre-existing
	 * behaviour of every usage-error path (diagnosis logged, process exits 0).
	 */
	NOT_STARTED(2)
}

/**
 * Maps a headless [RunOutcome] onto the [RunEndCause] recorded in the run's persisted JSON.
 *
 * #846's aggregator passes a run only when `completedNaturally`, which it derives from this cause.
 * Mapping a deadlocked run to [RunEndCause.NATURAL_COMPLETION] would therefore score it as a
 * **passing** data point — the same measurement-validity failure round 3's exit-code work removed,
 * reintroduced one layer up.
 *
 * - [RunOutcome.COMPLETED] → [RunEndCause.NATURAL_COMPLETION]
 * - [RunOutcome.TERMINATED_EARLY] → [RunEndCause.TERMINATED_EARLY] — the kDisco event queue
 *   drained before the requested horizon (deadlock or early process exit). This is deliberately
 *   distinct from [RunEndCause.TIMEOUT_ABORT], which is reserved for the sweep driver killing the
 *   JVM on wall-clock. See Issue #909 for the disambiguation rationale.
 * - [RunOutcome.NOT_STARTED] → [RunEndCause.CRASH] — setup failed, so no simulation ran.
 *   Unreachable in practice, since nothing is persisted when no context was built.
 *
 * @since Issue #847 round 4 (PR #891); updated Issue #909 (disambiguate TERMINATED_EARLY)
 */
fun RunOutcome.toRunEndCause(): RunEndCause =
	when (this) {
		RunOutcome.COMPLETED -> RunEndCause.NATURAL_COMPLETION
		RunOutcome.TERMINATED_EARLY -> RunEndCause.TERMINATED_EARLY
		RunOutcome.NOT_STARTED -> RunEndCause.CRASH
	}

/**
 * Decides whether a headless run actually finished (Issue #847 round 3, PR #891 defect C).
 *
 * ## The problem
 *
 * A deadlocked headless run is currently indistinguishable from a successful one. kDisco's
 * `Simulation.run()` breaks out of its loop when the event queue drains (`Simulation.kt:138`) and
 * then returns `true` unconditionally (`:187`), exactly as it does on reaching the end time — and
 * the caller discards the value anyway. `Main.main` has no `exitProcess`, so the JVM exits 0 on
 * every path, including a swallowed exception. `ThrottlingSimulationController.requestPause()` is a
 * documented no-op, so even a CRITICAL `CollisionWarning` produces no observable effect.
 *
 * Round 2's third verification run of PR #891 ended at simTime 72.0 s of a requested 600 s and
 * still exited 0. That is not only a diagnosis problem: #847's unattended N-run sweep would record
 * such a run as a valid data point.
 *
 * ## The signal
 *
 * Reached-versus-requested simulated time. It is available on every headless path without reading
 * anything private and without touching `core/` — the caller tracks it from the same
 * `PropertyChangeEvent` stream `TextReporter` consumes.
 *
 * This deliberately does not try to diagnose *why* a run stopped early. Distinguishing a deadlock
 * from a stalled admission queue needs simulation-internal state; the exit code only has to make
 * the failure impossible to miss.
 *
 * @since Issue #847 (round 3)
 */
object RunCompletionCheck {
	/**
	 * Fraction of the requested end time a run may fall short and still count as complete.
	 *
	 * A healthy run does not stop at exactly the end time: `ShuntingLoop.interLoopSleep` calls
	 * `env.stop()` once its work is done. Round 3 measured the rule-based `shuntingLoop` baseline —
	 * the healthy reference every AI run is compared against — finishing at **597.2 s** of a
	 * requested 600 s. A one-second tolerance flagged that as a deadlock and exited 1, which would
	 * have made #847's sweep discard its own control.
	 *
	 * 5% is chosen to sit far above that normal shortfall and far below a real failure: round 2's
	 * deadlock reached 72 s of 600 s, an 88% shortfall, and round 1's controller bug reached 0.0 s.
	 * The gap between the two populations is more than an order of magnitude, so the exact fraction
	 * is not delicate.
	 */
	const val DEFAULT_TOLERANCE_FRACTION: Double = 0.05

	/**
	 * Floor under [DEFAULT_TOLERANCE_FRACTION], so a short run still gets a usable tolerance rather
	 * than a fraction of a second. One `ShuntingLoop` iteration period.
	 */
	const val MIN_TOLERANCE_SECONDS: Double = 1.0

	/** Tolerance applied when a caller does not specify one. */
	fun defaultToleranceFor(requestedEndTime: Double): Double =
		maxOf(MIN_TOLERANCE_SECONDS, requestedEndTime * DEFAULT_TOLERANCE_FRACTION)

	/**
	 * Classifies a finished run.
	 *
	 * The tolerance model assumes a sweep-range horizon (>= ~10 s): for a sub-second
	 * [requestedEndTime] the [MIN_TOLERANCE_SECONDS] floor dominates, so any non-negative
	 * [reachedSimTime] returns [RunOutcome.COMPLETED]. The sweep grid uses 600 s, so this
	 * boundary is not exercised in practice.
	 *
	 * @param reachedSimTime Highest simulated time observed during the run.
	 * @param requestedEndTime End time the run was launched with; must be positive.
	 * @param toleranceSeconds Shortfall tolerated as complete; must not be negative. Defaults to
	 *   [defaultToleranceFor] of [requestedEndTime].
	 * @throws IllegalArgumentException if [requestedEndTime] is not positive or [toleranceSeconds]
	 *   is negative.
	 */
	fun evaluate(
		reachedSimTime: Double,
		requestedEndTime: Double,
		toleranceSeconds: Double = defaultToleranceFor(requestedEndTime)
	): RunOutcome {
		require(requestedEndTime > 0.0) {
			"requestedEndTime must be positive, was $requestedEndTime"
		}
		require(toleranceSeconds >= 0.0) {
			"toleranceSeconds must not be negative, was $toleranceSeconds"
		}
		return if (reachedSimTime >= requestedEndTime - toleranceSeconds) {
			RunOutcome.COMPLETED
		} else {
			RunOutcome.TERMINATED_EARLY
		}
	}
}
