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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.mp.KoinPlatform.getKoin
import java.awt.Cursor
import java.awt.event.ActionEvent
import java.io.File
import java.util.concurrent.ExecutionException
import javax.swing.AbstractAction
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.SwingWorker

/**
 * Application menu bar with File and Help menus
 */
class MenuBar : JMenuBar() {
	private val saveAction = SaveAction()
	private val saveAsAction = SaveAsAction()

	companion object {
		private val logger = KotlinLogging.logger {}

		/** System property naming the process working directory; the file chooser's start location. */
		private const val WORKING_DIRECTORY_PROPERTY = "user.dir"

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
			val fileChooser = JFileChooser(System.getProperty(WORKING_DIRECTORY_PROPERTY))
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
			val fileChooser = JFileChooser(System.getProperty(WORKING_DIRECTORY_PROPERTY))
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
			val fileChooser = JFileChooser(System.getProperty(WORKING_DIRECTORY_PROPERTY))
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
	 *
	 * **Resource management:** The intermediate [EditingContext] created by
	 * [JvmEditingContextFactory.createContext] is wrapped in `use {}` to ensure its Koin
	 * scope is closed after the [SimulationContext] transformation, preventing a resource
	 * leak of the temporary editing context.
	 *
	 * **Report types:** All report types are enabled on the [SimulationContext] before
	 * passing it to [Frame.setContext] so that [AnimationController] and
	 * [cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel] receive property-change
	 * events and the animation is not visually frozen.
	 *
	 * **Modification tracker:** The tracker is cleared before switching to simulation mode
	 * so that the "unsaved changes" path in [Frame.handleWindowClosing] does not attempt to
	 * save a [SimulationContext] through the editor's save logic.
	 */
	private inner class StartSimulationAction : AbstractAction("Start...") {
		override fun actionPerformed(e: ActionEvent) {
			val fileChooser = JFileChooser(System.getProperty(WORKING_DIRECTORY_PROPERTY))
			fileChooser.dialogTitle = "Start Simulation"

			val returnValue = fileChooser.showOpenDialog(this@MenuBar)
			if (returnValue != JFileChooser.APPROVE_OPTION) return

			val selectedFile: File = fileChooser.selectedFile
			val savedCursor = this@MenuBar.cursor
			this@MenuBar.cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)

			object : SwingWorker<SimulationContext, Void>() {
				override fun doInBackground(): SimulationContext = loadSimulationContext(selectedFile)

				override fun done() {
					this@MenuBar.cursor = savedCursor
					val simContext =
						try {
							get()
						} catch (ex: ExecutionException) {
							logger.error(ex.cause ?: ex) { "Failed to load simulation context from $selectedFile" }
							JOptionPane.showMessageDialog(
								this@MenuBar,
								"Failed to start simulation: ${(ex.cause ?: ex).message}\n\n" +
									"Ensure the file is a valid railway network XML.",
								"Cannot Start Simulation",
								JOptionPane.ERROR_MESSAGE
							)
							return
						}

					val frame = getKoin().get<Frame>()
					frame.modificationTracker.markClean()
					frame.modificationTracker.setCurrentFile(null)
					frame.setContext(simContext)
					frame.startSimulation()
				}
			}.execute()
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
		private val multiplier: Double
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

	/**
	 * Parses [file] as a railway XML, transforms it to a [SimulationContext], and enables
	 * all report types. Must be called off the Event Dispatch Thread.
	 *
	 * @throws Exception if the file is unreadable or the XML is invalid.
	 */
	internal fun loadSimulationContext(file: File): SimulationContext {
		val editingContextFactory = getKoin().get<JvmEditingContextFactory>()
		val simulationContextFactory = getKoin().get<SimulationContextFactory>()
		return editingContextFactory
			.createContext(file)
			.use { editCtx ->
				simulationContextFactory.createContext(editCtx as EditingContext)
			}.also { simCtx ->
				simCtx.addReportTypes(*SimulationContext.ReportType.values())
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
	 * Speed presets (0.1x, 0.5x, 1x, 2x, 5x, 10x, 50x) are available via menu items.
	 * Global keyboard shortcuts (keys 1–5, +/-, Space, S, T) are handled by
	 * [SimulationKeyBindings] during simulation mode (Phase 3.1, Issue #193; Goal 8).
	 *
	 * **Shortcut reservation:** Keys `S` (step event) and `T` (step time) are reserved
	 * for simulation-mode step controls. Menu items in this application must not use
	 * `S` or `T` as mnemonics or accelerators, to avoid conflicts with those bindings.
	 *
	 * Also contains the **Collision Response** submenu (Issue #616, Goal 3 SP6) with
	 * configurable auto-pause, auto-halt, and sound toggles, and a **Warning Panel**
	 * visibility toggle.
	 */
	private fun simulationMenu(): JMenu {
		val menu = JMenu("Simulation")
		menu.add(StartSimulationAction())
		menu.add(StopSimulationAction())
		menu.addSeparator()

		val speedMenu = JMenu("Speed")
		val speedPresets =
			listOf(
				Pair("0.1x", 0.1),
				Pair("0.5x", 0.5),
				Pair("1x", 1.0),
				Pair("2x", 2.0),
				Pair("5x", 5.0),
				Pair("10x", 10.0),
				Pair("50x", 50.0)
			)
		for ((label, multiplier) in speedPresets) {
			val item = JMenuItem(SetSpeedAction(label, multiplier))
			speedMenu.add(item)
		}
		menu.add(speedMenu)

		// Collision Response submenu (Issue #616, Goal 3 SP6)
		menu.addSeparator()
		menu.add(collisionResponseMenu())

		// Warning Panel toggle (Issue #616, Goal 3 SP6)
		menu.add(warningPanelToggleItem())

		return menu
	}

	/**
	 * Builds the "Collision Response" submenu with three configurable toggles.
	 *
	 * - **Auto-pause on critical warning** (default: on): keeps the simulation paused
	 *   after every collision warning; unchecking it causes the runner to resume
	 *   immediately on the EDT after the warning is logged.
	 * - **Auto-halt train on violation** (default: off): calls the halt callback
	 *   registered on [DefaultCollisionDetectionService] for the offending train.
	 * - **Sound on critical warning** (default: off): plays a short system beep via
	 *   [java.awt.Toolkit.beep] for each CRITICAL warning.
	 *
	 * @since Issue #616 (Goal 3 SP6)
	 */
	private fun collisionResponseMenu(): JMenu {
		val menu = JMenu("Collision Response")

		val autoPauseItem = JCheckBoxMenuItem("Auto-pause on critical warning", true)
		autoPauseItem.addActionListener {
			val frame = getKoin().get<Frame>()
			frame.autoPauseOnCriticalWarning = autoPauseItem.isSelected
		}
		menu.add(autoPauseItem)

		val autoHaltItem = JCheckBoxMenuItem("Auto-halt train on violation", false)
		autoHaltItem.addActionListener {
			val frame = getKoin().get<Frame>()
			frame.autoHaltTrainOnViolation = autoHaltItem.isSelected
		}
		menu.add(autoHaltItem)

		val soundItem = JCheckBoxMenuItem("Sound on critical warning", false)
		soundItem.addActionListener {
			val frame = getKoin().get<Frame>()
			frame.soundOnCriticalWarning = soundItem.isSelected
		}
		menu.add(soundItem)

		return menu
	}

	/**
	 * Builds a checkbox menu item that toggles the visibility of the warning log panel.
	 *
	 * @since Issue #616 (Goal 3 SP6)
	 */
	private fun warningPanelToggleItem(): JCheckBoxMenuItem {
		val item = JCheckBoxMenuItem("Warning Panel", true)
		item.addActionListener {
			val frame = getKoin().get<Frame>()
			frame.warningPanel.isVisible = item.isSelected
		}
		return item
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
					"<br><b>Simulation Speed (Phase 3.1 global keyboard shortcuts):</b><br>" +
					"- Key 1: 0.5x speed (half-time)<br>" +
					"- Key 2: 1x speed (real-time)<br>" +
					"- Key 3: 2x speed<br>" +
					"- Key 4: 5x speed<br>" +
					"- Key 5: 10x speed<br>" +
					"- Plus (+): Increase speed by 1.5x<br>" +
					"- Minus (-): Decrease speed by 1.5x<br>" +
					"- Space: Pause/resume simulation<br>" +
					"- S: Step one simulation event when paused<br>" +
					"- T: Step forward by the configured time delta when paused</html>"
			)
		)
		menu.add(
			InfoAction("About", "<html><b>Author</b>:<br> Bedrich Hovorka <br> <em>xhovor07@stud.fit.vutbr.cz</em></html>")
		)
		return menu
	}
}
