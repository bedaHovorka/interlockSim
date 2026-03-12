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

/**
 * Debug utility
 */
class NotImplementedException : RuntimeException {
	companion object {
		private const val serialVersionUID = 1L
	}

	/**
	 * @see Exception#Exception()
	 */
	constructor() : super()

	/**
	 * @see Exception#Exception(String)
	 */
	constructor(message: String) : super(message)

	/**
	 * @see Exception#Exception(String, Throwable)
	 * @param message
	 * @param cause
	 */
	constructor(message: String, cause: Throwable) : super(message, cause)
}
