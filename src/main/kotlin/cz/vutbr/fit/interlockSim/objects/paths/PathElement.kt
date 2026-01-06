/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

/**
 * Element of {@link Path}
 *
 */
interface PathElement {
	companion object {
		/**
		 * model constant
		 */
		const val COMMON_MAX_SPEED = 24.0 // in m/s

		/**
		 * model constant
		 */
		const val ABSOLUTE_MAX_SPEED = 90.0 // in m/s

		/**
		 * model constant
		 */
		const val MINIMAL_MAX_SPEED = 2.0 // in m/s
	}
}
