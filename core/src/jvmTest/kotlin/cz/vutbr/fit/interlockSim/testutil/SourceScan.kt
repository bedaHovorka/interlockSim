/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import java.io.File

/**
 * Shared machinery of the line-based production-source scans (the wall-clock tripwire over
 * `context/navigation`, Issue #1174, and the allow-list tripwire over target selection,
 * Issue #1152): the first-existing-wins directory resolution, the `.kt` walk and the code-line
 * filter. A scan keeps its own spellings list and its own forbidden pattern; without this object
 * each tripwire copies the mechanics — and a blind-spot fix then lands in one copy only.
 */
object SourceScan {
	/** The first of [spellings] that names an existing directory; fails loudly if none does. */
	fun directory(vararg spellings: String): File =
		spellings
			.map(::File)
			.firstOrNull { it.isDirectory }
			?: error("Source root not found under any of ${spellings.joinToString(", ")}")

	/** Every Kotlin source file under [dir]. */
	fun kotlinFiles(dir: File): List<File> =
		dir
			.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.toList()

	/** `true` for a line that is neither blank nor a comment/KDoc line. */
	fun isCode(line: String): Boolean {
		val trimmed = line.trim()
		return trimmed.isNotEmpty() &&
			!trimmed.startsWith("//") &&
			!trimmed.startsWith("*") &&
			!trimmed.startsWith("/*")
	}
}
