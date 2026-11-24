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

import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingConstants

/**
 * Everything between the canvas and the status bar (Issue #1421): the vertical [splitPane] with the
 * canvas scroll pane on top and the three [bottomTabs] below, fixed ordering Path Preview, Warnings,
 * Timeline, tab titles and mode switching, and the tab-area height rules (`defaultBottomHeight`).
 *
 * The tabs themselves never come or go, so the layout does not jump when the mode changes;
 * [applyTabMode] only enables the tabs that have content in the current mode.
 *
 * **All methods are EDT-only.**
 */
internal class BottomPane(
	private val canvas: RailwayNetGridCanvas,
	pathPreviewPanel: PathPreviewPanel,
	warningPanel: WarningPanel
) {
	/**
	 * Scroll pane around the canvas: the top of [splitPane]. Its preferred height is kept small on
	 * purpose: a large railway grid would otherwise claim more height than the window has, and the
	 * split pane would squeeze [bottomTabs] to its minimum. The canvas still gets all the free height
	 * through `resizeWeight = 1.0`.
	 */
	private val canvasScrollPane: JScrollPane =
		JScrollPane(canvas).apply {
			minimumSize = Dimension(0, MIN_CANVAS_HEIGHT)
			preferredSize = Dimension(0, MIN_CANVAS_HEIGHT)
		}

	/**
	 * Tabs under the canvas, in a fixed order: Path Preview, Warnings, Timeline. All three stay in
	 * both modes; [applyTabMode] enables the tabs that have content in the current mode.
	 */
	val bottomTabs: JTabbedPane =
		JTabbedPane().apply {
			preferredSize = Dimension(0, DEFAULT_BOTTOM_HEIGHT)
			minimumSize = Dimension(0, MIN_BOTTOM_HEIGHT)
		}

	/** Placeholder in the Timeline tab until the first simulation context creates a timeline panel. */
	private val timelinePlaceholder: JLabel =
		JLabel("The event timeline appears when a simulation is loaded.", SwingConstants.CENTER)

	/**
	 * Divider position of the last layout that gave the tab area usable height (PR #1430 review):
	 * remembered from the split pane's divider moves, used to expand a collapsed (one-touch) tab
	 * area back to where the user had it. `null` until the first such move.
	 */
	private var lastOpenDividerLocation: Int? = null

	/**
	 * Canvas above, [bottomTabs] below, with a bar the user can drag. `resizeWeight = 1.0`: when the
	 * window grows or shrinks, only the canvas changes height.
	 */
	val splitPane: JSplitPane =
		JSplitPane(JSplitPane.VERTICAL_SPLIT, canvasScrollPane, bottomTabs).apply {
			resizeWeight = 1.0
			isOneTouchExpandable = true
			isContinuousLayout = true
			border = null
		}

	init {
		// Wire PathPreviewPanel callbacks to the canvas (Issue #596)
		pathPreviewPanel.onRouteSelected = { routes, selectedIndex ->
			canvas.setPathPreview(routes, selectedIndex)
		}
		pathPreviewPanel.onClear = {
			canvas.clearPathPreview()
		}
		// Bottom tabs (Issue #1421), in the order of the TAB_* constants.
		bottomTabs.addTab(TAB_TITLE_PATH_PREVIEW, pathPreviewPanel)
		bottomTabs.addTab(warningsTabTitle(0), warningPanel)
		bottomTabs.addTab(TAB_TITLE_TIMELINE, timelinePlaceholder)
		applyTabMode(simulation = false)

		// Warning panel (Issue #616, Goal 3 SP6) – the Warnings tab title shows the count.
		warningPanel.onCountChanged = { count ->
			bottomTabs.setTitleAt(TAB_WARNINGS, warningsTabTitle(count))
		}

		// Track the divider so a collapsed (one-touch) tab area can be expanded back to the
		// position the user had (PR #1430 review): a divider move is "open" while the tab area
		// keeps its smallest usable height below the divider.
		splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { event ->
			(event.newValue as? Int)?.let { location ->
				val openLimit = splitPane.height - splitPane.dividerSize - MIN_BOTTOM_HEIGHT
				if (location > 0 && location <= openLimit) {
					lastOpenDividerLocation = location
				}
			}
		}
	}

	/**
	 * Reveals the Warnings tab for a critical warning (PR #1430 review): selects the tab, and when
	 * the tab area is collapsed (folded up with the split pane's one-touch bar) or too small to
	 * read, expands it to the divider position the user last had open, else to the first-start
	 * share of the current window height. The previous divider position is restored so the user's
	 * own layout decision survives a warning they did not ask to change it with.
	 */
	fun revealCriticalWarningsTab() {
		if (bottomTabs.isEnabledAt(TAB_WARNINGS)) {
			bottomTabs.selectedIndex = TAB_WARNINGS
		}
		if (bottomTabs.height >= MIN_BOTTOM_HEIGHT) return
		val restore = lastOpenDividerLocation ?: (splitPane.height * (PERCENT - BOTTOM_HEIGHT_PERCENT) / PERCENT)
		splitPane.dividerLocation = restore
	}

	/**
	 * Enables the bottom tabs that have content in the current mode and selects the main one
	 * (Issue #1421). Editing mode: Path Preview only. Simulation mode: Warnings and Timeline, with
	 * Timeline selected (a CRITICAL warning switches to Warnings).
	 */
	fun applyTabMode(simulation: Boolean) {
		bottomTabs.setEnabledAt(TAB_PATH_PREVIEW, !simulation)
		bottomTabs.setEnabledAt(TAB_WARNINGS, simulation)
		bottomTabs.setEnabledAt(TAB_TIMELINE, simulation)
		bottomTabs.selectedIndex = if (simulation) TAB_TIMELINE else TAB_PATH_PREVIEW
	}

	/**
	 * Puts [panel] into the Timeline tab in place of the placeholder (Issue #1421). A `null` panel
	 * (no simulation context yet) is a no-op, keeping the placeholder or the previous panel.
	 */
	fun showTimelinePanel(panel: EventTimelinePanel?) {
		val nonNullPanel = panel ?: return
		if (bottomTabs.getComponentAt(TAB_TIMELINE) !== nonNullPanel) {
			bottomTabs.setComponentAt(TAB_TIMELINE, nonNullPanel)
		}
	}

	/**
	 * Applies [savedHeight] as the restored tab-area height for a [windowHeight]-pixel-tall window
	 * (PR #1430 review): clamped between the smallest usable tab height and what still leaves the
	 * canvas its minimum (the saved height was measured against the window it was saved with, and a
	 * maximized restore on another screen can come back smaller). A `null` saved height falls back
	 * to the first-start share of [windowHeight], so a restore never lands on a flat default.
	 */
	fun setRestoredBottomHeight(
		savedHeight: Int?,
		windowHeight: Int
	) {
		val restored =
			savedHeight
				?.coerceAtLeast(MIN_BOTTOM_HEIGHT)
				?.coerceAtMost(maxUsableBottomHeight(windowHeight))
				?: defaultBottomHeight(windowHeight)
		bottomTabs.preferredSize = Dimension(0, restored)
	}

	/** The largest tab-area height a [windowHeight]-pixel-tall window can still use. */
	private fun maxUsableBottomHeight(windowHeight: Int): Int =
		(windowHeight - MIN_CANVAS_HEIGHT - MIN_BOTTOM_HEIGHT).coerceAtLeast(MIN_BOTTOM_HEIGHT)

	/** Applies the first-start tab-area height for a window [windowHeight] pixels high. */
	fun setInitialBottomHeight(windowHeight: Int) {
		bottomTabs.preferredSize = Dimension(0, defaultBottomHeight(windowHeight))
	}

	/** The tab-area height while the window shows one, or `null` (collapsed or never laid out). */
	fun rememberBottomHeight(): Int? = bottomTabs.height.takeIf { it > 0 }

	companion object {
		// Bottom tab indices, in the order the tabs are added to [bottomTabs]
		internal const val TAB_PATH_PREVIEW = 0
		internal const val TAB_WARNINGS = 1
		internal const val TAB_TIMELINE = 2

		internal const val TAB_TITLE_PATH_PREVIEW = "Path Preview"
		internal const val TAB_TITLE_WARNINGS = "Warnings"
		internal const val TAB_TITLE_TIMELINE = "Timeline"

		/** Lower limit of the tab-area height at the first start, before the user drags the split bar. */
		internal const val DEFAULT_BOTTOM_HEIGHT = 200

		/** Share of the window height, in percent, that the tab area gets at the first start. */
		internal const val BOTTOM_HEIGHT_PERCENT = 30

		/**
		 * Tab-area height at the first start for a window [windowHeight] pixels high:
		 * [BOTTOM_HEIGHT_PERCENT] of it, at least [DEFAULT_BOTTOM_HEIGHT].
		 */
		internal fun defaultBottomHeight(windowHeight: Int): Int =
			(windowHeight * BOTTOM_HEIGHT_PERCENT / PERCENT).coerceAtLeast(DEFAULT_BOTTOM_HEIGHT)

		private const val PERCENT = 100

		/** Smallest height of the tab area: one tab row and a few lines of content. */
		internal const val MIN_BOTTOM_HEIGHT = 80

		/** Smallest height the split bar leaves to the canvas. */
		internal const val MIN_CANVAS_HEIGHT = 120

		/** Warnings tab title: the plain title with no warnings, else the title with the count. */
		internal fun warningsTabTitle(count: Int): String =
			if (count > 0) "$TAB_TITLE_WARNINGS ($count)" else TAB_TITLE_WARNINGS
	}
}
