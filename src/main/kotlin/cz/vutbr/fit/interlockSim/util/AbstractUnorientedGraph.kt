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
import java.lang.reflect.InvocationTargetException
import java.util.Collection
import java.util.Map

/**
 * Base for Graphs
 * @param <N> node
 * @param <E> edge
 *
 */
abstract class AbstractUnorientedGraph<N, E> : UnorientedGraph<N, E> {
	protected abstract fun implementationContainer(): Any?

	override fun containsEdge(edge: E): Boolean = values().contains(edge)

	override fun toString(): String {
		val o = implementationContainer()
		return if (o == null) super.toString() else o.toString()
	}

	override fun size(): Int {
		val o = invokeForImplementationContainer()
		requireValidState(o is Int) { "Expected Int from size() but got ${o?.javaClass?.name}" }
		return o as Int
	}

	override fun clear() {
		invokeForImplementationContainer()
	}

	private fun invokeForImplementationContainer(vararg args: Any?): Any? {
		val o = implementationContainer()
		if (o == null || (o !is Map<*, *> && o !is Collection<*>)) throw UnsupportedOperationException()

		val e = Throwable().stackTrace[1]
		val methodName = e.methodName
		try {
			@Suppress("UNCHECKED_CAST")
			val classArray = Util.toClass(args) as Array<Class<*>?>
			val method = o.javaClass.getMethod(methodName, *classArray)
			return method.invoke(o, *args)
		} catch (ee: InvocationTargetException) {
			val cause = ee.cause
			requireValidState(cause is RuntimeException) { "Expected RuntimeException but got ${cause?.javaClass?.name}" }
			throw cause as RuntimeException
		} catch (ee: Exception) {
			throw UnsupportedOperationException(ee)
		}
	}

	override fun isEmpty(): Boolean = size() == 0
}
