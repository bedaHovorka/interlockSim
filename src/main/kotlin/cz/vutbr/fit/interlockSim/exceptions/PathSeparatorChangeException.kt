/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

/**
 * @author beda
 *
 */
class PathSeparatorChangeException : SimulationException {
	/**
	 * @param object
	 */
	constructor(obj: PathSeparator) : super(obj)

	/**
	 * @param message
	 * @param object
	 */
	constructor(message: String, obj: PathSeparator) : super(message, obj)

	/**
	 * @param cause
	 * @param object
	 */
	constructor(cause: Throwable, obj: PathSeparator) : super(cause, obj)

	/**
	 * @param message
	 * @param cause
	 * @param object
	 */
	constructor(message: String, cause: Throwable, obj: PathSeparator) : super(message, cause, obj)

	override fun getObject(): PathSeparator? = super.getObject() as? PathSeparator
}
