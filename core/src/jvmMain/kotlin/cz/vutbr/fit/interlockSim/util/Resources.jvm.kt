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
	actual fun read(path: String): String {
		val stream =
			Resources::class.java.getResourceAsStream("/$path")
				?: throw IllegalArgumentException("Resource not found on classpath: $path")
		return stream.use { it.readBytes().toString(Charsets.UTF_8) }
	}
}
