/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.exceptions.requireValidState

/**
 * Collection of type-agnostic utility methods.
 *
 * For domain-specific utilities (e.g., working with Cell objects),
 * see [cz.vutbr.fit.interlockSim.objects.cells.CellUtilities].
 */
object Util {
	/**
	 * Generic type assertion and cast utility.
	 *
	 * @param T target type
	 * @param clazz target class to cast to
	 * @param obj object to cast
	 * @return casted instance of type T
	 * @throws IllegalStateException if obj is not an instance of clazz
	 */
	fun <T> assertInstanceOf(
		clazz: Class<T>,
		obj: Any
	): T {
		requireValidState(clazz.isInstance(obj)) { "Expected instance of ${clazz.name} but got ${obj.javaClass.name}: $obj" }
		return clazz.cast(obj)
	}
}
