/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import jDisco.Process

/**
 *
 */
open class SimulationException : Exception {
	private val obj: Any?
	private val time: Double

	/**
	 *
	 */
	constructor() : this(null as Any?)

	/**
	 * @param object
	 *
	 */
	constructor(obj: Any?) : this("", obj)

	/**
	 * @param message
	 */
	constructor(message: String) : this(message, null as Any?)

	/**
	 * @param message
	 * @param object
	 */
	constructor(message: String, obj: Any?) : this(message, null as Throwable?, obj)

	/**
	 * @param cause
	 */
	constructor(cause: Throwable?) : this(cause, null as Any?)

	/**
	 * @param cause
	 * @param object
	 */
	constructor(cause: Throwable?, obj: Any?) : this("", cause, obj)

	/**
	 * @param message
	 * @param cause
	 * @param object
	 */
	constructor(message: String, cause: Throwable?, obj: Any?) : super(message, cause) {
		this.obj = obj
		this.time = Process.time()
	}

	/**
	 * @return object getter
	 */
	open fun getObject(): Any? = obj

	/**
	 * @return model time of exception
	 */
	fun getTime(): Double = time

	override fun toString(): String {
		val msg = message?.takeIf { it.isNotEmpty() } ?: ""
		return "${this::class.simpleName}: $msg at time $time"
	}
}
