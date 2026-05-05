/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.animation.AnimationState
import cz.vutbr.fit.interlockSim.gui.animation.SignalState
import cz.vutbr.fit.interlockSim.gui.animation.TrackState
import cz.vutbr.fit.interlockSim.gui.animation.TrainState
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.testutil.createMockDynamicSemaphore
import cz.vutbr.fit.interlockSim.testutil.createMockRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.createMockTrackBlockPart
import cz.vutbr.fit.interlockSim.util.PointF
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Tests for [AnimatedSimulationCellRenderer].
 *
 * Verifies state-based color rendering for track blocks and semaphore signals,
 * including fallback behavior when state is not available.
 *
 * @since 2026-01-22 (Issue #202)
 */
class AnimatedSimulationCellRendererTest {
	private companion object {
		const val MIN_EXPECTED_BODY_EXTENT_PIXELS = 10
		const val MIN_CAB_NARROWNESS_PIXELS = 2
	}

	private lateinit var animationController: AnimationController
	private lateinit var renderer: AnimatedSimulationCellRenderer
	private lateinit var graphics: Graphics2D

	private val cellWidth = 20
	private val cellHeight = 20

	// Slot to capture colors set on Graphics2D
	private val colorSlot = slot<Color>()

	@BeforeEach
	fun setup() {
		animationController = mockk<AnimationController>()
		renderer = AnimatedSimulationCellRenderer(cellWidth, cellHeight, animationController)
		graphics = mockk<Graphics2D>(relaxed = true)

		// Capture color assignments
		every { graphics.color = capture(colorSlot) } returns Unit
		every { graphics.color } answers { colorSlot.captured }
	}

	// ========== TrackBlockPart Rendering Tests ==========

	@Test
	fun `draw TrackBlockPart with FREE state renders gray color`() {
		// Given: A track block in FREE state
		val trackBlock = mockk<TrackBlock>()
		val trackBlockPart = createMockTrackBlockPart(trackBlock)
		val trackState = TrackState(trackBlock, TrackFacility.State.FREE)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = mapOf(trackBlock to trackState),
				signalStates = emptyMap(),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the track block part
		renderer.draw(graphics, trackBlockPart)

		// Then: Color should be set to gray (FREE)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.TRACK_FREE)
		assertThat(colorSlot.captured).isEqualTo(Color(0x80, 0x80, 0x80))
	}

	@Test
	fun `draw TrackBlockPart with RESERVED state renders yellow color`() {
		// Given: A track block in RESERVED state
		val trackBlock = mockk<TrackBlock>()
		val trackBlockPart = createMockTrackBlockPart(trackBlock)
		val trackState = TrackState(trackBlock, TrackFacility.State.RESERVED)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = mapOf(trackBlock to trackState),
				signalStates = emptyMap(),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the track block part
		renderer.draw(graphics, trackBlockPart)

		// Then: Color should be set to yellow (RESERVED)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.TRACK_RESERVED)
		assertThat(colorSlot.captured).isEqualTo(Color(0xFF, 0xFF, 0x00))
	}

	@Test
	fun `draw TrackBlockPart with OCCUPIED state renders red color`() {
		// Given: A track block in OCCUPIED state
		val trackBlock = mockk<TrackBlock>()
		val trackBlockPart = createMockTrackBlockPart(trackBlock)
		val trackState = TrackState(trackBlock, TrackFacility.State.OCCUPIED)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = mapOf(trackBlock to trackState),
				signalStates = emptyMap(),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the track block part
		renderer.draw(graphics, trackBlockPart)

		// Then: Color should be set to red (OCCUPIED)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.TRACK_OCCUPIED)
		assertThat(colorSlot.captured).isEqualTo(Color(0xFF, 0x00, 0x00))
	}

	@Test
	fun `draw TrackBlockPart with missing state renders default color`() {
		// Given: A track block not in animation state
		val trackBlock = mockk<TrackBlock>()
		val trackBlockPart = createMockTrackBlockPart(trackBlock)
		val animationState = AnimationState.EMPTY // No track states
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the track block part
		renderer.draw(graphics, trackBlockPart)

		// Then: Color should be set to default track color (light gray)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.DEFAULT_TRACK)
		assertThat(colorSlot.captured).isEqualTo(Color(0xC0, 0xC0, 0xC0))
	}

	// ========== DynamicRailSemaphore Rendering Tests ==========

	@Test
	fun `draw DynamicRailSemaphore with STOP signal renders red color`() {
		// Given: A semaphore with STOP signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.STOP)
		val signalState = SignalState(staticSemaphore, Signal.STOP)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to red (STOP)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_STOP)
		assertThat(colorSlot.captured).isEqualTo(Color(0xFF, 0x00, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with S40 signal renders yellow color`() {
		// Given: A semaphore with S40 signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.S40)
		val signalState = SignalState(staticSemaphore, Signal.S40)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to yellow (S40)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW_40)
		assertThat(colorSlot.captured).isEqualTo(Color(0xFF, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with FREE signal renders green color`() {
		// Given: A semaphore with FREE signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.FREE)
		val signalState = SignalState(staticSemaphore, Signal.FREE)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to green (FREE)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(colorSlot.captured).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with S30 signal renders green color`() {
		// Given: A semaphore with S30 signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.S30)
		val signalState = SignalState(staticSemaphore, Signal.S30)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to green (S30 is allowing)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(colorSlot.captured).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with S60 signal renders green color`() {
		// Given: A semaphore with S60 signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.S60)
		val signalState = SignalState(staticSemaphore, Signal.S60)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to green (S60 is allowing)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(colorSlot.captured).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with S80 signal renders green color`() {
		// Given: A semaphore with S80 signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.S80)
		val signalState = SignalState(staticSemaphore, Signal.S80)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to green (S80 is allowing)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(colorSlot.captured).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with S100 signal renders green color`() {
		// Given: A semaphore with S100 signal
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.S100)
		val signalState = SignalState(staticSemaphore, Signal.S100)
		val animationState =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = mapOf(staticSemaphore to signalState),
				switchStates = emptyMap()
			)
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to green (S100 is allowing)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(colorSlot.captured).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `draw DynamicRailSemaphore with missing state renders default color`() {
		// Given: A semaphore not in animation state
		val staticSemaphore = createMockRailSemaphore()
		val dynamicSemaphore = createMockDynamicSemaphore(staticSemaphore, Signal.STOP)
		val animationState = AnimationState.EMPTY // No signal states
		every { animationController.getCurrentState() } returns animationState

		// When: Rendering the semaphore
		renderer.draw(graphics, dynamicSemaphore)

		// Then: Color should be set to default signal color (light gray)
		verify { graphics.color = any() }
		assertThat(colorSlot.captured).isEqualTo(AnimationColors.DEFAULT_SIGNAL)
		assertThat(colorSlot.captured).isEqualTo(Color(0xC0, 0xC0, 0xC0))
	}

	@Test
	fun `drawTrain renders blue train body trailing behind eastbound front`() {
		renderTrainToImage(
			TrainState(
				trainNumber = 12,
				position = 0.0,
				velocity = 0.0,
				acceleration = 0.0,
				frontGridLocation = PointF(1.0f, 1.0f),
				length = 20.0,
				travelingRight = true
			)
		)

		val movedTrain =
			TrainState(
				trainNumber = 12,
				position = 4.0,
				velocity = 4.0,
				acceleration = 0.0,
				frontGridLocation = PointF(1.3f, 1.0f),
				length = 20.0,
				travelingRight = true
			)

		val image = renderTrainToImage(movedTrain)
		val bodyBounds = findOpaqueBounds(image) ?: error("Train shape not rendered")
		val frontPixelX = trainCenterPixelX(movedTrain)

		assertThat(countExactColorPixels(image, AnimationColors.TRAIN_FROM_B) > 0).isTrue()
		assertThat(bodyBounds.maxX >= frontPixelX - 1).isTrue()
		assertThat(frontPixelX - bodyBounds.minX >= MIN_EXPECTED_BODY_EXTENT_PIXELS).isTrue()
	}

	@Test
	fun `drawTrain renders narrower rear cab than main body for eastbound train`() {
		renderTrainToImage(
			TrainState(
				trainNumber = 31,
				position = 0.0,
				velocity = 0.0,
				acceleration = 0.0,
				frontGridLocation = PointF(1.0f, 1.0f),
				length = 20.0,
				travelingRight = true
			)
		)

		val movedTrain =
			TrainState(
				trainNumber = 31,
				position = 4.0,
				velocity = 4.0,
				acceleration = 0.0,
				frontGridLocation = PointF(1.3f, 1.0f),
				length = 20.0,
				travelingRight = true
			)

		val image = renderTrainToImage(movedTrain)
		val bodyBounds = findOpaqueBounds(image) ?: error("Train shape not rendered")
		val totalWidth = bodyBounds.maxX - bodyBounds.minX
		val cabSpan = opaqueVerticalSpanAtX(image, bodyBounds.minX + totalWidth / 4) ?: error("Cab span missing")
		val bodySpan = opaqueVerticalSpanAtX(image, bodyBounds.minX + totalWidth / 2) ?: error("Body span missing")

		assertThat(bodySpan - cabSpan >= MIN_CAB_NARROWNESS_PIXELS).isTrue()
	}

	@Test
	fun `drawTrain renders orange train body trailing behind southbound front`() {
		renderTrainToImage(
			TrainState(
				trainNumber = 21,
				position = 0.0,
				velocity = 0.0,
				acceleration = 0.0,
				frontGridLocation = PointF(2.0f, 1.0f),
				length = 20.0,
				travelingRight = false
			)
		)

		val movedTrain =
			TrainState(
				trainNumber = 21,
				position = 4.0,
				velocity = 4.0,
				acceleration = 0.0,
				frontGridLocation = PointF(2.0f, 1.3f),
				length = 20.0,
				travelingRight = false
			)

		val image = renderTrainToImage(movedTrain)
		val bodyBounds = findOpaqueBounds(image) ?: error("Train shape not rendered")
		val frontPixelY = trainCenterPixelY(movedTrain)

		assertThat(countExactColorPixels(image, AnimationColors.TRAIN_FROM_A) > 0).isTrue()
		assertThat(bodyBounds.maxY >= frontPixelY - 1).isTrue()
		assertThat(frontPixelY - bodyBounds.minY >= MIN_EXPECTED_BODY_EXTENT_PIXELS).isTrue()
	}

	@Test
	fun `drawTrain restores default antialiasing when no hint was previously set`() {
		val helperGraphics = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).createGraphics()
		val font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
		every { graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING) } returns null
		every { graphics.font } returns font
		every { graphics.fontMetrics } returns helperGraphics.getFontMetrics(font)

		try {
			renderer.drawTrain(
				graphics,
				TrainState(
					trainNumber = 99,
					position = 0.0,
					velocity = 0.0,
					acceleration = 0.0,
					frontGridLocation = PointF(1.0f, 1.0f),
					length = 20.0,
					travelingRight = true
				),
				cellWidth,
				cellHeight
			)
		} finally {
			helperGraphics.dispose()
		}

		verify {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_DEFAULT)
		}
	}

	// ========== Helper Methods ==========
	// (Mock factories moved to TrackTestMocks.kt - Phase 4, 2026-02-05)

	private fun renderTrainToImage(trainState: TrainState): BufferedImage {
		val image = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
		val graphics2D = image.createGraphics()
		try {
			renderer.drawTrain(graphics2D, trainState, cellWidth, cellHeight)
		} finally {
			graphics2D.dispose()
		}
		return image
	}

	private fun trainCenterPixelX(trainState: TrainState): Int =
		((trainState.frontGridLocation ?: error("Missing front grid location")).x * cellWidth + cellWidth / 2).roundToInt()

	private fun trainCenterPixelY(trainState: TrainState): Int =
		((trainState.frontGridLocation ?: error("Missing front grid location")).y * cellHeight + cellHeight / 2).roundToInt()

	private fun findOpaqueBounds(image: BufferedImage): ColorBounds? {
		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var maxX = Int.MIN_VALUE
		var maxY = Int.MIN_VALUE
		var found = false

		for (y in 0 until image.height) {
			for (x in 0 until image.width) {
				if ((image.getRGB(x, y) ushr 24) != 0) {
					found = true
					minX = minOf(minX, x)
					minY = minOf(minY, y)
					maxX = maxOf(maxX, x)
					maxY = maxOf(maxY, y)
				}
			}
		}

		if (!found) {
			return null
		}

		return ColorBounds(minX, minY, maxX, maxY)
	}

	private fun countExactColorPixels(
		image: BufferedImage,
		color: Color
	): Int {
		var matches = 0
		for (y in 0 until image.height) {
			for (x in 0 until image.width) {
				if (image.getRGB(x, y) == color.rgb) {
					matches++
				}
			}
		}
		return matches
	}

	private fun opaqueVerticalSpanAtX(
		image: BufferedImage,
		x: Int
	): Int? {
		var minY = Int.MAX_VALUE
		var maxY = Int.MIN_VALUE
		for (y in 0 until image.height) {
			if ((image.getRGB(x, y) ushr 24) != 0) {
				minY = minOf(minY, y)
				maxY = maxOf(maxY, y)
			}
		}

		return if (maxY >= minY) {
			maxY - minY + 1
		} else {
			null
		}
	}

	private data class ColorBounds(
		val minX: Int,
		val minY: Int,
		val maxX: Int,
		val maxY: Int
	)
}
