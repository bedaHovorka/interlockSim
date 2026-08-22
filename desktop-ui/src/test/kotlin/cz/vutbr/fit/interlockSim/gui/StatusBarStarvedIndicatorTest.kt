/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for StatusBar starved-run indicator (Issue #930, Wave 3)
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
 * Tests for the Issue #930 starved-run indicator in [StatusBar].
 *
 * Until #930 the GUI had no run-quality signal at all: a run in which no train completed a journey
 * looked exactly like a healthy one. [StatusBar.setStarvedIndicator] is the widget half of the fix;
 * the recording half is pinned by `DispatcherRunPersistenceTest` and `FrameDispatcherMetricsLogTest`.
 *
 * Mirrors [StatusBarWarningIndicatorTest], including its off-EDT case — the STOPPED transition can
 * be driven by `SimulationController`'s monitor thread, so the setter must marshal.
 */
@DisplayName("StatusBar starved indicator")
class StatusBarStarvedIndicatorTest {
	private lateinit var statusBar: StatusBar

	@BeforeEach
	fun setUp() {
		statusBar = StatusBar()
	}

	@Test
	@DisplayName("starved indicator is hidden by default")
	fun starvedIndicatorHiddenByDefault() {
		assertThat(statusBar.isStarvedIndicatorVisible()).isFalse()
		assertThat(statusBar.starvedIndicatorText()).isEqualTo("")
	}

	@Test
	@DisplayName("setStarvedIndicator(true) shows the starved badge")
	fun setStarvedIndicatorTrueShowsBadge() {
		statusBar.setStarvedIndicator(true)
		flushEDT()

		assertThat(statusBar.isStarvedIndicatorVisible()).isTrue()
		assertThat(statusBar.starvedIndicatorText()).isEqualTo(StatusBar.STARVED_BADGE_TEXT)
	}

	@Test
	@DisplayName("setStarvedIndicator(false) hides the starved badge")
	fun setStarvedIndicatorFalseHidesBadge() {
		statusBar.setStarvedIndicator(true)
		flushEDT()

		statusBar.setStarvedIndicator(false)
		flushEDT()

		assertThat(statusBar.isStarvedIndicatorVisible()).isFalse()
		assertThat(statusBar.starvedIndicatorText()).isEqualTo("")
	}

	/**
	 * The starvation verdict is independent of the collision-warning badge: a starved run raises no
	 * CRITICAL warning, and a collision does not mean the railway starved. Clearing one must not
	 * clear the other, or `Frame`'s stop path would erase the verdict it just recorded.
	 */
	@Test
	@DisplayName("the starved and warning indicators are independent")
	fun starvedAndWarningIndicatorsAreIndependent() {
		statusBar.setStarvedIndicator(true)
		statusBar.setWarningIndicator(false)
		flushEDT()

		assertThat(statusBar.isStarvedIndicatorVisible()).isTrue()
		assertThat(statusBar.isWarningIndicatorVisible()).isFalse()
	}

	@Test
	@DisplayName("setStarvedIndicator from a non-EDT thread marshals the update to the EDT")
	fun setStarvedIndicatorFromNonEdtMarshalsToEdt() {
		assertThat(SwingUtilities.isEventDispatchThread()).isFalse()
		val latch = CountDownLatch(1)

		Thread {
			statusBar.setStarvedIndicator(true)
			latch.countDown()
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(statusBar.isStarvedIndicatorVisible()).isTrue()
		assertThat(statusBar.starvedIndicatorText()).isEqualTo(StatusBar.STARVED_BADGE_TEXT)
	}

	private fun flushEDT() {
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }
	}
}
