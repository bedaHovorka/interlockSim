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
import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.animation.AnimationState
import cz.vutbr.fit.interlockSim.gui.animation.SignalState
import cz.vutbr.fit.interlockSim.gui.animation.TrackState
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.testutil.createMockDynamicSemaphore
import cz.vutbr.fit.interlockSim.testutil.createMockRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.createMockTrackBlockPart
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D

/**
 * Tests for [AnimatedSimulationCellRenderer].
 *
 * Verifies state-based color rendering for track blocks and semaphore signals,
 * including fallback behavior when state is not available.
 *
 * @since 2026-01-22 (Issue #202)
 */
class AnimatedSimulationCellRendererTest {
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

	// ========== Helper Methods ==========
	// (Mock factories moved to TrackTestMocks.kt - Phase 4, 2026-02-05)
}
