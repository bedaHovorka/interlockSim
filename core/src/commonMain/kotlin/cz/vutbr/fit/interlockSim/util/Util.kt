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
		requireValidState(obj is T) {
			val expectedName = T::class.simpleName ?: T::class.qualifiedName ?: T::class.toString()
			val actualName = obj::class.simpleName ?: obj::class.qualifiedName ?: obj::class.toString()
			"Expected instance of $expectedName but got $actualName: $obj"
		}
		return obj as T
	}
}
