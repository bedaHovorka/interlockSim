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
 *
 * The sampling itself is guarded too (#789): [HeadingFlipSampler] owns the per-train
 * skip contract — a train that yields no sample must never abort the frame for the
 * trains after it.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.ApprovesTrains
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.deg
import cz.vutbr.fit.interlockSim.testutil.normalizeAngleDiff
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import cz.vutbr.fit.interlockSim.util.PointF
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.koin.test.inject
import kotlin.math.abs

class StraightRunHeadingFlipRegressionTest : KoinTestBase() {
	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var sampler: HeadingFlipSampler

	@Test
	fun `straight A to B run has no spurious mid-journey heading flips`() {
		val context = startSamplerContext()

		val destinationName = "B"
		val loop =
			MultiTrainLoop(
				context = context,
				endTime = 400L,
				trainSpecs = listOf(MultiTrainLoop.TrainSpec(inName = "A", outName = destinationName, inTime = 0.0, length = 40.0))
			)
		context.setMainProcess(loop)

		runAndSample(context, loop, destinationName)

		sampler.flips.forEach { println("HEADING_FLIP: $it") }
		sampler.resolvedFlips.forEach { println("HEADING_FLIP: $it") }
		assertThat(sampler.flips).isEmpty()
		assertThat(sampler.resolvedFlips).isEmpty()
	}

	/**
	 * Frame independence (#789): a train that yields no sample must skip only itself.
	 *
	 * A train is added to `approvedTrains` when it is dispatched, but its `frontSection` stays
	 * `null` until it actually starts moving, so a real multi-train run always has frames that
	 * mix not-yet-started trains with a train already under way.
	 *
	 * The frame is ordered null-section-first on purpose. `getApprovedTrains()` returns trains in
	 * approval order, which is also start order, so a not-yet-started train would otherwise always
	 * trail the running one and could never suppress it. `sample()` must not depend on that order.
	 *
	 * `sample()` is invoked only for such mixed frames, so every recorded sample proves that a
	 * train behind a null-section train was still sampled. With a `return` on the null guards the
	 * maps stay empty.
	 *
	 * The loop is [ThreeTrainLoop]'s canonical three-train scenario — the same spec
	 * `RedSignalWaitHeadingFlipRegressionTest` drives.
	 *
	 * Heading flips are not asserted here — the three-train spec has mixed destinations, which the
	 * single `destinationName` arrival exclusion does not model. This test asserts frame
	 * independence only; the flip assertions live in the straight A→B test above.
	 */
	@Test
	fun `a train without a section does not suppress the trains after it in the same frame`() {
		val context = startSamplerContext()

		val destinationName = "B"
		val loop = ThreeTrainLoop(context = context, endTime = 600L)
		context.setMainProcess(loop)

		val nullSectionFirst =
			ApprovesTrains {
				loop.getApprovedTrains().sortedBy { if (it.frontSection == null) 0 else 1 }
			}

		val mixedFrames =
			runAndSample(context, nullSectionFirst, destinationName) { trains ->
				trains.any { it.frontSection == null } && trains.any { it.frontSection != null }
			}

		// Sanity: the mixed frame must actually occur, otherwise the scenario is vacuous.
		assertThat(mixedFrames, "mixed frames").isGreaterThan(0)
		assertThat(sampler.resolvedSampledTrainNumbers, "resolved samples behind a null-section train").isNotEmpty()
		assertThat(sampler.rawSampledTrainNumbers, "raw samples behind a null-section train").isNotEmpty()
	}

	/**
	 * #789, heading guard: a train whose heading cannot be resolved must skip only itself.
	 *
	 * The sim-level test cannot reach this guard — on `vyhybna.xml` a train with a non-null
	 * `frontSection` always has a resolvable heading — so the frame is hand-built: the guard
	 * train first, the sampleable train second. Reverting the guard's `continue` to `return`
	 * must make this test fail.
	 */
	@Test
	fun `an unresolvable heading skips only that train`() {
		val calculator = mockk<TrainPositionCalculator>()
		val sampler = HeadingFlipSampler(calculator)
		val destinationName = "B"

		sampler.sample(
			listOf(unresolvableHeadingTrain(1, calculator), sampleableTrain(2, calculator)),
			destinationName
		)

		assertThat(sampler.rawSampledTrainNumbers).contains(2)
		assertThat(sampler.resolvedSampledTrainNumbers).contains(2)
	}

	/**
	 * #789, arrived-set guard: a train already recorded as arrived must skip only itself.
	 *
	 * The first frame makes the guard train arrive at the destination, which puts it into the
	 * sampler's arrival-exclusion set. The second frame puts that arrived train in front of a
	 * sampleable one. Reverting the guard's `continue` to `return` must make this test fail.
	 *
	 * #788 (PR #1015) deletes the arrival-exclusion block this guard lives in — delete this
	 * test together with it.
	 */
	@Test
	fun `an already arrived train does not suppress the trains after it`() {
		val calculator = mockk<TrainPositionCalculator>()
		val sampler = HeadingFlipSampler(calculator)
		val destinationName = "B"
		val guardTrain = arrivingTrain(1, destinationName, calculator)

		// Frame 1: the guard train's front reaches the destination InOut — its RAW arrival
		// sample is excluded (the raw flip there is expected) and the train is marked arrived.
		sampler.sample(listOf(guardTrain), destinationName)
		assertThat(sampler.rawSampledTrainNumbers).doesNotContain(1)

		// Frame 2: the already-arrived guard train must not suppress the train after it.
		sampler.sample(listOf(guardTrain, sampleableTrain(2, calculator)), destinationName)

		assertThat(sampler.rawSampledTrainNumbers).contains(2)
	}

	/**
	 * #789, destination-arrival guard: a train whose front has reached the destination InOut
	 * must skip only itself.
	 *
	 * One hand-built frame: the arriving train first, a sampleable train second. The arriving
	 * train still produces a RESOLVED sample (the renderer keeps its heading, see #719) but its
	 * RAW sample is excluded. Reverting the exclusion's `continue` to `return` must make this
	 * test fail.
	 *
	 * #788 (PR #1015) deletes the arrival-exclusion block this guard lives in — delete this
	 * test together with it.
	 */
	@Test
	fun `a train arriving at the destination does not suppress the trains after it`() {
		val calculator = mockk<TrainPositionCalculator>()
		val sampler = HeadingFlipSampler(calculator)
		val destinationName = "B"

		sampler.sample(
			listOf(arrivingTrain(1, destinationName, calculator), sampleableTrain(2, calculator)),
			destinationName
		)

		assertThat(sampler.rawSampledTrainNumbers, "train behind a destination-arrival train").contains(2)
		assertThat(sampler.rawSampledTrainNumbers).doesNotContain(1)
		assertThat(sampler.resolvedSampledTrainNumbers).contains(1)
	}

	/**
	 * A train that can be sampled end to end: non-null section, resolvable heading, entry
	 * separator away from the destination, resolvable grid location. The guard-train builders
	 * below derive from it and override exactly what makes them skip.
	 */
	private fun sampleableTrain(
		trainNumber: Int,
		calculator: TrainPositionCalculator
	): Train {
		val train = mockk<Train>(relaxed = true)
		val section = mockk<TrackSection>()
		every { train.trainNumber } returns trainNumber
		every { train.frontSection } returns section
		every { train.trainEntrySeparator } returns null
		every { calculator.calculateTrainHeadingRadians(train, section) } returns 0.0
		every { calculator.calculateTrainGridLocation(train, section, train.frontPosition) } returns PointF(1f, 1f)
		return train
	}

	/** A train with a non-null section whose heading the calculator cannot resolve. */
	private fun unresolvableHeadingTrain(
		trainNumber: Int,
		calculator: TrainPositionCalculator
	): Train =
		sampleableTrain(trainNumber, calculator).also {
			every { calculator.calculateTrainHeadingRadians(it, it.frontSection) } returns null
		}

	/**
	 * A train whose entry separator is the destination InOut — the state a train is in when its
	 * front has reached the destination and cannot advance further.
	 */
	private fun arrivingTrain(
		trainNumber: Int,
		destinationName: String,
		calculator: TrainPositionCalculator
	): Train {
		val train = sampleableTrain(trainNumber, calculator)
		val destinationInOut = mockk<InOut>()
		val entryWrapper = mockk<DynamicInOut>()
		every { train.trainEntrySeparator } returns entryWrapper
		every { entryWrapper.staticRef } returns destinationInOut
		every { destinationInOut.getName() } returns destinationName
		return train
	}

	/**
	 * Build the shunting-loop context for a sim-level test, register it for the base class's
	 * automatic cleanup, and point [sampler] at it. The mock-only unit tests need no context and
	 * never call this.
	 */
	private fun startSamplerContext(): DefaultSimulationContext {
		val context =
			TestFixtures.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)
		testContext = context
		sampler = HeadingFlipSampler(TrainPositionCalculator(context, context.separatorPositionCache))
		return context
	}

	/**
	 * Wire the two sampling listeners, run the simulation, and sample every frame that passes
	 * [frameFilter]. Returns the number of frames the filter accepted — the mixed-frame test
	 * uses that count as its vacuity guard.
	 */
	private fun runAndSample(
		context: SimulationContext,
		trainSource: ApprovesTrains,
		destinationName: String,
		frameFilter: (List<Train>) -> Boolean = { true }
	): Int {
		var acceptedFrames = 0

		fun sampleFrame() {
			val trains = trainSource.getApprovedTrains()
			if (frameFilter(trains)) {
				acceptedFrames++
				sampler.sample(trains, destinationName)
			}
		}

		val reportListener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName != ReportType.TRAIN_EVENTS.name) return@ContextPropertyChangeListener
				sampleFrame()
			}
		val blockListener =
			object : BlockEventListener {
				override fun onBlockEvent(event: BlockEvent) {
					sampleFrame()
				}
			}

		context.addPropertyChangeListener(reportListener)
		context.addBlockEventListener(blockListener)
		try {
			context.run()
		} finally {
			context.removePropertyChangeListener(reportListener)
			context.removeBlockEventListener(blockListener)
		}
		return acceptedFrames
	}
}

/**
 * Per-train heading sampler behind the regression tests in this file.
 *
 * Holds all sampling state (previous headings, the arrival-exclusion set, the recorded flips)
 * so the test class itself stays stateless and the #789 skip contract has one home.
 *
 * A train that cannot be sampled (no section, no resolvable heading, already arrived) must
 * skip **only itself**: `continue`, never `return`. A `return` inside the loop would abort the
 * whole frame and silently drop every train after the skipped one, which can hide a real
 * heading flip (#789). The sim-level tests pin this through real `MultiTrainLoop` frames; the
 * unit tests pin each guard with a hand-built frame, because `vyhybna.xml` serialises trains
 * and never produces frames that exercise the other guards.
 *
 * The test harness (`runAndSample`) takes an [ApprovesTrains] rather than the loop itself so a
 * test can control the exact train list — and its order — of a single frame.
 */
private class HeadingFlipSampler(
	private val calculator: TrainPositionCalculator
) {
	private val resolver = TrainHeadingResolver()
	private val prevHeading = mutableMapOf<Int, Double>()
	private val prevResolvedHeading = mutableMapOf<Int, Double>()
	private val arrived = mutableSetOf<Int>()

	/** RAW heading flips observed, formatted for diagnostics. */
	val flips = mutableListOf<String>()

	/** RESOLVED heading flips observed, formatted for diagnostics. */
	val resolvedFlips = mutableListOf<String>()

	/** Train numbers that produced a RAW sample — a skipped train is absent. */
	val rawSampledTrainNumbers: Set<Int>
		get() = prevHeading.keys

	/** Train numbers that produced a RESOLVED sample. */
	val resolvedSampledTrainNumbers: Set<Int>
		get() = prevResolvedHeading.keys

	/**
	 * Sample exactly [trains] — one frame's train list, snapshotted once by the caller so the
	 * sampler and the frame filter can never disagree about what the frame contained.
	 */
	fun sample(
		trains: List<Train>,
		destinationName: String
	) {
		for (train in trains) {
			val trainNumber = train.trainNumber
			val section = train.frontSection ?: continue
			val heading = calculator.calculateTrainHeadingRadians(train, section) ?: continue
			val entry = train.trainEntrySeparator
			val entryName = (DynamicWrapperUtils.unwrapToStatic(entry) as? NodeCell)?.getName()

			// The RESOLVED heading (what the renderer draws) must never flip — including
			// the arrival sample at the destination InOut (#719 canvas-side fix).
			sampleResolved(train, section, heading)

			if (trainNumber in arrived) continue
			// The front has reached the destination InOut: the last section can't be
			// advanced past the InOut, so the RAW arrival sample is excluded — the raw
			// flip there is expected and corrected canvas-side by TrainHeadingResolver.
			if (entryName == destinationName) {
				arrived.add(trainNumber)
				continue
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

	/**
	 * Sample the resolved heading of a **single** train.
	 *
	 * The `?: return` below is deliberately *not* a `continue` (#789): this helper's whole body
	 * handles one train, so the `return` already skips exactly that train and nothing else — the
	 * caller's loop carries on with the next train. A `continue` would not even compile here.
	 */
	private fun sampleResolved(
		train: Train,
		section: TrackSection,
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
}
