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

actual object Resources {
	actual fun read(path: String): String =
		throw NotImplementedError("Native Resources.read not wired yet (Task 2): $path")
}
