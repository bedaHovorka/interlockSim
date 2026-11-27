/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.architecture

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SP2 architecture tripwire of Goal 9B (Issue #1436): the Goal 9A symbols that Goal 9B removes stay
 * removed. Owner ruling R1 (#1434) takes conflict resolution to the dispatcher thread, so the
 * deterministic Goal 9A pieces leave the code base leaf by leaf; each leaf adds its symbols to
 * [REMOVED_SYMBOLS] and this test keeps them from coming back.
 *
 * Two checks per symbol:
 * 1. **No class on the classpath.** The fully qualified name must not load (the `:core` jvm main
 *    output is on this test's classpath).
 * 2. **No main-source mention.** No file of a main source set (`src/main`, `src/<target>Main`) of any
 *    module, and no Gradle build script, names the simple name as a whole word — code, KDoc and
 *    comments alike, so a stale "see [Foo]" cannot outlive its class. Test sources are not scanned:
 *    this test itself, and tests that explain what replaced a deleted pin, may name the symbol.
 *
 * A removed symbol whose simple name is still carried by a kept class of another package (a
 * homonym, listed in [KEPT_HOMONYMS]) is matched more narrowly: a line counts when it names the
 * removed fully qualified name, or when it names the simple name in a file that could resolve it
 * to the removed class — a file of the removed class's package, or one that imports the removed
 * name or its package with a wildcard.
 *
 * A third check covers the one removed member that is not a class: [ConflictResolutionRanker]
 * keeps a single `rank` method, the base ranking.
 *
 * Like every tripwire in this repository it fails and never fixes.
 *
 * Leaves: SP2.1 (#1453) removed `AutoConflictResolutionService` and its default implementation.
 * SP2.2 (#1454) removed the in-memory preference learning: `StrategyPreferenceStore`, the
 * `sim.conflict` `DispatcherPreferenceStore` with `DefaultDispatcherPreferenceStore`, and the
 * preference overload of [ConflictResolutionRanker.rank].
 */
class Goal9ARemovedSymbolsTripwireTest {
	private companion object {
		/** Fully qualified names of the removed Goal 9A symbols, with the leaf that removed each. */
		val REMOVED_SYMBOLS: Map<String, String> =
			mapOf(
				"cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService" to "#1453",
				"cz.vutbr.fit.interlockSim.sim.conflict.DefaultAutoConflictResolutionService" to "#1453",
				"cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore" to "#1454",
				"cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore" to "#1454",
				"cz.vutbr.fit.interlockSim.sim.conflict.DefaultDispatcherPreferenceStore" to "#1454"
			)

		/**
		 * Kept classes that share a simple name with a removed symbol, by that simple name. The
		 * `:dispatcher-agent` store holds the author counts; Goal 9B SP6 deletes it after #1476.
		 */
		val KEPT_HOMONYMS: Map<String, String> =
			mapOf(
				"DispatcherPreferenceStore" to "cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore"
			)

		/** Directories never searched: build output, VCS and IDE state, nested checkouts. */
		val SKIPPED_DIRECTORIES =
			setOf("build", ".git", ".gradle", ".kotlin", ".idea", ".worktrees", ".claude", "node_modules")

		/** A main source set directory under `<module>/src/`: `main` or `<target>Main`. */
		fun isMainSourceSet(name: String): Boolean = name == "main" || name.endsWith("Main")

		fun findRepositoryRoot(): File =
			generateSequence(File("").absoluteFile) { it.parentFile }
				.firstOrNull { File(it, "settings.gradle.kts").isFile }
				?: error("Repository root (settings.gradle.kts) not found above ${File("").absolutePath}")
	}

	private val root: File by lazy { findRepositoryRoot() }

	/** Every scanned file: main source set files of every module plus every Gradle build script. */
	private val scannedFiles: List<File> by lazy {
		val modules =
			root.listFiles().orEmpty().filter { it.isDirectory && it.name !in SKIPPED_DIRECTORIES }
		val mainSources =
			modules
				.map { File(it, "src") }
				.filter { it.isDirectory }
				.flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && isMainSourceSet(it.name) } }
				.flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" } }
		val buildScripts =
			root
				.walkTopDown()
				.onEnter { it.name !in SKIPPED_DIRECTORIES }
				.filter { it.isFile && it.name.endsWith(".gradle.kts") }
				.toList()
		mainSources + buildScripts
	}

	@Test
	fun `no removed Goal 9A class is on the classpath`() {
		val loadable =
			REMOVED_SYMBOLS.filterKeys { fqn ->
				runCatching { Class.forName(fqn, false, javaClass.classLoader) }.isSuccess
			}

		assertThat(loadable.map { (fqn, leaf) -> "$fqn (removed by $leaf) still loads" }).isEmpty()
	}

	@Test
	fun `no main source or build script names a removed Goal 9A symbol`() {
		val hits =
			scannedFiles.flatMap { file ->
				val lines = file.readLines()
				REMOVED_SYMBOLS.keys.flatMap { fqn ->
					val pattern = mentionPattern(fqn, lines)
					lines.withIndex().filter { (_, line) -> pattern.containsMatchIn(line) }.map { (index, _) ->
						"${file.relativeTo(root).path}:${index + 1}: ${fqn.substringAfterLast('.')}"
					}
				}
			}

		assertThat(hits).isEmpty()
	}

	@Test
	fun `the ranker keeps only the base rank method`() {
		val rankMethods = ConflictResolutionRanker::class.java.declaredMethods.filter { it.name == "rank" }

		assertThat(rankMethods.map { it.parameterTypes.map(Class<*>::getSimpleName) }).containsExactly(listOf("List"))
	}

	@Test
	fun `a homonym mention counts only where it resolves to the removed class`() {
		val removed = "cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore"
		val kept = KEPT_HOMONYMS.getValue("DispatcherPreferenceStore")
		val line = "val store: DispatcherPreferenceStore"

		fun matches(vararg header: String) = mentionPattern(removed, header.toList()).containsMatchIn(line)

		assertThat(matches("package cz.vutbr.fit.interlockSim.dispatcher.agents")).isFalse()
		assertThat(matches("package cz.vutbr.fit.interlockSim.gui", "import $kept")).isFalse()
		assertThat(matches("package cz.vutbr.fit.interlockSim.sim.conflict")).isTrue()
		assertThat(matches("package cz.vutbr.fit.interlockSim.gui", "import $removed")).isTrue()
		assertThat(
			matches("package cz.vutbr.fit.interlockSim.gui", "import cz.vutbr.fit.interlockSim.sim.conflict.*")
		).isTrue()
		assertThat(mentionPattern(removed, emptyList()).containsMatchIn("see [$removed]")).isTrue()
	}

	/**
	 * The regex that finds a mention of [fqn] in a file with [lines]:
	 * the simple name as a whole word, or for a homonym (see [KEPT_HOMONYMS])
	 * the fully qualified name, plus the simple name only where the file resolves it to [fqn].
	 */
	private fun mentionPattern(
		fqn: String,
		lines: List<String>
	): Regex {
		val simpleName = fqn.substringAfterLast('.')
		val simpleNamePattern = "\\b$simpleName\\b"
		if (simpleName !in KEPT_HOMONYMS) return Regex(simpleNamePattern)
		val packageName = fqn.substringBeforeLast('.')
		val resolvesToRemoved =
			lines.any { line ->
				val trimmed = line.trim()
				trimmed == "package $packageName" ||
					trimmed == "import $fqn" ||
					trimmed == "import $packageName.*"
			}
		val fqnPattern = Regex.escape(fqn) + "\\b"
		return Regex(if (resolvesToRemoved) "$fqnPattern|$simpleNamePattern" else fqnPattern)
	}

	@Test
	fun `the scan is not vacuous`() {
		// The conflict package that held the removed classes is scanned, and so are the DI modules
		// that bound them and the build scripts.
		assertThat(scannedFiles.size).isGreaterThan(MIN_SCANNED_FILES)
		assertThat(scannedFiles.any { it.name == "ConflictResolver.kt" }).isTrue()
		assertThat(scannedFiles.any { it.name == "CoreModule.kt" }).isTrue()
		assertThat(scannedFiles.any { it.name == "SharedSimulationTestScopesModule.kt" }).isTrue()
		assertThat(scannedFiles.any { it.name == "build.gradle.kts" }).isTrue()
	}
}

/** Main sources scanned at least; `:core` commonMain alone holds several hundred files. */
private const val MIN_SCANNED_FILES = 200
