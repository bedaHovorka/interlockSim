/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ControlPanel (Issue #189)
*/

package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Unit tests for [ControlPanel].
 *
 * [ControlPanel] is a [javax.swing.JPanel] subclass and can be instantiated in
 * headless environments (no X11 display required). All state-mutation tests drive
 * it via its public API and inspect the child components directly.
 *
 * Covers:
 * - Stop button disabled on construction
 * - [setStopEnabled] enables and disables the Stop button
 * - [updateStatus] changes the status label text
 * - [onStop] callback wired and invoked when Stop button is clicked
 * - [onStop] = null removes callback (no exception on click)
 * - Status label initial value is "Status: Ready"
 */
@DisplayName("ControlPanel")
class ControlPanelTest {
	private lateinit var panel: ControlPanel

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			panel = ControlPanel()
		}
	}

	// ── Stop button initial state ─────────────────────────────────────────────

	@Test
	@DisplayName("stop button is disabled on construction")
	fun stopButtonDisabledOnConstruction() {
		SwingUtilities.invokeAndWait {
			assertThat(findStopButton()!!.isEnabled).isFalse()
		}
	}

	// ── setStopEnabled ────────────────────────────────────────────────────────

	@Test
	@DisplayName("setStopEnabled(true) enables the stop button")
	fun setStopEnabledTrue() {
		SwingUtilities.invokeAndWait {
			panel.setStopEnabled(true)
			assertThat(findStopButton()!!.isEnabled).isTrue()
		}
	}

	@Test
	@DisplayName("setStopEnabled(false) disables the stop button")
	fun setStopEnabledFalse() {
		SwingUtilities.invokeAndWait {
			panel.setStopEnabled(true)
			panel.setStopEnabled(false)
			assertThat(findStopButton()!!.isEnabled).isFalse()
		}
	}

	// ── updateStatus ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("status label initial value is 'Status: Ready'")
	fun statusLabelInitialValue() {
		SwingUtilities.invokeAndWait {
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Ready")
		}
	}

	@Test
	@DisplayName("updateStatus changes the status label")
	fun updateStatusChangesLabel() {
		SwingUtilities.invokeAndWait {
			panel.updateStatus(ControlPanel.SimulationStatus.RUNNING)
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Running")
		}
	}

	@Test
	@DisplayName("updateStatus to Stopped reflects in label")
	fun updateStatusToStopped() {
		SwingUtilities.invokeAndWait {
			panel.updateStatus(ControlPanel.SimulationStatus.STOPPED)
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Stopped")
		}
	}

	// ── onStop callback ───────────────────────────────────────────────────────

	@Test
	@DisplayName("onStop is null by default")
	fun onStopNullByDefault() {
		assertThat(panel.onStop).isNull()
	}

	@Test
	@DisplayName("onStop can be set to a non-null callback")
	fun onStopCanBeSet() {
		panel.onStop = { /* no-op */ }
		assertThat(panel.onStop).isNotNull()
	}

	@Test
	@DisplayName("clicking stop button invokes onStop callback")
	fun clickingStopInvokesCallback() {
		var invoked = false
		SwingUtilities.invokeAndWait {
			panel.onStop = { invoked = true }
			panel.setStopEnabled(true)
			findStopButton()!!.doClick()
		}
		assertThat(invoked).isTrue()
	}

	@Test
	@DisplayName("clicking stop button when onStop is null does not throw")
	fun clickingStopWithNullCallbackIsNoOp() {
		SwingUtilities.invokeAndWait {
			panel.onStop = null
			panel.setStopEnabled(true)
			findStopButton()!!.doClick() // must not throw
		}
	}

	@Test
	@DisplayName("setting onStop to null clears callback")
	fun settingOnStopToNullClearsCallback() {
		panel.onStop = { /* no-op */ }
		panel.onStop = null
		assertThat(panel.onStop).isNull()
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private fun findStopButton(): JButton? =
		(0 until panel.componentCount)
			.mapNotNull { panel.getComponent(it) as? JButton }
			.firstOrNull { it.text == "Stop" }

	private fun findStatusLabel(): JLabel? =
		(0 until panel.componentCount)
			.mapNotNull { panel.getComponent(it) as? JLabel }
			.firstOrNull { it.text.startsWith("Status:") }
}
