/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test: a train on a straight/direct A→B journey must NOT swap its
 * nose heading mid-journey. The authoritative entry→exit heading of `frontSection`
 * must stay consistent with `trainEntrySeparator` at every block/section transition,
 * so the only legitimate heading flip is a real reversal (`Train.reverseDirection()`,
 * which only happens at velocity 0).
 *
 * Root cause this guards against (PR #718): in `Train.Site.actions()`, after the
 * front crossed the exit separator of section X, `entrySeparator` was advanced to
 * that exit (= entry of X+1) while `frontSection` still reported the just-traversed
 * section X — a one-section lag. Sampled at switch crossings (where path reservation
 * suspends), the calculator treated the exit end as the entry end and reversed the
 * heading 180°. Two switches ⇒ two spurious flips on a straight run.
 *
 * The fix advances `frontSection` to the upcoming section atomically with
 * `entrySeparator`, so position AND heading stay consistent.
 *
 * NOTE: at two boundaries the simulation state is *inherently* stale (there is no
 * upcoming TrackSection to advance `frontSection` to): when the front reaches the
 * destination InOut, and when the front waits before a RED signal. There the raw
 * calculator heading still reverses; the animated canvas corrects it via
 * [TrainHeadingResolver] (#719). This test therefore asserts two things:
 * 1. the RAW calculator heading has no mid-journey flips (guards the PR #718 sim fix;
 *    the arrival sample is excluded because the raw flip there is expected), and
 * 2. the RESOLVED heading (what the renderer draws) has no flips at all, including
 *    the arrival samples (guards the #719 canvas fix).
 *
 * Drives `MultiTrainLoop` (one A→B spec) on `vyhybna.xml`, samples the authoritative
 * heading at every block/section transition, and asserts no >90° flip occurs
 * mid-journey.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.deg
import cz.vutbr.fit.interlockSim.testutil.normalizeAngleDiff
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import org.junit.jupiter.api.Test
import org.koin.test.inject
import kotlin.math.abs

class StraightRunHeadingFlipRegressionTest : KoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var calculator: TrainPositionCalculator
	private val resolver = TrainHeadingResolver()
	private val prevHeading = mutableMapOf<Int, Double>()
	private val prevResolvedHeading = mutableMapOf<Int, Double>()
	private val arrived = mutableSetOf<Int>()
	private val flips = mutableListOf<String>()
	private val resolvedFlips = mutableListOf<String>()

	private fun sample(
		loop: MultiTrainLoop,
		destinationName: String
	) {
		for (train in loop.getApprovedTrains()) {
			val trainNumber = train.trainNumber
			val section = train.frontSection ?: return
			val heading = calculator.calculateTrainHeadingRadians(train, section) ?: return
			val entry = train.trainEntrySeparator
			val entryName = (DynamicWrapperUtils.unwrapToStatic(entry) as? NodeCell)?.getName()

			// The RESOLVED heading (what the renderer draws) must never flip — including
			// the arrival sample at the destination InOut (#719 canvas-side fix).
			sampleResolved(train, section, heading)

			if (trainNumber in arrived) return
			// The front has reached the destination InOut: the last section can't be
			// advanced past the InOut, so the RAW arrival sample is excluded — the raw
			// flip there is expected and corrected canvas-side by TrainHeadingResolver.
			if (entryName == destinationName) {
				arrived.add(trainNumber)
				return
			}
			val prev = prevHeading[trainNumber]
			if (prev != null) {
				val delta = normalizeAngleDiff(heading - prev)
				if (abs(delta) > Math.PI / 2.0) {
					val ends = section.ends()
					val end0 = ends.getOrNull(0)
					val end1 = ends.getOrNull(1)
					val entryMatch =
						when {
							entry == null -> "NULL -> fallback DECLARED order"
							sameStatic(entry, end0) -> "matches ends[0] -> DECLARED"
							sameStatic(entry, end1) -> "matches ends[1] -> REVERSED"
							else -> "MATCHES_NEITHER -> fallback DECLARED order"
						}
					val line =
						"FLIP train#$trainNumber ${deg(prev)} -> ${deg(heading)} (delta ${deg(delta)}); " +
							"frontSection ends=[${endLabel(end0)}, ${endLabel(end1)}]; entrySep=${endLabel(entry)} | $entryMatch"
					flips.add(line)
				}
			}
			prevHeading[trainNumber] = heading
		}
	}

	private fun sampleResolved(
		train: Train,
		section: cz.vutbr.fit.interlockSim.objects.tracks.TrackSection,
		rawHeading: Double
	) {
		val trainNumber = train.trainNumber
		val location =
			calculator.calculateTrainGridLocation(train, section, train.frontPosition)
				?: return
		val resolved = resolver.resolveHeading(trainNumber, rawHeading, location)
		val prev = prevResolvedHeading[trainNumber]
		if (prev != null) {
			val delta = normalizeAngleDiff(resolved - prev)
			if (abs(delta) > Math.PI / 2.0) {
				resolvedFlips.add(
					"RESOLVED_FLIP train#$trainNumber ${deg(prev)} -> ${deg(resolved)} (delta ${deg(delta)}); " +
						"raw=${deg(rawHeading)}; entrySep=${endLabel(train.trainEntrySeparator)}"
				)
			}
		}
		prevResolvedHeading[trainNumber] = resolved
	}

	private fun endLabel(end: PathSeparator?): String {
		if (end == null) return "null"
		val name = (DynamicWrapperUtils.unwrapToStatic(end) as? NodeCell)?.getName()
		val grid = calculator.getGridPosition(end)
		return "${name ?: "?"}@${grid ?: "?"}"
	}

	private fun sameStatic(
		a: PathSeparator?,
		b: PathSeparator?
	): Boolean = a != null && b != null && DynamicWrapperUtils.unwrapToStatic(a) === DynamicWrapperUtils.unwrapToStatic(b)

	@Test
	fun `straight A to B run has no spurious mid-journey heading flips`() {
		val context =
			TestFixtures.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)
		val simContext = context
		calculator = TrainPositionCalculator(context, simContext.separatorPositionCache)

		val destinationName = "B"
		val loop =
			MultiTrainLoop(
				context = context,
				endTime = 400L,
				trainSpecs = listOf(MultiTrainLoop.TrainSpec(inName = "A", outName = destinationName, inTime = 0.0, length = 40.0))
			)
		simContext.setMainProcess(loop)

		val reportListener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName != ReportType.TRAIN_EVENTS.name) return@ContextPropertyChangeListener
				sample(loop, destinationName)
			}
		val blockListener =
			object : BlockEventListener {
				override fun onBlockEvent(event: BlockEvent) {
					sample(loop, destinationName)
				}
			}

		simContext.addPropertyChangeListener(reportListener)
		simContext.addBlockEventListener(blockListener)
		try {
			context.run()
		} finally {
			simContext.removePropertyChangeListener(reportListener)
			simContext.removeBlockEventListener(blockListener)
		}

		if (flips.isNotEmpty()) {
			flips.forEach { println("HEADING_FLIP: $it") }
		}
		if (resolvedFlips.isNotEmpty()) {
			resolvedFlips.forEach { println("HEADING_FLIP: $it") }
		}
		assertThat(flips).isEmpty()
		assertThat(resolvedFlips).isEmpty()
	}
}
