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
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * The headless unit tests of [BottomPane] (PR #1430 coverage): the three tabs under the canvas,
 * their titles and mode switching, the timeline slot, the tab-area height rules and the
 * collapse-and-reveal behavior behind a critical warning. The split pane lays itself out
 * headless (a spike proved the divider math without a `JFrame`).
 */
@DisplayName("BottomPane (headless)")
class BottomPaneTest {
	private lateinit var canvas: RailwayNetGridCanvas
	private lateinit var warningPanel: WarningPanel
	private lateinit var pathPreviewPanel: PathPreviewPanel
	private lateinit var bottomPane: BottomPane

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			canvas = RailwayNetGridCanvas()
			warningPanel = WarningPanel()
			pathPreviewPanel = PathPreviewPanel()
			bottomPane = BottomPane(canvas, pathPreviewPanel, warningPanel)
		}
	}

	/** Lays the split pane out at [width] x [height] so the divider math runs, on the EDT. */
	private fun layoutSplit(
		width: Int,
		height: Int
	) {
		SwingUtilities.invokeAndWait {
			bottomPane.splitPane.setSize(width, height)
			bottomPane.splitPane.doLayout()
		}
	}

	/** The user folded the tab area up: the divider sits at the bottom edge. */
	private fun collapseTabArea() {
		SwingUtilities.invokeAndWait {
			val split = bottomPane.splitPane
			split.dividerLocation = split.height - split.dividerSize
			split.doLayout()
		}
	}

	private fun <T> onEdt(action: () -> T): T {
		val holder =
			java.util.concurrent.atomic
				.AtomicReference<Any?>()
		if (SwingUtilities.isEventDispatchThread()) {
			return action()
		}
		SwingUtilities.invokeAndWait { holder.set(action()) }
		@Suppress("UNCHECKED_CAST")
		return holder.get() as T
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the three tabs are built in order: Path Preview, Warnings, Timeline placeholder")
	fun tabsAreBuiltInOrder() {
		onEdt {
			val tabs = bottomPane.bottomTabs
			assertThat(tabs.tabCount).isEqualTo(3)
			assertThat(tabs.getComponentAt(BottomPane.TAB_PATH_PREVIEW))
				.isSameInstanceAs(pathPreviewPanel)
			assertThat(tabs.getComponentAt(BottomPane.TAB_WARNINGS))
				.isSameInstanceAs(warningPanel)
			assertThat(tabs.getComponentAt(BottomPane.TAB_TIMELINE)).isInstanceOf(JLabel::class)
			assertThat(tabs.getTitleAt(BottomPane.TAB_PATH_PREVIEW)).isEqualTo(BottomPane.TAB_TITLE_PATH_PREVIEW)
			assertThat(tabs.getTitleAt(BottomPane.TAB_WARNINGS)).isEqualTo(BottomPane.warningsTabTitle(0))
			assertThat(tabs.getTitleAt(BottomPane.TAB_TIMELINE)).isEqualTo(BottomPane.TAB_TITLE_TIMELINE)
			assertThat(tabs.selectedIndex).isEqualTo(BottomPane.TAB_PATH_PREVIEW)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the Warnings tab title follows the count the panel reports")
	fun warningsCountFollowsTheTitle() {
		onEdt {
			warningPanel.onCountChanged?.invoke(2)
			assertThat(bottomPane.bottomTabs.getTitleAt(BottomPane.TAB_WARNINGS)).isEqualTo(
				BottomPane.warningsTabTitle(2)
			)
			warningPanel.onCountChanged?.invoke(0)
			assertThat(bottomPane.bottomTabs.getTitleAt(BottomPane.TAB_WARNINGS)).isEqualTo(
				BottomPane.TAB_TITLE_WARNINGS
			)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("applyTabMode switches the enabled set and the main selection per mode")
	fun applyTabModeSwitchesModes() {
		onEdt {
			bottomPane.applyTabMode(simulation = true)
			val tabs = bottomPane.bottomTabs
			assertThat(tabs.isEnabledAt(BottomPane.TAB_PATH_PREVIEW)).isFalse()
			assertThat(tabs.isEnabledAt(BottomPane.TAB_WARNINGS)).isTrue()
			assertThat(tabs.isEnabledAt(BottomPane.TAB_TIMELINE)).isTrue()
			assertThat(tabs.selectedIndex).isEqualTo(BottomPane.TAB_TIMELINE)

			bottomPane.applyTabMode(simulation = false)
			assertThat(tabs.isEnabledAt(BottomPane.TAB_PATH_PREVIEW)).isTrue()
			assertThat(tabs.isEnabledAt(BottomPane.TAB_WARNINGS)).isFalse()
			assertThat(tabs.selectedIndex).isEqualTo(BottomPane.TAB_PATH_PREVIEW)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("showTimelinePanel swaps the placeholder in place and is idempotent; null is a no-op")
	fun showTimelinePanelSwapsInPlace() {
		val timelinePanel = onEdt { EventTimelinePanel() }
		onEdt {
			bottomPane.showTimelinePanel(timelinePanel)
			assertThat(bottomPane.bottomTabs.getComponentAt(BottomPane.TAB_TIMELINE))
				.isSameInstanceAs(timelinePanel)

			bottomPane.showTimelinePanel(timelinePanel) // idempotent: no re-add, still the same slot
			assertThat(bottomPane.bottomTabs.getComponentAt(BottomPane.TAB_TIMELINE))
				.isSameInstanceAs(timelinePanel)

			bottomPane.showTimelinePanel(null)
			assertThat(bottomPane.bottomTabs.getComponentAt(BottomPane.TAB_TIMELINE))
				.isSameInstanceAs(timelinePanel)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setRestoredBottomHeight clamps both ends and falls back to the share on null")
	fun restoredHeightClampsAndFallsBack() {
		onEdt {
			bottomPane.setRestoredBottomHeight(50, 900)
			assertThat(bottomPane.bottomTabs.preferredSize.height).isEqualTo(BottomPane.MIN_BOTTOM_HEIGHT)

			bottomPane.setRestoredBottomHeight(5000, 900)
			assertThat(bottomPane.bottomTabs.preferredSize.height)
				.isEqualTo(900 - BottomPane.MIN_CANVAS_HEIGHT - BottomPane.MIN_BOTTOM_HEIGHT)

			bottomPane.setRestoredBottomHeight(null, 900)
			assertThat(bottomPane.bottomTabs.preferredSize.height).isEqualTo(BottomPane.defaultBottomHeight(900))
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("an open layout remembers its tab-area height; a collapsed one reads null")
	fun rememberHeightTracksTheLayout() {
		layoutSplit(1600, 900)
		val openHeight = onEdt { bottomPane.rememberBottomHeight() }
		assertThat(openHeight).isNotNull()

		collapseTabArea()
		assertThat(onEdt { bottomPane.rememberBottomHeight() }).isNull()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("reveal expands a collapsed tab area back to its last open divider position")
	fun revealExpandsTheCollapsedArea() {
		layoutSplit(1600, 900)
		val trackedHeight = onEdt { bottomPane.rememberBottomHeight() }!!
		onEdt { bottomPane.applyTabMode(simulation = true) }

		collapseTabArea()
		assertThat(onEdt { bottomPane.bottomTabs.height }).isEqualTo(0)

		onEdt {
			bottomPane.revealCriticalWarningsTab()
			assertThat(bottomPane.bottomTabs.selectedIndex).isEqualTo(BottomPane.TAB_WARNINGS)
			bottomPane.splitPane.doLayout()
			assertThat(bottomPane.bottomTabs.height).isGreaterThan(0)
		}
		assertThat(trackedHeight).isGreaterThan(0)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("reveal on an open tab area only selects Warnings and keeps the layout")
	fun revealKeepsAnOpenLayout() {
		layoutSplit(1600, 900)
		val openHeight = onEdt { bottomPane.rememberBottomHeight() }!!
		val dividerBefore = onEdt { bottomPane.splitPane.dividerLocation }
		onEdt {
			bottomPane.applyTabMode(simulation = true)
			bottomPane.bottomTabs.selectedIndex = BottomPane.TAB_TIMELINE

			bottomPane.revealCriticalWarningsTab()

			assertThat(bottomPane.bottomTabs.selectedIndex).isEqualTo(BottomPane.TAB_WARNINGS)
			bottomPane.splitPane.doLayout()
			assertThat(bottomPane.rememberBottomHeight()).isEqualTo(openHeight)
			assertThat(bottomPane.splitPane.dividerLocation).isEqualTo(dividerBefore)
		}
	}
}
