package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.util.NameValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * Modal dialog for renaming railway network elements.
 *
 * Follows the same pattern as [ValidationDialog] with a text field for name input,
 * OK/Cancel buttons, and keyboard shortcuts (Enter = OK, Escape = Cancel).
 *
 * @property parent Parent component for dialog positioning (nullable for standalone use)
 * @property currentName The current name of the element being renamed
 * @property elementType The type of element (e.g., "RailSemaphore", "RailSwitch")
 */
class RenameDialog(
	parent: Component?,
	private val currentName: String,
	private val elementType: String
) : JDialog(SwingUtilities.getWindowAncestor(parent), "Rename $elementType", ModalityType.APPLICATION_MODAL) {
	private val logger = KotlinLogging.logger {}
	private var userChoice: DialogResult = DialogResult.CANCEL
	private var newName: String = currentName
	private val nameTextField: JTextField

	/**
	 * Result of the dialog interaction.
	 */
	enum class DialogResult {
		/** User clicked OK button or pressed Enter */
		OK,

		/** User clicked Cancel button, pressed Escape, or closed the dialog */
		CANCEL
	}

	init {
		logger.info { "RenameDialog: Opening rename dialog for $elementType with current name: '$currentName'" }

		// Create content panel with vertical layout
		val contentPanel =
			JPanel().apply {
				layout = BoxLayout(this, BoxLayout.Y_AXIS)
				border = EmptyBorder(10, 10, 10, 10)
			}

		// Add instruction label
		val instructionLabel =
			JLabel("Enter new name:").apply {
				font = font.deriveFont(Font.BOLD, 14f)
				alignmentX = Component.LEFT_ALIGNMENT
			}
		contentPanel.add(instructionLabel)

		// Add vertical spacing
		contentPanel.add(javax.swing.Box.createVerticalStrut(5))

		// Create and configure text field
		nameTextField =
			JTextField(currentName, 20).apply {
				alignmentX = Component.LEFT_ALIGNMENT
				maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
				// Select all text when dialog opens for easy replacement
				SwingUtilities.invokeLater {
					selectAll()
					requestFocusInWindow()
				}
			}
		contentPanel.add(nameTextField)

		// Add vertical spacing
		contentPanel.add(javax.swing.Box.createVerticalStrut(5))

		// Add current name label
		val currentNameLabel =
			JLabel("Current name: $currentName").apply {
				font = font.deriveFont(Font.ITALIC, 11f)
				foreground = java.awt.Color.GRAY
				alignmentX = Component.LEFT_ALIGNMENT
			}
		contentPanel.add(currentNameLabel)

		// Create button panel
		val buttonPanel =
			JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
				border = EmptyBorder(5, 0, 0, 0)
			}

		// Create Cancel button with mnemonic
		val cancelButton =
			JButton("Cancel").apply {
				mnemonic = KeyEvent.VK_C
				addActionListener { handleCancel() }
			}
		buttonPanel.add(cancelButton)

		// Create OK button with mnemonic
		val okButton =
			JButton("OK").apply {
				mnemonic = KeyEvent.VK_O
				addActionListener { handleOk() }
			}
		buttonPanel.add(okButton)

		// Set OK button as default (activated by Enter key)
		rootPane.defaultButton = okButton

		// Add keyboard shortcuts
		setupKeyboardShortcuts()

		// Assemble dialog layout
		layout = BorderLayout()
		add(contentPanel, BorderLayout.CENTER)
		add(buttonPanel, BorderLayout.SOUTH)

		// Configure dialog properties
		pack()
		setLocationRelativeTo(parent)
		isResizable = false
	}

	/**
	 * Sets up keyboard shortcuts for dialog interaction.
	 * - Enter key → OK button
	 * - Escape key → Cancel button
	 */
	private fun setupKeyboardShortcuts() {
		// Escape key → Cancel
		val escapeKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
		rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeKey, "ESCAPE")
		rootPane.actionMap.put(
			"ESCAPE",
			object : AbstractAction() {
				override fun actionPerformed(e: ActionEvent) {
					handleCancel()
				}
			}
		)

		// Enter key is already handled by defaultButton mechanism
	}

	/**
	 * Handles OK button click or Enter key press.
	 * Validates input and stores the new name if valid, shows error dialog if invalid.
	 */
	private fun handleOk() {
		val inputText = nameTextField.text

		// Validate input using shared utility
		val validationResult = NameValidator.validate(inputText)

		if (!validationResult.isValid) {
			// Show error dialog with specific validation errors
			val errorMessage = validationResult.errors.joinToString("\n\n") { it.message }
			JOptionPane.showMessageDialog(
				this,
				errorMessage,
				"Invalid Name",
				JOptionPane.ERROR_MESSAGE
			)

			// Keep dialog open, let user fix the input
			nameTextField.requestFocusInWindow()
			nameTextField.selectAll()
			logger.warn { "Invalid name rejected: '$inputText' - ${errorMessage.replace("\n\n", ", ")}" }
			return
		}

		// Validation passed - accept the name
		newName = inputText
		userChoice = DialogResult.OK
		isVisible = false
		dispose()
		logger.info { "Name changed: '$currentName' → '$newName'" }
	}

	/**
	 * Handles Cancel button click, Escape key press, or dialog close.
	 * Closes the dialog with CANCEL result, preserving the original name.
	 */
	private fun handleCancel() {
		newName = currentName
		userChoice = DialogResult.CANCEL
		isVisible = false
		dispose()
	}

	/**
	 * Shows the dialog and waits for user interaction.
	 *
	 * @return Pair of dialog result and the name (either new name if OK, or current name if CANCEL)
	 */
	fun showDialog(): Pair<DialogResult, String> {
		isVisible = true
		return Pair(userChoice, newName)
	}
}
