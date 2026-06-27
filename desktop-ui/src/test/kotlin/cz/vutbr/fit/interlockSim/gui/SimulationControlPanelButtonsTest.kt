/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for SimulationControlPanel pause/step buttons (Issue #501)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import javax.swing.JButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

/**
 * Unit tests for the Goal 8 pause/step controls in [SimulationControlPanel].
 *
 * All Swing state is read and mutated on the EDT via [SwingUtilities.invokeAndWait]
 * so the tests run safely in headless mode.
 */
@DisplayName("SimulationControlPanel pause/step buttons")
class SimulationControlPanelButtonsTest {
	private lateinit var panel: SimulationControlPanel
	private lateinit var context: SimulationContext

	@BeforeEach
	fun setUp() {
		context = mockk(relaxed = true)
		SwingUtilities.invokeAndWait {
			panel = SimulationControlPanel()
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun findButton(text: String): JButton =
		findAllComponents(panel, JButton::class.java)
			.firstOrNull { it.text == text }
			?: error("JButton with text '$text' not found in SimulationControlPanel")

	private fun findSpinner(): JSpinner =
		findComponent(panel, JSpinner::class.java)
			?: error("JSpinner not found in SimulationControlPanel")

	private fun spinnerModel(): SpinnerNumberModel = findSpinner().model as SpinnerNumberModel

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

	private fun runnerWith(
		delta: Double = 1.0,
		paused: Boolean = false
	): SimulationRunner {
		val runner = SimulationRunner(context)
		runner.stepTimeDelta = delta
		runner.isPaused = paused
		return runner
	}

	private fun flushEdt() {
		SwingUtilities.invokeAndWait { /* drain pending EDT work */ }
	}

	// ── Initial state ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("pause button initially shows Pause, is disabled, and step controls are disabled")
	fun initialPauseState() {
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Pause").isEnabled).isFalse()
			assertThat(findButton("Step Event").isEnabled).isFalse()
			assertThat(findButton("Step Time").isEnabled).isFalse()
			assertThat(findSpinner().isEnabled).isFalse()
		}
	}

	@Test
	@DisplayName("pause/step button tooltips match the specification")
	fun tooltipsMatchSpec() {
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Pause").toolTipText)
				.isEqualTo("Pause/resume the simulation (Space)")
			assertThat(findButton("Step Event").toolTipText)
				.isEqualTo("Advance by one event (S)")
			assertThat(findButton("Step Time").toolTipText)
				.isEqualTo("Advance by the configured time delta (T)")
		}
	}

	// ── Runner wiring ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("setting a non-paused runner enables pause button and disables step buttons")
	fun wiredRunnerEnablesPauseOnly() {
		SwingUtilities.invokeAndWait {
			panel.runner = runnerWith(paused = false)
		}
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Pause").isEnabled).isTrue()
			assertThat(findButton("Step Event").isEnabled).isFalse()
			assertThat(findButton("Step Time").isEnabled).isFalse()
			assertThat(findSpinner().isEnabled).isTrue()
		}
	}

	@Test
	@DisplayName("setting a paused runner enables pause and step buttons")
	fun wiredPausedRunnerEnablesStepButtons() {
		SwingUtilities.invokeAndWait {
			panel.runner = runnerWith(paused = true)
		}
		SwingUtilities.invokeAndWait {
			val pauseBtn = findButton("Resume")
			assertThat(pauseBtn.text).isEqualTo("Resume")
			assertThat(pauseBtn.isEnabled).isTrue()
			assertThat(findButton("Step Event").isEnabled).isTrue()
			assertThat(findButton("Step Time").isEnabled).isTrue()
		}
	}

	@Test
	@DisplayName("setting runner null disables all pause/step controls and the spinner")
	fun nullRunnerDisablesControls() {
		SwingUtilities.invokeAndWait {
			panel.runner = runnerWith(paused = false)
			panel.runner = null
		}
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Pause").isEnabled).isFalse()
			assertThat(findButton("Step Event").isEnabled).isFalse()
			assertThat(findButton("Step Time").isEnabled).isFalse()
			assertThat(findSpinner().isEnabled).isFalse()
		}
	}

	@Test
	@DisplayName("panel synchronises spinner to runner's stepTimeDelta when runner is set")
	fun spinnerSyncsToRunnerDelta() {
		SwingUtilities.invokeAndWait {
			panel.runner = runnerWith(delta = 5.5)
		}
		assertThat(spinnerModel().value as Double).isEqualTo(5.5)
	}

	// ── Pause / Resume toggle ─────────────────────────────────────────────────

	@Test
	@DisplayName("clicking Pause toggles runner.isPaused and switches button text to Resume")
	fun pauseTogglePausesSimulation() {
		val runner = runnerWith(paused = false)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val pauseBtn = findButton("Pause")
			pauseBtn.doClick()
		}
		assertThat(runner.isPaused).isTrue()
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Resume").text).isEqualTo("Resume")
			assertThat(findButton("Step Event").isEnabled).isTrue()
			assertThat(findButton("Step Time").isEnabled).isTrue()
		}
	}

	@Test
	@DisplayName("clicking Resume toggles runner.isPaused and disables step buttons")
	fun resumeToggleResumesSimulation() {
		val runner = runnerWith(paused = true)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findButton("Resume").doClick()
		}
		assertThat(runner.isPaused).isFalse()
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Pause").text).isEqualTo("Pause")
			assertThat(findButton("Step Event").isEnabled).isFalse()
			assertThat(findButton("Step Time").isEnabled).isFalse()
		}
	}

	@Test
	@DisplayName("pause state change from a background thread updates button states on the EDT")
	fun backgroundPauseStatePropagatesToEdt() {
		val runner = runnerWith(paused = false)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
		}

		val latch = CountDownLatch(1)
		val thread =
			Thread {
				runner.isPaused = true
				latch.countDown()
			}
		thread.isDaemon = true
		thread.start()
		latch.await()
		thread.join(5_000)

		flushEdt()
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Resume").text).isEqualTo("Resume")
			assertThat(findButton("Step Event").isEnabled).isTrue()
			assertThat(findButton("Step Time").isEnabled).isTrue()
		}
	}

	// ── Step buttons ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("clicking Step Event requests one event step")
	fun stepEventRequestsEventStep() {
		val runner = runnerWith(paused = true)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findButton("Step Event").doClick()
		}
		assertThat(runner.pollStepEvent()).isTrue()
		assertThat(runner.pollStepEvent()).isFalse()
	}

	@Test
	@DisplayName("clicking Step Time requests a time step using the current delta")
	fun stepTimeRequestsTimeStep() {
		val runner = runnerWith(paused = true, delta = 3.0)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			findButton("Step Time").doClick()
		}
		assertThat(runner.pollStepTime()).isEqualTo(3.0)
		assertThat(runner.pollStepTime()).isNull()
	}

	@Test
	@DisplayName("step buttons are disabled when the simulation is not paused")
	fun stepButtonsDisabledWhenRunning() {
		SwingUtilities.invokeAndWait {
			panel.runner = runnerWith(paused = false)
		}
		SwingUtilities.invokeAndWait {
			assertThat(findButton("Step Event").isEnabled).isFalse()
			assertThat(findButton("Step Time").isEnabled).isFalse()
		}
	}

	// ── Step-time spinner ─────────────────────────────────────────────────────

	@Test
	@DisplayName("step-time spinner defaults to 1.0 with range 0.001–60.0")
	fun spinnerDefaults() {
		assertThat(spinnerModel().value as Double).isEqualTo(1.0)
		assertThat(spinnerModel().minimum as Double).isEqualTo(0.001)
		assertThat(spinnerModel().maximum as Double).isEqualTo(60.0)
	}

	@Test
	@DisplayName("changing the spinner updates runner.stepTimeDelta")
	fun spinnerUpdatesRunnerDelta() {
		val runner = runnerWith(paused = false)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			spinnerModel().value = 2.5
		}
		assertThat(runner.stepTimeDelta).isEqualTo(2.5)
	}

	@Test
	@DisplayName("step-time request uses the spinner value configured after wiring")
	fun stepTimeUsesConfiguredSpinnerValue() {
		val runner = runnerWith(paused = true)
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			spinnerModel().value = 4.0
			findButton("Step Time").doClick()
		}
		assertThat(runner.stepTimeDelta).isEqualTo(4.0)
		assertThat(runner.pollStepTime()).isEqualTo(4.0)
	}

	@Test
	@DisplayName("spinner rejects an out-of-range user value with ParseException")
	fun spinnerRejectsOutOfRangeUserValue() {
		val runner = runnerWith(paused = false)
		var parseException: java.text.ParseException? = null
		SwingUtilities.invokeAndWait {
			panel.runner = runner
			val spinner = findSpinner()
			(spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.text = "120.0"
			try {
				spinner.commitEdit()
			} catch (e: java.text.ParseException) {
				parseException = e
			}
		}
		assertThat(parseException).isNotNull()
		assertThat(runner.stepTimeDelta).isEqualTo(1.0)
	}
}
