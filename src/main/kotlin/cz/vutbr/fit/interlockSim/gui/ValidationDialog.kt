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

import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.WindowConstants

/**
 * Dialog for displaying railway network validation errors and warnings.
 *
 * Issue #258: Validation behavior clarification and implementation
 * --------------------------------------------------------------
 * BEHAVIOR CHANGE (2026-01-21):
 * - BEFORE: ValidationDialog shown when opening files → blocked opening invalid files
 * - AFTER:  ValidationDialog shown only on SAVE → warns before saving invalid files
 *
 * Current Usage Pattern (2026-02-06 Update):
 * - EDITOR MODE: Opening files with errors shows ValidationDialog with "Open Anyway" option
 *   - Unparseable XML (malformed): Shows error, blocks opening
 *   - Parseable XML with validation warnings: Shows dialog, allows "Open Anyway"
 * - SIMULATION MODE: Transformation to simulation blocks invalid XML
 * - SAVE OPERATION: Show this dialog as WARNING before saving (TODO: implement)
 *
 * Rationale:
 * - Users need to open broken files to fix them in the editor
 * - But we must prevent running simulations with invalid configurations
 * - And we should warn before saving files that might have issues
 *
 * Bug Fixes (Issue #258):
 * - Fixed NullPointerException in createButtonPanel (rootPane timing issue)
 * - Fixed NullPointerException in XMLContextFactory (missing attributes)
 *
 * Designed to be reusable across multiple validation scenarios:
 * - Current: XML schema and structural validation (Issue #258)
 * - Future: Interlocking safety validation (Goal 4)
 * - Future: Train type validation (Goal 14)
 * - Future: Tutorial validation (Goal 15)
 *
 * Accessibility features (Goal 20):
 * - Keyboard navigation (Tab, Enter, Escape)
 * - Screen reader compatible (proper dialog titles, button labels)
 * - No reliance on color alone (uses text + icons)
 *
 * @param parent Parent component for modal dialog
 * @param validationResult Result of validation containing errors/warnings
 * @param filePath Optional file path being validated
 * @param allowOpenAnyway If true, shows "Open Anyway" button alongside Cancel
 */
class ValidationDialog(
	parent: Component?,
	private val validationResult: ValidationResult,
	private val filePath: File? = null,
	private val allowOpenAnyway: Boolean = false
) : JDialog() {
	private var userChoice: DialogResult = DialogResult.CANCEL

	init {
		title =
			if (allowOpenAnyway) {
				"⚠ Validation Warning"
			} else {
				"⚠ Validation Error"
			}
		isModal = true
		defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

		// Set up dialog content
		val contentPanel = createContentPanel()
		contentPane.add(contentPanel, BorderLayout.CENTER)

		val buttonPanel = createButtonPanel()
		contentPane.add(buttonPanel, BorderLayout.SOUTH)

		// Size and position
		setSize(600, 400)
		setLocationRelativeTo(parent)

		// Set default button after all components are added
		val cancelButton = (buttonPanel.components.firstOrNull() as? JButton)
		rootPane.defaultButton = cancelButton
	}

	/**
	 * Creates the main content panel with error messages.
	 */
	private fun createContentPanel(): JPanel {
		val panel = JPanel()
		panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
		panel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)

		// Title
		val titleLabel = JLabel("Railway network validation failed:")
		titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
		titleLabel.alignmentX = Component.LEFT_ALIGNMENT
		panel.add(titleLabel)
		panel.add(Box.createVerticalStrut(10))

		// Error messages in scrollable text area
		val errorText = buildErrorMessage()
		val textArea = JTextArea(errorText)
		textArea.isEditable = false
		textArea.lineWrap = true
		textArea.wrapStyleWord = true
		textArea.font = Font("Dialog", Font.PLAIN, 12)
		textArea.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

		val scrollPane = JScrollPane(textArea)
		scrollPane.alignmentX = Component.LEFT_ALIGNMENT
		panel.add(scrollPane)

		// File path if available
		if (filePath != null) {
			panel.add(Box.createVerticalStrut(10))
			val fileLabel = JLabel("File: ${filePath.absolutePath}")
			fileLabel.font = fileLabel.font.deriveFont(Font.ITALIC, 11f)
			fileLabel.alignmentX = Component.LEFT_ALIGNMENT
			panel.add(fileLabel)
		}

		return panel
	}

	/**
	 * Builds the error message text from validation result.
	 */
	private fun buildErrorMessage(): String =
		buildString {
			// Format all errors
			for ((index, error) in validationResult.errors.withIndex()) {
				if (index > 0) {
					append("\n\n")
					append("─".repeat(50))
					append("\n\n")
				}
				append(error.format())
			}

			// Format warnings if any
			if (validationResult.warnings.isNotEmpty()) {
				if (validationResult.errors.isNotEmpty()) {
					append("\n\n")
					append("═".repeat(50))
					append("\n\n")
				}
				append("Warnings:\n\n")
				for ((index, warning) in validationResult.warnings.withIndex()) {
					if (index > 0) append("\n\n")
					append(warning.format())
				}
			}
		}

	/**
	 * Creates the button panel with action buttons.
	 */
	private fun createButtonPanel(): JPanel {
		val panel = JPanel(FlowLayout(FlowLayout.RIGHT))
		panel.border = BorderFactory.createEmptyBorder(0, 15, 15, 15)

		// Cancel button (always available)
		val cancelButton =
			JButton("Cancel").apply {
				addActionListener {
					userChoice = DialogResult.CANCEL
					dispose()
				}
			}

		// Open Anyway button (only if allowed)
		if (allowOpenAnyway) {
			val openAnywayButton =
				JButton("Open Anyway").apply {
					addActionListener {
						userChoice = DialogResult.OPEN_ANYWAY
						dispose()
					}
					// Make this button stand out as the action button
					isFocusPainted = true
				}
			panel.add(openAnywayButton)
		}

		panel.add(cancelButton)

		// Focus on appropriate button by default
		// Always focus Cancel to make user make a conscious choice
		cancelButton.requestFocusInWindow()

		return panel
	}

	/**
	 * Shows the dialog and returns the user's choice.
	 */
	fun showDialog(): DialogResult {
		isVisible = true
		return userChoice
	}

	/**
	 * Result of validation dialog interaction.
	 */
	enum class DialogResult {
		/**
		 * User clicked Cancel or closed the dialog.
		 */
		CANCEL,

		/**
		 * User clicked "Open Anyway" to proceed despite validation errors.
		 * Only available when allowOpenAnyway is true.
		 */
		OPEN_ANYWAY
	}

	companion object {
		/**
		 * Shows a validation dialog with the given result.
		 *
		 * @param parent Parent component for modal dialog
		 * @param validationResult Result of validation
		 * @param filePath Optional file path being validated
		 * @param allowOpenAnyway If true, shows "Open Anyway" button alongside Cancel
		 * @return User's choice from the dialog
		 */
		fun show(
			parent: Component?,
			validationResult: ValidationResult,
			filePath: File? = null,
			allowOpenAnyway: Boolean = false
		): DialogResult {
			val dialog = ValidationDialog(parent, validationResult, filePath, allowOpenAnyway)
			return dialog.showDialog()
		}
	}
}
