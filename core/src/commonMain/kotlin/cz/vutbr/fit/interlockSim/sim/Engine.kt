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

import cz.ksimulantenbande.kdisco.Condition
import cz.ksimulantenbande.kdisco.Continuous
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Variable
import cz.vutbr.fit.interlockSim.domain.MAXIMAL_TRAIN_ACCELERATION
import cz.vutbr.fit.interlockSim.domain.MINIMAL_TRAIN_DECELERATION
import cz.vutbr.fit.interlockSim.domain.brakingDistanceFrom
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Train propulsion / braking process — formerly `Train.Motor`.
 *
 * Extends [Continuous] (not [LoopProcess]/`Process`) because train kinematics require
 * ODE integration via [derivatives]; [start]/[stop] gate integration per acceleration
 * phase. The project-level "continuous simulation not required" finding concerns
 * framework choice (DSOL vs kDisco), not Engine's internal physics.
 *
 * The private `terminate` flag mirrors the [LoopProcess] shutdown pattern — necessary
 * duplication, because Engine cannot inherit it (LoopProcess is Process-based, discrete).
 *
 * Extracted from [Train] as a top-level class (Issue #1059) so kinematics stay testable and
 * documentable without living inside the multi-thousand-line train process.
 *
 * See `docs/MOTOR_CONTINUOUS_RATIONALE.md` (issue #373; renamed Engine in #1059).
 *
 * @param host the owning train surface this engine drives
 */
internal class Engine(
	private val host: Host
) : Continuous() {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Narrow train surface the engine needs. Implemented by [Train] so kinematics stay
	 * package-internal without exposing [Variable] fields on the public Train API.
	 */
	internal interface Host {
		val trainNumber: Int

		val velocityVariable: Variable

		val accelerationVariable: Variable

		fun getVelocity(): Double

		fun distanceToSemaphore(): Double

		fun semaphoreToStopShortOf(): DynamicRailSemaphore?

		fun nextSemaphore(): OrientedPathSeparator?

		val currentSpeedLimitMps: Double

		val signalAheadAspect: Signal?

		val semaphoreStopClearanceMeters: Double

		fun reportDebug(message: String)
	}

	private var currentCondition: AccelerationStopCondition? = null
	private var targetSpeed: Double = 0.0
	private var accelerate: Boolean = false
	private var terminate = false

	/**
	 * Set by [privateAccelerateTo] for the command it has just issued; consumed by [actions]
	 * when that command's iteration starts.
	 *
	 * A wake-up with no command pending is a surplus turn and must only passivate again. kDisco
	 * keeps `activate` and wait-notice wake-ups on separate channels (kdisco#73/#74), so a
	 * [cancelAccelerating] aimed at an engine parked in its wait resumes it twice: through the
	 * activate it issues and through the notice its `accelerate = false` satisfies. Without
	 * this flag the second turn re-ran [iteration] with the stale [currentCondition] — the engine
	 * started integrating towards its old target on a train whose velocity integration had been
	 * stopped, and stood there reporting a non-zero acceleration until the next real command
	 * (measured: 2.469 m/s² at a stand for an ownership conflict, `shuntingLoopAI`, 2026-09-13).
	 */
	private var commandPending = false

	/**
	 * The stop test of one engine leg, and how [derivatives] drives that leg.
	 *
	 * @property runUpRate a constant acceleration for the leg in place of the braking law;
	 *   `null` — every leg but an accelerating [resumeAtAspectCap] — keeps the law
	 */
	private inner class AccelerationStopCondition(
		private val stopTest: AccelerationStopTest,
		val runUpRate: Double? = null
	) : Condition {
		override fun test(): Boolean = !accelerate || stopTest.condition(targetSpeed, host.getVelocity())

		fun getStopTest(): AccelerationStopTest = stopTest
	}

	/**
	 * How much room phase 1 of [onWarning] still has, as a single margin that goes
	 * non-positive exactly when the phase must end (Issue #1014).
	 *
	 * Phase 1 runs the train up towards half the permitted speed; phase 2 then brakes it to
	 * a standstill. The margin is the smaller of two distances to that hand-over:
	 *
	 * - **half speed** — `targetSpeed / 2 - velocity`, the margin form of
	 *   [AccelerationStopTest.TO_HALF_SPEED]'s own condition;
	 * - **braking room** — what is left of the distance to the stop line after the textbook
	 *   braking distance at the deceleration bound, [brakingDistanceFrom].
	 *
	 * The braking-room term is what #1014 was missing. On a block too short for the ramp the
	 * train never reaches half speed, so [derivatives] eventually cleared `accelerate` when the
	 * remaining distance ran out. That ended the phase-1 wait *and* failed the `accelerate &&`
	 * guard in [iteration], so the braking phase was skipped and the train arrived at the signal
	 * at line speed, to be snapped to zero by `Front.fireStop`. Ending on the margin instead
	 * keeps `accelerate` true, so [iteration] enters phase 2 with the room it still needs.
	 *
	 * It aims at [clearanceStopLineDistance], the point phase 2 really brakes to (Issue #989) —
	 * the same rule [brakingTargetDistance] uses. Aiming at the signal itself would leave the
	 * phase one metre short of the room it needs and force a deceleration past the bound.
	 *
	 * The braking-room term is armed only while the train really does stop short of a
	 * restrictive signal. If the aspect clears during phase 1, [iteration] exits the phase
	 * immediately and resumes at the live aspect's speed cap, and brakes to the stop line again
	 * if the aspect turns restrictive before the resumed speed is reached. If the clearance is waived,
	 * [cz.vutbr.fit.interlockSim.sim.Train] cancels this wait and issues the replacement command.
	 *
	 * `-1.0` while `accelerate` is false ends the wait on a cancel or a re-command;
	 * `Front.fireStop` zeroes the velocity, which the half-speed term alone would never see.
	 *
	 * A non-positive remaining distance or velocity leaves the braking-room term out: those
	 * corners belong to the existing exits ([derivatives]' `s <= 0` branch).
	 */
	private fun approachMargin(): Double {
		if (!accelerate) return -1.0
		val halfSpeedMargin = targetSpeed / 2.0 - host.getVelocity()
		return minOf(halfSpeedMargin, brakingRoomMargin())
	}

	override suspend fun actions() {
		while (true) {
			if (terminate) break
			if (commandPending) {
				commandPending = false
				iteration()
				if (terminate) break
			}
			passivate()
		}
	}

	private suspend fun iteration() {
		val cond = requireSimulationNotNull(currentCondition) { "Current condition must not be null during iteration" }
		accelerate = true
		logger.trace {
			"Train ${host.trainNumber} engine iteration: target speed $targetSpeed, " +
				"current velocity ${host.getVelocity()}"
		}
		start()

		// The approach is the one wait located by root-finding: its exit is a threshold the
		// train crosses while accelerating, so a whole-step overshoot costs braking room the
		// short block does not have (Issue #1014; Issue #760 tracks the rest).
		//
		// The remaining arms keep `waitUntil`: their primary exits are velocity targets, where
		// one step of lateness is immaterial, and converting them would move the shunting-loop
		// baselines. The else-arm's second exit, [brakingRoomGone] (Issue #1057), is a distance
		// threshold, but a plain wait wakes at most one accepted step (kDisco `dtMax` = 1 ms)
		// late: a residual of well under 1 m/s at the worst line speed, inside the best-effort
		// clearance doctrine and far below the line-speed snap this fixes.
		//
		// Cancellation composes the same way it does for `waitUntil`. [cancelAccelerating]
		// clears `accelerate` and activates; kDisco checks level crossings straight after that
		// event, so [approachMargin] is already -1.0 and the wait ends at the same instant.
		// One of the two turns returns from the wait; a re-command's iteration then starts
		// from [commandPending] in [actions], and the other turn only passivates again.
		val isHalfSpeedLeg = cond.getStopTest() == AccelerationStopTest.TO_HALF_SPEED
		if (isHalfSpeedLeg) {
			// The second term ends phase 1 the instant the signal this phase started short of
			// itself turns allowing, rather than only letting a clear relax the braking-room
			// term (Copilot review, PR #1033): [host.semaphoreToStopShortOf] only
			// distinguishes allowing from non-allowing, so a clear to a restrictive-but-allowing
			// aspect (S30/S40) looked identical to a clear to FREE — phase 1 kept running toward
			// half of the pre-clear `targetSpeed`, which can be well above the new aspect's own
			// permitted speed by the time the train reaches it.
			//
			// Deliberately [restrictiveSignalCleared], not `semaphoreToStopShortOf() == null`:
			// the latter also goes null when clearance is waived for an unrelated
			// reason (the route past the signal was lost), which the front already re-commands
			// externally via `cancelAccelerating()` — reacting to it here
			// too raced that path and broke the Issue #989 clearance-stop and mid-journey
			// no-path tests. Reading the captured semaphore's own aspect sidesteps the waiver
			// entirely: it is unaffected by the waived flag.
			waitUntilCrossing { minOf(approachMargin(), if (restrictiveSignalCleared()) -1.0 else 1.0) }
		} else {
			// Also ends when a restrictive aspect has left no braking room (Issue #1057): the leg
			// was commanded while the signal allowed, so nothing else would ever brake it.
			waitUntil(Condition { cond.test() || brakingRoomGone() })
		}

		// `!terminate` because [terminate] now really does end the wait above (it reactivates
		// rather than activates): without it an engine torn down mid-approach would answer by
		// entering the braking phase and parking again instead of leaving the loop.
		// The cruise leg has reached its speed and would now go idle, deaf to an aspect that
		// turns restrictive while the train coasts at line speed (Issue #1057).
		if (
			!terminate &&
			accelerate &&
			!isHalfSpeedLeg &&
			targetSpeed > 0.0 &&
			host.nextSemaphore() is DynamicRailSemaphore &&
			watchForLateRestrictiveAspect()
		) {
			runApproachLoop(runningOn = false)
		}
		if (!terminate && accelerate && isHalfSpeedLeg) {
			// From here to the end of the leg the aspect may change any number of times, and each
			// change hands the engine over: a clear resumes the run at the live aspect's cap, a
			// return to a restrictive aspect brakes to the stop line again. Phase 1 may already
			// have seen a clear, hence the starting state — resuming immediately instead of
			// continuing toward half of the pre-clear target. Resuming without watching for the
			// return let a train accelerate into a signal back at danger and be snapped to zero
			// at the clearance line from 34 m/s (PR #1033 review).
			runApproachLoop(runningOn = restrictiveSignalCleared())
		}

		accelerate = false
		stop()
		host.accelerationVariable.state = 0.0
	}

	/**
	 * Hands the engine over on each aspect change until the leg ends: a clear resumes the run at
	 * the live aspect's cap, a return to a restrictive aspect brakes to the stop line.
	 *
	 * A resumed run that reaches its cap — half-way to the signal, see the speed-law section of
	 * [resumeAtAspectCap] — does not end the leg either (Issue #1087): going idle
	 * there left the engine deaf to an aspect turning restrictive while the train coasts at the
	 * resumed cap — line speed after a clear to FREE — the Issue #1057 defect class on the
	 * resume path into it. [watchForLateRestrictiveAspect] is re-armed instead, inside the
	 * engine's own process (a second engine command in the same instant would be the kdisco#73
	 * shape), and a restrictive flip that exhausts the braking room hands over to the braking
	 * phase like any other aspect change in this loop.
	 *
	 * @param runningOn whether the leg starts in the resumed state (the aspect already allows)
	 */
	private suspend fun runApproachLoop(runningOn: Boolean) {
		var running = runningOn
		while (true) {
			val aspectChanged =
				if (running) {
					// No `targetSpeed > 0` or `DynamicRailSemaphore` guard here, unlike the
					// `iteration` arm: an allowing aspect's cap is positive, so `targetSpeed > 0`
					// holds on this path, the resumed leg implies a `DynamicRailSemaphore` ahead,
					// and `brakingRoomGone()`/`brakingRoomMargin()` re-guard both anyway.
					resumeAtAspectCap() || (!terminate && accelerate && watchForLateRestrictiveAspect())
				} else {
					brakeToStopLine()
				}
			if (!aspectChanged) break
			running = !running
		}
	}

	/**
	 * Whether a restrictive aspect ahead has left no more braking room than the deceleration bound
	 * needs to reach the clearance stop line (Issue #1057) — the same rule [approachMargin] applies
	 * to a leg commanded at danger, here for every other leg.
	 */
	private fun brakingRoomGone(): Boolean = accelerate && targetSpeed > 0.0 && brakingRoomMargin() <= 0.0

	/**
	 * Keeps a finished leg listening for a restrictive aspect (Issue #1057). Armed both after
	 * a leg commanded by [accelerateTo] reaches its speed ([iteration]) and after a leg the
	 * engine resumed itself reaches the live aspect's cap ([runApproachLoop], Issue #1087).
	 *
	 * The engine commanded for a leg goes idle once the leg's speed is reached, and an idle
	 * engine evaluates no [derivatives]: a signal turning restrictive while the train coasts at
	 * line speed would be met at the clearance line by `Front.fireStop` snapping it to zero.
	 * The braking law itself needs no command — [brakingTargetDistance] already aims it at the
	 * stop line for a stand at a restrictive signal — only something to start it at the right
	 * moment. That is this wait, on the same [brakingRoomMargin] the two-phase approach uses; it is
	 * the engine's *own* process resuming, not a second command, so it cannot be the kdisco#73
	 * double command. `accelerate` stays true throughout so a real command ends the wait through
	 * [cancelAccelerating], exactly as it ends the approach wait.
	 *
	 * @return `true` when the braking room is gone and the engine is running again, ready to brake
	 */
	private suspend fun watchForLateRestrictiveAspect(): Boolean {
		if (!brakingRoomGone()) {
			stop()
			host.accelerationVariable.state = 0.0
			// `minOf(..., 1.0)` bounds the guard: an unarmed [brakingRoomMargin] is +∞, and
			// +∞ must never reach the root finder as a crossing value.
			waitUntilCrossing { if (accelerate) minOf(brakingRoomMargin(), 1.0) else -1.0 }
			if (terminate || !accelerate) return false
			start()
		}
		// The crossing above is the only wake reason left, but kDisco's root finder may land
		// up to its tolerance on the positive side: re-testing [brakingRoomGone] here could
		// read a margin in (0, ~1e-9] as "room left", idle the engine, and let the train coast
		// into the very snap this watch exists to prevent (Issue #1057 review).
		return !terminate
	}

	/**
	 * Room left to the clearance stop line after the textbook braking distance at the
	 * deceleration bound, `distance - brakingDistanceFrom(v)`; non-positive exactly
	 * when the train must start braking now to stand there (Issues #1014, #1057, #1056).
	 *
	 * [Double.POSITIVE_INFINITY] while it is not armed: no restrictive signal to stop short of, or a
	 * non-positive remaining distance or velocity — those corners belong to the existing exits
	 * ([derivatives]' `s <= 0` branch and the front's clearance gate).
	 */
	private fun brakingRoomMargin(): Double {
		if (host.semaphoreToStopShortOf() == null) return Double.POSITIVE_INFINITY
		val remaining = clearanceStopLineDistance()
		val speed = host.getVelocity()
		if (remaining <= 0 || speed <= 0) return Double.POSITIVE_INFINITY
		return remaining - brakingDistanceFrom(speed)
	}

	/**
	 * Whether the signal phase 1 started short of has itself turned allowing.
	 *
	 * `onWarning` is only ever commanded when [Host.nextSemaphore] is non-allowing (the `else`
	 * branch of the front's accelerate-to-signal), so this starts `false` and can only become
	 * `true` by that same semaphore's own aspect changing — unlike [Host.semaphoreToStopShortOf],
	 * which also goes `null` when clearance is waived for the unrelated route-lost reason the
	 * front already re-commands externally.
	 */
	private fun restrictiveSignalCleared(): Boolean =
		(host.nextSemaphore() as? DynamicRailSemaphore)?.signal?.isAllowing() == true

	/**
	 * Resumes the run at the live aspect's permitted speed, capped against
	 * [Host.currentSpeedLimitMps] exactly as the front caps it before commanding the engine —
	 * [Host.currentSpeedLimitMps] is deliberately aspect-independent (the physical track
	 * constraint only), so using it alone would let the train pass a restrictive-but-allowing
	 * aspect (S30/S40) above its permitted speed.
	 *
	 * Called only once the signal ahead is known to be showing something allowing — either
	 * [restrictiveSignalCleared] or `semaphoreToStopShortOf() == null` in the caller — so
	 * [Host.signalAheadAspect] must not be `null` here.
	 *
	 * The wait also ends when the aspect turns restrictive again before the resumed speed is
	 * reached. Waiting for the speed alone kept `targetSpeed` positive, so the train accelerated
	 * towards a signal back at danger and the front gate snapped it to zero at the clearance line
	 * (PR #1033 review). Once the resumed speed is reached the leg does not go
	 * idle either: [runApproachLoop] re-arms the late-aspect watch (Issue #1087), so a
	 * restrictive flip while the train coasts at the resumed cap is braked for exactly like one
	 * on a leg commanded by `accelerateTo` (Issue #1057).
	 *
	 * ## Speed law of the resumed leg (Issue #1087 decision)
	 *
	 * An **accelerating** resume does not use the braking law aimed at the signal. That law,
	 * `a = (T² − v²) / (2s)`, holds `(T² − v²) / s` constant along a leg, so it reaches its
	 * target only *at* the signal: a resumed train never ran at its cap anywhere in the block,
	 * and the watch armed after the cap could never run. Instead the leg runs up at
	 * [resumedRunUpRate] — the constant rate the same law gives when aimed at **half** the
	 * distance left at the resume instant, bounded by [MAXIMAL_TRAIN_ACCELERATION] — reaches
	 * the cap half-way and coasts at it, leaving the second half of the distance for the watch
	 * to brake in.
	 *
	 * - *Why this law.* A driver who sees the signal clear runs up to the permitted speed and
	 *   then holds it; spreading the run-up exactly to the signal is an artefact of the aim
	 *   point, not a train property. Aiming half-way keeps the model's geometry-driven law and
	 *   its bound (at most twice the old run-up rate) instead of introducing a traction
	 *   constant the model does not have — a fixed rate at the 4 m/s² bound is several times
	 *   what a real train manages. Aiming at the braking point of the cap was rejected:
	 *   the aspect allows, so there is no stand to brake for, and a leg that reaches its cap
	 *   exactly where the braking room runs out leaves the watch nothing to do.
	 * - *Why a fixed rate rather than the law re-aimed every step.* The law aimed at a moving
	 *   half-way point gives the same constant rate in exact arithmetic, but reaches `v = T`
	 *   and `s = 0` in the same step: `(T² − v²) / (2s)` degenerates to `0 / 0` there, and a
	 *   step that reaches `s ≤ 0` first takes [derivatives]' leg-ending branch, clearing
	 *   `accelerate` — the watch would then never arm, the very hole it closes. A rate fixed at
	 *   the resume instant has no singular point, and the leg ends on its plain
	 *   [AccelerationStopTest.ACCELERATION_ENDED] test, at most one 1 ms step late.
	 * - *What stays.* A **decelerating** resume (the cap below the current speed) keeps the
	 *   law aimed at the signal: the aspect's permitted speed applies at the signal, and
	 *   slowing over the whole distance left reaches it with no coasting state for a watch to
	 *   cover. In a block too short to reach the cap at the bound, [derivatives]' `s <= 0`
	 *   branch ends the leg at the signal exactly as before. Legs commanded by [accelerateTo],
	 *   both [onWarning] phases and the braking to the stop line are unchanged.
	 *
	 * @return `true` when the aspect turned restrictive again while this leg is still the engine's
	 *   command — the caller then brakes to the stop line
	 */
	private suspend fun resumeAtAspectCap(): Boolean {
		val aspect =
			requireSimulationNotNull(host.signalAheadAspect) {
				"Train ${host.trainNumber}: no signal aspect ahead when resuming"
			}
		val resumeSpeed = minOf(host.currentSpeedLimitMps, aspect.allowedSpeed())
		logger.trace { "Train ${host.trainNumber} engine: aspect cleared, resuming to $resumeSpeed" }
		targetSpeed = resumeSpeed
		// Same stop-test choice [accelerateTo] makes: a capped target below the current
		// velocity must decelerate to it, not complete immediately.
		val resuming =
			if (resumeSpeed > host.getVelocity()) {
				AccelerationStopCondition(AccelerationStopTest.ACCELERATION_ENDED, resumedRunUpRate(resumeSpeed))
			} else {
				AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED)
			}
		currentCondition = resuming
		waitUntil(Condition { resuming.test() || host.semaphoreToStopShortOf() != null })
		return !terminate && accelerate && host.semaphoreToStopShortOf() != null
	}

	/**
	 * Constant run-up rate of an accelerating [resumeAtAspectCap] leg: the braking law
	 * `(T² − v²) / (2s)` aimed at half the distance [brakingTargetDistance] has left now, that
	 * is `(T² − v²) / s`, bounded by [MAXIMAL_TRAIN_ACCELERATION]. See the speed-law section of
	 * [resumeAtAspectCap] for why.
	 *
	 * `null` — the law aimed at the signal — when no distance is left: [derivatives]' `s <= 0`
	 * branch then ends the leg, as it would for any other leg.
	 *
	 * @param target the resumed speed, above the current velocity
	 */
	private fun resumedRunUpRate(target: Double): Double? {
		val distance = brakingTargetDistance()
		if (distance <= 0.0) return null
		val speed = host.getVelocity()
		return minOf((target - speed) * (target + speed) / distance, MAXIMAL_TRAIN_ACCELERATION.toDouble())
	}

	/**
	 * Phase 2 of [onWarning]: brakes the train to a stand at the clearance stop line.
	 *
	 * The condition is published so [derivatives] sees a decelerating phase and applies the
	 * [MINIMAL_TRAIN_DECELERATION] bound to it; an inline condition left it clamping this phase as if it
	 * were still accelerating (Issue #1014).
	 *
	 * The wait also ends when the aspect clears before the stand. [derivatives] re-reads
	 * [Host.semaphoreToStopShortOf] every step and does move the aim point back to the signal, but
	 * `targetSpeed` would stay latched at zero and the train would still brake to a crawl in front
	 * of a signal showing proceed (PR #1033 review).
	 *
	 * @return `true` when the aspect cleared before the stand while this leg is still the engine's
	 *   command — the caller then resumes the run, the same recovery the front issues for a train
	 *   already stopped
	 */
	private suspend fun brakeToStopLine(): Boolean {
		targetSpeed = 0.0
		logger.trace { "Train ${host.trainNumber} engine: deceleration phase to stand, target $targetSpeed" }
		val braking = AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED)
		currentCondition = braking
		waitUntil(Condition { braking.test() || host.semaphoreToStopShortOf() == null })
		return !terminate && accelerate && host.semaphoreToStopShortOf() == null
	}

	private fun privateAccelerateTo(
		speed: Double,
		test: AccelerationStopTest
	) {
		requireSimulation(speed >= 0) { "Speed must be non-negative: $speed" }
		targetSpeed = speed
		currentCondition = AccelerationStopCondition(test)
		commandPending = true
		cancelAccelerating()
		Process.activate(this)
	}

	/**
	 * Change speed toward [speed], choosing acceleration or deceleration stop test from the
	 * current velocity.
	 *
	 * @param speed target speed in m/s (non-negative)
	 */
	fun accelerateTo(speed: Double) {
		logger.debug {
			"Train ${host.trainNumber} engine: accelerate to speed $speed, current velocity ${host.getVelocity()}"
		}
		host.reportDebug("in on warning")
		privateAccelerateTo(
			speed,
			if (speed > host.getVelocity()) {
				AccelerationStopTest.ACCELERATION_ENDED
			} else {
				AccelerationStopTest.DECELERATION_ENDED
			}
		)
	}

	/**
	 * Two-phase approach for a restrictive aspect: ramp toward half of [normalSpeed], then brake
	 * to a stand at the clearance stop line.
	 *
	 * @param normalSpeed permitted line speed used as the phase-1 target base
	 */
	fun onWarning(normalSpeed: Double) {
		logger.debug {
			"Train ${host.trainNumber} engine: warning mode, target speed $normalSpeed, current velocity ${host.getVelocity()}"
		}
		host.reportDebug("in on warning $normalSpeed")

		requireSimulation(host.getVelocity() >= 0) { "Velocity must be non-negative in onWarning" }
		privateAccelerateTo(normalSpeed, AccelerationStopTest.TO_HALF_SPEED)
	}

	/**
	 * Cancels the current acceleration phase if one is active.
	 */
	fun cancelAccelerating() {
		if (accelerate) {
			accelerate = false
			Process.activate(this)
		}
	}

	/**
	 * Reactivates rather than activates: kDisco absorbs a `Process.activate` aimed at a
	 * process parked in a crossing wait, so the phase-1 wait would survive teardown and its
	 * guard would keep being evaluated — on a train whose reservations are already released —
	 * for the rest of the run. `Process.reactivate` drops the pending notice and resumes now,
	 * and the resumed loop leaves through the existing `terminate` checks. Not a new activate
	 * call site (kdisco#73): the one that was here is replaced.
	 */
	override fun terminate() {
		terminate = true
		if (!terminated()) Process.reactivate(this)
	}

	override fun start(): Continuous = if (accelerate) super.start() else this

	/**
	 * Distance the braking law `a = (target² − v²) / (2s)` is aimed at.
	 *
	 * Normally [Host.distanceToSemaphore] — the point the train is authorised to run to. While
	 * the engine is bringing the train to a **stand** (`targetSpeed == 0`) in front of a
	 * **restrictive** signal, the target moves back by [Host.semaphoreStopClearanceMeters] so
	 * the existing law decelerates the train to that point instead of onto the separator
	 * (Issue #989). No new braking code: only the aiming point changes.
	 *
	 * Deliberately **not** applied while `targetSpeed > 0`. That is [onWarning]'s first
	 * phase, whose job is to run up to half the permitted speed before the braking phase
	 * begins; retargeting it would change that profile — and with it every timing baseline
	 * — for no benefit, since the phase never brakes.
	 *
	 * Clamped at zero. Once the front is inside the clearance (a signal that turned
	 * restrictive late) a negative `s` would flip the sign of the deceleration and make the
	 * train *accelerate* into the signal; zero instead takes [derivatives]' existing
	 * "stop accelerating" branch.
	 *
	 * [Host.distanceToSemaphore] itself is left alone on purpose: braking must keep measuring to
	 * the signal, while the port publishes distance-to-signal-ahead separately (Issue #1061).
	 *
	 * An accelerating [resumeAtAspectCap] leg is not driven by the law; it only reads this
	 * distance once, at the resume instant, to fix its run-up rate ([resumedRunUpRate]), and
	 * through [derivatives]' `s <= 0` branch, which still ends it at the signal.
	 */
	private fun brakingTargetDistance(): Double {
		val distance = host.distanceToSemaphore()
		if (targetSpeed > 0.0) return distance
		if (host.semaphoreToStopShortOf() == null) return distance
		return clearanceStopLineDistance()
	}

	/**
	 * Distance the front still has to run before the clearance stop line — the one place the
	 * engine decides where "a clearance short of the signal" is.
	 *
	 * [brakingTargetDistance] aims the braking law at it and [approachMargin] measures the
	 * braking room against it, so the guard that ends phase 1 and the target phase 2 brakes to
	 * are the same point by construction (Issue #1014; the invariant [Host.semaphoreToStopShortOf]
	 * documents).
	 *
	 * Clamped at zero: once the front is inside the clearance a negative distance would flip the
	 * sign of the deceleration and make the train accelerate into the signal.
	 *
	 * Distinct from the front's distance-to-clearance helper, which subtracts a further `dtMin` of
	 * slack because its gate must be seen to go non-positive by an asymptotic approach.
	 */
	private fun clearanceStopLineDistance(): Double =
		maxOf(0.0, host.distanceToSemaphore() - host.semaphoreStopClearanceMeters)

	/**
	 * The engine's acceleration: the braking law `a = (T² − v²) / (2s)` aimed at
	 * [brakingTargetDistance] and clamped to the deceleration or acceleration bound — or, for
	 * an accelerating [resumeAtAspectCap] leg, that leg's constant
	 * [AccelerationStopCondition.runUpRate]. Every leg ends once no distance is left.
	 */
	override fun derivatives() {
		// Clamp acceleration/deceleration to physical bounds.
		val s: Double = brakingTargetDistance()
		if (s <= 0) {
			accelerate = false
			return
		}
		if (host.velocityVariable.state <= 0) host.velocityVariable.state = 0.0

		val runUpRate = currentCondition?.runUpRate
		if (runUpRate != null) {
			host.accelerationVariable.state = runUpRate
			return
		}
		val a: Double =
			((targetSpeed - host.velocityVariable.state) * (targetSpeed + host.velocityVariable.state)) / (2 * s)
		host.accelerationVariable.state =
			if (requireNotNull(currentCondition) { "currentCondition must be set" }.getStopTest().isDecelerate()) {
				maxOf(a, MINIMAL_TRAIN_DECELERATION.toDouble())
			} else {
				minOf(a, MAXIMAL_TRAIN_ACCELERATION.toDouble())
			}
	}
}

/**
 * Stop-condition family for an [Engine] acceleration phase.
 *
 * @param decelerate `true` when the phase is a braking phase (target at or below velocity)
 */
internal enum class AccelerationStopTest(
	private val decelerate: Boolean
) {
	/** Phase ends when velocity reaches the (higher) target speed. */
	ACCELERATION_ENDED(false),

	/**
	 * Phase-1 of the restrictive approach: ends when velocity reaches half the target, or earlier
	 * via the engine's approach-margin wait.
	 */
	TO_HALF_SPEED(false) {
		override fun condition(
			targetSpeed: Double,
			velocity: Double
		): Boolean = targetSpeed <= 2 * velocity
	},

	/** Phase ends when velocity has fallen to the (lower) target speed. */
	DECELERATION_ENDED(true);

	/** Whether this stop test describes a decelerating phase. */
	fun isDecelerate(): Boolean = decelerate

	/**
	 * Default velocity-based completion test for the phase.
	 *
	 * @param targetSpeed commanded target
	 * @param velocity current train velocity
	 */
	open fun condition(
		targetSpeed: Double,
		velocity: Double
	): Boolean = if (isDecelerate()) targetSpeed >= velocity else targetSpeed <= velocity
}
