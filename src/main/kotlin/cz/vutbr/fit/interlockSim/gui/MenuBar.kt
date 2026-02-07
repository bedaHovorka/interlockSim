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

import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import org.koin.mp.KoinPlatform.getKoin
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JOptionPane

/**
 * Application menu bar with File and Help menus
 */
class MenuBar : JMenuBar() {
	private val saveAction = SaveAction()
	private val saveAsAction = SaveAsAction()

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
				val editingContextFactory = getKoin().get<cz.vutbr.fit.interlockSim.xml.XMLContextFactory>()
				val parseResult = editingContextFactory.createContextLenient(selectedFile)

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
	 * DEFERRED: Save-time validation (Issue #258)
	 * Decision: Defer to follow-up issue for comprehensive validation framework
	 *
	 * Rationale:
	 * - Current implementation: Editor allows opening/editing any file (including broken ones)
	 * - Save validation would be inconsistent without load validation
	 * - Comprehensive solution requires:
	 *   1. Define validation rules (structural, safety, configuration)
	 *   2. Implement validators for each category
	 *   3. Add validation on both load AND save
	 *   4. Support warnings (allow save) vs errors (block save)
	 *   5. Provide user choice dialog for warnings
	 *
	 * Current behavior: Saves all files without validation
	 * Future enhancement: Track in separate issue for comprehensive validation system
	 *
	 * @return true if save succeeded, false otherwise
	 */
	private fun performSave(file: File): Boolean {
		val editingContextFactory = getKoin().get<EditingContextFactory>()
		val frame = getKoin().get<Frame>()
		val editingContext = frame.railwayNetGridCanvas.getEditingContext()

		val success = editingContextFactory.saveContext(editingContext, file)

		if (success) {
			// Update modification tracker
			frame.modificationTracker.setCurrentFile(file)
			frame.modificationTracker.markClean()

			// Show non-intrusive success message in status bar
			frame.statusBar.showTemporaryMessage("✓ Saved: ${file.name}", 5000)
		} else {
			JOptionPane.showMessageDialog(
				this,
				"Failed to save railway network to file: ${file.absolutePath}",
				"Save Failed",
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
					"- Right mouse: Popup menu</html>"
			)
		)
		menu.add(
			InfoAction("About", "<html><b>Author</b>:<br> Bedrich Hovorka <br> <em>xhovor07@stud.fit.vutbr.cz</em></html>")
		)
		return menu
	}
}
