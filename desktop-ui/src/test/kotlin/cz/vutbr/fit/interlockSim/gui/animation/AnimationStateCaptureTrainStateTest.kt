/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit test for Issues #1030, #1028: AnimationStateCapture reads one front identity per train.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.TrainFrontIdentity
import cz.vutbr.fit.interlockSim.util.PointF
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit test for [AnimationStateCapture.captureTrainState] (Issues #1030, #1028).
 *
 * The capture runs on the Swing EDT while the simulation thread moves the train, so it must take
 * the front's section, entry separator and traversed-blocks distance from one
 * [Train.frontIdentity] read and combine it only with the live [Train.frontIntegratedPosition].
 * Here the live getters ([Train.frontSection], [Train.trainEntrySeparator], [Train.totalDistance],
 * [Train.frontPosition]) deliberately disagree with the identity — the shape of a torn read — and
 * the strict calculator mock accepts only the identity's values, so any live-getter use fails.
 */
@DisplayName("AnimationStateCapture.captureTrainState reads one front identity (Issues #1030, #1028)")
class AnimationStateCaptureTrainStateTest {
	private companion object {
		const val SECTION_LENGTH: Double = 100.0
		const val PREVIOUS_BLOCKS: Double = 300.0
		const val INTEGRATED_POSITION: Double = 12.5
		const val HEADING: Double = 1.5
	}

	private val section = mockk<TrackSection> { every { length() } returns SECTION_LENGTH }
	private val entry = mockk<DynamicPathSeparator>()
	private val calculator = mockk<TrainPositionCalculator>()

	private fun trainWith(identity: TrainFrontIdentity): Train {
		val train = mockk<Train>(relaxed = true)
		every { train.frontIdentity } returns identity
		every { train.frontIntegratedPosition } returns INTEGRATED_POSITION
		// Live getters that belong to another moment — a torn read the capture must not use.
		every { train.frontSection } returns mockk<TrackSection>()
		every { train.trainEntrySeparator } returns mockk<DynamicPathSeparator>()
		every { train.totalDistance } returns -1.0
		every { train.frontPosition } returns -1.0
		return train
	}

	@Test
	@DisplayName("inside a section: identity section and entry, integrated position along it")
	fun `capture inside a section uses the identity and the integrated position`() {
		val train = trainWith(TrainFrontIdentity(section, entry, onNext = true, previousBlocksLength = PREVIOUS_BLOCKS))
		every { calculator.calculateTrainGridLocation(entry, section, INTEGRATED_POSITION) } returns PointF(3f, 4f)
		every { calculator.calculateTrainHeadingRadians(entry, section) } returns HEADING

		val state = AnimationStateCapture.captureTrainState(train, calculator)

		assertThat(state.position).isEqualTo(PREVIOUS_BLOCKS + INTEGRATED_POSITION)
		assertThat(state.frontGridLocation).isEqualTo(PointF(3f, 4f))
		assertThat(state.headingRadians).isEqualTo(HEADING)
	}

	@Test
	@DisplayName("at the Issue #788 boundary: the front stands at the far end of the identity section")
	fun `capture at the boundary places the front at the section end`() {
		val train = trainWith(TrainFrontIdentity(section, entry, onNext = false, previousBlocksLength = PREVIOUS_BLOCKS))
		every { calculator.calculateTrainGridLocation(entry, section, SECTION_LENGTH) } returns PointF(5f, 6f)
		every { calculator.calculateTrainHeadingRadians(entry, section) } returns HEADING

		val state = AnimationStateCapture.captureTrainState(train, calculator)

		assertThat(state.position).isEqualTo(PREVIOUS_BLOCKS + INTEGRATED_POSITION)
		assertThat(state.frontGridLocation).isEqualTo(PointF(5f, 6f))
		assertThat(state.headingRadians).isEqualTo(HEADING)
	}

	@Test
	@DisplayName("before entry: no section, no entry, distance from the integrated position only")
	fun `capture before entry passes no section and no entry`() {
		val train = trainWith(TrainFrontIdentity.NOT_ENTERED)
		every { calculator.calculateTrainGridLocation(null, null, INTEGRATED_POSITION) } returns null
		every { calculator.calculateTrainHeadingRadians(null, null) } returns null

		val state = AnimationStateCapture.captureTrainState(train, calculator)

		assertThat(state.position).isEqualTo(INTEGRATED_POSITION)
		assertThat(state.frontGridLocation).isEqualTo(null)
		assertThat(state.headingRadians).isEqualTo(null)
	}
}
