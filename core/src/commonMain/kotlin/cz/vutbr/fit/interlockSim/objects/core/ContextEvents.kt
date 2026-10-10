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
 *
 * A listener observes a dynamic cell's property change (`DynamicRailSwitch`, `DynamicTrackBlock`,
 * simulation contexts). Observers only: a listener must not throw and must not reach back into
 * the cell that notifies it. Producers contain a throwing listener (log and continue with the
 * rest), so an exception here stops only the thrower's own work (Issue #1103).
 */
fun interface ContextPropertyChangeListener {
	fun propertyChange(event: ContextChangeEvent)
}
