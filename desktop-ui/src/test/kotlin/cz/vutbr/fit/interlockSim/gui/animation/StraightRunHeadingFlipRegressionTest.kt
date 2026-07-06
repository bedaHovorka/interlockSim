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
 * NOTE: a single remaining flip when the front reaches the destination InOut (the
 * last section can't be advanced past the InOut) is a known separate edge case
 * tracked in #719; this test excludes the arrival sample.
 *
 * Drives `MultiTrainLoop` (one A→B spec) on `vyhybna.xml`, samples the authoritative
 * heading at every block/section transition, and asserts no >90° flip occurs
 * mid-journey.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File
import kotlin.math.abs

class StraightRunHeadingFlipRegressionTest : KoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var calculator: TrainPositionCalculator
	private val prevHeading = mutableMapOf<Int, Double>()
	private val arrived = mutableSetOf<Int>()
	private val flips = mutableListOf<String>()

	private fun sample(
		loop: MultiTrainLoop,
		destinationName: String
	) {
		for (train in loop.getApprovedTrains()) {
			val trainNumber = train.getNumber()
			if (trainNumber in arrived) return
			val section = train.frontSection ?: return
			val heading = calculator.calculateTrainHeadingRadians(train, section) ?: return
			val entry = train.trainEntrySeparator
			val entryName = (DynamicWrapperUtils.unwrapToStatic(entry) as? NodeCell)?.getName()
			// The front has reached the destination InOut: the last section can't be
			// advanced past the InOut, so the arrival sample is excluded (separate issue).
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

	private fun deg(rad: Double): String = "${Math.toDegrees(rad).toInt()}°"

	private fun normalizeAngleDiff(d: Double): Double {
		var x = d
		while (x > Math.PI) x -= 2.0 * Math.PI
		while (x < -Math.PI) x += 2.0 * Math.PI
		return x
	}

	@Test
	fun `straight A to B run has no spurious mid-journey heading flips`() {
		val xmlFactory = XMLContextFactory()
		val resourcePath = javaClass.getResource("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
		checkNotNull(resourcePath) { "vyhybna.xml not on classpath" }
		val editingContext = xmlFactory.createContext(File(resourcePath!!.path)) as EditingContext
		val context = ContextTransformer.createSimulationContext(editingContext, processFactory)
		val simContext = context as DefaultSimulationContext
		calculator = TrainPositionCalculator(context, simContext.getSeparatorPositionCache())

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
		assertThat(flips).isEmpty()
	}
}
