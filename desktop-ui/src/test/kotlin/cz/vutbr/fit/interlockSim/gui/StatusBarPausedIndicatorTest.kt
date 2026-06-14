/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for StatusBar paused indicator (Issue #503)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.beans.PropertyChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Tests for the Goal 8 paused indicator in [StatusBar].
 *
 * The indicator is surfaced via [StatusBar.setPaused] and is wired to
 * [SimulationRunner.PROP_IS_PAUSED] events from a runner.
 */
@DisplayName("StatusBar paused indicator")
class StatusBarPausedIndicatorTest {
	private lateinit var statusBar: StatusBar
	private lateinit var context: SimulationContext

	@BeforeEach
	fun setUp() {
		statusBar = StatusBar()
		context = mockk(relaxed = true)
	}

	@Test
	@DisplayName("setPaused(true) shows [PAUSED] in the badge color")
	fun setPausedTrueShowsPausedBadge() {
		statusBar.setPaused(true)
		flushEDT()

		assertThat(statusBar.isPausedIndicatorVisible()).isTrue()
		assertThat(statusBar.pausedIndicatorText()).isEqualTo("[PAUSED]")
		assertThat(statusBar.pausedIndicatorForeground()).isEqualTo(StatusBarColors.PAUSED_BADGE_COLOR)
	}

	@Test
	@DisplayName("setPaused(false) hides the paused indicator")
	fun setPausedFalseHidesBadge() {
		statusBar.setPaused(true)
		flushEDT()

		statusBar.setPaused(false)
		flushEDT()

		assertThat(statusBar.isPausedIndicatorVisible()).isFalse()
		assertThat(statusBar.pausedIndicatorText()).isEqualTo("")
	}

	@Test
	@DisplayName("paused badge is not shown by default")
	fun pausedBadgeHiddenByDefault() {
		assertThat(statusBar.isPausedIndicatorVisible()).isFalse()
		assertThat(statusBar.pausedIndicatorText()).isEqualTo("")
	}

	@Test
	@DisplayName("PROP_IS_PAUSED event from runner updates indicator on EDT")
	fun propertyChangeEventUpdatesIndicator() {
		val runner = SimulationRunner(context)
		val latch = CountDownLatch(1)
		val listener = PropertyChangeListener { evt ->
			if (evt.propertyName == SimulationRunner.PROP_IS_PAUSED) {
				statusBar.setPaused(evt.newValue as Boolean)
				latch.countDown()
			}
		}
		runner.addPropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, listener)

		runner.isPaused = true
		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(statusBar.isPausedIndicatorVisible()).isTrue()
		assertThat(statusBar.pausedIndicatorText()).isEqualTo("[PAUSED]")

		runner.removePropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, listener)
	}

	@Test
	@DisplayName("setPaused from non-EDT thread marshals update to EDT")
	fun setPausedFromNonEdtMarshalsToEdt() {
		assertThat(SwingUtilities.isEventDispatchThread()).isFalse()
		val latch = CountDownLatch(1)

		Thread {
			statusBar.setPaused(true)
			latch.countDown()
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(statusBar.isPausedIndicatorVisible()).isTrue()
		assertThat(statusBar.pausedIndicatorText()).isEqualTo("[PAUSED]")
	}

	private fun flushEDT() {
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }
	}
}
