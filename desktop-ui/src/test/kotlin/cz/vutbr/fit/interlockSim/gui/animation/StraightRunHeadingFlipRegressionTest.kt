/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test: a train on a straight/direct A→B journey must NOT swap its
 * nose heading on the run. The authoritative entry→exit heading of `frontSection`
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
 * Two boundaries have no upcoming TrackSection to advance `frontSection` to: when the
 * front reaches the destination InOut, and when the route beyond the separator is not
 * reserved yet. The published pair used to go stale there and the raw calculator heading
 * reversed; Issue #788 fixed that in `:core`, so the raw heading is now correct at those
 * boundaries too and the arrival sample no longer needs excluding. This test asserts:
 * 1. the RAW calculator heading has no flips anywhere, arrival included (guards the PR #718
 *    mid-journey fix and the #788 boundary fix), and
 * 2. the RESOLVED heading (what the renderer draws) has no flips either, which keeps
 *    [TrainHeadingResolver] honest as the second layer of defence (#719, kept by #788).
 *
 * Drives `MultiTrainLoop` (one A→B spec) on `vyhybna.xml`, samples the authoritative
 * heading at every block/section transition, and asserts no >90° flip occurs anywhere
 * on the journey, arrival included.
 *
 * The sampling itself is guarded too (#789): [HeadingFlipSampler] owns the per-train
 * skip contract — a train that yields no sample must never abort the frame for the
 * trains after it.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.ApprovesTrains
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.testutil.HeadingFlipSampler
import cz.vutbr.fit.interlockSim.testutil.HeadingSamplerTestBase
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.util.PointF
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class StraightRunHeadingFlipRegressionTest : HeadingSamplerTestBase() {
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

		runAndSample(context, loop)

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
	 * Heading flips are not asserted here — the three-train spec has mixed destinations, which
	 * this test does not model closely enough to make flip assertions meaningful. This test
	 * asserts frame independence only; the flip assertions live in the straight A→B test above.
	 */
	@Test
	fun `a train without a section does not suppress the trains after it in the same frame`() {
		val context = startSamplerContext()

		val loop = ThreeTrainLoop(context = context, endTime = 600L)
		context.setMainProcess(loop)

		val nullSectionFirst =
			ApprovesTrains {
				loop.getApprovedTrains().sortedBy { if (it.frontSection == null) 0 else 1 }
			}

		val mixedFrames =
			runAndSample(context, nullSectionFirst) { trains ->
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
		val mockSampler = HeadingFlipSampler(calculator)

		mockSampler.sample(listOf(unresolvableHeadingTrain(1, calculator), sampleableTrain(2, calculator)))

		assertThat(mockSampler.rawSampledTrainNumbers).contains(2)
		assertThat(mockSampler.resolvedSampledTrainNumbers).contains(2)
	}

	/**
	 * A train that can be sampled end to end: non-null section, resolvable heading,
	 * resolvable grid location. The guard-train builders below derive from it and override
	 * exactly what makes them skip.
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
	 * Wire the two sampling listeners, run the simulation, and sample every frame that passes
	 * [frameFilter]. Returns the number of frames the filter accepted — the mixed-frame test
	 * uses that count as its vacuity guard.
	 */
	private fun runAndSample(
		context: SimulationContext,
		trainSource: ApprovesTrains,
		frameFilter: (List<Train>) -> Boolean = { true }
	): Int {
		var acceptedFrames = 0
		runSampled(context) {
			val trains = trainSource.getApprovedTrains()
			if (frameFilter(trains)) {
				acceptedFrames++
				sampler.sample(trains)
			}
		}
		return acceptedFrames
	}
}
