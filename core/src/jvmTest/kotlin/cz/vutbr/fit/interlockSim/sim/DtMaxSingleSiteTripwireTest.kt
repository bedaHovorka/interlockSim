/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Tripwire for the one global integration step cap (Goal 1B SP13, Issue #1160)
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.testutil.SourceScan
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * kDisco's `dtMax` has one value for every scenario run (Goal 1B SP13, Issue #1160): it is set in
 * exactly one place of the production code, `Generator.startAction`, from [Generator.DT_MAX]. Every
 * scenario run gets it from there, `MultiTrainLoop` and `ShuntingLoop` through their generators.
 *
 * The tripwire greps every module's sources for a `dtMax = …` statement:
 * - production source sets (`commonMain`, `jvmMain`, `nativeMain`, `main`, …): only the one
 *   `dtMax = DT_MAX` line in `Generator.kt`, so no scenario can carry its own override;
 * - test source sets: only the files in [TEST_FILES_ALLOWED_TO_SET_DT_MAX], which pin their own
 *   step on purpose (an engine stand-alone run, two hand-driven processes, the 1e-1 reproducer).
 *   Each of them must still set it, so the list cannot go stale.
 *
 * A named argument (`dtMax = x,` in a constructor call) is not a statement and is not matched, and
 * neither is a `dtMax` inside a comment or a string literal: the shared scanner
 * [SourceScan.codeLines] strips string literals and comment spans (whole block comments, the state
 * carried across the lines of one file) before [statementOf] looks for the statement. A statement
 * after a `when` branch arrow (`->`) IS a statement.
 *
 * Deliberate holes (a per-line grep is not a parser): a statement split as `dtMax` on one line and
 * `= value` on the next is not matched; a named argument behind `(` on its line is not matched,
 * with or without the trailing comma, while one that starts its line is matched unless it ends
 * with the comma. The scanner's own holes apply too: a raw triple-quoted string can raise a false
 * alarm; a close pair inside a string literal can move a block comment's close; nested block
 * comments close at the first close pair, so a nested opener is a false alarm. Pathological cases,
 * accepted. The walk up to `settings.gradle.kts` must always succeed under Gradle and CI; when it
 * cannot, the tests fail loudly — never a silent skip.
 *
 * The grep reads files outside `:core`, which Gradle does not track as inputs of this test task,
 * so a change in another module alone can leave this test up to date until `:core` changes too.
 */
@DisplayName("SP13: kDisco dtMax is set only at its one global site")
class DtMaxSingleSiteTripwireTest {
	@Test
	fun `production code sets dtMax only in Generator, from the global constant`() {
		val sites = assignmentSites().filter { it.production }

		assertThat(sites.map { "${it.path}: ${it.statement}" })
			.containsExactly("$GENERATOR_PATH: dtMax = DT_MAX")
	}

	@Test
	fun `only the allowed test files set dtMax`() {
		val testSites = assignmentSites().filterNot { it.production }

		assertThat(
			testSites.filterNot { File(it.path).name in TEST_FILES_ALLOWED_TO_SET_DT_MAX }.map { "${it.path}: ${it.statement}" },
			name = "dtMax statements in test files outside the allowlist"
		).isEmpty()
		assertThat(
			testSites.map { File(it.path).name }.toSortedSet(),
			name = "allowed test files that still set dtMax"
		).isEqualTo(TEST_FILES_ALLOWED_TO_SET_DT_MAX.toSortedSet())
	}

	@Test
	fun `the matcher sees statements and ignores named arguments, comments, strings and when labels`() {
		assertThat(statementOf("\t\tdtMax = 1e-3")).isEqualTo("dtMax = 1e-3")
		// Split so that this file's own source line is not a match.
		assertThat(statementOf("\t\t\tpinnedDtMax?.let {" + " dtMax = it }")).isEqualTo("dtMax = it")
		assertThat(statementOf("\t\t\tdtMax = loop.observedDtMax,")).isEqualTo(null)
		assertThat(statementOf("\t * `dtMax = 1e-3` in a KDoc line")).isEqualTo(null)
		assertThat(statementOf("\t\t// dtMax = 1e-1 in a comment")).isEqualTo(null)
		assertThat(statementOf("\t\t// if (fast) dtMax = 1e-1")).isEqualTo(null)
		assertThat(statementOf("\t\tval text = \"dtMax = 1e-1\"")).isEqualTo(null)
		assertThat(statementOf("\t\tval text = \"if (fast) dtMax = 1e-1\"")).isEqualTo(null)
		assertThat(statementOf("\t\tif (dtMax == 1e-3) return")).isEqualTo(null)
		assertThat(statementOf("\t\tdtMax = minOf(a, b)")).isEqualTo("dtMax = minOf(a, b)")
		assertThat(statementOf("\t\tif (step != null)" + " dtMax = step")).isEqualTo("dtMax = step")
		assertThat(statementOf("\t\tif (fast) x() else" + " dtMax = 1e-1")).isEqualTo("dtMax = 1e-1")
		assertThat(statementOf("\t\tthis." + "dtMax = 1e-1")).isEqualTo("this.dtMax = 1e-1")
		assertThat(statementOf("\t\tFAST -> dtMax = 1e-1")).isEqualTo("dtMax = 1e-1")
		assertThat(statementOf("\t\telse -> this.dtMax = 1e-1")).isEqualTo("this.dtMax = 1e-1")
		assertThat(statementOf("\t\tobservedDtMax = dtMax")).isEqualTo(null)
		// A dtMax inside a block-comment span is comment text, not a statement.
		assertThat(statementOf("\t\tval x = 1 /* (rare) dtMax = 1e-1 */")).isEqualTo(null)
		// A named argument behind `(` is rejected by anchor absence, not by the comma.
		assertThat(statementOf("\t\trunWith(dtMax = 1e-1)")).isEqualTo(null)
		// The blanking regex spans a string with escaped quotes to its end: only `else` remains.
		assertThat(statementOf("\t\telse \"a \\\" dtMax = 1e-1\\\" b\"")).isEqualTo(null)
		// A compound assignment is a real statement too.
		assertThat(statementOf("\t\tdtMax += 1e-1")).isEqualTo("dtMax += 1e-1")
	}

	@Test
	fun `the matcher on the shared scanner skips block comments across lines and sees code after the close`() {
		val kdoc =
			listOf(
				"/** Example prose:",
				"\t * else dtMax = 1e-1 and (fast) dtMax = 1e-2",
				"\t */",
				"\tval pinned = 1"
			)
		assertThat(SourceScan.codeLines(kdoc).mapNotNull { statementIn(it) }).isEmpty()

		val unclosed =
			listOf(
				"/** header without a close",
				"\t * (fast) dtMax = 1e-2 still prose",
				"\tval real code after the close" + " dtMax = 1e-3"
			)
		assertThat(SourceScan.codeLines(unclosed).mapNotNull { statementIn(it) }).isEmpty()

		val reopened =
			listOf(
				"/* header",
				"more prose */" + " dtMax = 1e-3"
			)
		assertThat(SourceScan.codeLines(reopened).mapNotNull { statementIn(it) }).containsExactly("dtMax = 1e-3")
	}

	/** One `dtMax = …` statement: the file relative to the repository root, and the statement. */
	private data class Site(
		val path: String,
		val statement: String,
		val production: Boolean
	)

	private fun assignmentSites(): List<Site> {
		val root = findRepositoryRoot() ?: error("repository root not found from ${File("").absolutePath}")
		val modules = root.listFiles().orEmpty().filter { File(it, "src").isDirectory }
		return modules.flatMap { module ->
			File(module, "src").listFiles().orEmpty().filter { it.isDirectory }.flatMap { sourceSet ->
				val production = sourceSet.name == "main" || sourceSet.name.endsWith("Main")
				SourceScan
					.kotlinFiles(sourceSet)
					.flatMap { file ->
						SourceScan.codeLines(file.readLines()).mapNotNull { code ->
							statementIn(code)?.let { Site(file.relativeTo(root).invariantSeparatorsPath, it, production) }
						}
					}.toList()
			}
		}
	}

	private fun findRepositoryRoot(): File? =
		generateSequence(File("").absoluteFile) { it.parentFile }
			.firstOrNull { File(it, "settings.gradle.kts").isFile }

	private companion object {
		/** The single production site, relative to the repository root. */
		const val GENERATOR_PATH = "core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Generator.kt"

		/** Test files that set their own step on purpose (simple file names). */
		val TEST_FILES_ALLOWED_TO_SET_DT_MAX =
			setOf(
				"EngineStandaloneTest.kt",
				"SimpleTestProcess.kt",
				"TrainHoldAtStationIntegrationTest.kt",
				"DtMaxTenthStallReproducerTest.kt"
			)

		/**
		 * A `dtMax = …` statement (plain, or compound with `+=`): at the start of the line or after
		 * `{`, `;`, `)` (an `if` without braces), `>` (a `when` branch arrow) or `else`; not `==`,
		 * and a named argument is matched only when it starts its line without the trailing comma.
		 * [statementOf] works on the comment-stripped code [SourceScan.codeLines] produces.
		 */
		val STATEMENT = Regex("""(?:^|[{;)]|>|\belse)\s*((?:\w+\.)*dtMax\s*(?:\+=|=)(?!=).*?)\s*}?\s*$""")

		/**
		 * The `dtMax = …` statement [line] holds outside comments and string literals, or null: named
		 * arguments (trailing comma) and assignments inside comments or string literals are not
		 * statements. The line is stripped first by [SourceScan.codeLines] with fresh comment state —
		 * string literals go first (a `//` inside one must not truncate a real statement).
		 */
		fun statementOf(line: String): String? = statementIn(SourceScan.codeLines(listOf(line)).first())

		private fun statementIn(code: String): String? =
			STATEMENT
				.find(code)
				?.groupValues
				?.get(1)
				?.trim()
				?.takeUnless { it.endsWith(",") }
	}
}
