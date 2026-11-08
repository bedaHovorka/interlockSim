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
 * Shared machinery of the line-based source scans (the wall-clock tripwire over
 * `context/navigation`, Issue #1174, the allow-list tripwire over target selection, Issue #1152,
 * and the kDisco `dtMax` single-site tripwire, Issue #1160): the first-existing-wins directory
 * resolution, the `.kt` walk and the comment-aware code-line scanner [codeLines] (Issue #1379).
 * A scan keeps its own spellings list and its own forbidden pattern; without this object each
 * tripwire copies the mechanics — and a blind-spot fix then lands in one copy only.
 */
object SourceScan {
	/**
	 * A string literal with Kotlin's backslash escapes (an inner `\"`), blanked before the comment
	 * handling so a `//` or a comment opener inside one cannot act on the comment state.
	 */
	private val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

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

	/**
	 * The [lines] of one file as code-only strings, one output line per input line (indices kept):
	 * string literals blanked first (a `//` or a comment opener inside one must not act on the
	 * comment state), then `//` to end of line and block-comment spans (opener to close) removed,
	 * with the block-comment state carried across the lines, so code after a close is seen again.
	 *
	 * Deliberate holes (a per-line scanner is not a parser): a raw triple-quoted string spanning
	 * lines is not blanked, so its content reads as code; a close pair inside a quoted span is
	 * blanked with it, which can move a block comment's close; nested block comments close at the
	 * first close pair, so the code-looking rest of the outer comment reads as code.
	 */
	fun codeLines(lines: List<String>): List<String> {
		var inBlock = false
		return lines.map { raw ->
			val (code, state) = commentFree(raw, inBlock)
			inBlock = state
			code
		}
	}

	private fun commentFree(
		raw: String,
		inBlockComment: Boolean
	): Pair<String, Boolean> {
		val blanked = raw.replace(STRING_LITERAL, "")
		val code = StringBuilder()
		var inBlock = inBlockComment
		var i = 0
		while (i < blanked.length) {
			if (inBlock) {
				val close = blanked.indexOf("*/", i)
				if (close < 0) return code.toString() to true
				i = close + 2
				inBlock = false
			} else {
				val lineComment = blanked.indexOf("//", i)
				val blockStart = blanked.indexOf("/*", i)
				when {
					lineComment >= 0 && (blockStart < 0 || lineComment < blockStart) -> {
						code.append(blanked.substring(i, lineComment))
						return code.toString() to false
					}
					blockStart >= 0 -> {
						code.append(blanked.substring(i, blockStart))
						i = blockStart + 2
						inBlock = true
					}
					else -> {
						code.append(blanked.substring(i))
						return code.toString() to false
					}
				}
			}
		}
		return code.toString() to inBlock
	}
}
