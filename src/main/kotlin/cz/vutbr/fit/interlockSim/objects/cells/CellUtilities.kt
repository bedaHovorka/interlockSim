/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.exceptions.requireValidState

/**
 * Domain-specific utility functions for working with Cell objects.
 *
 * This object contains utilities that depend on domain model classes (NodeCell, SimulationContext).
 * For type-agnostic utilities, see [cz.vutbr.fit.interlockSim.util.Util].
 */
object CellUtilities {
	/**
	 * Most used cast in program - asserts and casts to NodeCell
	 * @param obj object to cast
	 * @return casted NodeCell instance
	 * @throws IllegalStateException if obj is not a NodeCell
	 */
	fun assertNodeCell(obj: Any): NodeCell {
		requireValidState(obj is NodeCell) { "Expected instance of NodeCell but got ${obj.javaClass.name}: $obj" }
		return obj as NodeCell
	}

	/**
	 * Converts object to its class, with special handling for SimulationContext.
	 *
	 * This method is designed specifically for InterlockSim's reflection needs.
	 * SimulationContext instances are always represented by the SimulationContext interface class,
	 * not their concrete implementation class.
	 *
	 * @param o object to convert
	 * @return Class representing the object's type
	 */
	fun toClass(o: Any): Class<*> {
		val class1 = o.javaClass
		return if (SimulationContext::class.java.isAssignableFrom(class1)) SimulationContext::class.java else class1
	}

	/**
	 * Converts array of objects to array of classes, with special handling for domain types.
	 *
	 * This method is designed specifically for InterlockSim's reflection needs.
	 * Uses [toClass] for individual object conversion, which applies special handling for SimulationContext.
	 *
	 * @param objects array of objects (null values allowed)
	 * @return array of classes representing types of objects (null for null inputs)
	 * @throws IllegalStateException if any object is an array (arrays not supported)
	 */
	fun toClass(objects: Array<out Any?>): Array<Class<*>?> {
		val classes = arrayOfNulls<Class<*>>(objects.size)
		for (i in objects.indices) {
			val obj = objects[i]
			if (obj != null) {
				requireValidState(!obj.javaClass.isArray) { "Arrays are not supported as input objects" }
				classes[i] = toClass(obj)
			}
		}
		return classes
	}
}
