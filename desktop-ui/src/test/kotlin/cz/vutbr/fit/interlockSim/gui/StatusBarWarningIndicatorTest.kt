/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for StatusBar warning indicator (Issue #616, Goal 3 SP6)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Tests for the Goal 3 SP6 warning indicator in [StatusBar].
 *
 * The indicator is surfaced via [StatusBar.setWarningIndicator] and shows
 * "⚠ WARNING" in red when a CRITICAL collision warning is unacknowledged.
 */
@DisplayName("StatusBar warning indicator")
class StatusBarWarningIndicatorTest {
	private lateinit var statusBar: StatusBar

	@BeforeEach
	fun setUp() {
		statusBar = StatusBar()
	}

	@Test
	@DisplayName("warning indicator is hidden by default")
	fun warningIndicatorHiddenByDefault() {
		assertThat(statusBar.isWarningIndicatorVisible()).isFalse()
		assertThat(statusBar.warningIndicatorText()).isEqualTo("")
	}

	@Test
	@DisplayName("setWarningIndicator(true) shows warning badge")
	fun setWarningIndicatorTrueShowsBadge() {
		statusBar.setWarningIndicator(true)
		flushEDT()

		assertThat(statusBar.isWarningIndicatorVisible()).isTrue()
		assertThat(statusBar.warningIndicatorText()).isEqualTo("⚠ WARNING")
	}

	@Test
	@DisplayName("setWarningIndicator(false) hides warning badge")
	fun setWarningIndicatorFalseHidesBadge() {
		statusBar.setWarningIndicator(true)
		flushEDT()

		statusBar.setWarningIndicator(false)
		flushEDT()

		assertThat(statusBar.isWarningIndicatorVisible()).isFalse()
		assertThat(statusBar.warningIndicatorText()).isEqualTo("")
	}

	@Test
	@DisplayName("setWarningIndicator from non-EDT thread marshals update to EDT")
	fun setWarningIndicatorFromNonEdtMarshalsToEdt() {
		assertThat(SwingUtilities.isEventDispatchThread()).isFalse()
		val latch = CountDownLatch(1)

		Thread {
			statusBar.setWarningIndicator(true)
			latch.countDown()
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(statusBar.isWarningIndicatorVisible()).isTrue()
		assertThat(statusBar.warningIndicatorText()).isEqualTo("⚠ WARNING")
	}

	private fun flushEDT() {
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }
	}
}
