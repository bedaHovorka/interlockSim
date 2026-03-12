/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

/**
 * Is throwing by [ContextFactory], when source for context is wrong
 *
 */
class ContextCreationException : Exception {
	/**
	 * @see Exception.Exception
	 *
	 */
	constructor() : super()

	/**
	 * @see Exception.Exception
	 * @param cause
	 */
	constructor(cause: Throwable) : super(cause)

	/**
	 * @see Exception.Exception
	 * @param string
	 */
	constructor(string: String) : super(string)

	/**
	 * @see Exception.Exception
	 * @param message
	 * @param cause
	 */
	constructor(message: String, cause: Throwable?) : super(message, cause)
}
