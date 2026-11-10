/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Pins the shared comment-aware line scanner (Issue #1379)
 * 2026
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The comment-aware scanners the line-based source tripwires read through (Issues #1379, #1380):
 * [SourceScan.codeLines] blanks string literals, then drops `//` and block-comment spans with the
 * block-comment state carried across the lines. [SourceScan.codeLinesKeepingStrings] removes the
 * same comment spans but keeps each literal's text.
 */
@DisplayName("SourceScan.codeLines keeps code and drops comments")
class SourceScanTest {
	@Test
	fun `a trailing line comment is dropped and the code before it kept`() {
		assertThat(SourceScan.codeLines(listOf("val t = now() // System.currentTimeMillis")))
			.containsExactly("val t = now() ")
	}

	@Test
	fun `whole comment lines and KDoc lines become empty`() {
		assertThat(SourceScan.codeLines(listOf("\t// comment", "/** KDoc", "\t * prose", "\t */")))
			.containsExactly("\t", "", "", "")
	}

	@Test
	fun `an inline block comment span is dropped and the code after its close kept`() {
		assertThat(SourceScan.codeLines(listOf("val x = 1 /* note */ + 2")))
			.containsExactly("val x = 1  + 2")
	}

	@Test
	fun `a block comment state is carried across lines and code after the close is seen again`() {
		val lines =
			listOf(
				"/* header",
				"\tval inside = reservePath()",
				"more prose */ val after = 1",
				"val next = 2"
			)

		assertThat(SourceScan.codeLines(lines)).containsExactly("", "", " val after = 1", "val next = 2")
	}

	@Test
	fun `an unclosed block comment swallows every following line`() {
		assertThat(SourceScan.codeLines(listOf("/** header without a close", "\tval code = 1")))
			.containsExactly("", "")
	}

	@Test
	fun `string literals are blanked so comment markers inside them do not act`() {
		assertThat(SourceScan.codeLines(listOf("val url = \"http://host\" + x", "val s = \"/*\"; val y = 1")))
			.containsExactly("val url =  + x", "val s = ; val y = 1")
	}

	@Test
	fun `a string literal with an escaped quote is blanked to its end`() {
		assertThat(SourceScan.codeLines(listOf("f(\"a \\\" b\") // tail")))
			.containsExactly("f() ")
	}

	@Test
	fun `the string-keeping scan keeps a literal and drops the trailing comment`() {
		assertThat(SourceScan.codeLinesKeepingStrings(listOf("errorStop(\"no route at \$where\") // old wording")))
			.containsExactly("errorStop(\"no route at \$where\") ")
	}

	@Test
	fun `the string-keeping scan drops block comments and carries their state across lines`() {
		val lines = listOf("val a = \"x\" /* note", "\"y\" inside */ + \"z\"", "/** KDoc \"w\" */")

		assertThat(SourceScan.codeLinesKeepingStrings(lines)).containsExactly("val a = \"x\" ", " + \"z\"", "")
	}

	@Test
	fun `the string-keeping scan lets no comment marker inside a literal act`() {
		assertThat(SourceScan.codeLinesKeepingStrings(listOf("val url = \"http://host\" + \"/*\"; val y = 1")))
			.containsExactly("val url = \"http://host\" + \"/*\"; val y = 1")
	}

	// The deliberate holes the [codeLines] KDoc names, pinned one by one.
	// Every expected value is the scanner's observed output on the exact input.

	@Test
	fun `a raw triple-quoted string across lines is not blanked, its content reads as code`() {
		val lines =
			listOf(
				"val text = \"\"\"begin // prose",
				"\tval inside = f() // prose",
				"\t\"\"\"",
				"val after = 1"
			)

		assertThat(SourceScan.codeLines(lines))
			.containsExactly("val text = \"begin ", "\tval inside = f() ", "\t\"", "val after = 1")
	}

	@Test
	fun `a close pair inside a literal moves a block comment's close`() {
		// The comment opens on the first line. The compiler closes at the `*/` inside the second
		// line's literal; the scanner cannot see it there, so it closes on the third line instead:
		// the second line's code is hidden, and `still open */` is treated as comment text.
		val lines = listOf("/* open", "val s = \"*/\"", "still open */ val okay = 1")

		assertThat(SourceScan.codeLines(lines)).containsExactly("", "", " val okay = 1")
	}

	@Test
	fun `the keeping-strings scan moves the close the same way`() {
		// A literal's text survives only inside a code span. The `*/` inside the middle line's
		// literal cannot close the block comment opened on the first line, so the middle line's
		// kept text is still swallowed here with it.
		val lines = listOf("/* open", "\tval s = \"*/\"", "still */ val okay = 1")

		assertThat(SourceScan.codeLinesKeepingStrings(lines)).containsExactly("", "", " val okay = 1")
	}

	@Test
	fun `nested block comments close at the first close pair, the rest reads as code`() {
		// The compiler nests block comments, so it would carry one comment through the outer
		// close on the next line; the scanner closes at the first pair and exposes the inner line.
		val lines = listOf("/* outer /* inner */ val exposed = 1", "val next = 2")

		assertThat(SourceScan.codeLines(lines)).containsExactly(" val exposed = 1", "val next = 2")
	}

	@Test
	fun `a string quoted inside a template expression is not one literal, its argument reads as code`() {
		// The regex masks `"abc ${f("` and `")}"` as two literals; nothing masks the five chars of
		// `inner` between them, so they survive the scan as code the compiler sees only inside a string.
		assertThat(SourceScan.codeLines(listOf("val m = \"abc \${f(\"inner\")}\"")))
			.containsExactly("val m = inner")
	}

	@Test
	fun `a line comment right behind a literal's closing quote begins at the mask end`() {
		// The `//` inside the literal cannot comment (it is masked); the real `//` comment starts
		// at the byte after the closing quote, with no space in between either.
		val lines = listOf("val u = \"http://x\"//tail")

		assertThat(SourceScan.codeLines(lines)).containsExactly("val u = ")
		assertThat(SourceScan.codeLinesKeepingStrings(lines)).containsExactly("val u = \"http://x\"")
	}

	@Test
	fun `an empty input yields no lines and an empty line carries the block state`() {
		assertThat(SourceScan.codeLines(emptyList())).isEmpty()
		assertThat(SourceScan.codeLines(listOf("/* open", "", "*/ val after = 1", "")))
			.containsExactly("", "", " val after = 1", "")
		assertThat(SourceScan.codeLinesKeepingStrings(listOf("/* open", "", "*/ val after = 1", "")))
			.containsExactly("", "", " val after = 1", "")
	}
}
