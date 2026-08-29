/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #989 — a train must come to a stand clear of a restrictive signal.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.ClearanceStopRun
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.LinearSemaphoreTopology
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Issue #989 — a train facing a non-allowing aspect must come to a stand a clearance
 * distance **in front of** the signal, never on its path separator.
 *
 * ## Why this matters
 *
 * Before this change [Train]'s block-boundary gate carried the front all the way onto the
 * separator and only then ran `separatorAction` → `semaphoreAction` → `fireStop()`. The train
 * therefore stood *on* the sensor point it had already triggered, inside the block the signal
 * protects. Its only way out was the `allowingSignal` condition; any gap in that condition
 * parked it forever, because a train that has already crossed the sensor point cannot be
 * re-detected there.
 *
 * ## The ladder
 *
 * - [clearanceConstantIsSaneForEveryLegalBlock] — the constant itself.
 * - [trainStandsShortOfARestrictiveSignal] — the stop *position*.
 * - [trainBrakesToTheStandInsteadOfSnappingToZero] — the stand is reached by the braking law,
 *   not by an instant `fireStop()` applied one metre earlier. This is the rung that separates
 *   "brake to a stand" from "teleport-stop".
 * - [trainCoversTheClearanceAndPassesTheSignalOnceItClears] — restart.
 * - [aspectClearingDuringTheApproachLeavesNoTrainParked] — the displaced-#797 hazard.
 * - [aspectTurningRestrictiveOnApproachStillStopsTheTrainShort] — late flip, outside the
 *   clearance.
 * - [aspectTurningRestrictiveInsideTheClearanceStopsWithoutOverrunOrReversal] — late flip,
 *   inside the clearance: best effort, never a reversal and never an overrun.
 * - [clearanceHoldsOnTheShortestLegalBlock] — a [MIN_TRACK_LENGTH] block.
 * - [trainWithoutAnOnwardRouteIsNotHeldShortOfTheSignal] — the clearance must never displace
 *   the loop's own bounded diagnosis.
 * - [routeReleasedWhileHeldReachesTheSeparatorNotTheStopLine] — a route reclaimed while the
 *   train is held must waive the stop and hand the train to the separator, never park it.
 * - [aspectFlickerBackToAllowingLeavesNoStandAndFullRecovery] — a restrictive flicker that
 *   clears again before the stop line: no stand in front of the now-allowing signal, and
 *   full speed recovery afterwards.
 *
 * ## Fixture
 *
 * `A —(approach)— Sem —100 m— B`, taken from [TestTopologies.linearPathWithSemaphoreNetwork]
 * because the network variant also hands back the intermediate semaphore's dynamic wrapper,
 * which these scenarios must flip. Reserving `A → B` **lights every semaphore on the route**,
 * so a fixture flag alone cannot produce a restrictive aspect. These scenarios reserve the
 * route (which is what lets the train leave `A` at all — [Train.actions] waits for a
 * reservation) and then set the intermediate aspect explicitly. The aspect, not the
 * reservation, is the variable under test.
 *
 * @see Issue797StoppedAtAllowingSignalTest for the complementary invariant: no train may
 *   stand still in front of an *allowing* signal.
 */
@Tag("integration-test")
@DisplayName("Issue #989 — a train stands clear of a restrictive signal")
class Issue989StopShortOfRestrictiveSignalTest : KoinTestBase() {
	private companion object {
		/** Length of the approach block `A → Sem` in the default fixture. */
		const val APPROACH_BLOCK_LENGTH = 100.0

		/** Simulation end time for a scenario that never releases the train. */
		const val HELD_END_TIME = 40L

		/** Simulation end time for a scenario in which the train completes its journey. */
		const val RUNNING_END_TIME = 120L

		/** Default sampling period of [TrainKinematicSampler], in simulated seconds. */
		const val SAMPLE_PERIOD = 0.05

		/**
		 * Fine sampling period for the two late-flip scenarios, whose trigger window is a
		 * fraction of a metre wide while the train is running at a proceed aspect's speed.
		 */
		const val FINE_SAMPLE_PERIOD = 0.005

		/**
		 * `maxAbsError` applied by [Generator.startAction], which this coordinator's generator
		 * inherits. Position assertions are made against this tolerance.
		 */
		const val POSITION_TOLERANCE = 1e-2

		/** Train length used by every scenario on the 100 m approach block. */
		const val TRAIN_LENGTH = 20.0

		/**
		 * Window around the signal within which the approach speed is inspected. Wide enough
		 * to contain the whole final crawl, narrow enough to exclude the departure ramp.
		 */
		const val APPROACH_WINDOW_METRES = 10.0

		/**
		 * Upper bound on the slowest speed the train may still be doing on its approach.
		 *
		 * A train braked to a stand by the physics is crawling well below this by the time it
		 * gets there — the braking law `v = C·√s` drives `v → 0` as the target is reached. A
		 * train snapped to zero by `fireStop()` one metre early is still doing `C·√1` ≈ 1.6 m/s
		 * at that moment, because the law was still aimed at the separator. This constant is
		 * what separates the two.
		 */
		const val CRAWL_SPEED_MPS = 0.5

		/**
		 * Lower bound on the speed a train must be running at after a restrictive aspect
		 * flickered back to allowing mid-approach.
		 *
		 * The crawl the braking law leaves at a stop line stays at or below [CRAWL_SPEED_MPS];
		 * a healthy pass-through runs on towards the proceed aspect's speed (S30 ≈ 8.3 m/s), so
		 * a peak below this bound means the flicker left a residue: a stand the gate should not
		 * have taken, or a crawl it should not have kept.
		 */
		const val FLICKER_RECOVERY_SPEED_MPS = 2.0

		/** Proceed aspect used when a scenario needs the train to run past the signal. */
		val PROCEED_ASPECT = Signal.S30
	}

	/** Samples taken while the train was within [APPROACH_WINDOW_METRES] of the signal. */
	private fun ClearanceStopRun.nearSignal(): List<TrainKinematicSample> =
		samples.filter { it.distanceToSemaphore in 0.0..APPROACH_WINDOW_METRES }

	private fun ClearanceStopRun.dump(label: String) {
		val near = nearSignal()
		logger.info { "$label: ${samples.size} samples, ${near.size} within $APPROACH_WINDOW_METRES m" }
		near.take(20).forEach { logger.info { "  $it" } }
		logger.info { "  final ${samples.lastOrNull()}" }
	}

	/**
	 * Runs one train `A → B` over [network] with the whole route reserved, sampling throughout —
	 * the shared [runClearanceStopScenario] chain, with the fixture's semaphore and this class's
	 * scenario knobs plugged in. The wrapper exists to own the `tracked()` registration (the
	 * protected extension the shared runner cannot call) and the single-semaphore default.
	 *
	 * @param initialAspect the aspect forced onto the intermediate semaphore immediately after
	 *   the reservation, which would otherwise have lit it.
	 * @param onReserved invoked with the freshly reserved blocks, in path order, before the
	 *   first sample.
	 * @param onSample invoked on the simulation thread for every sample, so a scenario can
	 *   change the aspect at a chosen point of the approach.
	 */
	private fun runScenario(
		network: LinearSemaphoreTopology,
		endTime: Long,
		initialAspect: Signal = Signal.STOP,
		trainLength: Double = TRAIN_LENGTH,
		samplePeriod: Double = SAMPLE_PERIOD,
		reserveOnlyToSemaphore: Boolean = false,
		onReserved: (List<DynamicTrackBlock>) -> Unit = {},
		onSample: (Train, TrainKinematicSample) -> Unit = { _, _ -> }
	): ClearanceStopRun {
		val ctx = network.context.tracked()
		return runClearanceStopScenario(
			ctx,
			semaphores = listOf(network.semaphore),
			endTime = endTime,
			initialAspect = initialAspect,
			trainLength = trainLength,
			samplePeriod = samplePeriod,
			reserveOnlyToSemaphore = reserveOnlyToSemaphore,
			onReserved = onReserved,
			onSample = onSample
		)
	}

	// ── T0 ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("T0: the clearance is at least a metre and fits inside the shortest legal block")
	fun clearanceConstantIsSaneForEveryLegalBlock() {
		assertThat(Train.SEMAPHORE_STOP_CLEARANCE_METERS, name = "clearance").isGreaterThanOrEqualTo(1.0)
		// A clearance at or beyond the shortest legal block could push the stop point behind the
		// block's entry separator, which is not a place a train can stand.
		assertThat(Train.SEMAPHORE_STOP_CLEARANCE_METERS, name = "clearance").isLessThan(MIN_TRACK_LENGTH)
	}

	// ── T1 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T1: a train held by a restrictive aspect stands a clearance short of it")
	fun trainStandsShortOfARestrictiveSignal() {
		val run = runScenario(TestTopologies.linearPathWithSemaphoreNetwork(), HELD_END_TIME)
		run.dump("T1")

		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		val last = run.samples.last()

		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertThat(last.distanceToSemaphore, name = "final distance to the signal")
			.isGreaterThanOrEqualTo(clearance - POSITION_TOLERANCE)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}

	// ── T2 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T2: the train brakes to that stand instead of being snapped to zero on it")
	fun trainBrakesToTheStandInsteadOfSnappingToZero() {
		val run = runScenario(TestTopologies.linearPathWithSemaphoreNetwork(), HELD_END_TIME)
		run.dump("T2")

		// The slowest the train was still seen moving on its approach, and where that was.
		val slowestApproach = run.nearSignal().filter { it.velocity > 0.0 }.minByOrNull { it.velocity }

		assertThat(slowestApproach, name = "slowest moving sample near the signal").isNotNull()
		val crawl = requireNotNull(slowestApproach)
		logger.info { "T2 slowest approach sample: $crawl" }

		// (a) It braked: the train was crawling before it stopped, not running. Scope: this
		//     fixture's aspect stands restrictive from departure, so the motor aimed at the
		//     stop line and braked the whole approach. A restrictive flip arriving mid-leg
		//     re-commands nothing (the motor is commanded once per leg), so that stand still
		//     snaps to zero from line speed — the T5 rung pins where it lands.
		assertThat(crawl.velocity, name = "slowest approach speed").isLessThanOrEqualTo(CRAWL_SPEED_MPS)
		// (b) It braked to the RIGHT point: that crawl happened at the clearance stop line, not
		//     on the separator.
		assertThat(crawl.distanceToSemaphore, name = "distance at the slowest approach sample")
			.isGreaterThanOrEqualTo(Train.SEMAPHORE_STOP_CLEARANCE_METERS - POSITION_TOLERANCE)
	}

	// ── T3 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T3: once the aspect clears, the train covers the clearance and passes the signal")
	fun trainCoversTheClearanceAndPassesTheSignalOnceItClears() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		var travelledAtStand = -1.0
		val clearAtStand =
			AspectFlipOnce(
				network.semaphore,
				PROCEED_ASPECT,
				trigger = {
					network.semaphore.signal == Signal.STOP &&
						it.velocity == 0.0 &&
						it.totalDistance > 0.0
				},
				onFlip = { travelledAtStand = it.totalDistance }
			)
		val run =
			runScenario(network, RUNNING_END_TIME) { _, sample -> clearAtStand.onSample(sample) }
		run.dump("T3")

		// Distance travelled, not `distanceToSemaphore`: while the front is parked *on* a
		// separator `pathToSemaphore` still describes the leg it has just finished, so the
		// reading is stale there and would make this assertion vacuous.
		assertStoodAtClearanceStopLine(travelledAtStand, APPROACH_BLOCK_LENGTH, name = "distance travelled while held")
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
		assertThat(run.train.totalDistance, name = "distance travelled").isGreaterThan(APPROACH_BLOCK_LENGTH)
	}

	// ── T4 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T4: an aspect clearing mid-approach leaves no train parked at the clearance line")
	fun aspectClearingDuringTheApproachLeavesNoTrainParked() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val clearMidApproach =
			AspectFlipOnce(
				network.semaphore,
				PROCEED_ASPECT,
				trigger = {
					network.semaphore.signal == Signal.STOP &&
						it.distanceToSemaphore in 2.0..20.0
				}
			)
		val run =
			runScenario(network, RUNNING_END_TIME) { _, sample -> clearMidApproach.onSample(sample) }
		run.dump("T4")

		assertThat(clearMidApproach.fired, name = "aspect was cleared during the approach").isEqualTo(true)
		// The displaced-#797 hazard: a train that reaches the clearance stop line just as the
		// aspect clears must be restarted, not left parked one metre short of a green signal.
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
		assertThat(run.train.totalDistance, name = "distance travelled").isGreaterThan(APPROACH_BLOCK_LENGTH)
	}

	// ── T5 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T5: an aspect turning restrictive outside the clearance still stops the train short")
	fun aspectTurningRestrictiveOnApproachStillStopsTheTrainShort() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val turnRestrictive =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = {
					network.semaphore.signal.isAllowing() &&
						it.distanceToSemaphore in 1.5..3.0
				}
			)
		val run =
			runScenario(
				network,
				HELD_END_TIME,
				initialAspect = PROCEED_ASPECT,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample -> turnRestrictive.onSample(sample) }
		run.dump("T5")

		assertThat(turnRestrictive.fired, name = "aspect turned restrictive on the approach").isEqualTo(true)
		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}

	// ── T6 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T6: an aspect turning restrictive inside the clearance stops without overrun or reversal")
	fun aspectTurningRestrictiveInsideTheClearanceStopsWithoutOverrunOrReversal() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val turnRestrictiveInside =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = {
					network.semaphore.signal.isAllowing() &&
						it.distanceToSemaphore in 0.05..0.6
				}
			)
		val run =
			runScenario(
				network,
				HELD_END_TIME,
				initialAspect = PROCEED_ASPECT,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample -> turnRestrictiveInside.onSample(sample) }
		run.dump("T6")

		assertThat(turnRestrictiveInside.fired, name = "aspect turned restrictive inside the clearance")
			.isEqualTo(true)
		// The clearance is a best-effort marker, not a guarantee against a late aspect change:
		// the train stops where it is, at or before the separator, and never backs up to reach
		// the stop line it has already passed.
		val last = run.samples.last()
		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		// It stopped between the stop line it had already passed and the separator — never past
		// the separator, and never backed up to the stop line.
		assertThat(last.totalDistance, name = "final distance travelled")
			.isBetween(
				APPROACH_BLOCK_LENGTH - clearance - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH + POSITION_TOLERANCE
			)
		val reversals =
			run.samples.zipWithNext().filter { (previous, next) ->
				next.totalDistance < previous.totalDistance - POSITION_TOLERANCE
			}
		assertThat(reversals.size, name = "samples where the train moved backwards").isEqualTo(0)
	}

	// ── T7 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T7: the clearance holds on the shortest legal block")
	fun clearanceHoldsOnTheShortestLegalBlock() {
		val run =
			runScenario(
				TestTopologies.linearPathWithSemaphoreNetwork(approachLength = MIN_TRACK_LENGTH),
				HELD_END_TIME,
				trainLength = 3.0,
				samplePeriod = FINE_SAMPLE_PERIOD
			)
		run.dump("T7")

		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		val last = run.samples.last()

		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertThat(last.distanceToSemaphore, name = "final distance to the signal")
			.isGreaterThanOrEqualTo(clearance - POSITION_TOLERANCE)
		assertStoodAtClearanceStopLine(last.totalDistance, MIN_TRACK_LENGTH)
	}

	// ── T8 ────────────────────────────────────────────────────────────────────────

	/**
	 * The clearance stop is an **unbounded** wait on the signal aspect. `Train.Site.actions()`
	 * owns two *bounded* policies for a train navigation cannot serve — the mid-journey retry
	 * count and the ownership-conflict horizon — and can only apply either once the front has
	 * reached the separator and re-queried. So a train whose route ahead is not reserved must not
	 * be held short: it has to reach the separator and let the loop diagnose it.
	 *
	 * Here the route is reserved only as far as the semaphore, so the query from the semaphore
	 * yields no onward path. Without the waiver the train stands at the stop line for the rest of
	 * the run and the loop never gets to speak — the displaced-deadlock failure this whole change
	 * risks introducing.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T8: a train with no reserved route past the signal reaches the separator, not the stop line")
	fun trainWithoutAnOnwardRouteIsNotHeldShortOfTheSignal() {
		val run =
			runScenario(
				TestTopologies.linearPathWithSemaphoreNetwork(),
				HELD_END_TIME,
				reserveOnlyToSemaphore = true
			)
		run.dump("T8")

		assertThat(run.train.totalDistance, name = "distance travelled")
			.isBetween(
				APPROACH_BLOCK_LENGTH - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH + POSITION_TOLERANCE
			)
	}

	// ── T9 ────────────────────────────────────────────────────────────────────────

	/**
	 * The hold must watch the route, not just the aspect. Here the dispatcher reclaims the
	 * un-travelled tail while the train stands at the clearance stop line, in the exact
	 * `RegistryPartialRouteReleaser` order — `resetSemaphoresForReleasedBlocks` first, while
	 * `reservedFrom` is still live, then `cancelPathSetup` and `unregisterBlock` — which leaves
	 * the semaphore at STOP with the train still short of it. A hold that waits only on the
	 * aspect parks this train at the stop line forever; the hold must notice the lost route,
	 * waive the clearance stop, and hand the train to the separator. There the semaphore's own
	 * `allowingSignal` wait owns it — the same machinery that owns any train held at a STOP
	 * signal — and in this configuration that wait is unbounded: the loop's bounded retry and
	 * horizon policies engage only after the aspect allows and the loop re-queries, so this
	 * train never reaches an `errorStop`. It stands where the pre-PR code left it: detectable
	 * at the sensor, resumable the moment a dispatcher serves it again.
	 *
	 * The scenario deliberately ends at that handoff. A released tail cannot be re-given to
	 * the same train mid-journey: the registry keeps the stored `PathInfo` for navigation
	 * (Issue #301), so its target still reads "B" while a fresh reservation would start at the
	 * semaphore, and `mergePathInfo`'s Step 0a fail-safe (Issue #834) aborts every such merge.
	 * The correct end state is therefore the train standing *at* the separator — never past it
	 * into the now-unreserved tail — with no exit until a dispatcher serves it again.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T9: a route released while held waives the stop and hands the train to the separator")
	fun routeReleasedWhileHeldReachesTheSeparatorNotTheStopLine() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val reservationService = network.context.getRoutingServices().getPathReservationService()
		var untravelledTail: DynamicTrackBlock? = null
		var released = false
		var separatorStandRecorded = false
		var releaseAt = -1.0
		var separatorStandAt = -1.0
		val run =
			runScenario(
				network,
				RUNNING_END_TIME,
				onReserved = { blocks -> untravelledTail = blocks.last() },
				onSample = { train, sample ->
					val tail = untravelledTail
					if (!released &&
						tail != null &&
						network.semaphore.signal == Signal.STOP &&
						sample.velocity == 0.0 &&
						sample.totalDistance > 0.0
					) {
						released = true
						releaseAt = sample.totalDistance
						// Mirrored here because core test code cannot depend on the dispatcher
						// module: the semaphore reset comes FIRST, while `reservedFrom` is still
						// live, then the block is freed and unregistered.
						reservationService.resetSemaphoresForReleasedBlocks(train.name, listOf(tail))
						tail.cancelPathSetup(
							requireNotNull(tail.reservedFrom) { "released tail lost its reservedFrom" }
						)
						reservationService.unregisterBlock(train.name, tail)
					}
					// Past the stop line and standing again: the separator stand. The clearance
					// is the gap between the two stands, so half of it separates this trigger
					// from the release trigger above. The stand is only recorded: the released
					// tail cannot be re-reserved for this train (see the KDoc above), so from
					// here the separator's `allowingSignal` wait owns the train — unbounded
					// while the aspect stands at STOP, as the KDoc above explains.
					if (released &&
						!separatorStandRecorded &&
						sample.velocity == 0.0 &&
						sample.totalDistance > APPROACH_BLOCK_LENGTH - Train.SEMAPHORE_STOP_CLEARANCE_METERS / 2
					) {
						separatorStandRecorded = true
						separatorStandAt = sample.totalDistance
					}
				}
			)
		run.dump("T9")

		assertStoodAtClearanceStopLine(
			releaseAt,
			APPROACH_BLOCK_LENGTH,
			name = "distance travelled when the tail was released"
		)
		// Not parked at the stop line: the lost route must waive the clearance stop, so the
		// train reaches the separator and stands there under the bounded route-extension wait.
		assertThat(separatorStandAt, name = "distance travelled at the separator stand")
			.isBetween(
				APPROACH_BLOCK_LENGTH - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH + POSITION_TOLERANCE
			)
		// The waived stop must never become an unauthorised entry: with the tail unreserved the
		// front stands at the separator and never crosses it.
		assertThat(
			run.samples.dropWhile { it.totalDistance < releaseAt }.maxOf { it.totalDistance },
			name = "farthest the front reached after the release"
		).isBetween(
			APPROACH_BLOCK_LENGTH - POSITION_TOLERANCE,
			APPROACH_BLOCK_LENGTH + POSITION_TOLERANCE
		)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(0)
	}

	// ── T10 ───────────────────────────────────────────────────────────────────────

	/**
	 * An aspect that flickers — restrictive while the train runs up, allowing again before the
	 * stop line is reached — must behave as if the restrictive instant never stood: the braking
	 * must not finish into a stand in front of the now-allowing signal (the Issue #797
	 * invariant), and the train must come back up to running speed, not coast the rest of the
	 * way at whatever crawl the flicker left it with.
	 *
	 * Scope note: this scenario cannot exercise the kDisco 0.6.1 double-command hazard
	 * (kdisco#73). A mid-leg flip re-commands nothing — the motor is commanded once per leg —
	 * and the aspect clears again before the gate can `fireStop`, so nothing is ever parked
	 * for a command to miss; that guard is the stand-down comment in [Train] plus the
	 * stands-and-resumes of the T3/T4 rungs. What this rung pins is the flicker leaving no
	 * residue: no stand, and full speed after the clear.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T10: an aspect flicker back to allowing leaves no stand and no lost speed")
	fun aspectFlickerBackToAllowingLeavesNoStandAndFullRecovery() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val turnRestrictiveOnTheRunUp =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = {
					network.semaphore.signal.isAllowing() &&
						it.distanceToSemaphore in 10.0..30.0
				}
			)
		var travelledAtClear = -1.0
		val clearBeforeTheStopLine =
			AspectFlipOnce(
				network.semaphore,
				PROCEED_ASPECT,
				trigger = {
					network.semaphore.signal == Signal.STOP &&
						it.distanceToSemaphore in 1.5..5.0
				},
				onFlip = { travelledAtClear = it.totalDistance }
			)
		val run =
			runScenario(
				network,
				RUNNING_END_TIME,
				initialAspect = PROCEED_ASPECT,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample ->
				turnRestrictiveOnTheRunUp.onSample(sample)
				clearBeforeTheStopLine.onSample(sample)
			}
		run.dump("T10")

		assertThat(turnRestrictiveOnTheRunUp.fired, name = "aspect turned restrictive on the run-up")
			.isEqualTo(true)
		assertThat(clearBeforeTheStopLine.fired, name = "aspect cleared again before the stop line")
			.isEqualTo(true)
		// The Issue #797 invariant for this scenario: from the moment the train moves until it
		// crosses the separator it never stands — in particular never in front of the allowing
		// signal the flicker left behind, and never back on the sensor point either.
		val stands =
			run.samples.filter {
				it.velocity == 0.0 &&
					it.totalDistance > 0.0 &&
					it.totalDistance < APPROACH_BLOCK_LENGTH + POSITION_TOLERANCE
			}
		assertThat(stands.size, name = "samples standing in front of the separator").isEqualTo(0)
		// The recovery bound: the peak speed after the clear must leave the crawl regime
		// behind and run on towards the proceed aspect's speed.
		val peakAfterClear =
			run.samples
				.filter { it.totalDistance >= travelledAtClear }
				.maxOf { it.velocity }
		assertThat(peakAfterClear, name = "peak speed after the aspect cleared back")
			.isGreaterThan(FLICKER_RECOVERY_SPEED_MPS)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
	}
}
