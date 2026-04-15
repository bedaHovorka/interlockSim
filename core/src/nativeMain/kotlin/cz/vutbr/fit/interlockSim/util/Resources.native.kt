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

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

actual object Resources {
	actual fun read(path: String): String {
		for (root in NATIVE_RESOURCE_ROOTS) {
			val candidate = Path("$root/$path")
			if (SystemFileSystem.exists(candidate)) {
				return SystemFileSystem.source(candidate).buffered().readString()
			}
		}
		throw IllegalArgumentException(
			"Resource not found in any native root (${NATIVE_RESOURCE_ROOTS.joinToString()}): $path",
		)
	}
}
