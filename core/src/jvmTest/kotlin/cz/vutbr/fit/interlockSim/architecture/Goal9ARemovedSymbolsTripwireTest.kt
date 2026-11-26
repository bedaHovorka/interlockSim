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
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
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
 * Like every tripwire in this repository it fails and never fixes.
 *
 * Leaves: SP2.1 (#1453) removed `AutoConflictResolutionService` and its default implementation.
 */
class Goal9ARemovedSymbolsTripwireTest {
	private companion object {
		/** Fully qualified names of the removed Goal 9A symbols, with the leaf that removed each. */
		val REMOVED_SYMBOLS: Map<String, String> =
			mapOf(
				"cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService" to "#1453",
				"cz.vutbr.fit.interlockSim.sim.conflict.DefaultAutoConflictResolutionService" to "#1453"
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
			(listOf(root) + modules).flatMap { dir ->
				dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".gradle.kts") }
			}
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
		val patterns = REMOVED_SYMBOLS.keys.map { it.substringAfterLast('.') }.associateWith { Regex("\\b$it\\b") }
		val hits =
			scannedFiles.flatMap { file ->
				file.readLines().withIndex().flatMap { (index, line) ->
					patterns
						.filterValues { it.containsMatchIn(line) }
						.keys
						.map { name -> "${file.relativeTo(root).path}:${index + 1}: $name" }
				}
			}

		assertThat(hits).isEmpty()
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
