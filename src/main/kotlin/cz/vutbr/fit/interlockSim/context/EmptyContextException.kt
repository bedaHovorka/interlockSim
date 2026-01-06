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
 * In context be missing something
 */
class EmptyContextException : Exception {
	/**
	 * @see Exception.Exception
	 */
	constructor() : super() {
		// EMPTY
	}

	/**
	 * @see Exception.Exception
	 */
	constructor(message: String) : super(message)

	/**
	 * @see Exception.Exception
	 */
	constructor(cause: Throwable) : super(cause)

	/**
	 * @see Exception.Exception
	 */
	constructor(message: String, cause: Throwable) : super(message, cause)
}
