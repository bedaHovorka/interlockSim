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
	 * @param T target type (reified)
	 * @param obj object to cast
	 * @return casted instance of type T
	 * @throws IllegalStateException if obj is not an instance of T
	 */
	inline fun <reified T> assertInstanceOf(obj: Any): T {
		requireValidState(obj is T) { "Expected instance of ${T::class.simpleName} but got ${obj::class.simpleName}: $obj" }
		return obj as T
	}
}
