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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [SourceScan.codeLines] is the one comment-aware scanner every line-based source tripwire reads
 * through (Issue #1379): string literals blanked first, then `//` to end of line and block-comment
 * spans removed, with the block-comment state carried across the lines.
 * [SourceScan.codeLinesKeepingStrings] removes the same comment spans but keeps each literal's text
 * (Issue #1380).
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
}
