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
class Util private constructor() {
	companion object {
		internal fun toClass(o: Any): Class<*> {
			val class1 = o.javaClass
			return if (SimulationContext::class.java.isAssignableFrom(class1)) SimulationContext::class.java else class1
		}

		/**
		 * !!! THIS METHOD is designed only for interlocksim
		 * @param objects
		 * @return array of classes which represents types in objects
		 */
		@JvmStatic
		fun toClass(objects: Array<Any?>?): Array<Class<*>?>? {
			if (objects == null) return null
			val classes = arrayOfNulls<Class<*>>(objects.size)
			for (i in objects.indices) {
				assert(objects[i]?.javaClass?.isArray != true)
				classes[i] = if (objects[i] == null) null else toClass(objects[i]!!)
			}
			return classes
		}

		/**
		 * Most used cast in program
		 * @param obj
		 * @return casted instance
		 */
		@JvmStatic
		fun assertNodeCell(obj: Any): NodeCell = assertInstanceOf(NodeCell::class.java, obj)

		/**
		 * assert and cast routine
		 * @param <T>
		 * @param clazz
		 * @param obj
		 * @return casted instance
		 */
		@JvmStatic
		fun <T> assertInstanceOf(
			clazz: Class<T>,
			obj: Any
		): T {
			assert(clazz != null)
			assert(clazz.isInstance(obj)) { "$clazz $obj" }
			return clazz.cast(obj)
		}
	}
}
