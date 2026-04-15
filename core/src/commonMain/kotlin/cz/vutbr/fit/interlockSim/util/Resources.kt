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
 * Multiplatform resource loader.
 *
 * Path is classpath-style, relative to the resources root, e.g.
 * `cz/vutbr/fit/interlockSim/resource/vyhybna.xml`.
 *
 * - JVM actual reads from classpath via `ClassLoader.getResourceAsStream`.
 * - Native (linuxX64) actual reads from a Gradle-configured list of root
 *   directories on disk — "emulation on Linux" of JVM classpath lookup.
 *
 * @throws IllegalArgumentException if the resource cannot be located.
 *   Other I/O failures during reading propagate as platform-specific exceptions
 *   (on JVM: `java.io.IOException`; on native: `kotlinx.io.IOException`).
 */
expect object Resources {
	fun read(path: String): String
}
