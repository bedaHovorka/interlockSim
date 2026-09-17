/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

/**
 * Per-adapter circuit breaker guarding the LLM call in [KoogAgentPlanAdapter] against sustained
 * overload (Issue #1058): once [failureThreshold] cycles fail in a row, further cycles skip the
 * LLM entirely for [cooldownSeconds] of simulation time rather than paying another full
 * [KoogAgentPlanAdapter.inferenceTimeout] stall, before probing once to see if the LLM has
 * recovered.
 *
 * ## Why simulation time, not wall-clock
 *
 * [shouldAttempt] and [recordFailure] take `simTime` from the same [cz.vutbr.fit.interlockSim.sim.DispatchObservation]
 * already passed into [KoogAgentPlanAdapter.plan] — no separate clock, no coroutine delay, no
 * wall-clock drift to account for. This keeps the breaker deterministic and trivially testable
 * with plain numbers.
 *
 * ## States
 *
 * - **CLOSED**: normal operation, [shouldAttempt] always `true`.
 * - **OPEN**: the LLM is skipped; [shouldAttempt] returns `false` until [cooldownSeconds] of
 *   simulation time have passed since the breaker opened, at which point it transitions to
 *   HALF_OPEN and allows exactly one probe attempt.
 * - **HALF_OPEN**: the next attempt is a probe. [recordSuccess] closes the breaker; [recordFailure]
 *   re-opens it (restarting the cooldown from the probe's `simTime`).
 *
 * ## Single-probe guard
 *
 * A HALF_OPEN window grants exactly one in-flight probe: [shouldAttempt] sets [probeInFlight] when
 * it grants a probe, and only [recordSuccess], [recordFailure] or [abandonProbe] clears it. While
 * the flag is set, further [shouldAttempt] calls return `false` (counted in [totalSkips]) — two
 * concurrent probes would race their verdicts against each other, the later one overwriting the
 * earlier's. A probe caller cancelled before it can record either verdict must call
 * [abandonProbe] (KoogAgentPlanAdapter does this in a `try/finally`), or the breaker grants no
 * probe ever again while stuck in HALF_OPEN.
 *
 * Not thread-safe beyond [Synchronized] on the mutating methods ([shouldAttempt], [recordSuccess],
 * [recordFailure], [abandonProbe]) — cheap insurance against the same concurrent-`plan()`
 * scenario [KoogAgentPlanAdapter]'s own agent-init `Mutex` guards against, even though production
 * call patterns serialize `plan()` per adapter in practice.
 */
class LlmCircuitBreaker(
	private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
	private val cooldownSeconds: Double = DEFAULT_COOLDOWN_SECONDS
) {
	init {
		require(failureThreshold > 0) { "failureThreshold must be positive, was $failureThreshold" }
		// isFinite rejects +Inf (which passes `> 0`) — an infinite cooldown would mean the breaker
		// never probes recovery again — and NaN (which fails `> 0` anyway, pinned here so the
		// guard's intent cannot silently regress).
		require(cooldownSeconds > 0 && cooldownSeconds.isFinite()) {
			"cooldownSeconds must be positive and finite, was $cooldownSeconds"
		}
	}

	enum class State { CLOSED, OPEN, HALF_OPEN }

	@Volatile
	var state: State = State.CLOSED
		private set

	/** Failures recorded since the last success. Reset to 0 by [recordSuccess]. */
	@Volatile
	var consecutiveFailures: Int = 0
		private set

	/** Total cycles skipped while OPEN, across the whole adapter lifetime. For [summaryLine]. */
	@Volatile
	var totalSkips: Long = 0L
		private set

	/**
	 * Incremented every time the breaker transitions into OPEN (including a HALF_OPEN probe
	 * failure re-opening it). Lets a caller distinguish "still the same open window" from "opened
	 * again" without exposing [openedAtSimTime] directly — used by [KoogAgentPlanAdapter] to log a
	 * skip at `warn` only once per open window and at `debug` for the rest.
	 */
	@Volatile
	var openCount: Long = 0L
		private set

	private var openedAtSimTime: Double = 0.0

	/**
	 * `true` while a granted HALF_OPEN probe has not yet reached a [recordSuccess]/[recordFailure]
	 * verdict. Guarded by this class's monitor like every private field — unlike the four public
	 * counters above, it has no reader outside [shouldAttempt]/[recordSuccess]/[recordFailure]/
	 * [abandonProbe], so it needs no `@Volatile`.
	 */
	private var probeInFlight = false

	/**
	 * `true` if this cycle should call the LLM: always when CLOSED, once per cooldown window when
	 * OPEN (the HALF_OPEN probe), never otherwise. Calling this and then NOT attempting the LLM
	 * (e.g. the caller decides not to for an unrelated reason) would incorrectly consume the probe;
	 * callers must attempt the LLM whenever this returns `true`. A caller that cannot reach either
	 * record method afterwards (e.g. its coroutine was cancelled mid-probe) must call [abandonProbe].
	 */
	@Synchronized
	fun shouldAttempt(simTime: Double): Boolean =
		when (state) {
			State.CLOSED -> true
			State.HALF_OPEN ->
				if (probeInFlight) {
					totalSkips++
					false
				} else {
					probeInFlight = true
					true
				}
			State.OPEN -> {
				if (simTime - openedAtSimTime >= cooldownSeconds) {
					state = State.HALF_OPEN
					probeInFlight = true
					true
				} else {
					totalSkips++
					false
				}
			}
		}

	/** Records a successful LLM cycle: closes the breaker, resets the failure count, releases the probe. */
	@Synchronized
	fun recordSuccess() {
		consecutiveFailures = 0
		state = State.CLOSED
		probeInFlight = false
	}

	/**
	 * Records a failed LLM cycle (timeout or exception, after any retry already happened). Opens
	 * the breaker once [failureThreshold] failures have accumulated in a row — including a single
	 * HALF_OPEN probe failure re-opening it immediately, since a probe failing means the outage has
	 * not ended. Releases the probe claim either way.
	 */
	@Synchronized
	fun recordFailure(simTime: Double) {
		consecutiveFailures++
		if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
			state = State.OPEN
			openedAtSimTime = simTime
			openCount++
		}
		probeInFlight = false
	}

	/**
	 * Releases the probe claim without recording a verdict — for a probe caller that was cancelled
	 * before it could attempt (or finish attempting) the LLM, so the next cycle can probe instead
	 * of the breaker granting no probe ever again. No-op when no probe is in flight.
	 */
	@Synchronized
	fun abandonProbe() {
		probeInFlight = false
	}

	/** One-line end-of-run summary, mirroring [cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop.summaryLine]. */
	fun summaryLine(): String =
		"[LlmCircuitBreaker] state=$state consecutiveFailures=$consecutiveFailures totalSkips=$totalSkips"

	companion object {
		const val DEFAULT_FAILURE_THRESHOLD: Int = 3
		const val DEFAULT_COOLDOWN_SECONDS: Double = 60.0
	}
}
