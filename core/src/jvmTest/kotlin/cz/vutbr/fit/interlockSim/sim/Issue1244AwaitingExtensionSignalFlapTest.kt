/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1244: the signal of a train awaiting a route extension must not flip STOP/proceed every tick.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.FirstTrainOwnershipConflict
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Acceptance pin for Issue #1244 part 1: in the #1060 "awaiting extension" shape the signal a train
 * stands at no longer flips STOP/proceed on every control step.
 *
 * ## The flap this pins
 *
 * A train stands at its signal; its stored route runs past the signal, but navigation refuses the
 * leg (`OwnershipConflict`), so the shell reports the input as awaiting a route extension. Per 2 s
 * tick the reservation expiry (phase 2) found the signal at proceed, kept the approach-locked block,
 * put the signal back to STOP and deferred; then the rule-based dispatcher picked the very separator
 * the route already ends at and re-requested it, and the kernel re-cleared the signal: after each
 * STOP reset the next control step put the signal back to proceed (measured: on 60 of 90 control
 * steps), and the expiry never completed.
 *
 * The fix: the shell reports where the held route ends ([BlockInputObservation.heldRouteEndName]),
 * and [RuleBasedDispatcher] does not request a route to that very separator for an awaiting train.
 * The signal now stays at STOP after a deferral until the expiry completes; the one legitimate flip
 * per expiry cycle is the fresh grant (STOP -> proceed) on the tick the tail is freed.
 *
 * ## How the shape is produced
 *
 * As in [Issue943OwnershipConflictStallBoundTest]: a [FirstTrainOwnershipConflict] answers
 * [PathResult.OwnershipConflict] for the first train that queries from a non-[DynamicInOut]
 * separator, from then on. That train's route already ends at a facing signal one section ahead, so
 * the dispatcher's pick is the held end — a same-target re-request. The stall lasts until the #943
 * `errorStop` horizon, which ends the run (recorded, not thrown).
 *
 * A re-clear here is a control step whose start and end aspects differ (`stepsWithAspectFlip`). The
 * expiry's STOP resets happen in the loop tick between two control steps, so they are not counted;
 * they are tracked separately as deferrals (proceed at a step's end, STOP at the next step's start).
 *
 * Measured on `vyhybna.xml` (90 control steps from the first stand to the errorStop): with the
 * guard, 2 re-clears, 2 deferrals and 2 expiries (t = 74 s and 138 s); with the guard removed
 * (mutation check, not pinned), 60 re-clears, 60 deferrals and no expiry at all.
 */
@DisplayName("Issue #1244: an awaiting-extension train's signal does not flip STOP/proceed every tick")
@Tag("integration-test")
class Issue1244AwaitingExtensionSignalFlapTest : KoinTestBase() {
	private companion object {
		/** Ample room for the first stand plus the #943 errorStop horizon; errorStop ends the run first. */
		const val END_TIME: Long = 400L

		/** Distance to the signal ahead (m) at or under which a train counts as standing at it. */
		const val STANDING_AT_SIGNAL_TOLERANCE = 1e-6

		/** Distinctive fragment of the never-extended-route `errorStop` message (Issue #943). */
		const val ROUTE_EXTENSION_FRAGMENT = "no route extension"
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `the kept signal changes aspect at most once per completed expiry`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val stall = FirstTrainOwnershipConflict(context.getRoutingServices().getTrainNavigationService())
		val errors = CopyOnWriteArrayList<Throwable>()
		val conflictEnv = NavigationDecoratingContext(context, stall.navigation) { errors.add(it) }
		val expired = collectExpiries(context, stall.heldTrainId)
		val loop = prepareShuntingLoop(context, END_TIME, env = conflictEnv)
		val probe = FlapProbe(loop, stall.heldTrainId, expired).also { it.wrapWiredListener() }

		context.run()

		val run = runSummary(probe, expired, errors).also { logger.info { "Issue1244 flap test: $it" } }
		val trainId = requireNotNull(stall.heldTrainId.get()) { "no train was stalled [$run]" }
		// One assertAll reports every failed assertion, so a regression shows the flip count, too.
		assertAll {
			assertRunNotVacuous(trainId, probe, expired, errors, run)
			assertNoPerTickFlap(probe, expired, run)
		}
	}

	/** Collects the [ReservationExpired] events of the held train, whichever train that turns out to be. */
	private fun collectExpiries(
		context: SimulationEnvironment,
		heldTrainId: AtomicReference<String?>
	): List<ReservationExpired> {
		val expired = CopyOnWriteArrayList<ReservationExpired>()
		context.onReservationExpired { event -> if (event.trainId == heldTrainId.get()) expired += event }
		return expired
	}

	/** Every measured number of the run, so a failing assertion shows the whole run. */
	private fun runSummary(
		probe: FlapProbe,
		expired: List<ReservationExpired>,
		errors: List<Throwable>
	): String =
		"keptSignal=${probe.keptSignal?.name}, steps=${probe.stepsObserved}, " +
			"flips=${probe.stepsWithAspectFlip}, deferrals=${probe.deferrals}, " +
			"guardReachedSteps=${probe.guardConditionSteps}, expired=${expired.map { it.time }}, " +
			"errorStops=${errors.map { it.message }}"

	/**
	 * Non-vacuity: the train stood, the guard's condition was reached, several expiry cycles ran,
	 * and the stand lasted the whole #943 window (the run ended in its errorStop naming the train).
	 */
	private fun assertRunNotVacuous(
		trainId: String,
		probe: FlapProbe,
		expired: List<ReservationExpired>,
		errors: List<Throwable>,
		run: String
	) {
		assertThat(probe.keptSignal, name = "signal the stalled train stood at [$run]").isNotNull()
		assertThat(probe.guardConditionSteps, name = "steps awaiting with the pick == held route end [$run]")
			.isGreaterThanOrEqualTo(1)
		assertThat(expired.size, name = "ReservationExpired events of $trainId [$run]").isGreaterThanOrEqualTo(2)
		assertThat(
			errors.mapNotNull { it.message }.any { ROUTE_EXTENSION_FRAGMENT in it && trainId in it },
			name = "a #943 errorStop naming $trainId [$run]"
		).isTrue()
	}

	/**
	 * The acceptance: one legitimate flip per completed expiry (the fresh grant), not one per tick,
	 * and the aspect holds STOP between a deferral and the expiry that completes it.
	 */
	private fun assertNoPerTickFlap(
		probe: FlapProbe,
		expired: List<ReservationExpired>,
		run: String
	) {
		assertThat(probe.stepsWithAspectFlip, name = "control steps whose start and end aspects differ [$run]")
			.isLessThanOrEqualTo(expired.size + 1)
		assertThat(probe.proceedBeforeExpiryViolations, name = "proceed at a step start after a deferral [$run]")
			.isEmpty()
	}

	/**
	 * Observes the stalled train's kept signal on the simulation thread at the start and the end of
	 * every control step.
	 */
	private class FlapProbe(
		private val loop: ShuntingLoop,
		private val heldTrainId: AtomicReference<String?>,
		private val expired: List<ReservationExpired>
	) {
		var keptSignal: DynamicRailSemaphore? = null
		var stepsObserved = 0
		var stepsWithAspectFlip = 0
		var deferrals = 0
		var guardConditionSteps = 0
		val proceedBeforeExpiryViolations = mutableListOf<String>()

		private var startAspect: Signal? = null
		private var previousEndAspect: Signal? = null
		private var expiredSeen = 0
		private var deferredSinceLastExpiry = false

		/** Installs this probe around the listener the loop was wired with. */
		fun wrapWiredListener() {
			val wired = loop.controlStepListener
			loop.controlStepListener =
				ControlStepListener {
					beforeStep()
					wired?.onControlStep()
					afterStep()
				}
		}

		private fun beforeStep() {
			val trainId = heldTrainId.get() ?: return
			val signal = lockOnKeptSignal(trainId) ?: return
			stepsObserved++
			val start = signal.signal
			recordDeferral(start)
			countGuardStep(trainId)
			startAspect = start
		}

		private fun afterStep() {
			val start = startAspect ?: return
			val end = requireNotNull(keptSignal).signal
			if (end != start) stepsWithAspectFlip++
			previousEndAspect = end
			startAspect = null
		}

		/** The signal [trainId] stands at, fixed on the first step it stands there; `null` before. */
		private fun lockOnKeptSignal(trainId: String): DynamicRailSemaphore? {
			if (keptSignal == null) {
				val train = loop.getApprovedTrains().firstOrNull { it.name == trainId } ?: return null
				if (train.distanceToSignalAhead() > STANDING_AT_SIGNAL_TOLERANCE) return null
				keptSignal = train.nextSemaphore() as? DynamicRailSemaphore
			}
			return keptSignal
		}

		/**
		 * Counts a deferral (proceed at the previous step's end, STOP at this step's start) and
		 * records a proceed [start] that no expiry has completed since the last deferral.
		 */
		private fun recordDeferral(start: Signal) {
			if (expired.size > expiredSeen) {
				expiredSeen = expired.size
				deferredSinceLastExpiry = false
			}
			if (start != Signal.STOP && deferredSinceLastExpiry) {
				proceedBeforeExpiryViolations += "proceed ($start) at a step start, no expiry since the last deferral"
			}
			val previousEnd = previousEndAspect
			if (previousEnd != null && previousEnd != Signal.STOP && start == Signal.STOP) {
				deferrals++
				deferredSinceLastExpiry = true
			}
		}

		/** Counts a step on which an input of [trainId] meets the #1244 guard's condition. */
		private fun countGuardStep(trainId: String) {
			if ((loop.getInnerBlockInputs() + loop.getOuterBlockInputs()).any { it.reachesTheGuard(trainId) }) {
				guardConditionSteps++
			}
		}

		/** The guard's condition, with the pick taken through production's [chosenTargetName]. */
		private fun BlockInputObservation.reachesTheGuard(trainId: String): Boolean =
			ownerTrainId == trainId &&
				awaitingRouteExtension &&
				heldRouteEndName != null &&
				chosenTargetName() == heldRouteEndName
	}
}
