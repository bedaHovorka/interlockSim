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
	 *
	 * In simulation contexts, handles both static NodeCell objects and Dynamic* wrappers
	 * (DynamicInOut, DynamicRailSwitch, DynamicRailSemaphore) by extracting their static NodeCell.
	 *
	 * @param obj object to cast (NodeCell or Dynamic* wrapper)
	 * @return casted NodeCell instance (or extracted static NodeCell from Dynamic wrapper)
	 * @throws IllegalStateException if obj is neither NodeCell nor Dynamic* wrapper
	 */
	fun assertNodeCell(obj: Any): NodeCell {
		// Handle Dynamic* wrappers by extracting their static NodeCell
		when (obj) {
			is DynamicInOut -> return obj.staticRef
			is DynamicRailSwitch -> return obj.staticRef
			is DynamicRailSemaphore -> return obj.staticRef
			is NodeCell -> return obj
			else -> {
				requireValidState(false) {
					"Expected instance of NodeCell or Dynamic* wrapper but got ${obj.javaClass.name}: $obj"
				}
				throw IllegalStateException("Unreachable")
			}
		}
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
