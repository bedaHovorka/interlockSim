/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test (#719, revised for #788): the rendered nose heading must not flip 180°
 * when a train's front runs out of track to advance into.
 *
 * Root cause: after the front crossed a separator, `Train.Site.actions()` advanced
 * `entrySeparator` to that separator. When there was no upcoming section to advance
 * `frontSection` to — the next path not reserved yet, or the separator being the
 * destination InOut — the pair went stale: `frontSection` kept reporting the
 * just-traversed section while `entrySeparator` pointed at its *exit* end. The raw
 * authoritative heading then reversed 180°, which made a waiting train render *beyond*
 * the semaphore (looking like a collision with the opposing train on the switch) and made
 * an arriving train flip its nose.
 *
 * #719 fixed the symptom canvas-side by routing headings through [TrainHeadingResolver];
 * #788 fixed the cause in `:core`, which is why the raw flips [HeadingFlipSampler.flips]
 * records must now stay empty.
 *
 * Drives the three-train shunting-loop spec (same as the `shuntingLoop` example used by
 * `runExampleGui`) on `vyhybna.xml`, samples both the raw and the resolved heading at every
 * train/block event, and asserts that neither ever flips by more than 90°.
 *
 * Scope note (measured while fixing #788): [MultiTrainLoop] reserves a train's whole
 * entry-to-exit path before admitting it, so trains in this scenario never come to a stand
 * in front of a STOP signal mid-route. The boundary this test actually exercises is arrival
 * at the destination InOut — before #788 that produced exactly one raw 180° flip per train.
 * The class name is kept for continuity with #719/#786.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.ArrivalTally
import cz.vutbr.fit.interlockSim.testutil.HeadingSamplerTestBase
import cz.vutbr.fit.interlockSim.testutil.runSampled
import org.junit.jupiter.api.Test

class RedSignalWaitHeadingFlipRegressionTest : HeadingSamplerTestBase() {
	private companion object {
		/** Trains that must complete their journey for the boundary state to be exercised. */
		const val MIN_ARRIVALS: Int = 3
	}

	private val arrivals = ArrivalTally()

	@Test
	fun `raw and resolved heading never flip where the front cannot advance`() {
		val context = startSamplerContext()

		// Same spec as the `shuntingLoop` example (runExampleGui): two A→B trains and one
		// opposing B→A train on the passing loop — the boundary each of them reaches is
		// arrival at its destination InOut (see the scope note above).
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
		context.setMainProcess(loop)

		runSampled(context) { message ->
			message?.let(arrivals::record)
			sampler.sample(loop.getApprovedTrains())
		}

		sampler.flips.forEach { println("HEADING_FLIP: $it") }
		sampler.resolvedFlips.forEach { println("HEADING_FLIP: $it") }
		// Non-vacuity witness that replaces the old "a raw flip must occur" assertion, which
		// pinned the #788 defect as expected behaviour: the boundary state is reached by every
		// train that arrives at its destination InOut, so require the arrivals instead.
		assertThat(
			arrivals.arrivedTrainNumbers.size >= MIN_ARRIVALS,
			name = "trains reached their destination InOut (boundary state exercised)"
		).isTrue()
		assertThat(sampler.flips, name = "raw heading reversals").isEmpty()
		assertThat(sampler.resolvedFlips, name = "resolved heading reversals").isEmpty()
	}
}
