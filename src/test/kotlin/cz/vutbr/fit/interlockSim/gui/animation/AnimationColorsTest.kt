/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Tests for [AnimationColors] color scheme utility.
 *
 * Verifies correct color mapping for track states and semaphore signals
 * according to standard railway color conventions.
 *
 * @since 2026-01-22 (Issue #202)
 */
class AnimationColorsTest {
	// ========== Track State Color Tests ==========

	@Test
	fun `forTrackState FREE returns gray color`() {
		val color = AnimationColors.forTrackState(TrackFacility.State.FREE)
		assertThat(color).isEqualTo(AnimationColors.TRACK_FREE)
		assertThat(color).isEqualTo(Color(0x80, 0x80, 0x80))
	}

	@Test
	fun `forTrackState RESERVED returns yellow color`() {
		val color = AnimationColors.forTrackState(TrackFacility.State.RESERVED)
		assertThat(color).isEqualTo(AnimationColors.TRACK_RESERVED)
		assertThat(color).isEqualTo(Color(0xFF, 0xFF, 0x00))
	}

	@Test
	fun `forTrackState OCCUPIED returns red color`() {
		val color = AnimationColors.forTrackState(TrackFacility.State.OCCUPIED)
		assertThat(color).isEqualTo(AnimationColors.TRACK_OCCUPIED)
		assertThat(color).isEqualTo(Color(0xFF, 0x00, 0x00))
	}

	@Test
	fun `all track states have unique colors`() {
		val freeColor = AnimationColors.forTrackState(TrackFacility.State.FREE)
		val reservedColor = AnimationColors.forTrackState(TrackFacility.State.RESERVED)
		val occupiedColor = AnimationColors.forTrackState(TrackFacility.State.OCCUPIED)

		// Verify all colors are distinct
		assertThat(freeColor).isNotEqualTo(reservedColor)
		assertThat(freeColor).isNotEqualTo(occupiedColor)
		assertThat(reservedColor).isNotEqualTo(occupiedColor)
	}

	// ========== Signal State Color Tests ==========

	@Test
	fun `forSignal STOP returns red color`() {
		val color = AnimationColors.forSignal(Signal.STOP)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_STOP)
		assertThat(color).isEqualTo(Color(0xFF, 0x00, 0x00))
	}

	@Test
	fun `forSignal S40 returns yellow color`() {
		val color = AnimationColors.forSignal(Signal.S40)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW_40)
		assertThat(color).isEqualTo(Color(0xFF, 0xFF, 0x00))
	}

	@Test
	fun `forSignal S30 returns green color`() {
		val color = AnimationColors.forSignal(Signal.S30)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `forSignal S60 returns green color`() {
		val color = AnimationColors.forSignal(Signal.S60)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `forSignal S80 returns green color`() {
		val color = AnimationColors.forSignal(Signal.S80)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `forSignal S100 returns green color`() {
		val color = AnimationColors.forSignal(Signal.S100)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `forSignal FREE returns green color`() {
		val color = AnimationColors.forSignal(Signal.FREE)
		assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
		assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
	}

	@Test
	fun `all allowing signals except S40 return same green color`() {
		val allowingSignals = listOf(Signal.S30, Signal.S60, Signal.S80, Signal.S100, Signal.FREE)
		val colors = allowingSignals.map { AnimationColors.forSignal(it) }

		// All should be the same green color
		colors.forEach { color ->
			assertThat(color).isEqualTo(AnimationColors.SIGNAL_ALLOW)
			assertThat(color).isEqualTo(Color(0x00, 0xFF, 0x00))
		}
	}

	// ========== Color Constant Value Tests ==========

	@Test
	fun `TRACK_FREE constant has correct RGB values`() {
		assertThat(AnimationColors.TRACK_FREE.red).isEqualTo(0x80)
		assertThat(AnimationColors.TRACK_FREE.green).isEqualTo(0x80)
		assertThat(AnimationColors.TRACK_FREE.blue).isEqualTo(0x80)
	}

	@Test
	fun `TRACK_RESERVED constant has correct RGB values`() {
		assertThat(AnimationColors.TRACK_RESERVED.red).isEqualTo(0xFF)
		assertThat(AnimationColors.TRACK_RESERVED.green).isEqualTo(0xFF)
		assertThat(AnimationColors.TRACK_RESERVED.blue).isEqualTo(0x00)
	}

	@Test
	fun `TRACK_OCCUPIED constant has correct RGB values`() {
		assertThat(AnimationColors.TRACK_OCCUPIED.red).isEqualTo(0xFF)
		assertThat(AnimationColors.TRACK_OCCUPIED.green).isEqualTo(0x00)
		assertThat(AnimationColors.TRACK_OCCUPIED.blue).isEqualTo(0x00)
	}

	@Test
	fun `SIGNAL_STOP constant has correct RGB values`() {
		assertThat(AnimationColors.SIGNAL_STOP.red).isEqualTo(0xFF)
		assertThat(AnimationColors.SIGNAL_STOP.green).isEqualTo(0x00)
		assertThat(AnimationColors.SIGNAL_STOP.blue).isEqualTo(0x00)
	}

	@Test
	fun `SIGNAL_ALLOW_40 constant has correct RGB values`() {
		assertThat(AnimationColors.SIGNAL_ALLOW_40.red).isEqualTo(0xFF)
		assertThat(AnimationColors.SIGNAL_ALLOW_40.green).isEqualTo(0xFF)
		assertThat(AnimationColors.SIGNAL_ALLOW_40.blue).isEqualTo(0x00)
	}

	@Test
	fun `SIGNAL_ALLOW constant has correct RGB values`() {
		assertThat(AnimationColors.SIGNAL_ALLOW.red).isEqualTo(0x00)
		assertThat(AnimationColors.SIGNAL_ALLOW.green).isEqualTo(0xFF)
		assertThat(AnimationColors.SIGNAL_ALLOW.blue).isEqualTo(0x00)
	}

	@Test
	fun `DEFAULT_TRACK constant has correct RGB values`() {
		assertThat(AnimationColors.DEFAULT_TRACK.red).isEqualTo(0xC0)
		assertThat(AnimationColors.DEFAULT_TRACK.green).isEqualTo(0xC0)
		assertThat(AnimationColors.DEFAULT_TRACK.blue).isEqualTo(0xC0)
	}

	@Test
	fun `DEFAULT_SIGNAL constant has correct RGB values`() {
		assertThat(AnimationColors.DEFAULT_SIGNAL.red).isEqualTo(0xC0)
		assertThat(AnimationColors.DEFAULT_SIGNAL.green).isEqualTo(0xC0)
		assertThat(AnimationColors.DEFAULT_SIGNAL.blue).isEqualTo(0xC0)
	}

	// ========== Semantic Mapping Tests ==========

	@Test
	fun `STOP and OCCUPIED use same red color`() {
		// Both danger conditions use the same red
		assertThat(AnimationColors.SIGNAL_STOP).isEqualTo(AnimationColors.TRACK_OCCUPIED)
		assertThat(AnimationColors.forSignal(Signal.STOP))
			.isEqualTo(AnimationColors.forTrackState(TrackFacility.State.OCCUPIED))
	}

	@Test
	fun `RESERVED and ALLOW_40 use same yellow color`() {
		// Both caution conditions use the same yellow
		assertThat(AnimationColors.TRACK_RESERVED).isEqualTo(AnimationColors.SIGNAL_ALLOW_40)
		assertThat(AnimationColors.forTrackState(TrackFacility.State.RESERVED))
			.isEqualTo(AnimationColors.forSignal(Signal.S40))
	}
}
