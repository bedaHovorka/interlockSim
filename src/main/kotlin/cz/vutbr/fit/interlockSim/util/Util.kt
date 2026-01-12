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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell

/**
 * Collection of shared static methods
 *
 */
object Util {
	internal fun toClass(o: Any): Class<*> {
		val class1 = o.javaClass
		return if (SimulationContext::class.java.isAssignableFrom(class1)) SimulationContext::class.java else class1
	}

	/**
	 * Most used cast in program
	 * @param obj
	 * @return casted instance
	 */
	fun assertNodeCell(obj: Any): NodeCell = assertInstanceOf(NodeCell::class.java, obj)

	/**
	 * assert and cast routine
	 * @param <T>
	 * @param clazz
	 * @param obj
	 * @return casted instance
	 */
	fun <T> assertInstanceOf(
		clazz: Class<T>,
		obj: Any
	): T {
		assert(clazz.isInstance(obj)) { "${'$'}clazz ${'$'}obj" }
		return clazz.cast(obj)
	}
}
