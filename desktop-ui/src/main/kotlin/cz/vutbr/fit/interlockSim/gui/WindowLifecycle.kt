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

import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Window chrome lifecycle (Issue #1421): the start geometry, the normal-bounds tracking behind a
 * maximized window, and the close path — unsaved-changes confirmation, save-and-exit and the
 * teardown exit that saves the window state.
 *
 * The window is reached through [WindowHandle], so the headless tests drive the close path on a
 * fake (PR #1430 coverage). [Frame] keeps only the two event listeners (its own canvas revalidation
 * and the close event) and forwards them here. [teardown] bundles Frame's own stop/cleanup lines so
 * this class stays free of Frame internals; [isDirty] and [save] read the tracker and the menu bar
 * lazily at close time.
 *
 * **All methods are EDT-only.**
 */
internal class WindowLifecycle(
	private val handle: WindowHandle,
	private val store: WindowStateStore?,
	private val bottomPane: BottomPane,
	/** Parent component for the modal unsaved-changes dialog; today [Frame] itself. */
	private val dialogParent: java.awt.Component?,
	private val isDirty: () -> Boolean,
	private val save: () -> Boolean,
	private val teardown: () -> Unit
) {
	/** Last bounds while the window was neither maximized nor iconified; saved on exit. */
	private var lastNormalBounds: Rectangle? = null

	/**
	 * Terminal JVM exit; replaceable so tests can watch the exit instead of leaving the worker
	 * (same seam pattern as [SimulationRunner nanoTime]).
	 */
	internal var exit: (Int) -> Unit = { System.exit(it) }

	/**
	 * Sets the start bounds (Issue #1421): the saved layout when it still lies on a connected
	 * screen, else [WindowGeometry.initialBounds] on the usable screen area.
	 */
	fun applyInitialGeometry() {
		val saved = store?.load()
		if (saved != null && WindowGeometry.isOnScreen(saved.bounds, WindowGeometry.screenBounds())) {
			handle.windowBounds = saved.bounds
			lastNormalBounds = Rectangle(saved.bounds)
			if (saved.maximized) {
				handle.windowState = handle.windowState or JFrame.MAXIMIZED_BOTH
			}
			bottomPane.setRestoredBottomHeight(saved.bottomHeight, handle.windowBounds.height)
		} else {
			handle.windowBounds = WindowGeometry.initialBounds(WindowGeometry.usableScreen())
			bottomPane.setRestoredBottomHeight(null, handle.windowBounds.height)
		}
	}

	/** Records the bounds while the window is in its normal state, so a maximized exit saves them. */
	fun rememberNormalBounds() {
		if (handle.windowState and (JFrame.MAXIMIZED_BOTH or JFrame.ICONIFIED) == 0 && handle.windowShowing) {
			lastNormalBounds = handle.windowBounds
		}
	}

	/** Saves bounds, maximized state and tab-area height on exit (Issue #1421). */
	fun saveWindowState() {
		val store = store ?: return
		val maximized = handle.windowState and JFrame.MAXIMIZED_BOTH == JFrame.MAXIMIZED_BOTH
		store.save(
			WindowStateStore.WindowState(
				bounds = lastNormalBounds ?: handle.windowBounds,
				maximized = maximized,
				bottomHeight = bottomPane.rememberBottomHeight()
			)
		)
	}

	/**
	 * Handles a close request: confirms unsaved changes and exits (or stays open on cancel). Also
	 * the route menu Exit takes, so both exit paths confirm and save the layout (PR #1430 review).
	 *
	 * Thread safety: ensures execution on the EDT for Swing component access.
	 */
	fun requestClose() {
		// Defensive programming: ensure we're on the Event Dispatch Thread
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater { requestClose() }
			return
		}

		if (isDirty()) {
			val result =
				JOptionPane.showConfirmDialog(
					dialogParent,
					"The railway network has unsaved changes.\n\n" +
						"Do you want to save your changes before closing?",
					"Unsaved Changes",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.WARNING_MESSAGE
				)

			when (result) {
				JOptionPane.YES_OPTION -> {
					// Trigger save action
					saveAndExit()
				}

				JOptionPane.NO_OPTION -> {
					// Exit without saving
					exitWithoutSaving()
				}

				JOptionPane.CANCEL_OPTION -> {
					// Do nothing - keep window open
					return
				}
			}
		} else {
			// No unsaved changes - exit immediately
			exitWithoutSaving()
		}
	}

	/**
	 * Attempts to save the current context and then exits.
	 * If save fails, the window remains open.
	 */
	private fun saveAndExit() {
		// Get the save action from menu bar and trigger it
		val saved = save()

		// Only exit if save was successful
		if (saved) {
			exitWithoutSaving()
		}
	}

	/**
	 * Exits the application without saving.
	 */
	private fun exitWithoutSaving() {
		teardown()
		saveWindowState()
		handle.dispose()
		exit(0)
	}
}
