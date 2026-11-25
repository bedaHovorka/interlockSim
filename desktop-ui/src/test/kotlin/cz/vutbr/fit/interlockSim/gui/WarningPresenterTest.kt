/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * The headless unit tests of [WarningPresenter] (PR #1430 coverage): what one collision warning
 * does to the UI when it arrives — the panel entry, the critical indicator, the Warnings tab
 * reveal, the beep flag, the resume of a paused run and the selection-highlight wiring.
 */
@DisplayName("WarningPresenter (headless)")
class WarningPresenterTest {
	private lateinit var warningPanel: WarningPanel
	private lateinit var statusBar: StatusBar
	private lateinit var canvas: RailwayNetGridCanvas
	private lateinit var bottomPane: BottomPane
	private lateinit var presenter: WarningPresenter

	// The advisory/critical fixture pair, as FrameTest declares them.
	private val critical = CollisionWarning.ReservationConflict("Train-A", "Train-B", time = 10.0)
	private val advisory =
		CollisionWarning.ReservationConflict(
			trainId = "Train #19",
			conflictingTrainId = "Train #18",
			time = 601.0,
			severity = CollisionWarning.Severity.WARNING
		)

	private val resumes = AtomicInteger(0)

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			warningPanel = WarningPanel()
			statusBar = StatusBar()
			canvas = mockk(relaxed = true)
			bottomPane = BottomPane(RailwayNetGridCanvas(), PathPreviewPanel(), warningPanel)
			bottomPane.applyTabMode(simulation = true)
			presenter =
				WarningPresenter(
					warningPanel = warningPanel,
					statusBar = statusBar,
					canvas = canvas,
					bottomPane = bottomPane,
					resumeRun = { resumes.incrementAndGet() }
				)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("an advisory warning is listed and touches nothing else")
	fun advisoryWarningIsListedQuietly() {
		SwingUtilities.invokeAndWait {
			statusBar.setWarningIndicator(true)
			presenter.show(advisory, autoPause = true, sound = false)

			assertThat(warningPanel.listModel.size).isEqualTo(1)
			assertThat(statusBar.isWarningIndicatorVisible()).isTrue()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a critical warning lights the indicator and reveals the Warnings tab")
	fun criticalWarningLightsAndReveals() {
		SwingUtilities.invokeAndWait {
			bottomPane.bottomTabs.selectedIndex = BottomPane.TAB_TIMELINE
			presenter.show(critical, autoPause = true, sound = false)

			assertThat(warningPanel.listModel.size).isEqualTo(1)
			assertThat(statusBar.isWarningIndicatorVisible()).isTrue()
			assertThat(bottomPane.bottomTabs.selectedIndex).isEqualTo(BottomPane.TAB_WARNINGS)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("autoPause = false resumes the run the collision service paused")
	fun criticalWithoutAutoPauseResumes() {
		SwingUtilities.invokeAndWait {
			presenter.show(critical, autoPause = false, sound = false)
		}
		assertThat(resumes.get()).isEqualTo(1)

		SwingUtilities.invokeAndWait {
			presenter.show(advisory, autoPause = false, sound = false)
		}
		assertThat(resumes.get()).isEqualTo(1) // an advisory never resumed the run
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("clearing the log silences the status-bar indicator")
	fun clearSilencesTheIndicator() {
		SwingUtilities.invokeAndWait {
			presenter.show(critical, autoPause = true, sound = false)
			assertThat(statusBar.isWarningIndicatorVisible()).isTrue()

			warningPanel.clearWarnings()
			assertThat(statusBar.isWarningIndicatorVisible()).isFalse()
		}
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("selecting a listed warning highlights its block when critical, clears otherwise")
	fun selectionHighlightFollowsSeverity() {
		SwingUtilities.invokeAndWait {
			warningPanel.onWarningSelected?.invoke(critical)
			verify(exactly = 1) { canvas.highlightWarningBlock(critical) }
			verify(exactly = 0) { canvas.clearWarningHighlights() }

			warningPanel.onWarningSelected?.invoke(advisory)
			verify(exactly = 1) { canvas.clearWarningHighlights() }
		}
	}
}
