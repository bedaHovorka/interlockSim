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
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * A named argument (`dtMax = x,` in a constructor call) is not a statement and is not matched.
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
	fun `the matcher sees statements and ignores named arguments, comments and strings`() {
		assertThat(statementOf("\t\tdtMax = 1e-3")).isEqualTo("dtMax = 1e-3")
		// Split so that this file's own source line is not a match.
		assertThat(statementOf("\t\t\tpinnedDtMax?.let {" + " dtMax = it }")).isEqualTo("dtMax = it")
		assertThat(statementOf("\t\t\tdtMax = loop.observedDtMax,")).isEqualTo(null)
		assertThat(statementOf("\t * `dtMax = 1e-3` in a KDoc line")).isEqualTo(null)
		assertThat(statementOf("\t\t// dtMax = 1e-1 in a comment")).isEqualTo(null)
		assertThat(statementOf("\t\tval text = \"dtMax = 1e-1\"")).isEqualTo(null)
		assertThat(statementOf("\t\tif (dtMax == 1e-3) return")).isEqualTo(null)
		assertThat(statementOf("\t\tdtMax = minOf(a, b)")).isEqualTo("dtMax = minOf(a, b)")
		assertThat(statementOf("\t\tif (step != null)" + " dtMax = step")).isEqualTo("dtMax = step")
		assertThat(statementOf("\t\tif (fast) x() else" + " dtMax = 1e-1")).isEqualTo("dtMax = 1e-1")
		assertThat(statementOf("\t\tthis." + "dtMax = 1e-1")).isEqualTo("this.dtMax = 1e-1")
		assertThat(statementOf("\t\tobservedDtMax = dtMax")).isEqualTo(null)
	}

	/** One `dtMax = …` statement: the file relative to the repository root, and the statement. */
	private data class Site(
		val path: String,
		val statement: String,
		val production: Boolean
	)

	private fun assignmentSites(): List<Site> {
		val root = findRepositoryRoot()
		assumeTrue(root != null, "repository root not found from ${File("").absolutePath}")
		root!!
		val modules = root.listFiles().orEmpty().filter { File(it, "src").isDirectory }
		return modules.flatMap { module ->
			File(module, "src").listFiles().orEmpty().filter { it.isDirectory }.flatMap { sourceSet ->
				val production = sourceSet.name == "main" || sourceSet.name.endsWith("Main")
				sourceSet
					.walkTopDown()
					.filter { it.isFile && it.extension == "kt" }
					.flatMap { file ->
						file.readLines().mapNotNull { line ->
							statementOf(line)?.let { Site(file.relativeTo(root).invariantSeparatorsPath, it, production) }
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
		 * A `dtMax = …` statement: at the start of the line or after `{`, `;`, `)` (an `if` without
		 * braces) or `else`; not `==`, and not a named argument (which ends with a comma).
		 */
		val STATEMENT = Regex("""(?:^|[{;)]|\belse)\s*((?:\w+\.)*dtMax\s*=(?!=).*?)\s*}?\s*$""")

		fun statementOf(line: String): String? =
			STATEMENT
				.find(line)
				?.groupValues
				?.get(1)
				?.trim()
				?.takeUnless { it.endsWith(",") }
	}
}
