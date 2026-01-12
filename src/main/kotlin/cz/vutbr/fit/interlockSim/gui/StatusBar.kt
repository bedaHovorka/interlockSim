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
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionListener
import java.beans.PropertyChangeEvent
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
				assert(source is StatusProducer) { "Source must be a StatusProducer" }
				val status = (source as StatusProducer).getStatus(e)
				if (status != null) {
					text = status
				}
			}
		}

	init {
		preferredSize = Dimension(100, 25)
		text = "Welcome to " + PROGRAM_NAME
	}

	private fun checkComponent(producer: StatusProducer): Component {
		assert(producer is Component) { "StatusProducer must be a Component" }
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

	override fun propertyChange(evt: PropertyChangeEvent) {
		val newValue = evt.newValue
		when {
			newValue is CharSequence -> text = newValue.toString()
			newValue != null -> text = newValue.toString()
		}
	}
}
