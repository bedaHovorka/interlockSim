/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;

/**
 * Collection of shared static methods
 *
 */
public final class Util {
	private Util() {
		//EMPTY
	}

	private static Class<?> toClass(Object o) {
		final Class<?> class1 = o.getClass();
		return SimulationContext.class.isAssignableFrom(class1) ? SimulationContext.class : class1;
	}

	/**
	 * !!! THIS METHOD is designed only for interlocksim
	 * @param objects
	 * @return array of classes which reprezents types in objects
	 */
	public static Class<?>[] toClass(Object[] objects) {
		if (objects == null) return null;
		final Class<?>[] classes = new Class[objects.length];
		for (int i = 0; i < objects.length; i++) {
			assert !objects[i].getClass().isArray();
			classes[i] = objects[i] == null ? null : toClass(objects[i]);
		}
		return classes;
	}

	/**
	 * Most used cast in program
	 * @param obj
	 * @return casted instance
	 */
	public static NodeCell assertNodeCell(Object obj) {
		return assertInstanceOf(NodeCell.class, obj);
	}
	
	/**
	 * assert and cast routine
	 * @param <T>
	 * @param clazz
	 * @param obj
	 * @return casted instance
	 */
	public static <T> T assertInstanceOf(Class<T> clazz, Object obj) {
		assert clazz != null;
		assert clazz.isInstance(obj) : clazz + " " + obj;
		return clazz.cast(obj);
	}
}
