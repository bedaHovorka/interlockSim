/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #788: the RAW calculator heading must be correct at every
 * boundary, and fixing it must not move the drawn position.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.ArrivalTally
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.deg
import cz.vutbr.fit.interlockSim.testutil.normalizeAngleDiff
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import cz.vutbr.fit.interlockSim.util.PointF
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Regression test for Issue #788 — the **raw** heading returned by
 * [TrainPositionCalculator.calculateTrainHeadingRadians], with no canvas-side correction.
 *
 * [TrainHeadingResolver] is deliberately **not** used here. PR #786 made the *rendered* heading
 * stable by suppressing the flip in the resolver; this test asserts that the authoritative
 * heading the calculator returns is itself correct, which is what every non-GUI consumer sees.
 *
 * ## The two assertions belong together
 *
 * At the boundary the front has crossed out of its section with nothing to enter, and the
 * simulation rebases the front's distance to ~0 at the separator it just crossed. Interpolating
 * from that separator at ratio ~0 draws the train **in the right place** — only the heading is
 * reversed, because the separator is the section's *exit* end. Two errors cancel in the drawn
 * position, so a fix that repairs the heading alone would move the train a whole section back.
 * This test therefore pins the drawn position at the same samples as the heading:
 *
 * 1. no raw heading may flip by more than 90° between consecutive samples of a train, and
 * 2. while `frontSection` keeps reporting the same section, the drawn point may not move by
 *    more than half of that section's on-grid length in one sampling step.
 *
 * The half-section bound is chosen so that normal motion passes (sampling is at least once per
 * simulated second, and the trains need several seconds per section) while the full-section
 * backward jump a heading-only fix would produce fails.
 *
 * @since Issue #788
 */
@DisplayName("Raw calculator heading and drawn position at section boundaries (Issue #788)")
class BoundaryHeadingAndPositionRegressionTest : KoinTestBase() {
	private companion object {
		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 40.0

		/** Half a turn, in radians — a flip larger than this is the reversal being guarded. */
		const val FLIP_THRESHOLD_RADIANS: Double = Math.PI / 2.0

		/** Fraction of a section's on-grid length allowed to be covered in one sampling step. */
		const val MAX_STEP_AS_SECTION_FRACTION: Double = 0.5

		/** Trains that must complete their journey for the boundary state to be exercised. */
		const val MIN_ARRIVALS: Int = 3
	}

	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var calculator: TrainPositionCalculator

	private val previousHeading = mutableMapOf<Int, Double>()
	private val previousSection = mutableMapOf<Int, TrackSection>()
	private val previousLocation = mutableMapOf<Int, PointF>()
	private val headingFlips = mutableListOf<String>()
	private val positionJumps = mutableListOf<String>()

	private val arrivals = ArrivalTally()

	/** On-grid distance between the two ends of [section], or `null` if it is not resolvable. */
	private fun gridLength(section: TrackSection): Double? {
		val ends = section.ends()
		if (ends.size < 2) return null
		val first = calculator.getGridPosition(ends[0]) ?: return null
		val second = calculator.getGridPosition(ends[1]) ?: return null
		return hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
	}

	private fun sample(loop: MultiTrainLoop) {
		for (train in loop.getApprovedTrains()) {
			val trainNumber = train.trainNumber
			val section = train.frontSection ?: continue
			val heading = calculator.calculateTrainHeadingRadians(train, section) ?: continue
			val location = calculator.calculateTrainGridLocation(train, section, train.frontPosition) ?: continue

			previousHeading[trainNumber]?.let { previous ->
				val delta = normalizeAngleDiff(heading - previous)
				if (abs(delta) > FLIP_THRESHOLD_RADIANS) {
					headingFlips.add(
						"RAW_FLIP train#$trainNumber ${deg(previous)} -> ${deg(heading)} " +
							"(delta ${deg(delta)}); entrySep=${separatorLabel(train.trainEntrySeparator)}; " +
							"frontPosition=${train.frontPosition}, sectionLength=${section.length()}"
					)
				}
			}

			val samePreviousSection = previousSection[trainNumber] === section
			val previousPoint = previousLocation[trainNumber]
			val budget = gridLength(section)?.times(MAX_STEP_AS_SECTION_FRACTION)
			if (samePreviousSection && previousPoint != null && budget != null) {
				val step = hypot((location.x - previousPoint.x).toDouble(), (location.y - previousPoint.y).toDouble())
				if (step > budget) {
					positionJumps.add(
						"POSITION_JUMP train#$trainNumber moved $step grid units in one step " +
							"(budget $budget) while still on the same section; " +
							"entrySep=${separatorLabel(train.trainEntrySeparator)}; frontPosition=${train.frontPosition}"
					)
				}
			}

			previousHeading[trainNumber] = heading
			previousSection[trainNumber] = section
			previousLocation[trainNumber] = location
		}
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("the raw heading never reverses and the drawn position never jumps back")
	fun `raw heading and drawn position stay coherent at every boundary`() {
		val context =
			TestFixtures.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)
		testContext = context
		calculator = TrainPositionCalculator(context, context.separatorPositionCache)

		val loop =
			MultiTrainLoop(
				context = context,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec(inName = "B", outName = "A", inTime = 1.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 2.0, length = TRAIN_LENGTH)
					)
			)
		context.setMainProcess(loop)

		// TRAIN_CONTINUOUS ticks once per simulated second per train, so a boundary the
		// train rests at is sampled even when no block transition happens meanwhile.
		runSampled(context, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) { message ->
			message?.let(arrivals::record)
			sample(loop)
		}

		headingFlips.forEach { println(it) }
		positionJumps.forEach { println(it) }

		// Non-vacuity witnesses: both terminal assertions are `isEmpty()`, so without these
		// the test would also pass if no train was ever approved or every sample bailed out at
		// a `?: continue` guard (for example a context regression emptying the separator cache).
		assertThat(
			arrivals.arrivedTrainNumbers.size >= MIN_ARRIVALS,
			name = "trains reached their destination InOut (boundary state exercised)"
		).isTrue()
		assertThat(previousLocation, name = "sampled locations").isNotEmpty()
		assertThat(headingFlips, name = "raw heading reversals").isEmpty()
		assertThat(positionJumps, name = "drawn position jumps within one section").isEmpty()
	}
}
