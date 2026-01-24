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

import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JScrollPane

/**
 * Program main window
 */
class Frame : JFrame(PROGRAM_FULL_NAME) {
	val railwayNetGridCanvas: RailwayNetGridCanvas = RailwayNetGridCanvas()
	internal val statusBar: StatusBar = StatusBar()

	/**
	 * Tracks modification state for unsaved changes warning.
	 */
	val modificationTracker: ModificationTracker =
		ModificationTracker { isDirty ->
			updateTitle()
		}

	init {
		setSize(800, 600)
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE) // Handle close event manually
		setLayout(BorderLayout())
		jMenuBar = MenuBar()
		contentPane.add(JScrollPane(railwayNetGridCanvas), BorderLayout.CENTER)
		contentPane.add(ToolBar(), BorderLayout.NORTH)

		statusBar.registerProducer(railwayNetGridCanvas)
		contentPane.add(statusBar, BorderLayout.SOUTH)

		// Add component listener to refresh canvas when frame is resized
		addComponentListener(
			object : ComponentAdapter() {
				override fun componentResized(e: ComponentEvent) {
					// When frame is resized, refresh grid canvas to handle scrollbar appearance/disappearance
					railwayNetGridCanvas.revalidate()
				}
			}
		)

		// Add window listener to handle close event with unsaved changes warning
		addWindowListener(
			object : WindowAdapter() {
				override fun windowClosing(e: WindowEvent) {
					handleWindowClosing()
				}
			}
		)
	}

	fun setContext(context: Context<*, *>) {
		context.addPropertyChangeListener(statusBar)
		railwayNetGridCanvas.setContext(context)

		// Register modification tracker if context supports editing
		if (context is EditingContext) {
			context.addPropertyChangeListener(modificationTracker)
		}
	}

	/**
	 * Updates the window title to reflect current file and dirty state.
	 */
	private fun updateTitle() {
		val fileName = modificationTracker.getDisplayFileName()
		val suffix = modificationTracker.getTitleSuffix()

		title =
			if (fileName != null) {
				"$PROGRAM_FULL_NAME - $fileName$suffix"
			} else {
				PROGRAM_FULL_NAME
			}
	}

	/**
	 * Handles window closing event.
	 * Shows confirmation dialog if there are unsaved changes.
	 *
	 * Thread safety: Ensures execution on EDT for Swing component access.
	 */
	private fun handleWindowClosing() {
		// Defensive programming: ensure we're on the Event Dispatch Thread
		if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
			javax.swing.SwingUtilities.invokeLater { handleWindowClosing() }
			return
		}

		if (modificationTracker.isDirty()) {
			val result =
				JOptionPane.showConfirmDialog(
					this,
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
		val menuBar = jMenuBar as MenuBar
		val saved = menuBar.triggerSave()

		// Only exit if save was successful
		if (saved) {
			exitWithoutSaving()
		}
	}

	/**
	 * Exits the application without saving.
	 */
	private fun exitWithoutSaving() {
		dispose()
		System.exit(0)
	}
}
