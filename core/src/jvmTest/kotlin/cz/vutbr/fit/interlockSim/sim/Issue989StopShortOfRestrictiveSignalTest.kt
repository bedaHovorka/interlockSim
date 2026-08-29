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
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.mp.KoinPlatformTools
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
 *
 * ## Fixture
 *
 * `A —(approach)— Sem —100 m— B`, built here rather than taken from `TestTopologies` for one
 * reason: reserving `A → B` **lights every semaphore on the route**, so a fixture flag alone
 * cannot produce a restrictive aspect. These scenarios reserve the route (which is what lets
 * the train leave `A` at all — [Train.actions] waits for a reservation) and then set the
 * intermediate aspect explicitly. The aspect, not the reservation, is the variable under test.
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

		/** Default sampling period of [TrainSampler], in simulated seconds. */
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

		/** Proceed aspect used when a scenario needs the train to run past the signal. */
		val PROCEED_ASPECT = Signal.S30
	}

	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	/** One observation of the train's kinematic state, taken on the simulation thread. */
	private data class Sample(
		val time: Double,
		val distanceToSemaphore: Double,
		val velocity: Double,
		val totalDistance: Double
	) {
		override fun toString(): String =
			"t=%.3f d=%.6f v=%.6f s=%.6f".format(time, distanceToSemaphore, velocity, totalDistance)
	}

	/**
	 * Polls [train] every [period] simulated seconds until [endTime].
	 *
	 * A kDisco [Process] rather than a listener, because [Train]'s kinematic state must only be
	 * read from the simulation thread.
	 */
	private class TrainSampler(
		private val train: Train,
		private val endTime: Double,
		private val period: Double,
		private val onSample: (Sample) -> Unit
	) : Process() {
		override suspend fun actions() {
			while (time() < endTime) {
				onSample(
					Sample(
						time = time(),
						distanceToSemaphore = train.distanceToSemaphore(),
						velocity = train.getVelocity(),
						totalDistance = train.totalDistance
					)
				)
				hold(period)
			}
		}
	}

	/** The built network plus the dynamic wrapper of its intermediate semaphore. */
	private class Network(
		val context: DefaultSimulationContext,
		val semaphore: DynamicRailSemaphore
	)

	/** Everything a scenario produces for assertion after `run()` returns. */
	private class Run(
		val train: Train,
		val samples: List<Sample>,
		val process: SimpleLinearTrackTestProcess
	) {
		/** Samples taken while the train was within [APPROACH_WINDOW_METRES] of the signal. */
		fun nearSignal(): List<Sample> = samples.filter { it.distanceToSemaphore in 0.0..APPROACH_WINDOW_METRES }

		fun dump(label: String) {
			val near = nearSignal()
			logger.info { "$label: ${samples.size} samples, ${near.size} within $APPROACH_WINDOW_METRES m" }
			near.take(20).forEach { logger.info { "  $it" } }
			logger.info { "  final ${samples.lastOrNull()}" }
		}
	}

	/**
	 * `A —[approachLength]— Sem —100 m— B`.
	 *
	 * Orientations follow `TestContextBuilder`: an entry [InOut] is `orientation = false`, an
	 * exit [InOut] is `orientation = true`, and the semaphore faces the direction of travel with
	 * `orientation = false`.
	 */
	private fun linearNetwork(approachLength: Double = APPROACH_BLOCK_LENGTH): Network {
		val editing = DefaultEditingContext(100, 100)
		val entry = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val semaphore = RailSemaphore("Sem", false, Cell.SpatialType.HORIZONTAL)
		val exit = InOut("B", true, Cell.SpatialType.HORIZONTAL)

		val entryPoint = Point(1, 1)
		val semaphorePoint = Point(3, 3)
		val exitPoint = Point(5, 5)
		editing.putCell(entryPoint, entry)
		editing.putCell(semaphorePoint, semaphore)
		editing.putCell(exitPoint, exit)
		editing.joinCells(entryPoint, semaphorePoint, SimpleTrackBlock(entry, semaphore, approachLength, 80.0))
		editing.joinCells(
			semaphorePoint,
			exitPoint,
			SimpleTrackBlock(semaphore, exit, APPROACH_BLOCK_LENGTH, 80.0)
		)

		val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
		val ctx = DefaultSimulationContext.fromEditingContext(editing, processFactory)
		ctx.getInOuts()
		context = ctx

		val dynamicSemaphore = ctx.toDynamic(semaphore) as DynamicRailSemaphore
		return Network(ctx, dynamicSemaphore)
	}

	/**
	 * Runs one train `A → B` over [network] with the whole route reserved, sampling throughout.
	 *
	 * @param initialAspect the aspect forced onto the intermediate semaphore immediately after
	 *   the reservation, which would otherwise have lit it.
	 * @param onSample invoked on the simulation thread for every sample, so a scenario can
	 *   change the aspect at a chosen point of the approach.
	 */
	private fun runScenario(
		network: Network,
		endTime: Long,
		initialAspect: Signal = Signal.STOP,
		trainLength: Double = TRAIN_LENGTH,
		samplePeriod: Double = SAMPLE_PERIOD,
		reserveOnlyToSemaphore: Boolean = false,
		onSample: (Train, Sample) -> Unit = { _, _ -> }
	): Run {
		val ctx = network.context
		val inOuts = ctx.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val target = if (reserveOnlyToSemaphore) network.semaphore else b
		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val samples = mutableListOf<Sample>()
		var capturedTrain: Train? = null
		val process =
			SimpleLinearTrackTestProcess(
				ctx,
				endTime = endTime,
				trainSpecs =
					listOf(
						SimpleLinearTrackTestProcess.TrainSpec(
							inName = "A",
							outName = "B",
							inTime = 1.0,
							outTime = endTime.toDouble(),
							length = trainLength
						)
					),
				onTrainCreated = { train ->
					capturedTrain = train
					val reserved = reservationService.reservePath(train.name, a, target)
					check(reserved is PathReservationService.ReservationResult.Success) {
						"reservation A -> $target failed: $reserved"
					}
					network.semaphore.signal = initialAspect
					Process.activate(
						TrainSampler(train, endTime.toDouble(), samplePeriod) { sample ->
							samples += sample
							onSample(train, sample)
						}
					)
				}
			)
		ctx.setMainProcess(process)
		ctx.run()

		return Run(
			train = requireNotNull(capturedTrain) { "onTrainCreated was never called" },
			samples = samples.toList(),
			process = process
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
		val run = runScenario(linearNetwork(), HELD_END_TIME)
		run.dump("T1")

		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		val last = run.samples.last()

		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertThat(last.distanceToSemaphore, name = "final distance to the signal")
			.isGreaterThanOrEqualTo(clearance - POSITION_TOLERANCE)
		assertThat(last.totalDistance, name = "final distance travelled")
			.isBetween(
				APPROACH_BLOCK_LENGTH - clearance - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH - clearance + POSITION_TOLERANCE
			)
	}

	// ── T2 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T2: the train brakes to that stand instead of being snapped to zero on it")
	fun trainBrakesToTheStandInsteadOfSnappingToZero() {
		val run = runScenario(linearNetwork(), HELD_END_TIME)
		run.dump("T2")

		// The slowest the train was still seen moving on its approach, and where that was.
		val slowestApproach = run.nearSignal().filter { it.velocity > 0.0 }.minByOrNull { it.velocity }

		assertThat(slowestApproach, name = "slowest moving sample near the signal").isNotNull()
		val crawl = requireNotNull(slowestApproach)
		logger.info { "T2 slowest approach sample: $crawl" }

		// (a) It braked: the train was crawling before it stopped, not running.
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
		val network = linearNetwork()
		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		var travelledAtStand = -1.0
		val run =
			runScenario(network, RUNNING_END_TIME) { _, sample ->
				if (travelledAtStand < 0.0 &&
					network.semaphore.signal == Signal.STOP &&
					sample.velocity == 0.0 &&
					sample.totalDistance > 0.0
				) {
					travelledAtStand = sample.totalDistance
					network.semaphore.signal = PROCEED_ASPECT
				}
			}
		run.dump("T3")

		// Distance travelled, not `distanceToSemaphore`: while the front is parked *on* a
		// separator `pathToSemaphore` still describes the leg it has just finished, so the
		// reading is stale there and would make this assertion vacuous.
		assertThat(travelledAtStand, name = "distance travelled while held")
			.isBetween(
				APPROACH_BLOCK_LENGTH - clearance - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH - clearance + POSITION_TOLERANCE
			)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
		assertThat(run.train.totalDistance, name = "distance travelled").isGreaterThan(APPROACH_BLOCK_LENGTH)
	}

	// ── T4 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T4: an aspect clearing mid-approach leaves no train parked at the clearance line")
	fun aspectClearingDuringTheApproachLeavesNoTrainParked() {
		val network = linearNetwork()
		var cleared = false
		val run =
			runScenario(network, RUNNING_END_TIME) { _, sample ->
				if (!cleared &&
					network.semaphore.signal == Signal.STOP &&
					sample.distanceToSemaphore in 2.0..20.0
				) {
					cleared = true
					network.semaphore.signal = PROCEED_ASPECT
				}
			}
		run.dump("T4")

		assertThat(cleared, name = "aspect was cleared during the approach").isEqualTo(true)
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
		val network = linearNetwork()
		var flipped = false
		val run =
			runScenario(
				network,
				HELD_END_TIME,
				initialAspect = PROCEED_ASPECT,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample ->
				if (!flipped &&
					network.semaphore.signal.isAllowing() &&
					sample.distanceToSemaphore in 1.5..3.0
				) {
					flipped = true
					network.semaphore.signal = Signal.STOP
				}
			}
		run.dump("T5")

		assertThat(flipped, name = "aspect turned restrictive on the approach").isEqualTo(true)
		val clearance = Train.SEMAPHORE_STOP_CLEARANCE_METERS
		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertThat(last.totalDistance, name = "final distance travelled")
			.isBetween(
				APPROACH_BLOCK_LENGTH - clearance - POSITION_TOLERANCE,
				APPROACH_BLOCK_LENGTH - clearance + POSITION_TOLERANCE
			)
	}

	// ── T6 ────────────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("T6: an aspect turning restrictive inside the clearance stops without overrun or reversal")
	fun aspectTurningRestrictiveInsideTheClearanceStopsWithoutOverrunOrReversal() {
		val network = linearNetwork()
		var flipped = false
		val run =
			runScenario(
				network,
				HELD_END_TIME,
				initialAspect = PROCEED_ASPECT,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample ->
				if (!flipped &&
					network.semaphore.signal.isAllowing() &&
					sample.distanceToSemaphore in 0.05..0.6
				) {
					flipped = true
					network.semaphore.signal = Signal.STOP
				}
			}
		run.dump("T6")

		assertThat(flipped, name = "aspect turned restrictive inside the clearance").isEqualTo(true)
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
				linearNetwork(approachLength = MIN_TRACK_LENGTH),
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
		assertThat(last.totalDistance, name = "final distance travelled")
			.isBetween(
				MIN_TRACK_LENGTH - clearance - POSITION_TOLERANCE,
				MIN_TRACK_LENGTH - clearance + POSITION_TOLERANCE
			)
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
				linearNetwork(),
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
}
