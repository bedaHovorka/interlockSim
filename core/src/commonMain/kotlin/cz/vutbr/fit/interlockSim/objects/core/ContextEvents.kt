/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

/**
 * Kotlin-native replacement for java.beans.PropertyChangeEvent.
 * Used to notify listeners of property changes in Dynamic cell objects and contexts.
 */
data class ContextChangeEvent(
	val propertyName: String,
	val oldValue: Any?,
	val newValue: Any?
)

/**
 * Kotlin-native replacement for java.beans.PropertyChangeListener.
 * Fun interface allows lambda usage.
 */
fun interface ContextPropertyChangeListener {
	fun propertyChange(event: ContextChangeEvent)
}
