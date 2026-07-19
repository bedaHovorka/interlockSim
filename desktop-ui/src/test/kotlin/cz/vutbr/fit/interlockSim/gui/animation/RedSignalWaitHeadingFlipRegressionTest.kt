/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test (#719): the rendered nose heading must not flip 180° while a train
 * waits before a RED signal, nor when its front reaches the destination InOut.
 *
 * Root cause: after the front crosses a separator, `Train.Site.actions()` advances
 * `entrySeparator` to that separator. When there is no upcoming section to advance
 * `frontSection` to — the next path is not reserved yet (RED signal / ownership
 * conflict) or the separator is the destination InOut — the pair goes stale:
 * `frontSection` keeps reporting the just-traversed section while `entrySeparator`
 * points at its *exit* end. The raw authoritative heading then reverses 180°, which
 * made a waiting train render *beyond* the semaphore (looking like a collision with
 * the opposing train on the switch) and made an arriving train flip its nose.
 *
 * The canvas-side fix routes headings through [TrainHeadingResolver], which suppresses
 * a 180° flip while the front is stationary (both stale states freeze the front at the
 * crossed separator) and accepts it only when the train actually moves (genuine
 * reversal).
 *
 * Drives the three-train shunting-loop spec (same as the `shuntingLoop` example used
 * by `runExampleGui`) on `vyhybna.xml`, where opposing trains force RED-signal waits,
 * samples the RESOLVED heading at every train/block event, and asserts no >90° flip
 * ever occurs — including waits before RED signals and arrivals at destination InOuts.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
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

class RedSignalWaitHeadingFlipRegressionTest : KoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var calculator: TrainPositionCalculator
	private val resolver = TrainHeadingResolver()
	private val prevResolvedHeading = mutableMapOf<Int, Double>()
	private val resolvedFlips = mutableListOf<String>()
	private val rawFlipsObserved = mutableListOf<String>()
	private val prevRawHeading = mutableMapOf<Int, Double>()

	private fun sample(loop: MultiTrainLoop) {
		for (train in loop.getApprovedTrains()) {
			val trainNumber = train.getNumber()
			val section = train.frontSection ?: continue
			val rawHeading = calculator.calculateTrainHeadingRadians(train, section) ?: continue
			val location =
				calculator.calculateTrainGridLocation(train, section, train.frontPosition)
					?: continue

			// Record raw flips only as evidence that the stale boundary state actually
			// occurred during this run (the scenario would be vacuous otherwise).
			val prevRaw = prevRawHeading[trainNumber]
			if (prevRaw != null && abs(normalizeAngleDiff(rawHeading - prevRaw)) > Math.PI / 2.0) {
				rawFlipsObserved.add(
					"raw train#$trainNumber ${deg(prevRaw)} -> ${deg(rawHeading)} " +
						"entrySep=${entryLabel(train.trainEntrySeparator)}"
				)
			}
			prevRawHeading[trainNumber] = rawHeading

			// The RESOLVED heading (what the renderer draws) must never flip.
			val resolved = resolver.resolveHeading(trainNumber, rawHeading, location)
			val prev = prevResolvedHeading[trainNumber]
			if (prev != null) {
				val delta = normalizeAngleDiff(resolved - prev)
				if (abs(delta) > Math.PI / 2.0) {
					resolvedFlips.add(
						"RESOLVED_FLIP train#$trainNumber ${deg(prev)} -> ${deg(resolved)} " +
							"(delta ${deg(delta)}); raw=${deg(rawHeading)}; " +
							"entrySep=${entryLabel(train.trainEntrySeparator)}"
					)
				}
			}
			prevResolvedHeading[trainNumber] = resolved
		}
	}

	private fun entryLabel(entry: cz.vutbr.fit.interlockSim.objects.core.PathSeparator?): String {
		if (entry == null) return "null"
		val name = (DynamicWrapperUtils.unwrapToStatic(entry) as? NodeCell)?.getName()
		return name ?: "?"
	}

	private fun deg(rad: Double): String = "${Math.toDegrees(rad).toInt()}°"

	private fun normalizeAngleDiff(d: Double): Double {
		var x = d
		while (x > Math.PI) x -= 2.0 * Math.PI
		while (x < -Math.PI) x += 2.0 * Math.PI
		return x
	}

	@Test
	fun `resolved heading never flips during RED signal waits and arrivals`() {
		val xmlFactory = XMLContextFactory()
		val resourcePath = javaClass.getResource("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
		checkNotNull(resourcePath) { "vyhybna.xml not on classpath" }
		val editingContext = xmlFactory.createContext(File(resourcePath!!.path)) as EditingContext
		val context = ContextTransformer.createSimulationContext(editingContext, processFactory)
		val simContext = context as DefaultSimulationContext
		calculator = TrainPositionCalculator(context, simContext.getSeparatorPositionCache())

		// Same spec as the `shuntingLoop` example (runExampleGui): the opposing B→A train
		// and the follower A→B train force RED-signal waits at the switch semaphores.
		val loop =
			MultiTrainLoop(
				context = context,
				endTime = 600L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "B", outName = "A", inTime = 1.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 2.0, length = 40.0)
					)
			)
		simContext.setMainProcess(loop)

		val reportListener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName != ReportType.TRAIN_EVENTS.name) return@ContextPropertyChangeListener
				sample(loop)
			}
		val blockListener =
			object : BlockEventListener {
				override fun onBlockEvent(event: BlockEvent) {
					sample(loop)
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

		if (resolvedFlips.isNotEmpty()) {
			resolvedFlips.forEach { println("HEADING_FLIP: $it") }
		}
		// Sanity: the stale boundary state (raw 180° flip) must actually occur in this
		// scenario, otherwise the regression is not being exercised.
		assertThat(rawFlipsObserved).isNotEmpty()
		assertThat(resolvedFlips).isEmpty()
	}
}
