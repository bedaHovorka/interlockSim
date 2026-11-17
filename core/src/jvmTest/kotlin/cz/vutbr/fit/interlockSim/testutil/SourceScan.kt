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
 * resolution, the `.kt` walk and the comment-aware code-line scanners [codeLines] (Issue #1379) and
 * [codeLinesKeepingStrings] (Issue #1380).
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
	 * The comment-free, string-keeping code text of one [relativePath] source file, resolved under
	 * the first of [spellings] that names an existing directory ([directory], Issue #1387): the
	 * read-once plumbing of the wording tripwires, so a resolution or join fix lands here once.
	 *
	 * @throws IllegalStateException when no [spellings] resolves the file.
	 */
	fun codeText(
		relativePath: String,
		vararg spellings: String
	): String =
		directory(*spellings)
			.resolve(relativePath)
			.takeIf { it.isFile }
			?.let { joinConcatenations(codeLinesKeepingStrings(it.readLines())) }
			?: error("$relativePath not found under the scanned commonMain sources")

	/**
	 * The [lines] of one file as code-only strings, one output line per input line (indices kept):
	 * string literals blanked first (a `//` or a comment opener inside one must not act on the
	 * comment state), then `//` to end of line and block-comment spans (opener to close) removed,
	 * with the block-comment state carried across the lines, so code after a close is seen again.
	 *
	 * Deliberate holes (a per-line scanner is not a parser): a raw triple-quoted string spanning
	 * lines is not blanked, so its content reads as code; a close pair inside a quoted span is
	 * blanked with it, which can move a block comment's close; nested block comments close at the
	 * first close pair, so the code-looking rest of the outer comment reads as code. A string
	 * quoted inside a `${…}` template expression does not mask as one literal either: the inner
	 * quotes end the regex match, so the expression's argument reads as code and the tail up to
	 * the next quote is masked with it.
	 */
	fun codeLines(lines: List<String>): List<String> = scan(lines, keepStrings = false)

	/**
	 * [codeLines] with the string literals kept: the same comment spans are removed, but each
	 * literal's text stays in the output. For a scan that pins message wording, which lives inside
	 * string literals (the `Train` errorStop fragment tripwire, Issue #1380). A `//` or a comment
	 * opener inside a literal still does not act on the comment state, and the same deliberate holes
	 * as [codeLines] apply.
	 */
	fun codeLinesKeepingStrings(lines: List<String>): List<String> = scan(lines, keepStrings = true)

	/** A `"…" +` line end followed by the next line's opening quote: one concatenation boundary (Issue #1396). */
	private val stringLiteralBoundary = Regex("\"\\s*\\+\\s*\n\\s*\"")

	/**
	 * The code [lines] as one text with each `"…" +` / `"…"` boundary across a line end removed
	 * (Issue #1396), so a wording split over two lines reads whole. A comment line between the two
	 * halves breaks the join, which can only make a wording pin fail, never pass.
	 */
	fun joinConcatenations(lines: List<String>): String = lines.joinToString("\n").replace(stringLiteralBoundary, "")

	/** The count of the overlapping occurrences of [needle] in [text]. */
	fun countOccurrences(
		text: String,
		needle: String
	): Int {
		var count = 0
		var fromIndex = text.indexOf(needle)
		while (fromIndex >= 0) {
			count++
			fromIndex = text.indexOf(needle, fromIndex + 1)
		}
		return count
	}

	/**
	 * Both scanners in one: each literal is masked with [LITERAL_MASK] characters of the same length,
	 * so the comment spans found on the masked line have the raw line's indices. [keepStrings] picks
	 * the raw text of the kept spans; otherwise the masked text with the mask dropped, which blanks
	 * the literals.
	 */
	private fun scan(
		lines: List<String>,
		keepStrings: Boolean
	): List<String> {
		var inBlock = false
		return lines.map { raw ->
			val masked = raw.replace(STRING_LITERAL) { LITERAL_MASK.toString().repeat(it.value.length) }
			val (spans, state) = codeSpans(masked, inBlock)
			inBlock = state
			val source = if (keepStrings) raw else masked
			spans.joinToString("") { source.substring(it) }.let { code ->
				if (keepStrings) code else code.replace(LITERAL_MASK.toString(), "")
			}
		}
	}

	/** A character no Kotlin source holds and no comment marker is: the literal mask. */
	private const val LITERAL_MASK = '\u0000'

	/**
	 * The code spans of one [masked] line: `//` to end of line and block-comment spans removed,
	 * starting inside a block comment when [inBlockComment]. Returns the spans and the state at the
	 * line's end.
	 */
	private fun codeSpans(
		masked: String,
		inBlockComment: Boolean
	): Pair<List<IntRange>, Boolean> {
		val spans = mutableListOf<IntRange>()
		var inBlock = inBlockComment
		var i = 0
		while (i < masked.length) {
			if (inBlock) {
				val close = masked.indexOf("*/", i)
				if (close < 0) return spans to true
				i = close + 2
				inBlock = false
			} else {
				val lineComment = masked.indexOf("//", i)
				val blockStart = masked.indexOf("/*", i)
				when {
					lineComment >= 0 && (blockStart < 0 || lineComment < blockStart) -> {
						spans += i until lineComment
						return spans to false
					}
					blockStart >= 0 -> {
						spans += i until blockStart
						i = blockStart + 2
						inBlock = true
					}
					else -> {
						spans += i until masked.length
						return spans to false
					}
				}
			}
		}
		return spans to inBlock
	}
}
