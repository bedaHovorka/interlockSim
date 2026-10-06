/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit test for Issue #1008: AnimationStateCapture records whether a switch is locked.
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit test for [AnimationStateCapture.captureSwitchState] (Issue #1008): the captured [SwitchState]
 * carries the switch's lock state next to its configuration, so playback shows the padlock as it
 * was at capture time.
 */
@DisplayName("AnimationStateCapture.captureSwitchState records the lock state (Issue #1008)")
class AnimationStateCaptureSwitchStateTest {
	private val dynamicSwitch =
		DynamicRailSwitch(RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE))

	@Test
	fun `a locked switch is captured as locked`() {
		dynamicSwitch.lock()

		val state = AnimationStateCapture.captureSwitchState(dynamicSwitch)

		assertThat(state.locked).isTrue()
		assertThat(state.railSwitch).isSameInstanceAs(dynamicSwitch.staticRef)
		assertThat(state.conf).isEqualTo(dynamicSwitch.conf)
	}

	@Test
	fun `an unlocked switch is captured as unlocked`() {
		val state = AnimationStateCapture.captureSwitchState(dynamicSwitch)

		assertThat(state.locked).isFalse()
	}

	@Test
	fun `a switch unlocked again is captured as unlocked`() {
		dynamicSwitch.lock()
		dynamicSwitch.unlock()

		val state = AnimationStateCapture.captureSwitchState(dynamicSwitch)

		assertThat(state.locked).isFalse()
	}
}
