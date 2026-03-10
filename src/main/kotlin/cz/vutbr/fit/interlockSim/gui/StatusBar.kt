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

import cz.vutbr.fit.interlockSim.PROGRAM_NAME
import cz.vutbr.fit.interlockSim.context.ContextChangeListener
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.ContextChangeEvent
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionListener
import javax.swing.JLabel

/**
 * Status bar for displaying context information and mouse motion status
 */
class StatusBar :
	JLabel(),
	ContextChangeListener {
	private val mouseListener =
		object : MouseMotionListener {
			override fun mouseDragged(e: MouseEvent) {
				// Not used
			}

			override fun mouseMoved(e: MouseEvent) {
				val source = e.source
				requireValidState(source is StatusProducer) { "Source must be a StatusProducer" }
				val status = (source as StatusProducer).getStatus(e)
				text = status
			}
		}

	init {
		preferredSize = Dimension(100, 25)
		text = "Welcome to " + PROGRAM_NAME
	}

	private fun checkComponent(producer: StatusProducer): Component {
		requireValidState(producer is Component) { "StatusProducer must be a Component" }
		return producer as Component
	}

	fun registerProducer(producer: StatusProducer) {
		val producerComponent = checkComponent(producer)
		producerComponent.addMouseMotionListener(mouseListener)
	}

	fun unregisterProducer(producer: StatusProducer) {
		val producerComponent = checkComponent(producer)
		producerComponent.removeMouseMotionListener(mouseListener)
	}

	override fun propertyChange(event: ContextChangeEvent) {
		val newValue = event.newValue
		when {
			newValue is CharSequence -> text = newValue.toString()
			newValue != null -> text = newValue.toString()
		}
	}

	/**
	 * Shows a temporary message in the status bar.
	 * The message will be displayed for the specified duration, then cleared.
	 *
	 * @param message The message to display
	 * @param durationMs Duration in milliseconds (default: 3000ms = 3 seconds)
	 */
	fun showTemporaryMessage(
		message: String,
		durationMs: Long = 3000
	) {
		val originalText = text
		text = message

		// Use Swing Timer to restore original text after duration
		val timer =
			javax.swing.Timer(durationMs.toInt()) {
				text = originalText
			}
		timer.isRepeats = false
		timer.start()
	}
}
