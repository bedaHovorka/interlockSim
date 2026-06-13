/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for SimulationControlPanel (Issue #190)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities

/**
 * Unit tests for [SimulationControlPanel].
 *
 * All Swing operations are performed on the EDT via [SwingUtilities.invokeAndWait].
 * [SimulationControlPanel] is a [javax.swing.JPanel] subclass that can be created in
 * headless mode (no display required).
 *
 * Covers:
 * - Initial state: slider at 1×, label shows "1.0x", 7 preset buttons visible
 * - Slider movement updates speed label and runner
 * - Preset buttons set speed and update slider / label
 * - Setting runner synchronises UI to runner's current speed
 * - Runner property change events update the UI
 * - Null runner clears wiring without throwing
 * - Preset 50x moves slider to max (10x) but sets runner to 50x
 */
@DisplayName("SimulationControlPanel")
class SimulationControlPanelTest {
	private lateinit var panel: SimulationControlPanel
	private lateinit var context: SimulationContext

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			panel = SimulationControlPanel()
		}
		context = mockk(relaxed = true)
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun findSlider(): JSlider =
		findComponent(panel, JSlider::class.java)
			?: error("JSlider not found in SimulationControlPanel")

	private fun findSpeedLabel(): JLabel {
		// The speed label is the one whose text ends with 'x'
		return findAllComponents(panel, JLabel::class.java)
			.firstOrNull { it.text.endsWith("x") }
			?: error("Speed label not found in SimulationControlPanel")
	}

	private fun findPresetButtons(): List<JButton> =
		findAllComponents(panel, JButton::class.java).filter { it.text.endsWith("x") }

	/** Recursively find the first component of [type] in the container hierarchy. */
	private fun <T> findComponent(
		container: java.awt.Container,
		type: Class<T>
	): T? {
		for (c in container.components) {
			if (type.isInstance(c)) {
				@Suppress("UNCHECKED_CAST")
				return c as T
			}
			if (c is java.awt.Container) {
				val found = findComponent(c, type)
				if (found != null) return found
			}
		}
		return null
	}

	/** Recursively collect all components of [type] in the container hierarchy. */
	private fun <T> findAllComponents(
		container: java.awt.Container,
		type: Class<T>
	): List<T> {
		val result = mutableListOf<T>()
		for (c in container.components) {
			if (type.isInstance(c)) {
				@Suppress("UNCHECKED_CAST")
				result.add(c as T)
			}
			if (c is java.awt.Container) {
				result.addAll(findAllComponents(c, type))
			}
		}
		return result
	}

	// ── Initial state ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("initial slider value corresponds to 1.0x speed")
	fun initialSliderValue() {
		SwingUtilities.invokeAndWait {
			val slider = findSlider()
			// slider 10 → 10 / 10.0 = 1.0x
			assertThat(slider.value).isEqualTo(10)
		}
	}

	@Test
	@DisplayName("initial speed label shows 1.0x")
	fun initialSpeedLabel() {
		SwingUtilities.invokeAndWait {
			val label = findSpeedLabel()
			assertThat(label.text).isEqualTo("1.0x")
		}
	}

	@Test
	@DisplayName("panel contains exactly 7 preset buttons")
	fun sevenPresetButtons() {
		SwingUtilities.invokeAndWait {
			val buttons = findPresetButtons()
			assertThat(buttons.size).isEqualTo(7)
		}
	}

	@Test
	@DisplayName("slider minimum is 1 (= 0.1x)")
	fun sliderMinimum() {
		SwingUtilities.invokeAndWait {
			assertThat(findSlider().minimum).isEqualTo(1)
		}
	}

	@Test
	@DisplayName("slider maximum is 100 (= 10.0x)")
	fun sliderMaximum() {
		SwingUtilities.invokeAndWait {
			assertThat(findSlider().maximum).isEqualTo(100)
		}
	}

	// ── Slider interaction ────────────────────────────────────────────────────

	@Test
	@DisplayName("moving slider to 50 updates speed label to 5.0x")
	fun sliderUpdateLabel() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findSlider().value = 50
		}
		SwingUtilities.invokeAndWait {
			assertThat(findSpeedLabel().text).isEqualTo("5.0x")
		}
	}

	@Test
	@DisplayName("moving slider updates runner speedMultiplier")
	fun sliderUpdatesRunner() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findSlider().value = 20 // 20 / 10.0 = 2.0x
		}
		assertThat(runner.speedMultiplier).isEqualTo(2.0)
	}

	@Test
	@DisplayName("moving slider to 1 sets speed to 0.1x")
	fun sliderAtMinSpeed() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findSlider().value = 1
		}
		assertThat(runner.speedMultiplier).isEqualTo(0.1)
	}

	@Test
	@DisplayName("moving slider to 100 sets speed to 10.0x")
	fun sliderAtMaxSpeed() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findSlider().value = 100
		}
		assertThat(runner.speedMultiplier).isEqualTo(10.0)
	}

	// ── Preset buttons ────────────────────────────────────────────────────────

	@Test
	@DisplayName("clicking 0.1x preset sets runner speed to 0.1")
	fun presetPointOneX() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val btn = findPresetButtons().first { it.text == "0.1x" }
			btn.doClick()
		}
		assertThat(runner.speedMultiplier).isEqualTo(0.1)
	}

	@Test
	@DisplayName("clicking 1x preset sets runner speed to 1.0")
	fun presetOneX() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			// First move away from default
			findSlider().value = 50
			val btn = findPresetButtons().first { it.text == "1x" }
			btn.doClick()
		}
		assertThat(runner.speedMultiplier).isEqualTo(1.0)
	}

	@Test
	@DisplayName("clicking 2x preset updates slider to position 20")
	fun preset2xUpdatesSlider() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val btn = findPresetButtons().first { it.text == "2x" }
			btn.doClick()
		}
		SwingUtilities.invokeAndWait {
			assertThat(findSlider().value).isEqualTo(20)
		}
	}

	@Test
	@DisplayName("clicking 10x preset sets runner speed to 10.0")
	fun preset10x() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val btn = findPresetButtons().first { it.text == "10x" }
			btn.doClick()
		}
		assertThat(runner.speedMultiplier).isEqualTo(10.0)
	}

	@Test
	@DisplayName("clicking 50x preset sets runner speed to 50.0 (above slider range)")
	fun preset50xSetsRunnerSpeed() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val btn = findPresetButtons().first { it.text == "50x" }
			btn.doClick()
		}
		assertThat(runner.speedMultiplier).isEqualTo(50.0)
	}

	@Test
	@DisplayName("clicking 50x preset clamps slider to maximum (100)")
	fun preset50xClampsSlider() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val btn = findPresetButtons().first { it.text == "50x" }
			btn.doClick()
		}
		SwingUtilities.invokeAndWait {
			assertThat(findSlider().value).isEqualTo(100)
		}
	}

	@Test
	@DisplayName("clicking preset routes through onSpeedChanged callback when wired")
	fun presetRoutesViaCallbackWhenWired() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			// Simulate SimulationController.setSpeed: callback updates runner
			panel.onSpeedChanged = { speed -> runner.speedMultiplier = speed }
			findPresetButtons().first { it.text == "2x" }.doClick()
		}
		assertThat(runner.speedMultiplier).isEqualTo(2.0)
	}

	@Test
	@DisplayName("dragging slider routes through onSpeedChanged callback when wired")
	fun sliderRoutesViaCallbackWhenWired() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			// Simulate SimulationController.setSpeed: callback updates runner
			panel.onSpeedChanged = { speed -> runner.speedMultiplier = speed }
			findSlider().value = 20 // 20 / 10.0 = 2.0x
		}
		assertThat(runner.speedMultiplier).isEqualTo(2.0)
	}

	// ── Runner wiring ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("setting runner synchronises UI to runner's current speed")
	fun settingRunnerSyncsUi() {
		val runner = SimulationRunner(context)
		runner.speedMultiplier = 2.0
		SwingUtilities.invokeAndWait {
			panel.runner = runner
		}
		SwingUtilities.invokeAndWait {
			assertThat(findSpeedLabel().text).isEqualTo("2.0x")
			assertThat(findSlider().value).isEqualTo(20)
		}
	}

	@Test
	@DisplayName("runner speed change fires PropertyChange and updates panel label")
	fun runnerPropChangeUpdatesLabel() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
		}
		// Change speed on a background thread (simulates simulation thread)
		runner.speedMultiplier = 5.0
		// Allow invokeLater to settle
		SwingUtilities.invokeAndWait { /* flush EDT queue */ }
		SwingUtilities.invokeAndWait {
			assertThat(findSpeedLabel().text).isEqualTo("5.0x")
		}
	}

	@Test
	@DisplayName("setting runner to null does not throw and panel stays at last speed")
	fun settingRunnerNullNoThrow() {
		val runner = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findSlider().value = 30
			panel.runner = null // Should not throw
		}
		// After null, panel retains last displayed speed
		SwingUtilities.invokeAndWait {
			assertThat(findSpeedLabel().text).isEqualTo("3.0x")
		}
	}

	@Test
	@DisplayName("replacing runner removes listener from old runner")
	fun replacingRunnerRemovesOldListener() {
		val runner1 = SimulationRunner(context)
		val runner2 = SimulationRunner(context)
		SwingUtilities.invokeAndWait {
			panel.runner = runner1
			panel.runner = runner2
		}
		// Change speed on old runner — should NOT affect panel (listener removed)
		runner1.speedMultiplier = 8.0
		SwingUtilities.invokeAndWait { /* flush */ }
		SwingUtilities.invokeAndWait {
			// Panel should still show runner2's default speed (1.0x), not runner1's 8.0x
			assertThat(findSpeedLabel().text).isEqualTo("1.0x")
		}
	}

	// ── Panel visibility ──────────────────────────────────────────────────────

	@Test
	@DisplayName("panel has positive preferred width")
	fun panelHasSize() {
		SwingUtilities.invokeAndWait {
			panel.addNotify() // trigger layout
			assertThat(panel.preferredSize.width).isGreaterThan(0)
		}
	}

	@Test
	@DisplayName("panel is visible by default (visibility controlled by Frame)")
	fun panelVisibleByDefault() {
		SwingUtilities.invokeAndWait {
			assertThat(panel.isVisible).isTrue()
		}
	}

	// ── Preset labels ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("preset button labels match expected values")
	fun presetButtonLabels() {
		SwingUtilities.invokeAndWait {
			val labels = findPresetButtons().map { it.text }
			assertThat(labels).isEqualTo(listOf("0.1x", "0.5x", "1x", "2x", "5x", "10x", "50x"))
		}
	}

	// ── onSpeedChanged callback ───────────────────────────────────────────────

	@Test
	@DisplayName("moving slider invokes onSpeedChanged callback with correct speed")
	fun sliderInvokesOnSpeedChangedCallback() {
		var capturedSpeed: Double? = null
		SwingUtilities.invokeAndWait {
			panel.onSpeedChanged = { speed -> capturedSpeed = speed }
			findSlider().value = 30 // 30 / 10.0 = 3.0x
		}
		assertThat(capturedSpeed).isEqualTo(3.0)
	}

	@Test
	@DisplayName("clicking preset button invokes onSpeedChanged callback with correct speed")
	fun presetClickInvokesOnSpeedChangedCallback() {
		var capturedSpeed: Double? = null
		SwingUtilities.invokeAndWait {
			panel.onSpeedChanged = { speed -> capturedSpeed = speed }
			findPresetButtons().first { it.text == "2x" }.doClick()
		}
		assertThat(capturedSpeed).isEqualTo(2.0)
	}

	@Test
	@DisplayName("runner speed change does not invoke onSpeedChanged callback (no feedback loop)")
	fun runnerSpeedChangeDoesNotInvokeCallback() {
		val runner = SimulationRunner(context)
		var callbackCount = 0
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			panel.onSpeedChanged = { callbackCount++ }
		}
		runner.speedMultiplier = 3.0
		SwingUtilities.invokeAndWait { /* flush invokeLater from runnerListener */ }
		assertThat(callbackCount).isEqualTo(0)
	}
}
