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
import cz.ksimulantenbande.kdisco.dtMin
import cz.ksimulantenbande.kdisco.maxAbsError
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Builds a stable, human-readable label for a track block for logging.
 *
 * Prefers the block's explicit `name` (from the XML configuration). When the block
 * has no name, derives a deterministic label from the names of its two end
 * separators (e.g. `kB-k1`), sorted so the same block always yields the same label
 * regardless of travel direction. Falls back to `"unknown"` only when no end name is
 * available.
 */
internal fun blockLabel(section: TrackSection): String {
	val block = section.getTrackBlock()
	val explicit = block.name
	if (!explicit.isNullOrBlank()) {
		return explicit
	}
	val endNames =
		runCatching { block.ends() }
			.getOrNull()
			?.mapNotNull { end ->
				(DynamicWrapperUtils.unwrapToStatic(end) as? NodeCell)
					?.getName()
					?.takeIf { it.isNotBlank() }
			}?.sorted()
			.orEmpty()
	return if (endNames.isNotEmpty()) endNames.joinToString("-") else "unknown"
}

/**
 * Train Process
 *
 */
class Train :
	Process,
	TrackOccupant {
	companion object {
		private val logger = KotlinLogging.logger {}
		private var countValue = 0

		private fun nextCount(): Int = ++countValue

		/**
		 * Maximum train acceleration in m/s²
		 */
		private const val MAXIMAL_ACCELERATION = 4

		/**
		 * Minimum train deceleration in m/s² (negative value for braking)
		 */
		private const val MINIMAL_DECELERATION = -3

		/**
		 * Distance in metres a train keeps between its front and the path separator of a
		 * semaphore showing a **non-allowing** aspect.
		 *
		 * ## Why a train must not stand on the separator
		 *
		 * The separator is the sensor point. A train that has already crossed it cannot be
		 * detected there any more, so its only way out is the `allowingSignal` condition —
		 * and any gap in that condition parks it for the rest of the run. Standing short of
		 * the separator keeps the front on the approach side of the sensor, and it is what
		 * real railway practice requires: a train comes to a stand *in front of* a signal at
		 * danger, not on it, and not inside the block that signal protects.
		 *
		 * ## Scope
		 *
		 * This is an **operating rule**, not a physical braking model — which is why it lives
		 * here and not in `domain/PhysicsConstants.kt` alongside geometry and vehicle
		 * capability. A real approach to a signal at danger is braked over hundreds of metres;
		 * a fixed metre is a marker for "not on the signal". A speed-scaled approach model is
		 * separate work (Issue #989, open question 1).
		 *
		 * The clearance applies **only** when the aspect ahead is non-allowing. An allowing
		 * aspect changes nothing about where the train runs or when it gets there.
		 *
		 * ## Clamp rule
		 *
		 * `SimpleTrack` enforces `length >= MIN_TRACK_LENGTH` (5.0 m), so on any legal network
		 * the stop point stays at least 4 m past the block's entry separator.
		 *
		 * The two consumers treat "the front is already past the stop line" differently, and
		 * deliberately so. [Motor.brakingTargetDistance] clamps its braking target at zero: a
		 * negative distance in `a = (target² − v²) / (2s)` flips the sign of the deceleration
		 * and would make the train *accelerate* into the signal. [Front.boundaryGuard] does
		 * **not** clamp — a negative guard is how it releases the gate at once so the train
		 * stops where it stands.
		 *
		 * ## Best effort, not a guarantee
		 *
		 * If the aspect turns restrictive while the front is *already inside* the last metre,
		 * the train stops where it is and never reverses to reach a stop line it has passed.
		 * If it is inside the last metre of the *section*, `semaphoreAction`'s STOP branch at
		 * the separator remains the backstop, exactly as before this rule existed. The
		 * clearance is a marker the train aims for, not a guarantee against a late aspect
		 * change.
		 *
		 * ## It is not the train's only escape
		 *
		 * Standing at the stop line is an unbounded wait on the aspect, so it is taken only
		 * when navigation genuinely serves the train past the signal and the aspect is the one
		 * thing in the way. Otherwise the clearance is waived for that traversal
		 * ([Front.waiveClearanceStop]) and the train runs to the separator, where the loop in
		 * `Site.actions()` applies its own **bounded** policies — see
		 * [MAX_MID_JOURNEY_NO_PATH_RETRIES] and [OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS].
		 *
		 * `internal` rather than `private` so tests can assert against the value itself,
		 * matching [MAX_ORIGIN_NO_PATH_RETRIES] and [OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS].
		 *
		 * @see Issue #989
		 */
		internal const val SEMAPHORE_STOP_CLEARANCE_METERS = 1.0

		/**
		 * Maximum number of 5-second retries when no topological path exists from the origin
		 * InOut before calling [SimulationEnvironment.errorStop].
		 *
		 * A train that cannot find any continuation from its entry point has a misconfigured
		 * network. After this many retries the simulation is stopped rather than looping
		 * silently forever.
		 *
		 * @see Issue #905
		 */
		internal const val MAX_ORIGIN_NO_PATH_RETRIES = 5

		/**
		 * Maximum number of 5-second retries when navigation reports no usable path **mid-journey**
		 * before calling [SimulationEnvironment.errorStop].
		 *
		 * The mid-journey counterpart of [MAX_ORIGIN_NO_PATH_RETRIES], which guarded only
		 * `where is DynamicInOut && current == null`. Everything else fell into an `else` branch with
		 * no counter, no `errorStop` and no state change between iterations, so a train that got here
		 * logged an error every 5 s until the run's end time while holding its block against every
		 * train behind it.
		 *
		 * **This bound is only sound because `NoTopologicalPath` now means what it says.**
		 * A train waiting for the dispatcher to extend its route is reported as
		 * [cz.vutbr.fit.interlockSim.context.navigation.PathResult.OwnershipConflict] and waits on
		 * `createPathAvailableCondition` instead of reaching this branch — before that change a run
		 * was measured recovering after 10 such retries, and this bound would have killed it.
		 * Reaching here now means navigation itself cannot serve this train, which no amount of
		 * waiting fixes. The `OwnershipConflict` wait carries its own bound for the case where the
		 * dispatcher never acts — see [OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS] and
		 * [OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS], which rest on the same argument.
		 *
		 * Deliberately more generous than the origin bound: a mid-journey train holds track, so the
		 * diagnosis is worth a little more patience than an unadmitted one.
		 */
		internal const val MAX_MID_JOURNEY_NO_PATH_RETRIES = 10

		/**
		 * Simulated seconds a train may wait on a
		 * [cz.vutbr.fit.interlockSim.context.navigation.PathResult.OwnershipConflict] before one
		 * WARN names the stall.
		 *
		 * The wait itself is event-driven and correct: the train resumes the instant the dispatcher
		 * reserves or extends its path. What was missing is a diagnostic for the case where the
		 * dispatcher **never** does — a route whose terminus is rear-facing, so the next hop the
		 * train needs is behind it. Such a train used to sit on the wait to the run's end time with
		 * no log line, no counter and no bound, holding its reserved block against every train
		 * behind it, indistinguishable in the logs and metrics from a healthy momentary wait.
		 *
		 * Healthy waits in `vyhybna.xml` last seconds, so a full minute without any extension is
		 * already abnormal.
		 *
		 * **Invariant:** this horizon must stay strictly below
		 * [OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS]. Reverse the two and the run is stopped before
		 * this WARN can ever be emitted, so the two-stage escalation collapses into a single hard
		 * stop with no diagnostic. `Issue943OwnershipConflictStallBoundTest` asserts the ordering.
		 *
		 * ## Relationship to `tickPeriodMs` and the dispatcher control period
		 *
		 * This horizon is measured in **simulated time** from the start of the current uninterrupted
		 * wait. It is safe only when the dispatcher fires frequently enough — at most a few simulated
		 * seconds between decisions — so that a healthy train waiting for its next extension never
		 * reaches the horizon.
		 *
		 * In production the driver is typically paced by `SnapshotSignal` (signalled once per control
		 * step), which avoids polling and ensures the snapshot boundary is tick-aligned.
		 *
		 * Note that `SnapshotSignal` is *coalescing* (at most one pending permit). If the driver is
		 * slower than the control-step cadence (e.g. long inference or large `tickPeriodMs`), multiple
		 * control steps can collapse into one driver cycle, increasing the simulated-time gap between
		 * decisions and making this horizon easier to reach.
		 *
		 * **The pathological case** is a free-running lifted stack without any driver↔sim barrier and
		 * with a large `tickPeriodMs`, where a headless sim can run much faster than wall-clock time.
		 * In that configuration the sim may advance far in simulated time between driver cycles and a
		 * healthy train waiting for its next extension can cross this horizon as a false positive.
		 * each control step, and the driver processes it when it wakes — even if that wake is
		 * deferred by `tickPeriodMs` milliseconds. As long as the driver is paced by
		 * `SnapshotSignal`, any `tickPeriodMs` value is safe for this horizon.
		 *
		 * **The pathological case** is a free-running lifted stack without `SnapshotSignal` and with
		 * a large `tickPeriodMs`, where a headless sim runs much faster than wall-clock time. In that
		 * configuration the sim could advance thousands of simulated seconds between driver cycles
		 * and a healthy train waiting for its next extension would cross this horizon as a false
		 * positive. The calibration gate
		 * `OwnershipConflictStallWarningTest.cleanBaselineRunLogsNoStallWarningLiftedStack`
		 * (Issue #946) pins the correct behaviour under the lifted stack with the lock-step
		 * handshake (one driver cycle per control step). Any aiSweep grid cell that uses a large
		 * `tickPeriodMs` without `SnapshotSignal`-paced dispatch must verify that this horizon still
		 * comfortably exceeds the resulting simulated-time gap between decisions, or adopt
		 * `SnapshotSignal` pacing.
		 *
		 * Issue #943 introduced the two-stage horizon; Issue #946 added the lifted-stack calibration
		 * gate and this documentation.
		 */
		internal const val OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS = 60.0

		/**
		 * Simulated seconds a train may wait on an
		 * [cz.vutbr.fit.interlockSim.context.navigation.PathResult.OwnershipConflict] before
		 * [SimulationEnvironment.errorStop] ends the run.
		 *
		 * **This bound is only sound because the wait is event-driven.** A train merely waiting for
		 * its route to be extended wakes on its own path-available event, so the only way to still
		 * be on this wait after the horizon is that the dispatcher genuinely produced no such event
		 * — no amount of further waiting fixes that. It is the same argument that justifies
		 * [MAX_MID_JOURNEY_NO_PATH_RETRIES], and it holds for the same reason: since PR #940 the
		 * transient "waiting for an extension" case is reported as `OwnershipConflict` rather than
		 * misreported as `NoTopologicalPath`.
		 *
		 * Deliberately three times the WARN horizon: the WARN is the diagnosis and must have room
		 * to be seen in a run that then recovers, while this bound only fires on a genuine
		 * never-extended train. **Invariant:** strictly greater than
		 * [OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS], for that reason — asserted by
		 * `Issue943OwnershipConflictStallBoundTest`.
		 *
		 * @see Issue #943
		 */
		internal const val OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS = 180.0

		/**
		 * Formats the structured TRAIN_APPROVED message payload.
		 * The format is consumed by [TextReporter]'s regex: `train="([^"]+)"`.
		 */
		internal fun formatApprovalMessage(
			trainName: String,
			inName: String,
			outName: String
		): String = """train="$trainName" route=$inName->$outName"""
	}

	// GitHub #62: Support bidirectional train operation (reverse direction)
	// Allow train engineer to move to opposite end and drive in reverse direction.
	// This is a simulation simplification of locomotive coupling/uncoupling operations.
	// Implementation: Either swap start/end positions OR cancel/restore events with train stationary.

	private abstract inner class Site : Process() { // lepsi nazev?

		/**
		 * Consecutive mid-journey `NoTopologicalPath` results, the counterpart of the origin-InOut
		 * retry counter. Owned by [holdOrStopAfterNoUsablePath] and reset by [actions] on every
		 * iteration that yields a usable path, so only *consecutive* failures count — a train that
		 * moves on has proved navigation works for it, and a later unrelated failure starts at zero.
		 */
		private var midJourneyNoPathRetries = 0

		/**
		 * Consecutive `NoTopologicalPath` results at the origin `InOut` (`current == null`), the
		 * origin counterpart of [midJourneyNoPathRetries]. Owned by [holdOrStopAtOriginWithoutPath].
		 * A [Site] runs [actions] once, so this holds exactly the run of one journey.
		 */
		private var originNoPathRetries = 0

		/**
		 * Simulated time at which the train's current, uninterrupted wait for a path reservation
		 * began, or `null` when it is not waiting. Owned by [waitForPathOrReportStall] and cleared
		 * by [resetOwnershipConflictWait] on every outcome that is not another ownership conflict,
		 * so only a *continuous* stall accumulates — the same rule as [midJourneyNoPathRetries].
		 */
		private var ownershipConflictWaitSince: Double? = null

		/** Whether the one-shot WARN for the current wait has already been emitted. */
		private var ownershipConflictWarned = false

		/**
		 * Waits before the next path query at the origin `InOut`, or stops the simulation when the
		 * network offers no topological continuation from it at all (Issue #905, AC2).
		 *
		 * No unbounded silent loop: after [MAX_ORIGIN_NO_PATH_RETRIES] retries the simulation stops
		 * through [SimulationEnvironment.errorStop], naming the misconfigured `InOut`.
		 *
		 * Extracted from [actions] to keep that method inside its length and complexity budgets.
		 *
		 * @return `true` when the caller must return (the simulation has been stopped).
		 */
		private suspend fun holdOrStopAtOriginWithoutPath(where: DynamicInOut): Boolean {
			// Navigation answered with a topology verdict, not an ownership conflict, so an earlier
			// wait no longer counts: the stall horizon measures *consecutive* conflicts.
			resetOwnershipConflictWait()
			originNoPathRetries++
			if (originNoPathRetries >= MAX_ORIGIN_NO_PATH_RETRIES) {
				env.errorStop(
					SimulationException(
						"Train $name: No topological path from origin InOut '${where.name}' " +
							"after $MAX_ORIGIN_NO_PATH_RETRIES retries. Network is misconfigured."
					)
				)
				return true
			}
			hold(5.0)
			return false
		}

		/**
		 * Clears the wait bookkeeping, so a later stall is measured from its own beginning.
		 */
		private fun resetOwnershipConflictWait() {
			ownershipConflictWaitSince = null
			ownershipConflictWarned = false
		}

		/**
		 * Suspends until the dispatcher makes this train's path available, and reports the stall
		 * when it never does.
		 *
		 * The wait stays event-driven (Issue #582): kDisco re-tests the condition after every
		 * discrete event and every integration step, so the train resumes the instant the
		 * reservation appears. The added term `time() >= deadline` gives the wait a horizon as
		 * well, which is what turns a never-extended train from a silent livelock into first a
		 * WARN and then a stopped run (Issue #943).
		 *
		 * Two horizons, measured from the start of the current uninterrupted wait:
		 * [OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS] emits one WARN naming the train and the
		 * separator, and [OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS] ends the run through
		 * [SimulationEnvironment.errorStop]. The messages distinguish a train still at its entry
		 * `InOut` (`current == null`, holds no track) from one under way (holds a block that every
		 * train behind it needs).
		 *
		 * @return `true` when the caller must return (the simulation has been stopped).
		 */
		private suspend fun waitForPathOrReportStall(where: DynamicPathSeparator): Boolean {
			val since = ownershipConflictWaitSince ?: time().also { ownershipConflictWaitSince = it }
			val available = env.createPathAvailableCondition(name, where)
			val horizon =
				if (ownershipConflictWarned) {
					OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS
				} else {
					OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS
				}
			val deadline = since + horizon
			waitUntil(Condition { available.test() || time() >= deadline })
			if (available.test()) {
				resetOwnershipConflictWait()
				return false
			}
			val waited = (time() - since).toInt() // truncates to whole seconds; the log reads "N s" intentionally
			val atOrigin = current == null
			if (!ownershipConflictWarned) {
				ownershipConflictWarned = true
				val errorHorizon = OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS.toInt()
				logger.warn {
					if (atOrigin) {
						"$name: still waiting at entry InOut '$where' for the dispatcher to reserve its " +
							"entry route after $waited s of simulated time (the run stops after $errorHorizon s)."
					} else {
						"$name: still waiting at '$where' for the dispatcher to extend its route after " +
							"$waited s of simulated time; it holds track it cannot leave " +
							"(the run stops after $errorHorizon s)."
					}
				}
				return false
			}
			env.errorStop(
				SimulationException(
					if (atOrigin) {
						"$name: no entry route reserved at InOut '$where' after $waited s of waiting."
					} else {
						"$name: no route extension at '$where' after $waited s of waiting. " +
							"The train holds track it cannot leave."
					}
				)
			)
			return true
		}

		/**
		 * Waits before the next path query, or stops the simulation when a mid-journey navigation
		 * failure has repeated [MAX_MID_JOURNEY_NO_PATH_RETRIES] times.
		 *
		 * Before this bound existed, a mid-journey [PathResult.NoTopologicalPath] fell into an
		 * unbounded `hold(5.0)` + `continue` with no counter, no `errorStop`, and nothing in the
		 * cycle that could change the outcome. A measured `shuntingLoopAI` run logged the same error
		 * 48 times — every 5 s to the end of the run — while the train held its block against every
		 * train behind it.
		 *
		 * See [MAX_MID_JOURNEY_NO_PATH_RETRIES] for why bounding this is only sound now that a train
		 * merely waiting for its route to be extended is reported as [PathResult.OwnershipConflict]
		 * and never reaches here.
		 *
		 * Extracted from [actions] to keep that method inside its length and complexity budgets.
		 *
		 * @return `true` when the caller must return (the simulation has been stopped).
		 */
		private suspend fun holdOrStopAfterNoUsablePath(
			pathResult: PathResult,
			where: DynamicPathSeparator
		): Boolean {
			// Navigation served this train with something other than an ownership conflict, so an
			// earlier wait no longer counts: the stall horizon measures *consecutive* conflicts.
			resetOwnershipConflictWait()
			if (pathResult is PathResult.NoTopologicalPath) {
				midJourneyNoPathRetries++
				if (midJourneyNoPathRetries >= MAX_MID_JOURNEY_NO_PATH_RETRIES) {
					env.errorStop(
						SimulationException(
							"Train $name: navigation reports no usable path from '$where' after " +
								"$MAX_MID_JOURNEY_NO_PATH_RETRIES retries. The train holds track it cannot leave."
						)
					)
					return true
				}
			}
			/**
			 * Polling Mechanism Trade-off (Issue #291, PR #358)
			 *
			 * Conservative 5-second retry so the train does not freeze silently. Also covers the
			 * non-`NoTopologicalPath` fall-through (e.g. a path that resolved but yielded no next
			 * section), which stays unbounded exactly as before.
			 */
			hold(5.0)
			return false
		}

		/**
		 * Whether this site is the train's [Front]. The shared [entrySeparator] field tracks the
		 * separator through which the **Front** entered its current section — it is read by the
		 * animation calculator ([TrainPositionCalculator]) to pick the interpolation direction for
		 * the front's section. The [Tail] trails the front by one train-length and is therefore in
		 * a different section; if it also wrote [entrySeparator], the field would point at an end
		 * of the tail's section (not the front's), flipping interpolation and making the rendered
		 * front snap backward at every block boundary. Only the Front writes it.
		 */
		protected abstract val isFront: Boolean

		private val position: Variable = Variable(0.0)
		private val pv: SimpleIntegration = SimpleIntegration(position, velocity)
		private var totalLengthOfPreviousBlocks: Double = 0.0
		private var next: TrackSection? = null
		private var current: TrackSection? = null
		private var onNext: Boolean = false

		val terminated: Condition = Condition { terminated() }

		final override suspend fun actions() {
			var where: DynamicPathSeparator = timetable.getIn()
			requireSimulationNotNull(where) { "PathSeparator from timetable.getIn() must not be null" }
			// out se muze rovnat in => bude vyreseno "prepojenim lokomotivy"

			// Initialize entry separator for animation (train enters network here).
			// Only the Front writes it — see [isFront].
			if (isFront) this@Train.entrySeparator = where

			while (true) {
				// Check if we've reached the destination InOut BEFORE querying for path
				if (where is DynamicInOut && current != null) {
					// We're at an InOut and we've already traveled through at least one block
					// This is our destination - exit the loop
					break
				}

				/**
				 * PathResult Pattern Matching (Issue #291, PR #358)
				 *
				 * This pattern matching logic distinguishes between permanent and temporary path failures:
				 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
				 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
				 *
				 * Rationale for sim/ package modification:
				 * - Type safety: Sealed class prevents null-pointer errors
				 * - Semantic clarity: Explicit distinction aids debugging and logging
				 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
				 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
				 *
				 * Conservative approach compliance:
				 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
				 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
				 * - ✅ Physics validation (no regression in motor behavior)
				 * - ✅ Backward compatible (sealed class replaces nullable Path)
				 *
				 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
				 * @see docs/TRAIN_PASSIVATION_FIX.md
				 */
				val pathResult = trainNavService.findReservedPathForTrain(name, where)
				val path =
					when (pathResult) {
						is PathResult.Available -> pathResult.path
						is PathResult.NoTopologicalPath -> {
							// Permanent condition - no path exists in network topology.
							// Note: the destination case (where is DynamicInOut && current != null)
							// is already handled at the top of the loop and never reaches here.
							if (where is DynamicInOut && current == null) {
								// At origin InOut with no topological continuation.
								// This is a configuration error; do NOT break — fall through to
								// the bounded-retry handler below (Issue #905, AC2).
								// 1-based attempt counter; the final attempt (== MAX) immediately
								// triggers env.errorStop in the handler below, so word it as the
								// terminal attempt rather than another retry (Issue #905, AC2).
								// Capture the InOut name as a val so the warn lambda does not have to
								// smart-cast `where` (a var mutated later in the loop) to DynamicInOut.
								val originName = where.name
								logger.warn {
									"Train $number: No topological path from origin InOut '$originName' " +
										"(attempt ${originNoPathRetries + 1}/$MAX_ORIGIN_NO_PATH_RETRIES; " +
										"simulation stops after $MAX_ORIGIN_NO_PATH_RETRIES attempts). " +
										"Network may be misconfigured."
								}
							} else {
								// Not at destination, this is an error.
								// Deliberately does NOT claim a dead end: this branch is reached from
								// DefaultTrainNavigationService's genuine topology check, and a train
								// merely waiting for its route to be extended is now reported as
								// OwnershipConflict instead of landing here (see that class for the
								// measured case this wording sent down a false dead-end hypothesis).
								logger.error {
									"Train $number: navigation reports no usable path from $where " +
										"(attempt ${midJourneyNoPathRetries + 1}/$MAX_MID_JOURNEY_NO_PATH_RETRIES; " +
										"simulation stops after $MAX_MID_JOURNEY_NO_PATH_RETRIES attempts)."
								}
							}
							null
						}
						is PathResult.OwnershipConflict -> {
							// Temporary condition - blocks reserved for different train, or the
							// reserved path does not yet reach a forward-facing separator (PR #940:
							// a train waiting for its route to be extended is reported here too).
							logger.debug {
								"Train $number: Path blocked by ownership conflict at $where, " +
									"halting and waiting for dispatcher to extend the route"
							}
							null
						}
					}
				next = path?.getNext(current)

				if (path == null || next == null) {
					// Destination (where is DynamicInOut && current != null) is handled at the
					// top of this loop before the path query; it never falls through to here.
					// At the origin (current == null) we must NOT break — the train waits for
					// the dispatcher to reserve a path (Issue #905, AC1).
					// Stop motor completely to prevent creeping motion during passivation
					motor.cancelAccelerating()
					this@Train.stop()

					// One exit for every bounded branch: each arm answers "has the simulation been
					// stopped?", so the branches stay inside `actions()`'s complexity budget.
					val stopped =
						when {
							pathResult is PathResult.OwnershipConflict -> {
								/**
								 * Event-Driven Wait (Issue #582, Goal 1 SP3)
								 *
								 * Instead of polling with a fixed 5-second hold, suspend until the
								 * dispatcher reserves the path. kDisco re-evaluates the condition
								 * after every discrete event (including block releases), so the train
								 * resumes exactly when the path becomes available.
								 *
								 * The motor has already been stopped, so there is no creeping risk.
								 *
								 * This branch is now also reached at the origin (Issue #905, AC1):
								 * an OwnershipConflict at the entry InOut resolves the instant the
								 * dispatcher makes the reservation.
								 */
								logger.debug {
									"Train $number: event-driven wait for path reservation at $where"
								}
								waitForPathOrReportStall(where)
							}
							pathResult is PathResult.NoTopologicalPath && where is DynamicInOut && current == null ->
								holdOrStopAtOriginWithoutPath(where)
							else -> holdOrStopAfterNoUsablePath(pathResult, where)
						}
					if (stopped) return
					continue // Restart loop to retry path request
				}
				// The train has a usable path: this iteration is a success, so consecutive-failure
				// counting starts over (see MAX_MID_JOURNEY_NO_PATH_RETRIES) and the stall horizon
				// of any earlier wait starts over too (see OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS).
				midJourneyNoPathRetries = 0
				resetOwnershipConflictWait()
				val nextLength: Double = next!!.length()
				separatorAction(where, current, next)

				onNext = true
				// kDisco 0.6.0 renamed Variable.isActive()/Continuous.isActive() (which returned
				// `_pred != null` = "in the active integration list") to isStarted(). The Process
				// base class now owns isActive() with a different meaning (process lifecycle
				// RUNNING/SCHEDULED), so isStarted() is the faithful equivalent here — it asserts
				// the position Variable and the pv SimpleIntegration (Continuous) are both in
				// their respective active lists and thus being integrated before we advance.
				requireSimulation(position.isStarted() && pv.isStarted()) {
					"Position and velocity integration must be active"
				}
				// Resume when the front reaches the section boundary. This is a LEVEL condition
				// ("the front has reached the boundary"), not an event, so it needs a
				// level-triggered wait — but Issue #750 also wants the crossing time located by
				// root-finding within one integration step so `dtMax` need not be tiny.
				// `waitUntilCrossing` (kDisco, bedaHovorka/kdisco#71/#72) is exactly that: it
				// resumes as soon as `guard() <= 0`, re-tested after every discrete event and every
				// accepted step (so an asymptotic approach is never missed), and locates a
				// within-step crossing by bisection.
				//
				// This replaces two earlier forms. Issue #750 step 1 used the edge-triggered
				// `waitCrossing`, which permanently parked a train that decelerates to a stop at a
				// semaphore located *at* the boundary: the braking law `a = -v² / (2s)`
				// (`Motor.derivatives`) drives `v → 0` as `s → 0`, so the front halts a few
				// nanometres short, the guard's sign change is missed, and — because
				// `separatorAction`/`semaphoreAction`/the path re-query all sit downstream of this
				// gate — the train goes silent for the rest of the run (the #797 deadlock: Train
				// #16 stood at `zA` showing S80, on a reserved route, from t=676 to the end). The
				// interim #797 fix used a plain `waitUntil`, which is safe but forfeits the
				// root-finding precision. `waitUntilCrossing` keeps both. The `dtMin` slack still
				// makes the threshold reachable for the asymptotic approach.
				//
				// Issue #989 splits this single gate into three statements without changing
				// what any of them does when the signal ahead is allowing. [boundaryGuard] is
				// the expression above verbatim for the [Tail] and for a [Front] with nothing
				// restrictive in front of it, [holdShortOfRestrictiveSignal] is then a no-op,
				// and the third statement is `waitUntilCrossing`'s documented
				// "already satisfied at registration → returns immediately" case. So on an
				// allowing aspect the release point, and with it the RKF45 step sequence, is
				// unchanged. Only a [Front] approaching a non-allowing aspect sees a different
				// first guard — it stops a clearance short — and only that case reaches the
				// hold. See [SEMAPHORE_STOP_CLEARANCE_METERS].
				waitUntilCrossing { boundaryGuard(nextLength) }
				holdShortOfRestrictiveSignal(nextLength)
				waitUntilCrossing { (nextLength - dtMin) - position.state }

				position.state -= nextLength
				totalLengthOfPreviousBlocks += nextLength
				val staticWhere = next!!.getSecondEnd(where)
				requireSimulationNotNull(staticWhere) { "PathSeparator from getSecondEnd() must not be null" }
				where = env.toDynamic(staticWhere)

				// Store entry separator for animation position calculation.
				// where = separator we just crossed (entry to next section).
				// Only the Front writes it — the Tail is in a different section and would
				// corrupt the field for the front-based interpolation. See [isFront].
				if (isFront) this@Train.entrySeparator = where

				// Advance frontSection atomically with entrySeparator so the animation
				// sees a consistent (frontSection, entry) pair. Previously `current = next`
				// + `onNext = false` left frontSection pointing at the just-traversed
				// section while entrySeparator already pointed at its exit (= entry of the
				// upcoming section) — a one-section lag. Sampled at switch crossings (where
				// path reservation suspends in waitUntil above), the heading calculator
				// treated the exit end as the entry end and flipped the nose 180° (PR #718).
				//
				// The `path` found at the top of this iteration was queried from the entry of
				// the section just traversed, so it is partial and `path.getNext(current)`
				// is null here. Re-query from the new `where` (the separator just crossed =
				// entry of the upcoming section) — the same query the next iteration makes
				// at the top of the loop — and take the section after `current` as the
				// upcoming one. Keeping onNext=true makes getSection() report the section
				// being entered, with entrySeparator as its entry end. `next` is recomputed
				// at the top of the next iteration anyway, so this only affects the visible
				// state during the gap. When the upcoming section is null the front has
				// reached the destination InOut (or the reserved path ends here); onNext=false
				// lets the loop's destination check / passivation handle the next iteration.
				current = next
				val upcoming: TrackSection? =
					if (current != null) {
						val nextPathResult = trainNavService.findReservedPathForTrain(name, where)
						if (nextPathResult is PathResult.Available) nextPathResult.path.getNext(current) else null
					} else {
						null
					}
				next = upcoming
				onNext = upcoming != null
			}

			stop()
			separatorAction(where, current, null)
		}

		/**
		 * Action at aPath separator
		 * @param where
		 * @param current
		 * @param next
		 */
		abstract suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		)

		// Not an override: kdisco-engine Process has no start()/stop() — intentional design.
		open fun start(): Site {
			position.start()
			pv.start()
			return this
		}

		fun stop() {
			position.stop()
			pv.stop()
		}

		/**
		 * Called by [Tail] when the train first enters the network from its home [InOut].
		 * Corrects the initial position to account for the [Front]'s integration overshoot past
		 * the train-length threshold, ensuring [LengthChecker] invariant (front − tail = length)
		 * holds from the moment the tail enters.
		 *
		 * Without this correction, RKF45 may overshoot `front.getTotalDistance() >= length` by
		 * several metres (one integration step ≈ dtMax × velocity), leaving tail at 0 while
		 * front is already ahead by the overshoot amount, which violates `abs(front−tail−length) ≤ maxAbsError`.
		 */
		protected fun initPositionFromFrontOffset(
			frontTotalDistance: Double,
			trainLength: Double
		) {
			val offset = frontTotalDistance - trainLength
			if (offset > 0.0) position.state = offset
		}

		/**
		 * @return distance
		 */
		fun distanceToPathSeparator(): Double = if (next == null) 0.0 else next!!.length() - position.state

		/**
		 * Ground this site still has to cover in the section it is traversing, with the `dtMin`
		 * slack that makes the threshold reachable for an asymptotic approach (Issue #797).
		 *
		 * This is the historical block-boundary gate expression, extracted so [Front] can both
		 * take a minimum against it and ask afterwards whether the section end was what released
		 * the gate.
		 *
		 * @param nextLength length of the section being traversed
		 */
		protected fun distanceToSectionEnd(nextLength: Double): Double = (nextLength - dtMin) - position.state

		/**
		 * Guard for the block-boundary gate in [actions]: positive while this site may keep
		 * running, non-positive once it must stop.
		 *
		 * The base is [distanceToSectionEnd], the historical gate. [Front] overrides it to also
		 * stop a clearance short of a restrictive signal (Issue #989); the [Tail] uses this one.
		 */
		protected open fun boundaryGuard(nextLength: Double): Double = distanceToSectionEnd(nextLength)

		/**
		 * Called by [actions] the moment [boundaryGuard] releases, before the historical boundary
		 * gate runs.
		 *
		 * A no-op for the [Tail], which has no signal to observe. [Front] overrides it to bring
		 * the train to a stand short of a restrictive signal and hold it there until the aspect
		 * clears (Issue #989).
		 *
		 * Declared `suspend` for that override, which waits on the signal aspect; the base body
		 * has nothing to suspend on.
		 */
		@Suppress("RedundantSuspendModifier")
		protected open suspend fun holdShortOfRestrictiveSignal(nextLength: Double) {
			// EMPTY — only the Front reacts to signals.
		}

		/**
		 * @return getter
		 */
		fun getPosition(): Double = position.state

		/**
		 * @return "to co cast vlaku urazila uvnitr modelu"
		 */
		fun getTotalDistance(): Double = totalLengthOfPreviousBlocks + position.state

		protected fun getSection(): TrackSection? = if (onNext) next else current

		internal fun getFrontSection(): TrackSection? = getSection()

		/**
		 * The section the front has fully traversed and now stands at the far end of, or `null`
		 * whenever the front is inside or entering a section normally.
		 *
		 * Issue #788: [actions] rebases [position] to ~0 at the separator the front has just
		 * crossed and only then advances [current]/[next]. When there is no section to enter —
		 * the route beyond the separator is not reserved yet (the signal ahead shows STOP), or
		 * the separator is the destination InOut — [onNext] goes false and [getSection] keeps
		 * reporting the section that was just traversed. The raw pair (traversed section,
		 * separator just crossed) then reads as "entering that section through its exit end",
		 * which reverses the heading derived from it by exactly 180°.
		 *
		 * This helper identifies that state so the published front state can describe the same
		 * physical point the other way round — still on the traversed section, entered through
		 * that section's own entry end, standing at its far end. Advancing the pair to the next
		 * section is not an option here precisely because there is no next section yet.
		 */
		private fun traversedSectionAtExit(): TrackSection? = if (onNext) null else current

		/**
		 * The end of [getFrontSection] through which the front entered it: the separator most
		 * recently crossed, except at the boundary identified by [traversedSectionAtExit], where
		 * that separator is the section's *exit* end and the opposite end is published instead.
		 *
		 * The separator most recently crossed is always an end of [current], so the
		 * [cz.vutbr.fit.interlockSim.objects.core.StaticTrack.getSecondEnd] lookup below always
		 * resolves. Only meaningful for the [Front] — the [Tail] never writes [entrySeparator].
		 */
		internal fun publishedEntrySeparator(): DynamicPathSeparator? {
			val crossed = this@Train.entrySeparator ?: return null
			val traversed = traversedSectionAtExit() ?: return crossed
			return env.toDynamic(traversed.getSecondEnd(crossed))
		}

		/**
		 * Distance along [getFrontSection] to publish, kept consistent with
		 * [publishedEntrySeparator]: the length of the traversed section at the boundary (the
		 * front stands at its far end), the integrated position otherwise.
		 *
		 * Publishing both together is what keeps the rendered position unchanged. At the
		 * boundary the raw pair interpolates from the exit end at ratio ~0 and the published
		 * pair interpolates from the entry end at ratio 1 — the same point, reached from the
		 * other side, now with the travel direction the right way round.
		 */
		internal fun publishedPosition(): Double = traversedSectionAtExit()?.length() ?: position.state

		internal fun getTailSection(): TrackSection? = getSection()
	}

	private inner class Front : Site() {
		override val isFront: Boolean = true

		private suspend fun semaphoreAction(
			semaphore: DynamicRailSemaphore,
			separator: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			// isSeparatorInDirection accepts nullable Track parameters
			requireSimulation(env.isSeparatorInDirection(separator as OrientedPathSeparator, next, current)) {
				"Separator must be in direction, semaphore: $semaphore"
			}
			requireSimulationNotNull(semaphore.signal) { "Semaphore signal must not be null" }
			logger.info {
				"${Process.time()} SENSOR: Train $number detected at semaphore " +
					"${semaphore.name}, " +
					"signal=${semaphore.signal}, velocity=${getVelocity()} m/s"
			}

			/**
			 * PathResult Pattern Matching (Issue #291, PR #358)
			 *
			 * This pattern matching logic distinguishes between permanent and temporary path failures:
			 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
			 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
			 *
			 * Rationale for sim/ package modification:
			 * - Type safety: Sealed class prevents null-pointer errors
			 * - Semantic clarity: Explicit distinction aids debugging and logging
			 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
			 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
			 *
			 * Conservative approach compliance:
			 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
			 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
			 * - ✅ Physics validation (no regression in motor behavior)
			 * - ✅ Backward compatible (sealed class replaces nullable Path)
			 *
			 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
			 * @see docs/TRAIN_PASSIVATION_FIX.md
			 */
			val pathResult = trainNavService.findReservedPathForTrain(name, separator)
			val path: Path? =
				when (pathResult) {
					is PathResult.Available -> pathResult.path
					is PathResult.NoTopologicalPath -> {
						logger.error {
							"Train $number at semaphore ${semaphore.name}: No topological path exists. " +
								"Network may be misconfigured."
						}
						null
					}
					is PathResult.OwnershipConflict -> {
						logger.debug {
							"Train $number at semaphore ${semaphore.name}: Path blocked by ownership conflict"
						}
						null
					}
				}

			// GOAL 15: Station stops for tutorial scenarios - see LONG_TERM_GOALS.md

			if (semaphore.signal == Signal.STOP) {
				requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative when approaching semaphore" }
				logger.debug { "Train $number approaching semaphore with STOP signal, halting" }
				fireStop()
				env.report(semaphore.signal.toString(), this@Train, ReportType.TRAIN_EVENTS)

				// freePath(separator, next); //vlak si sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
				waitUntil(allowingSignal(semaphore, separator))
				logger.debug { "Train $number received allowing signal from semaphore, resuming movement" }
				env.report("OK " + semaphore.signal, this@Train, ReportType.TRAIN_EVENTS)

				/**
				 * PathResult Pattern Matching (Issue #291, PR #358)
				 *
				 * This pattern matching logic distinguishes between permanent and temporary path failures:
				 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
				 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
				 *
				 * Rationale for sim/ package modification:
				 * - Type safety: Sealed class prevents null-pointer errors
				 * - Semantic clarity: Explicit distinction aids debugging and logging
				 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
				 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
				 *
				 * Conservative approach compliance:
				 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
				 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
				 * - ✅ Physics validation (no regression in motor behavior)
				 * - ✅ Backward compatible (sealed class replaces nullable Path)
				 *
				 * Note: Re-fetch path after signal becomes allowing.
				 * The signal should only become allowing when a path is reserved.
				 *
				 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
				 * @see docs/TRAIN_PASSIVATION_FIX.md
				 */
				val resumeResult = trainNavService.findReservedPathForTrain(name, separator)
				val resumePath: Path? =
					when (resumeResult) {
						is PathResult.Available -> resumeResult.path
						is PathResult.NoTopologicalPath -> {
							logger.error {
								"Train $number at semaphore ${semaphore.name}: Signal is allowing but no topological path exists. " +
									"This indicates a logic error - signal should only allow when path exists."
							}
							null
						}
						is PathResult.OwnershipConflict -> {
							logger.error {
								"Train $number at semaphore ${semaphore.name}: Signal is allowing but path not reserved for this train. " +
									"This indicates a logic error - signal should only allow when path is reserved."
							}
							null
						}
					}
				requireSimulationNotNull(resumePath) {
					"Train $number at semaphore ${semaphore.name}: Signal is allowing but no reserved path found. " +
						"This indicates a logic error - signal should only allow when path is reserved."
				}
				fireStart(semaphore, resumePath)
			} else if (semaphore.signal.isAllowing() && velocity.state <= maxAbsError) {
				logger.debug { "Train $number starting movement with allowing signal" }
				// Validate path exists before starting
				requireSimulationNotNull(path) {
					"Train $number at semaphore ${semaphore.name}: Signal is allowing but no reserved path found. " +
						"This indicates a logic error - signal should only allow when path is reserved."
				}
				fireStart(semaphore, path)
			} else {
				// Already moving, accelerate toward next semaphore
				logger.debug { "Train $number accelerating toward next semaphore" }
				// Validate path exists before accelerating
				requireSimulationNotNull(path) {
					"Train $number at semaphore ${semaphore.name}: Cannot accelerate without reserved path."
				}
				accelerateToSignal(semaphore, path)
			}
			hold(1.0)
			semaphore.signal = Signal.STOP
		}

		// pro ucely ladeni - moznost ze si vlak sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
// 		private Unit freePath(final PathSeparator separator, final TrackSection next) {
// 			if (separator instanceof InOut) return;
// 			try {
// 				env.pathToNextSemaphore(separator, next).setUpPath(separator);
// 			} catch (TrackOperationException e1) {
// 				env.errorStop(e1);
// 				e1.printStackTrace();
// 			}
// 			Process.activate(Process() {
//
// 				override //				protected Unit actions() {
// 					waitUntil(Condition() {
// 						Path aPath;
//
// 						Boolean test() {
// 							aPath = env.pathToNextSemaphore(separator, next);
// 							try {
// 								final Boolean b = aPath != null && aPath.isFreeFrom(separator);
// 								if (b == true) aPath.setUpPath(separator);
// 								return b;
// 							} catch (TrackOperationException e) {
// 								env.errorStop(e);
// 								return false;
// 							}
// 						}
//
// 					});
//
// 				}
//
// 			});
// 		}

		private fun allowingSignal(
			semaphore: DynamicRailSemaphore,
			separator: DynamicPathSeparator
		): Condition =
			Condition {
				semaphore.signal.isAllowing() &&
					trainNavService.findReservedPathForTrain(name, separator) is PathResult.Available
			}

		/**
		 * The historical boundary guard, additionally capped at the clearance stop line while a
		 * restrictive signal stands at the end of the reserved leg (Issue #989).
		 *
		 * The `minOf` is what keeps the train inside its own section. [pathToSemaphore] runs to
		 * the next *oriented* separator, which can be several sections away when switches sit
		 * between them (a switch takes the `else` branch of [separatorAction] and never ends a
		 * leg) — `vyhybna.xml` is exactly such a network. Taking the clearance term alone would
		 * then leave the guard positive across a whole block boundary and [separatorAction]
		 * would never run for it. Taking the minimum means the clearance term binds only when
		 * the restrictive signal really is at the end of the section being traversed.
		 *
		 * The aspect is read live, so it is re-sampled after every discrete event and every
		 * accepted integration step: an aspect that turns restrictive during the approach is
		 * picked up here for free, and one that clears releases the cap again.
		 */
		override fun boundaryGuard(nextLength: Double): Double {
			val boundary = distanceToSectionEnd(nextLength)
			if (semaphoreToStopShortOf() == null) return boundary
			return minOf(boundary, distanceToClearanceStopLine())
		}

		/**
		 * Distance the front still has to run before its clearance stop line, less the same
		 * `dtMin` slack the boundary gate uses — for the same reason: the braking law is
		 * asymptotic in the stop point, so a guard aimed exactly at it may never be seen to go
		 * non-positive. The stand therefore settles at `SEMAPHORE_STOP_CLEARANCE_METERS + dtMin`,
		 * which is inside "one metre or longer".
		 *
		 * Negative when the front is already inside the clearance — a signal that turned
		 * restrictive late. That releases the gate at once, so the train stops where it is; it
		 * never reverses to reach a stop line it has passed.
		 */
		private fun distanceToClearanceStopLine(): Double = distanceToSemaphore() - SEMAPHORE_STOP_CLEARANCE_METERS - dtMin

		/**
		 * Brings the train to a stand short of a restrictive signal and holds it there until the
		 * aspect clears (Issue #989).
		 *
		 * Called on every release of the gate and stands down immediately unless it was
		 * [boundaryGuard]'s clearance term that bound — that is, unless the front is a clearance
		 * short of the separator and, because [Motor] aims at the same point, already braked to
		 * walking pace. The wait is the same level-triggered form [semaphoreAction] uses at the
		 * separator; the train resumes the instant the aspect clears, and the front is still on
		 * the *approach* side of the sensor point while it waits, which is the whole point of
		 * the clearance.
		 */
		override suspend fun holdShortOfRestrictiveSignal(nextLength: Double) {
			// Stand down once the section end itself has been reached. That release belongs to
			// the historical path: separatorAction/semaphoreAction run next and own every signal
			// decision at a separator, including the restart. Acting here as well would issue a
			// second motor command in the same instant, and kDisco's Process.activate queues a
			// *duplicate* event rather than coalescing — which resumes the motor coroutine twice
			// and leaves it wedged with its acceleration off. A train then coasts at whatever
			// crawl it arrived with and never accelerates again (measured on this branch: a
			// train crossing at 1.6 mm/s and holding that speed to the end of the run).
			//
			// Compared against `dtMin`, not zero: `waitUntilCrossing`'s root-finder may
			// terminate up to its tolerance on the positive side of the boundary, so the exact
			// arrival reads as a tiny positive remainder. `dtMin` is the gate's own structural
			// slack and is three orders of magnitude above that tolerance, while the clearance
			// release it must stay distinguishable from is a whole metre away.
			if (distanceToSectionEnd(nextLength) <= dtMin) return
			val semaphore = semaphoreToStopShortOf()
			if (semaphore == null) {
				// The aspect cleared while the train was braking towards the stop line. If it
				// had already come to a stand there, nothing else will ever move it again: the
				// gate below is level-triggered on a position that no longer changes and the
				// motor was told to stop at a point the train has now reached. Restart it.
				if (getVelocity() <= maxAbsError) fireResume()
				return
			}
			// **The hold must never be this train's only escape.** Standing at the stop line is
			// an unbounded wait on the aspect, and the loop in `actions()` owns two *bounded*
			// policies for a train that navigation cannot serve — the mid-journey retry count
			// ([MAX_MID_JOURNEY_NO_PATH_RETRIES]) and the ownership-conflict horizon
			// ([OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS]) — both of which it can only apply
			// once the front has reached the separator and re-queried. Holding here in that
			// state would silently displace a bounded diagnosis with an unbounded wait: a train
			// stranded on a misconfigured network would sit at the stop line to the end of the
			// run instead of stopping it (measured on this branch by
			// `MidJourneyNoPathErrorStopTest`, whose `errorStop` stopped firing).
			//
			// So the clearance applies only when the interlocking is holding a train it would
			// otherwise let through: navigation serves it past this separator and the aspect is
			// the one thing in the way. Otherwise waive the clearance for the rest of this
			// traversal and run the last metres exactly as before this rule existed.
			if (trainNavService.findReservedPathForTrain(name, semaphore) !is PathResult.Available) {
				waiveClearanceStop(semaphore)
				return
			}
			requireSimulation(getVelocity() >= 0) {
				"Velocity must be non-negative when stopping short of a semaphore"
			}
			logger.debug {
				"Train $number stopping ${SEMAPHORE_STOP_CLEARANCE_METERS}m short of semaphore " +
					"${semaphore.name} showing ${semaphore.signal}"
			}
			fireStop()
			env.report(semaphore.signal.toString(), this@Train, ReportType.TRAIN_EVENTS)
			waitUntil { semaphoreToStopShortOf() == null }
			logger.debug { "Train $number resuming: semaphore ${semaphore.name} now ${semaphore.signal}" }
			env.report("OK " + semaphore.signal, this@Train, ReportType.TRAIN_EVENTS)
			fireResume()
		}

		/**
		 * Gives up the clearance stop for the rest of this section traversal and re-commands the
		 * motor at the separator itself.
		 *
		 * Both are needed. Clearing the flag alone would leave the motor's braking target — and
		 * with it the train — parked at a stop line it has already reached, crawling the last
		 * metre at the millimetres per second the braking law leaves there. The command is the
		 * same [Motor.onWarning] two-phase approach [accelerateToSignal] issues for a
		 * non-allowing signal ahead, so the last metres are run exactly as the historical code
		 * ran them.
		 */
		private fun waiveClearanceStop(semaphore: DynamicRailSemaphore) {
			logger.debug {
				"Train $number: navigation does not serve it past semaphore ${semaphore.name}; " +
					"waiving the stop-short clearance so the route query at the separator can diagnose it"
			}
			clearanceStopWaived = true
			motor.onWarning(currentSpeedLimitMps)
		}

		private fun fireStop() {
			requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative when stopping" }
			front.stop()
			tail.stop()
			this@Train.stop()
			velocity.state = 0.0
			motor.cancelAccelerating()
		}

		/**
		 * Restarts the train from the clearance stop line, mid-section.
		 *
		 * The counterpart of [fireStart] for a stop that did **not** happen at a separator:
		 * [accelerateToSignal] cannot be reused, because it re-commands the motor from the
		 * semaphore the train *departed* — which is back at STOP by then (`semaphoreAction`
		 * sets it after its `hold(1.0)`). What is known here instead is the aspect ahead, which
		 * has just become allowing, and the leg's physical speed limit. Both sites and the train
		 * are restarted together, exactly as [fireStart] does, so [LengthChecker]'s
		 * front − tail = length invariant is preserved.
		 *
		 * The command only has to carry the train over the last clearance metres; on reaching
		 * the separator [semaphoreAction] re-commands the motor for the next leg as usual.
		 */
		private fun fireResume() {
			val aspect =
				requireSimulationNotNull(separatorAspect(nextSemaphore())) {
					"Train $number: no signal aspect ahead when resuming from the clearance stop line"
				}
			requireSimulation(aspect.isAllowing()) {
				"Train $number: signal ahead must be allowing when resuming from the clearance stop line, was $aspect"
			}
			motor.accelerateTo(minOf(currentSpeedLimitMps, aspect.allowedSpeed()))
			this@Train.start()
			front.start()
			tail.start()
		}

		private fun fireStart(
			semaphore: DynamicRailSemaphore,
			path: Path?
		) {
			accelerateToSignal(semaphore, path)
			this@Train.start()
			front.start()
			tail.start()
		}

		@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
		private fun accelerateToSignal(
			semaphore: DynamicRailSemaphore,
			path: Path?
		) {
			requireSimulationNotNull(path) { "Path must not be null in accelerate method" }
			val thisSignal: Signal = semaphore.signal
			requireSimulation(thisSignal.isAllowing()) { "Signal must be allowing: $thisSignal" }
			@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			val lastSeparator = path.getLast()
			val nextSemaphore: DynamicRailSemaphore =
				when (lastSeparator) {
					is DynamicRailSemaphore -> lastSeparator
					is DynamicInOut -> lastSeparator.outSemaphore
					else -> error("Last path separator must be DynamicRailSemaphore or DynamicInOut")
				}
			val nextSignal: Signal = nextSemaphore.signal
			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			pathToSemaphore = path

			@Suppress(
				"RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS",
				"NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS"
			)
			val min: Double =
				minOf(
					path.maxSpeed(path.getFirst()),
					thisSignal.allowedSpeed()
				)
			if (nextSignal.isAllowing()) {
				motor.accelerateTo(minOf(nextSignal.allowedSpeed(), min))
			} else {
				motor.onWarning(min)
			}
		}

		override suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${Process.time()} POSITION: Train $number front at separator $where, " +
					"entering block $next, leaving block $current"
			}
			// A waiver is scoped to the traversal that granted it: the train has now reached the
			// separator it was let through to, and the next leg gets the clearance rule back.
			clearanceStopWaived = false

			if (where is DynamicRailSemaphore &&
				next != null &&
				env.isSeparatorInDirection(where, next, current)
			) {
				val semaphore: DynamicRailSemaphore = where
				semaphoreAction(semaphore, semaphore, current, next)
			} else if (where == timetable.getIn() && next != null) {
				requireSimulationNotNull(getAcceleration()) { "Acceleration must not be null at timetable entry" }
				semaphoreAction((where as DynamicInOut).inSemaphore, where, current, next)
			} else {
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
			}
			requireSimulation(pathToSemaphore?.getFirst() == where) {
				"Path to semaphore first element must match current position: ${pathToSemaphore ?: "null"}"
			}
			if (next != null) {
				val blockName = blockLabel(next)
				logger.info { "${time()} BLOCK_TRANSITION: Train $number entering block $blockName" }
				env.report("enter block $blockName", this@Train, ReportType.TRAIN_EVENTS)
				next.enter(this@Train)
				val block = next.getTrackBlock()
				if (block is DynamicTrackBlock) {
					env.fireBlockEvent(
						BlockEvent.OccupancySet(
							block = block,
							occupant = this@Train,
							entrySeparator = where,
							time = time()
						)
					)
				}
			}
		}
	}

	private inner class Tail : Site() {
		override val isFront: Boolean = false

		private var fromHome: Boolean = false

		override suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${time()} POSITION: Train $number tail at separator $where, clearing block $current"
			}
			if (where == timetable.getIn()) {
				fromHome = true
				initPositionFromFrontOffset(front.getTotalDistance(), length)
				start()
			}

			if (current != null) {
				val blockName = blockLabel(current)
				logger.info { "${time()} BLOCK_TRANSITION: Train $number leaving block $blockName" }
				env.report("leave block $blockName", this@Train, ReportType.TRAIN_EVENTS)
				current.leave(this@Train)
				val block = current.getTrackBlock()
				if (block is DynamicTrackBlock) {
					env.unregisterBlock(name, block)
					env.fireBlockEvent(
						BlockEvent.OccupancyCleared(
							block = block,
							occupant = this@Train,
							time = time()
						)
					)
				}
			}
			if (next == null &&
				where != timetable.getOut()
			) {
				env.report("ends in wrong out", this@Train, ReportType.TRAIN_EVENTS)
			}
		}

		override fun start(): Site = if (fromHome) super.start() else this
	}

	private inner class LengthChecker : ContinuousInvariantChecker() {
		override fun check(): Boolean =
			kotlin.math.abs(front.getTotalDistance() - tail.getTotalDistance() - length) <= maxAbsError

		override fun report(reportObj: StringBuilder): StringBuilder {
			requireSimulationNotNull(reportObj) { "Report object must not be null" }
			reportObj.append(front.getTotalDistance()).append(' ').append(tail.getTotalDistance())
			return reportObj.append(' ').append(length)
		}
	}

	private enum class AccelerationStopTest(
		private val decelarate: Boolean
	) {
		/**
		 *
		 */
		ACCELERATION_ENDED(false),

		/**
		 *
		 */
		TO_HALF_SPEED(false) {
			override fun condition(
				targetSpeed: Double,
				velocity: Double
			): Boolean = targetSpeed <= 2 * velocity
		},

		/**
		 *
		 */
		DECELERATION_ENDED(true);

		fun isDecelarate(): Boolean = decelarate

		open fun condition(
			targetSpeed: Double,
			velocity: Double
		): Boolean = if (isDecelarate()) targetSpeed >= velocity else targetSpeed <= velocity
	}

	/**
	 * Extends [Continuous] (not [LoopProcess]/`Process`) because train kinematics require
	 * ODE integration via [derivatives]; [start]/[stop] gate integration per acceleration
	 * phase. The project-level "continuous simulation not required" finding concerns
	 * framework choice (DSOL vs kDisco), not Motor's internal physics.
	 *
	 * The private `terminate` flag mirrors the [LoopProcess] shutdown pattern — necessary
	 * duplication, because Motor cannot inherit it (LoopProcess is Process-based, discrete).
	 *
	 * See `docs/MOTOR_CONTINUOUS_RATIONALE.md` (issue #373).
	 */
	private inner class Motor : Continuous() {
		private var currentCondition: AccelerationStopCondition? = null
		private var targetSpeed: Double = 0.0
		private var accelerate: Boolean = false
		private var terminate = false

		private inner class AccelerationStopCondition(
			private val stopTest: AccelerationStopTest
		) : Condition {
			override fun test(): Boolean = !accelerate || stopTest.condition(targetSpeed, getVelocity())

			fun getStopTest(): AccelerationStopTest = stopTest
		}

		override suspend fun actions() {
			while (true) {
				if (terminate) break
				iteration()
				if (terminate) break
				passivate()
			}
		}

		private suspend fun iteration() {
			val cond = requireSimulationNotNull(currentCondition) { "Current condition must not be null during iteration" }
			accelerate = true
			logger.trace {
				"Train $number motor iteration: target speed $targetSpeed, " +
					"current velocity ${getVelocity()}"
			}
			start()
			waitUntil(cond)

			if (accelerate && cond.getStopTest() == AccelerationStopTest.TO_HALF_SPEED) {
				targetSpeed = 0.0
				logger.trace { "Train $number motor: deceleration phase to half speed, target $targetSpeed" }
				waitUntil(AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED))
			}

			accelerate = false
			stop()
			acceleration.state = 0.0
		}

		private fun privateAccelerateTo(
			speed: Double,
			test: AccelerationStopTest
		) {
			requireSimulation(speed >= 0) { "Speed must be non-negative: $speed" }
			targetSpeed = speed
			currentCondition = AccelerationStopCondition(test)
			cancelAccelerating()
			Process.activate(this)
		}

		/**
		 * change speed
		 * @param speed
		 */
		fun accelerateTo(speed: Double) {
			logger.debug { "Train $number motor: accelerate to speed $speed, current velocity ${getVelocity()}" }
			env.report("in on warning", this@Train, ReportType._DEBUG)
			privateAccelerateTo(
				speed,
				if (speed >
					getVelocity()
				) {
					AccelerationStopTest.ACCELERATION_ENDED
				} else {
					AccelerationStopTest.DECELERATION_ENDED
				}
			)
		}

		/**
		 * special behaviour
		 * @param normalSpeed
		 */
		fun onWarning(normalSpeed: Double) {
			logger.debug {
				"Train $number motor: warning mode, target speed $normalSpeed, current velocity ${getVelocity()}"
			}
			env.report("in on warning $normalSpeed", this@Train, ReportType._DEBUG)

			requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative in onWarning" }
			privateAccelerateTo(normalSpeed, AccelerationStopTest.TO_HALF_SPEED)
		}

		/**
		 *
		 */
		fun cancelAccelerating() {
			if (accelerate) {
				accelerate = false
				Process.activate(this)
			}
		}

		override fun terminate() {
			terminate = true
			if (!terminated()) Process.activate(this)
		}

		override fun start(): Continuous = if (accelerate) super.start() else this

		/**
		 * Distance the braking law `a = (target² − v²) / (2s)` is aimed at.
		 *
		 * Normally [distanceToSemaphore] — the point the train is authorised to run to. While
		 * the motor is bringing the train to a **stand** (`targetSpeed == 0`) in front of a
		 * **restrictive** signal, the target moves back by [SEMAPHORE_STOP_CLEARANCE_METERS] so
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
		 * [distanceToSemaphore] itself is left alone on purpose: it is published to agents as
		 * `distanceToSignalAheadMetres` and must keep meaning "distance to the signal".
		 */
		private fun brakingTargetDistance(): Double {
			val distance = distanceToSemaphore()
			if (targetSpeed > 0.0) return distance
			if (semaphoreToStopShortOf() == null) return distance
			return maxOf(0.0, distance - SEMAPHORE_STOP_CLEARANCE_METERS)
		}

		override fun derivatives() {
			// minmax zpomaleni
			val s: Double = brakingTargetDistance()
			if (s <= 0) {
				accelerate = false
				return
			}
			if (velocity.state <= 0) velocity.state = 0.0

			val a: Double = ((targetSpeed - velocity.state) * (targetSpeed + velocity.state)) / (2 * s)
			acceleration.state =
				if (requireNotNull(currentCondition) { "currentCondition must be set" }.getStopTest().isDecelarate()) {
					maxOf(a, MINIMAL_DECELERATION.toDouble())
				} else {
					minOf(a, MAXIMAL_ACCELERATION.toDouble())
				}
		}
	}

	/**
	 * Periodic 1 Hz reporter for continuous train telemetry.
	 *
	 * Extends [LoopProcess] to use the standard loop + cooperative-termination pattern.
	 * Reporting logic is in [iteration]; the 1-second delay between reports is in
	 * [interLoopSleep]. Safe termination (including DiscoException-guarded activate())
	 * is provided by [LoopProcess.terminate].
	 *
	 * @see LoopProcess
	 */
	private inner class TrainReporter : LoopProcess() {
		override suspend fun iteration() {
			if (env.isReporting(ReportType.TRAIN_CONTINUOUS)) {
				val builder = StringBuilder()
				builder.append(getAcceleration()).append(' ')
				builder.append(getVelocity()).append(' ')
				builder.append(front.getTotalDistance()).append(' ')
				builder.append(front.getFrontSection().toString()).append(' ')
				builder.append(tail.getTailSection().toString()).append(' ')
				val distanceToSemaphore: Double = distanceToSemaphore()
				builder.append(if (distanceToSemaphore > 0) distanceToSemaphore else 0)
				env.report(builder, this@Train, ReportType.TRAIN_CONTINUOUS)
			}
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	private val reporter: TrainReporter = TrainReporter()

	private val acceleration: Variable = Variable(0.0)
	private val velocity: Variable = Variable(0.0)
	private val va: SimpleIntegration = SimpleIntegration(velocity, acceleration)
	private val front: Front = Front()
	private val tail: Tail = Tail()
	private val motor: Motor = Motor()
	private val timetable: Timetable
	private val env: SimulationEnvironment
	private var pathToSemaphore: Path? = null
	override val name: String
	private val trainNavService: TrainNavigationService

	/**
	 * The separator the train's **front** most recently crossed. Written only by the [Front]
	 * (see [Site.isFront]); null until the train enters the network.
	 *
	 * This is the raw state, not what the animation reads: at a boundary where the front has
	 * crossed out of its section with nothing to enter, this separator is that section's *exit*
	 * end. [trainEntrySeparator] publishes the coherent value — see [Site.publishedEntrySeparator]
	 * (Issue #788).
	 */
	private var entrySeparator: DynamicPathSeparator? = null

	private val number: Int

	private var length: Double
	private var ap: ContinuousInvariantChecker = LengthChecker()

	/**
	 * Create train
	 * @param env The simulation environment
	 * @param timetable Train timetable
	 * @throws SimulationException if train length exceeds track distance between InOuts
	 */
	constructor(env: SimulationEnvironment?, timetable: Timetable?) {
		this.env = requireSimulationNotNull(env) { "env must not be null" }
		val validatedTimetable = requireSimulationNotNull(timetable) { "timetable must not be null" }
		this.timetable = validatedTimetable
		this.length = validatedTimetable.getLength()
		number = nextCount()
		name = "Train #$number"
		val inName = validatedTimetable.getIn().name
		val outName = validatedTimetable.getOut().name
		trainNavService = env.getRoutingServices().getTrainNavigationService()

		// Issue #60: Validate train length against track distance between InOuts
		validateTrainLength(env, validatedTimetable, this.length)

		logger.debug { "Train $number created: from $inName to $outName, length $length" }
	}

	/**
	 * Validates that train length does not exceed the shortest track distance between InOuts.
	 *
	 * **Issue #60: Track Length Validation**
	 * - Calculates shortest path distance between origin and destination InOuts
	 * - Ensures train can physically fit on the track
	 * - Prevents runtime simulation errors from track being too short
	 *
	 * **Implementation:**
	 * - Uses TopologyNavigator to find all possible paths
	 * - Calculates total track distance for each path
	 * - Validates train length against shortest available path
	 * - Gracefully handles test mocks by catching exceptions
	 *
	 * @param env Simulation environment providing topology navigator
	 * @param timetable Train timetable with origin and destination InOuts
	 * @param trainLength Length of the train in meters
	 * @throws SimulationException if train length exceeds shortest track distance
	 * @since 2026-02-06 (Issue #60)
	 */
	private fun validateTrainLength(
		env: SimulationEnvironment,
		timetable: Timetable,
		trainLength: Double
	) {
		val inOut = timetable.getIn()
		val outOut = timetable.getOut()

		// Skip validation if either InOut is not registered in this context (e.g. mock objects in tests)
		val contextInOuts = env.getInOuts()
		if (!contextInOuts.contains(inOut) || !contextInOuts.contains(outOut)) {
			logger.trace { "Train length validation skipped: InOuts not registered in context (likely test mock)" }
			return
		}

		try {
			val topologyNavigator = env.getRoutingServices().getTopologyNavigator()

			// Find all topologically possible paths between InOuts
			val paths =
				topologyNavigator.findAllTopologicalPaths(
					start = inOut,
					target = outOut,
					maxDepth = 100
				)

			requireSimulation(paths.isNotEmpty()) {
				"Train length validation failed: No route exists between " +
					"InOut '${inOut.name}' and InOut '${outOut.name}'. " +
					"Railway network must provide at least one path between entry and exit points."
			}

			// Calculate distance for each path and find the shortest using idiomatic Kotlin
			val shortestPathDistance =
				paths.minOf { path ->
					path.sumOf { section -> section.length() }
				}

			// Validate train length against shortest path
			requireSimulation(trainLength <= shortestPathDistance) {
				"Train length ($trainLength m) exceeds track distance ($shortestPathDistance m) " +
					"between InOut '${inOut.name}' and InOut '${outOut.name}'. " +
					"Minimum track length required: $trainLength m, available: $shortestPathDistance m. " +
					"Reduce train length or increase track distance to resolve this issue."
			}

			logger.debug {
				"Train length validation passed: train=$trainLength m, " +
					"shortest path=$shortestPathDistance m (${inOut.name} → ${outOut.name})"
			}
		} catch (e: SimulationException) {
			// Rethrow validation failures (train too long, no route, etc.)
			throw e
		} catch (e: Exception) {
			// Any other exception indicates an unexpected failure in topology/navigation logic.
			// Log at WARN and rethrow so that validation is not silently bypassed.
			logger.warn(e) {
				"Train length validation failed due to unexpected error; simulation will be aborted: ${e.message}"
			}
			throw e
		}
	}

	override fun distanceToSemaphore(): Double =
		if (pathToSemaphore == null) 0.0 else pathToSemaphore!!.length() - front.getPosition()

	override suspend fun actions() { // spusten odsouhlasenim
		// zarazeni do fronty vstupniho bodu (simulace systemu sousedni stanice)
		val inout = timetable.getIn()
		val worker: InOutWorker = env.getWorkerFor(inout)
		logger.debug { "Train $number approved for movement from ${inout.name} to ${timetable.getOut().name}" }
		worker.enterTrain(this)
		env.report(
			formatApprovalMessage(name, inout.name, timetable.getOut().name),
			this,
			ReportType.TRAIN_APPROVED
		)

		// Wait for InOutWorker to reserve initial path before starting Front
		// This prevents race condition where Front checks for reserved path before InOutWorker completes
		waitUntil {
			// Check if we have any reserved blocks (path has been reserved)
			trainNavService.isPathReservedForTrain(name, inout)
		}
		logger.info { "Train $number path is reserved, starting Front process" }

		Process.activate(front)

		// Start the tail once the front has advanced one train-length. Same level-triggered,
		// root-found wait as the block-boundary gate above (Issue #797 / kDisco#72): a front that
		// decelerates to a stop within one train-length of entry asymptotes short of the
		// threshold, so an edge-triggered `waitCrossing` would never fire and the tail would stay
		// permanently outside the network. `waitUntilCrossing` resumes as soon as the level
		// condition holds while still root-finding a within-step crossing. The `dtMin` slack makes
		// the threshold reachable.
		waitUntilCrossing { (length - dtMin) - front.getTotalDistance() }
		Process.activate(tail)

		out()
		(worker.getQueqe().first() as? Train)?.let { Process.activate(it) }
		ap.start()
		Process.activate(reporter)

		waitUntil(front.terminated)
		ap.stop()
		// predkem v systemu sousedni stanice

		waitUntil(tail.terminated)
		reporter.terminate()
		stop()
		motor.terminate()
		env.releaseTrainReservations(name)
		// ukoncovaci..
		logger.debug { "Train $number completed journey: distance traveled ${front.getTotalDistance()}" }
		env.report("ends", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * @return current acceleration of train
	 */
	fun getAcceleration(): Double = acceleration.state

	/**
	 * Current acceleration of train (Kotlin property accessor).
	 * Delegates to [getAcceleration].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainAcceleration: Double
		get() = getAcceleration()

	/**
	 * @return current speed of train
	 */
	fun getVelocity(): Double = velocity.state

	/**
	 * Current velocity of train in m/s (Kotlin property accessor).
	 * Delegates to [getVelocity].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainVelocity: Double
		get() = getVelocity()

	/**
	 * Length of train in meters.
	 *
	 * Currently the length configured for the train as a whole; it is not yet
	 * computed as the sum of the individual wagon lengths.
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainLength: Double
		get() = length

	/**
	 * Reverse the train's direction of travel.
	 *
	 * This simulates the train engineer moving to the opposite end of the train
	 * and driving in the reverse direction. This is a simulation simplification
	 * of real-world locomotive coupling/uncoupling operations.
	 *
	 * **Preconditions:**
	 * - Train must be completely stopped (velocity = 0)
	 * - Motor must not be accelerating
	 *
	 * **Operation:**
	 * - Validates train is stopped
	 * - Simulates engineer movement delay (30 seconds)
	 * - Swaps In/Out destinations in timetable
	 * - Reports the reversal event
	 *
	 * **Usage Example:**
	 * ```kotlin
	 * // In a custom interlocking/dispatcher process
	 * class CustomInterlocking(context: SimulationContext) : Interlocking(context) {
	 *     override suspend fun actions() {
	 *         val train = Train(env, timetable)
	 *         Process.activate(train)
	 *
	 *         // Wait for train to reach station
	 *         waitUntil { train.getVelocity() == 0.0 }
	 *
	 *         // Reverse direction (this will hold for 30 seconds)
	 *         train.reverseDirection()
	 *
	 *         // Train can now continue in opposite direction
	 *     }
	 * }
	 * ```
	 *
	 * **Note:** This method uses `hold(30.0)` and must be called from within
	 * a kdisco-engine coroutine process context (i.e., from a `suspend` function
	 * inside a [Process.actions] override).
	 *
	 * @throws IllegalStateException if train is not stopped
	 * @since GitHub #62: Bidirectional train operation support
	 */
	suspend fun reverseDirection() {
		// Validate preconditions
		requireSimulation(getVelocity() == 0.0) {
			"Train $number must be stopped (velocity = 0) to reverse direction. Current velocity: ${getVelocity()}"
		}

		logger.info { "Train $number: Engineer moving to opposite end (reversing direction)" }
		env.report("reversing direction", this, ReportType.TRAIN_EVENTS)

		// Simulate time for engineer to walk to opposite end of train
		// Typical walking speed: 1.5 m/s, train length varies (e.g., 200m)
		// Use fixed 30 second delay for simulation consistency
		hold(30.0)

		// Swap In and Out destinations
		timetable.reverseDirection()

		val newDestination = timetable.getOut().name
		logger.info { "Train $number: Direction reversed, new destination: $newDestination" }
		env.report("reversed, destination now $newDestination", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * Unique train number for identification and rendering.
	 *
	 * Each train is assigned a unique sequential number starting from 1.
	 * Used for train overlay rendering and identification in animation.
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainNumber: Int
		get() = number

	/**
	 * Origin InOut where this train entered the network.
	 *
	 * Used by animation system to determine train color coding based on entry point.
	 * Color mapping: InOut "B" → blue, InOut "A" → orange (configurable in vyhybna.xml).
	 *
	 * This is a minimal accessor to support animation visualization without exposing
	 * full Timetable structure. No sim/ package refactoring required.
	 *
	 * @return The DynamicInOut where the train originated
	 * @since 2026-02-04 (Fix train color coding bug)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val originInOut: DynamicInOut
		get() = timetable.getIn()

	/**
	 * Read-only snapshot of the origin/destination InOut names and scheduled times
	 * derived from this train's [Timetable], for agent perception.
	 *
	 * These four properties expose exactly what the
	 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort] implementation needs to
	 * build a [cz.vutbr.fit.interlockSim.ports.TimetableReading] — scalar values only,
	 * no live references to the [Timetable] or its mutable [DynamicInOut] endpoints.
	 * Handing out the [Timetable] itself would let callers mutate reachable sim state
	 * (e.g. via [Timetable.reverseDirection]); these narrow properties keep the
	 * perception data flow strictly one-directional (snapshot out, nothing back in).
	 *
	 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
	 */
	val timetableOriginName: String
		get() = timetable.getIn().name

	/** @see timetableOriginName */
	val timetableDestinationName: String
		get() = timetable.getOut().name

	/** @see timetableOriginName */
	val scheduledDepartureTime: Double
		get() = timetable.getInTime().value

	/** @see timetableOriginName */
	val scheduledArrivalTime: Double
		get() = timetable.getOutTime().value

	// ── SP2a.1 per-train first-person perception (Issue #552) ─────────────

	/**
	 * Name of the next semaphore ahead of this train on its reserved path.
	 *
	 * The destination semaphore of the currently reserved path
	 * (`pathToSemaphore.getLast()`). Returns `null` when no path is set (train not yet
	 * moving, or approaching its final destination with no further path reserved).
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val signalAheadName: String?
		get() = separatorName(nextSemaphore())

	/**
	 * Signal aspect of the next semaphore ahead of this train.
	 *
	 * For a [DynamicRailSemaphore] endpoint, returns the semaphore's current signal.
	 * For a [DynamicInOut] endpoint (the path ends at an entry/exit point), returns the
	 * `outSemaphore` signal, which controls departure from that InOut. `DynamicInOut.outSemaphore`
	 * is non-null by construction (`DynamicInOut` declares it non-nullable), the same invariant the
	 * pre-existing `accelerateToSignal` relies on — the `else -> null` arm here only covers the
	 * no-reserved-path case (`nextSemaphore() == null`).
	 *
	 * Returns `null` when no semaphore is ahead (same conditions as [signalAheadName]).
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val signalAheadAspect: Signal?
		get() = separatorAspect(nextSemaphore())

	/**
	 * Name of the **second** semaphore ahead — the one after [nextSemaphore] along the
	 * reserved route — or `null` when there is no second signal (no reserved route, or the
	 * train is within one semaphore of its destination InOut).
	 *
	 * Together with [signalAheadAspect] this forms the `(immediate, second)` aspect pair that
	 * encodes předvěst / Výstraha semantics for the SP2a.2 decision-maker (see `TrainPerceptionReading`).
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val nextSignalAheadName: String?
		get() = separatorName(secondSemaphoreAhead())

	/**
	 * Signal aspect of the **second** semaphore ahead (see [nextSignalAheadName]), or `null`
	 * when there is no second signal. See [signalAheadAspect] for the `DynamicInOut.outSemaphore`
	 * non-null invariant.
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val nextSignalAheadAspect: Signal?
		get() = separatorAspect(secondSemaphoreAhead())

	/**
	 * The oriented separator after [firstSep] along this train's reserved route, or `null`
	 * when [firstSep] is `null` or is the last oriented separator on the route.
	 *
	 * [firstSep] defaults to [nextSemaphore] so the public perception properties
	 * ([nextSignalAheadName], [nextSignalAheadAspect]) read the immediate-then-second pair with no
	 * extra arguments; the live perception port passes the already-computed immediate separator to
	 * avoid a second `nextSemaphore()` call per capture (M1).
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	internal fun secondSemaphoreAhead(firstSep: OrientedPathSeparator? = nextSemaphore()): OrientedPathSeparator? =
		firstSep?.let { trainNavService.reservedSeparatorsAhead(name, it, 1).getOrNull(0) }

	/**
	 * Current track speed limit in m/s derived from the reserved path.
	 *
	 * Folds each intermediate track/switch element's [PathElement.contributeToPathMaxSpeed]
	 * along [pathToSemaphore], starting from an unrestricted seed — the minimum speed limit
	 * across all tracks of the reserved path, as approached from the train's current
	 * separator (track geometry, switch positions, etc.). This is the **physical** track
	 * constraint, independent of the signal aspect (captured separately in [signalAheadAspect]).
	 *
	 * **Bugfix (Issue #565 follow-up, SP4.3 regression):** [pathToSemaphore]'s first element
	 * is the semaphore the train most recently departed — e.g. a bidirectional semaphore
	 * that reports `STOP` for the *opposite* direction it no longer faces (see the
	 * `SEMAPHORE_SIGNAL_NOT_UPDATED ... reverse direction` warning in
	 * [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]). Delegating straight to
	 * [Path.maxSpeed] would seed its running minimum from that departed semaphore's *own*
	 * live [cz.vutbr.fit.interlockSim.objects.cells.Signal.allowedSpeed] (0.0 for `STOP`),
	 * collapsing this supposedly signal-independent value to `0.0` for the rest of the leg —
	 * every subsequent [TrainDecisionPolicy] tick would then see "no speed permitted" and
	 * brake the train to a stand it can never leave, because a train at velocity 0 never
	 * crosses another separator to advance [pathToSemaphore] and clear the stale reading.
	 * This was latent since [Path.maxSpeed] was introduced (Issue #552) — nothing invoked
	 * this property against a live, moving train until SP4.3 wired [TrainDecisionPolicy]
	 * into [wireSynchronousDispatcher], which is what exposed it as trains failing to
	 * complete their routes. Folding the path elements directly (mirroring
	 * [cz.vutbr.fit.interlockSim.objects.paths.AbstractPath.maxSpeed]'s own loop, minus its
	 * departed-separator seed) restores the "independent of signal aspect" contract without
	 * touching the shared [Path.maxSpeed] used by [cz.vutbr.fit.interlockSim.objects.paths.AbstractPath]'s
	 * other, legitimate caller (semaphore path setup, where seeding from the entry signal is correct).
	 *
	 * Returns [ABSOLUTE_MAX_SPEED] when no path is currently set for this train (the
	 * interlocking has not yet reserved a route; no physical constraint is known).
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val currentSpeedLimitMps: Double
		get() =
			pathToSemaphore?.let { path ->
				var min = ABSOLUTE_MAX_SPEED
				var prevSep: PathSeparator? = path.getFirst()
				for (element in path) {
					if (element == path.getFirst() || element == path.getLast()) continue
					val contribution = element.contributeToPathMaxSpeed(prevSep, min)
					min = contribution.minSpeed
					prevSep = contribution.updatedPreviousSeparator
				}
				min
			} ?: ABSOLUTE_MAX_SPEED

	/**
	 * Whether the train is currently stopped (velocity = 0).
	 *
	 * `true` when the train is not moving, which occurs when:
	 * - waiting at a [Signal.STOP] semaphore
	 * - holding at a station during direction reversal (30-second dwell)
	 * - before first departure (just approved, not yet started)
	 *
	 * A reactive train agent (SP2a.2) can distinguish these states by inspecting
	 * [signalAheadAspect]: a STOP aspect means the train is blocked by a signal; an
	 * allowing or null aspect means the train may be at a station dwell.
	 *
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	val isDwelling: Boolean
		get() = getVelocity() == 0.0

	/**
	 * Backing flag for [isStationDwelling]; set by [holdAtStation] and cleared by
	 * [StationDwellProcess] when the dwell expires.
	 */
	private var stationDwelling: Boolean = false

	/**
	 * Whether a station dwell ([holdAtStation]) is currently in progress.
	 *
	 * Distinct from [isDwelling]: [isDwelling] is `true` whenever the train is not moving
	 * for *any* reason (STOP semaphore, station dwell, before first departure), whereas
	 * [isStationDwelling] is `true` only while a [StationDwellProcess] is actively holding
	 * the train — from the [holdAtStation] call until the dwell duration expires.
	 *
	 * A reactive train agent (SP2a.2/SP2a.3) reads this to know the minimum dwell has not
	 * yet elapsed: while it is `true` the agent must not accelerate away; once it flips to
	 * `false` the agent's next decide → act cycle may restart the train via [setTargetSpeed].
	 *
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	val isStationDwelling: Boolean
		get() = stationDwelling

	/**
	 * Track section where the train's front is currently located.
	 *
	 * Used for train position interpolation in animation rendering.
	 * Returns null if train has not yet started moving.
	 *
	 * @return Current track section for train front, or null
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val frontSection: TrackSection?
		get() = front.getFrontSection()

	/**
	 * Distance traveled by the train's front along current track section.
	 *
	 * Used for train position interpolation in animation rendering.
	 * Returns position within the current section (0.0 to section length).
	 *
	 * Published together with [frontSection] and [trainEntrySeparator]: when the front has
	 * crossed out of its section and has no section to enter yet, this is the section length
	 * rather than the rebased ~0, because the entry separator published then is the section's
	 * other end (Issue #788).
	 *
	 * @return Distance along current section in meters
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 * @since Issue #788 (published consistently with [trainEntrySeparator] at boundaries)
	 */
	val frontPosition: Double
		get() = front.publishedPosition()

	/**
	 * Total distance traveled by the train's front since departure.
	 *
	 * Includes all previously completed sections plus position in current section.
	 * Used for train progress tracking and animation.
	 *
	 * @return Total distance traveled in meters
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val totalDistance: Double
		get() = front.getTotalDistance()

	/**
	 * Separator through which the train's front entered [frontSection].
	 *
	 * Used together with [frontSection] and [frontPosition] for position interpolation and for
	 * the authoritative heading in animation: the front always travels from this separator
	 * towards the section's other end.
	 *
	 * This is the separator the front most recently crossed, except at the boundary where the
	 * front has crossed out of its section and has no section to enter yet — there the
	 * separator just crossed is the *exit* end of [frontSection] and the opposite end is
	 * published instead, so the direction never reverses while the front is standing still
	 * (Issue #788).
	 *
	 * @return entry separator, or null if train hasn't entered any section yet
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 * @since Issue #788 (always the end the front entered [frontSection] through)
	 */
	val trainEntrySeparator: DynamicPathSeparator?
		get() = front.publishedEntrySeparator()

	@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
	override fun nextSemaphore(): OrientedPathSeparator? = pathToSemaphore?.getLast()

	/**
	 * Set once [Front.holdShortOfRestrictiveSignal] has decided this traversal must run to the
	 * separator after all, and cleared at the start of every traversal by [Front.separatorAction].
	 * See [Front.waiveClearanceStop].
	 */
	private var clearanceStopWaived = false

	/**
	 * The semaphore at the end of this train's reserved leg when it is showing a non-allowing
	 * aspect and the clearance stop still applies, otherwise `null`. The single
	 * "should this train be stopping short?" test, shared by [Front]'s block-boundary guard and
	 * [Motor]'s braking target (Issue #989).
	 *
	 * Read live rather than cached, so an aspect change during the approach is observed
	 * immediately by both callers, and so both give up the clearance together the moment it is
	 * waived — leaving guard and braking target aimed at the same point at all times.
	 *
	 * A [DynamicInOut] endpoint is deliberately excluded: its `outSemaphore` is a constant
	 * [Signal.FREE] and can never be restrictive, and a train must not be held short of its own
	 * exit point.
	 */
	private fun semaphoreToStopShortOf(): DynamicRailSemaphore? =
		if (clearanceStopWaived) {
			null
		} else {
			(nextSemaphore() as? DynamicRailSemaphore)?.takeIf { !it.signal.isAllowing() }
		}

	fun start(): Train {
		acceleration.start()
		velocity.start()
		va.start()
		return this
	}

	fun stop() {
		acceleration.stop()
		velocity.stop()
		va.stop()
		velocity.state = 0.0
		velocity.rate = 0.0
		acceleration.rate = 0.0
		acceleration.state = 0.0
	}

	/**
	 * Request an immediate halt of this train.
	 *
	 * Brings the train to a standstill by zeroing its velocity, acceleration, and all
	 * associated integration variables. Safe to call even if the train has already
	 * stopped — the operation is idempotent.
	 *
	 * Intended for use by [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * when [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService.autoHaltTrainOnViolation]
	 * is `true`: register this method as the halt callback via
	 * `collisionDetectionService.registerHaltCallback(train.name, train::requestHalt)`.
	 *
	 * **Thread safety:** This method modifies kDisco [cz.ksimulantenbande.kdisco.Variable] state.
	 * It must only be called from the simulation thread (e.g., from within the
	 * [DefaultCollisionDetectionService] warning delivery, which runs on that thread).
	 *
	 * @since Issue #615 (Goal 3 SP5)
	 */
	fun requestHalt() {
		stop()
	}

	/**
	 * Set the target speed for this train's motor from an external agent.
	 *
	 * Delegates to [Motor.accelerateTo]: the physics model (acceleration ramp, braking
	 * ramp, speed-limit enforcement) takes effect immediately.  This is the public surface
	 * used by [cz.vutbr.fit.interlockSim.ports.DefaultTrainActuatorPort] to bridge the
	 * agent's [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort] call into the kDisco
	 * physics kernel.
	 *
	 * ## Safety: "allowed to go"
	 *
	 * A positive target speed is only accepted when the train has an active route
	 * reservation (blocks reserved via [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]).
	 * Without a reserved route the call is a no-op (warning is logged) — the train must
	 * not move if the interlocking has not cleared a path for it.  Setting speed to `0.0`
	 * (emergency stop) is always accepted regardless of reservation state.
	 *
	 * **Thread safety:** Must be called from the kDisco simulation thread.
	 *
	 * @param speed Target speed in m/s.  Must be ≥ 0.
	 * @throws IllegalArgumentException if [speed] is negative.
	 * @since Issue #545 (SP0.6 — Goal 10)
	 */
	fun setTargetSpeed(speed: Double) {
		require(speed >= 0.0) { "Target speed must be >= 0, got $speed" }
		if (speed > 0.0) {
			val pathReservationService = env.getRoutingServices().getPathReservationService()
			val reservedBlocks = pathReservationService.getReservedBlocks(name)
			val occupiedBlocks = pathReservationService.getOccupiedBlocks(name).toSet()
			if (reservedBlocks.all { it in occupiedBlocks }) {
				logger.warn { "Train $number: setTargetSpeed($speed) ignored — no cleared block ahead for $name" }
				return
			}
		}
		motor.accelerateTo(speed)
	}

	/**
	 * Hold this train stationary at a station for [dwellDurationSeconds] simulation seconds.
	 *
	 * SP2a.3 act step for station dwell (Issue #554).  Spawns a fire-and-forget
	 * [StationDwellProcess] that waits for [dwellDurationSeconds] sim-seconds and sets
	 * [isStationDwelling] for the duration of the dwell.  The caller is **not** blocked.
	 *
	 * ## Precondition: the train must already be stopped
	 *
	 * This method does **not** brake the train — it starts a dwell timer.  The train must
	 * already be at rest (`velocity == 0`); the agent is responsible for braking first
	 * (`applyDecision(BRAKE)` → [setTargetSpeed]`(0.0)`) and calling this only once the
	 * train has come to a stand.  Calling it on a moving train throws, because a dwell
	 * timer running alongside a rolling train would silently misrepresent a station stop.
	 * This mirrors [reverseDirection], which requires the same precondition.
	 *
	 * After the dwell expires the motor is *not* restarted here — [isStationDwelling] flips
	 * back to `false` and the agent's next [setTargetSpeed] call restarts the train.
	 *
	 * **Thread safety:** Must be called from the kDisco simulation thread.
	 *
	 * @param dwellDurationSeconds Dwell time in simulation seconds (must be > 0).
	 * @throws IllegalArgumentException if [dwellDurationSeconds] is ≤ 0.
	 * @throws cz.vutbr.fit.interlockSim.exceptions.SimulationException if the train is moving
	 *   (`velocity != 0`) or a station dwell is already in progress.
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	fun holdAtStation(dwellDurationSeconds: Double) {
		require(dwellDurationSeconds > 0.0) {
			"dwellDurationSeconds must be > 0, got $dwellDurationSeconds"
		}
		requireSimulation(getVelocity() == 0.0) {
			"Train $number must be stopped (velocity = 0) to hold at station. " +
				"Current velocity: ${getVelocity()}"
		}
		requireSimulation(!stationDwelling) {
			"Train $number is already dwelling at a station"
		}
		logger.info {
			"Train $number: holding at station for ${dwellDurationSeconds}s"
		}
		// Defensive: the precondition above guarantees the train is at rest, so this is
		// normally a no-op.  Kept so a pending acceleration ramp cannot resume mid-dwell.
		motor.cancelAccelerating()
		stationDwelling = true
		Process.activate(StationDwellProcess(dwellDurationSeconds))
	}

	/**
	 * Fire-and-forget kDisco process that implements a station dwell pause.
	 *
	 * Spawned by [holdAtStation]; holds for [dwellSeconds] simulation seconds and then
	 * completes.  Clears [isStationDwelling] when the dwell expires.  The motor is not
	 * restarted here — the agent's next [setTargetSpeed] call will do that.
	 *
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	private inner class StationDwellProcess(
		private val dwellSeconds: Double
	) : Process() {
		override suspend fun actions() {
			env.report("dwell start ${dwellSeconds}s", this@Train, ReportType.TRAIN_EVENTS)
			hold(dwellSeconds)
			stationDwelling = false
			logger.info { "Train $number: station dwell of ${dwellSeconds}s complete; ready to resume" }
			env.report("dwell end", this@Train, ReportType.TRAIN_EVENTS)
		}
	}

	override fun toString(): String = name
}
