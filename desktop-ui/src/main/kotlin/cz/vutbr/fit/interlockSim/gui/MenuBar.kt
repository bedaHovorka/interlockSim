/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.mp.KoinPlatform.getKoin
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.KeyStroke

/**
 * Application menu bar with File and Help menus
 */
class MenuBar : JMenuBar() {
	private val saveAction = SaveAction()
	private val saveAsAction = SaveAsAction()

	companion object {
		/**
		 * Pure validation: returns true if [context] has enough InOut elements to be saved.
		 * Does not show any dialog — callers handle error presentation.
		 * Issue #80: GUI validation to prevent saving contexts with insufficient InOut elements
		 */
		fun validateForSave(context: EditingContext): Boolean =
			context.getInOuts().size >= XMLContextFactory.MIN_INOUT_ELEMENTS
	}

	/**
	 * Opens a railway network file from disk into the EDITOR.
	 *
	 * Issue #258 & Enhancement: Lenient validation behavior for editor mode:
	 * - EDITOR MODE: Show WARNING for parseable XML with validation errors, allow "Open Anyway"
	 *   (Users need to be able to open broken files to fix them)
	 * - UNPARSEABLE XML (malformed syntax): BLOCK with error message
	 * - SIMULATION MODE: BLOCK invalid XML from transforming to simulation context
	 *   (Invalid configurations must not be allowed to run simulations)
	 * - Validation will occur on SAVE to prevent creating invalid files
	 *
	 * Implementation (2026-02-06):
	 * 1. Try lenient parsing via XMLContextFactory.createContextLenient()
	 * 2. If unparseable (malformed XML): Show error dialog and block
	 * 3. If parseable but has validation errors: Show ValidationDialog with "Open Anyway"
	 * 4. If user clicks "Open Anyway": Load the context into editor for fixing
	 * 5. If no errors: Load context directly
	 */
	private inner class OpenAction : AbstractAction("Open...") {
		override fun actionPerformed(e: ActionEvent) {
			val fileChooser = JFileChooser(System.getProperty("user.dir"))
			fileChooser.dialogTitle = "Open Railway Network"

			val returnValue = fileChooser.showOpenDialog(this@MenuBar)
			if (returnValue != JFileChooser.APPROVE_OPTION) return

			val selectedFile: File = fileChooser.selectedFile

			try {
				// Use lenient parsing to separate unparseable XML from validation errors
				val xmlContextFactory = getKoin().get<XMLContextFactory>()
				val parseResult = xmlContextFactory.createContextLenient(selectedFile)

				when {
					// Case 1: Successfully parsed with no errors
					parseResult.isParseable && parseResult.validationResult.isValid -> {
						val context = parseResult.context!!
						val frame = getKoin().get<Frame>()
						frame.setContext(context)
						frame.modificationTracker.setCurrentFile(selectedFile)
						frame.modificationTracker.markClean()
					}

					// Case 2: Parseable but has validation errors - show ValidationDialog with "Open Anyway"
					parseResult.isParseable && !parseResult.validationResult.isValid -> {
						val context = parseResult.context!!
						val dialogResult =
							ValidationDialog.show(
								this@MenuBar,
								parseResult.validationResult,
								selectedFile,
								allowOpenAnyway = true
							)

						if (dialogResult == ValidationDialog.DialogResult.OPEN_ANYWAY) {
							// User chose to open anyway - load context
							val frame = getKoin().get<Frame>()
							frame.setContext(context)
							frame.modificationTracker.setCurrentFile(selectedFile)
							frame.modificationTracker.markClean()

							// Check if context has insufficient InOuts and show warning (Issue #80)
							val inOutsCount = context.getInOuts().size
							val minRequired = XMLContextFactory.MIN_INOUT_ELEMENTS
							if (inOutsCount < minRequired) {
								JOptionPane.showMessageDialog(
									this@MenuBar,
									"WARNING: This railway network has insufficient InOut elements " +
										"($inOutsCount found, $minRequired required).\n\n" +
										"The editor will prevent saving this context until you add at least " +
										"$minRequired InOut element (entry/exit point).\n\n" +
										"InOut elements define entry/exit points for trains.",
									"Validation Warning",
									JOptionPane.WARNING_MESSAGE
								)
							}
						} else {
							// User cancelled - close context to avoid resource leak
							context.close()
						}
					}

					// Case 3: Unparseable XML (malformed syntax) - show error and block
					else -> {
						JOptionPane.showMessageDialog(
							this@MenuBar,
							"Cannot open file: The XML is malformed and cannot be parsed.\n\n" +
								"Please check the file for syntax errors (missing tags, invalid characters, etc.).",
							"Unparseable XML",
							JOptionPane.ERROR_MESSAGE
						)
					}
				}
			} catch (exception: Exception) {
				// Unexpected error during parsing
				JOptionPane.showMessageDialog(
					this@MenuBar,
					"Failed to open file: ${exception.message}\n\n" +
						"An unexpected error occurred while loading the file.",
					"Cannot Open File",
					JOptionPane.ERROR_MESSAGE
				)
			}
		}
	}

	private inner class SaveAction : AbstractAction("Save") {
		override fun actionPerformed(e: ActionEvent) {
			val frame = getKoin().get<Frame>()
			val currentFile = frame.modificationTracker.getCurrentFile()

			if (currentFile != null) {
				// Save to current file
				performSave(currentFile)
			} else {
				// No current file - delegate to "Save as..."
				saveAsAction.actionPerformed(e)
			}
		}
	}

	private inner class SaveAsAction : AbstractAction("Save as...") {
		override fun actionPerformed(e: ActionEvent) {
			val fileChooser = JFileChooser(System.getProperty("user.dir"))
			fileChooser.dialogTitle = "Save Railway Network"

			val returnValue = fileChooser.showSaveDialog(this@MenuBar)
			if (returnValue != JFileChooser.APPROVE_OPTION) return

			performSave(fileChooser.selectedFile)
		}
	}

	/**
	 * Performs the actual save operation to the specified file.
	 * Updates modification tracker on success.
	 *
	 * **Validation (Issue #80):**
	 * - Pre-save validation checks InOut element count via [validateForSave]
	 * - Shows user-friendly error dialog if validation fails
	 * - Prevents saving invalid contexts that cannot be reloaded
	 *
	 * **Deferred validation (Issue #258):**
	 * - Other validation rules deferred for comprehensive validation framework
	 * - Future: Track constraints, path connectivity, etc.
	 *
	 * @return true if save succeeded, false otherwise
	 */
	private fun performSave(file: File): Boolean {
		val editingContextFactory = getKoin().get<JvmEditingContextFactory>()
		val frame = getKoin().get<Frame>()
		val editingContext = frame.railwayNetGridCanvas.getEditingContext()

		// Validate InOut count before saving (Issue #80)
		val inOutsCount = editingContext.getInOuts().size
		val minRequired = XMLContextFactory.MIN_INOUT_ELEMENTS
		if (!validateForSave(editingContext)) {
			JOptionPane.showMessageDialog(
				this,
				"Railway network must have at least $minRequired InOut element (entry/exit point).\n\n" +
					"Current count: $inOutsCount\n\n" +
					"An InOut element defines where trains can enter and/or exit the railway network.\n" +
					"Please add the required InOut element before saving.",
				"Cannot Save - Insufficient InOut Elements",
				JOptionPane.ERROR_MESSAGE
			)
			return false
		}

		val success = editingContextFactory.saveContext(editingContext, file)

		if (success) {
			// Update modification tracker
			frame.modificationTracker.setCurrentFile(file)
			frame.modificationTracker.markClean()

			// Show non-intrusive success message in status bar
			frame.statusBar.showTemporaryMessage("✓ Saved: ${file.name}", 5000)
		} else {
			// IO error — InOut validation already passed above
			JOptionPane.showMessageDialog(
				this,
				"Failed to save railway network to file: ${file.absolutePath}\n\n" +
					"Check file permissions and disk space.",
				"Save Failed - IO Error",
				JOptionPane.ERROR_MESSAGE
			)
		}

		return success
	}

	/**
	 * Triggers the save action programmatically.
	 * Used by Frame when handling window close with unsaved changes.
	 *
	 * @return true if save succeeded (or was cancelled), false if save failed
	 */
	fun triggerSave(): Boolean {
		val frame = getKoin().get<Frame>()
		val currentFile = frame.modificationTracker.getCurrentFile()

		return if (currentFile != null) {
			// Save to current file
			performSave(currentFile)
		} else {
			// Show save dialog
			val fileChooser = JFileChooser(System.getProperty("user.dir"))
			fileChooser.dialogTitle = "Save Railway Network"

			val returnValue = fileChooser.showSaveDialog(this)
			if (returnValue == JFileChooser.APPROVE_OPTION) {
				performSave(fileChooser.selectedFile)
			} else {
				// User cancelled - don't exit, stay in editor
				false
			}
		}
	}

	private inner class ExitAction : AbstractAction("Exit") {
		override fun actionPerformed(e: ActionEvent) {
			System.exit(0)
		}
	}

	/**
	 * Shows a file chooser, loads the selected XML as a [SimulationContext], sets it on the
	 * [Frame] and immediately starts the simulation.
	 */
	private inner class StartSimulationAction : AbstractAction("Start...") {
		override fun actionPerformed(e: ActionEvent) {
			val fileChooser = JFileChooser(System.getProperty("user.dir"))
			fileChooser.dialogTitle = "Start Simulation"

			val returnValue = fileChooser.showOpenDialog(this@MenuBar)
			if (returnValue != JFileChooser.APPROVE_OPTION) return

			val selectedFile: File = fileChooser.selectedFile

			try {
				val simulationContextFactory = getKoin().get<SimulationContextFactory>()
				val simContext = simulationContextFactory.createContext(selectedFile) as SimulationContext
				val frame = getKoin().get<Frame>()
				frame.setContext(simContext)
				frame.startSimulation()
			} catch (exception: Exception) {
				JOptionPane.showMessageDialog(
					this@MenuBar,
					"Failed to start simulation: ${exception.message}\n\n" +
						"Ensure the file is a valid railway network XML.",
					"Cannot Start Simulation",
					JOptionPane.ERROR_MESSAGE
				)
			}
		}
	}

	/** Terminates the currently running simulation via [Frame.stopSimulation]. */
	private inner class StopSimulationAction : AbstractAction("Stop") {
		override fun actionPerformed(e: ActionEvent) {
			val frame = getKoin().get<Frame>()
			frame.stopSimulation()
		}
	}

	/** Sets the simulation speed multiplier via [SimulationController.setSpeed]. */
	private inner class SetSpeedAction(
		private val label: String,
		private val multiplier: Double,
	) : AbstractAction(label) {
		override fun actionPerformed(e: ActionEvent) {
			val frame = getKoin().get<Frame>()
			frame.simulationController.setSpeed(multiplier)
		}
	}

	private inner class InfoAction(
		private val infoName: String,
		private val text: String
	) : AbstractAction(infoName) {
		override fun actionPerformed(e: ActionEvent) {
			JOptionPane.showMessageDialog(this@MenuBar, text, infoName, JOptionPane.INFORMATION_MESSAGE)
		}
	}

	init {
		add(fileMenu())
		add(simulationMenu())
		add(helpMenu())
	}

	private fun fileMenu(): JMenu {
		val menu = JMenu("File")
		menu.add(OpenAction())
		menu.add(saveAction)
		menu.add(saveAsAction)
		menu.addSeparator()
		menu.add(ExitAction())
		return menu
	}

	/**
	 * Builds the "Simulation" menu with Start/Stop actions and a Speed submenu.
	 *
	 * Speed presets (0.1x, 0.5x, 1x, 2x, 10x) have keyboard accelerators (keys 1–5)
	 * so that the user can change the speed without reaching for the mouse during a run.
	 */
	private fun simulationMenu(): JMenu {
		val menu = JMenu("Simulation")
		menu.add(StartSimulationAction())
		menu.add(StopSimulationAction())
		menu.addSeparator()

		val speedMenu = JMenu("Speed")
		val speedPresets =
			listOf(
				Triple("0.1x", 0.1, KeyEvent.VK_1),
				Triple("0.5x", 0.5, KeyEvent.VK_2),
				Triple("1x", 1.0, KeyEvent.VK_3),
				Triple("2x", 2.0, KeyEvent.VK_4),
				Triple("10x", 10.0, KeyEvent.VK_5),
			)
		for ((label, multiplier, keyCode) in speedPresets) {
			val item = JMenuItem(SetSpeedAction(label, multiplier))
			item.accelerator = KeyStroke.getKeyStroke(keyCode, 0)
			speedMenu.add(item)
		}
		menu.add(speedMenu)

		return menu
	}

	private fun helpMenu(): JMenu {
		val menu = JMenu("Help")
		menu.add(
			InfoAction(
				"Usage",
				"<html><b>File Operations:</b><br>" +
					"- Open: Load railway network from XML file<br>" +
					"- Save as...: Save railway network to XML file<br>" +
					"<br><b>Editing:</b><br>" +
					"- Left mouse: Insert nodes and join them<br>" +
					"- Middle mouse: Delete nodes<br>" +
					"- Right mouse: Popup menu<br>" +
					"<br><b>Simulation:</b><br>" +
					"- Simulation &gt; Start...: Load XML and start simulation<br>" +
					"- Simulation &gt; Stop: Terminate running simulation<br>" +
					"<br><b>Simulation Speed (keyboard shortcuts):</b><br>" +
					"- Key 1: 0.1x speed<br>" +
					"- Key 2: 0.5x speed<br>" +
					"- Key 3: 1x speed (real-time)<br>" +
					"- Key 4: 2x speed<br>" +
					"- Key 5: 10x speed</html>"
			)
		)
		menu.add(
			InfoAction("About", "<html><b>Author</b>:<br> Bedrich Hovorka <br> <em>xhovor07@stud.fit.vutbr.cz</em></html>")
		)
		return menu
	}
}
