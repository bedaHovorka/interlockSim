/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.docs

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tripwire for stale `<Name>.kt:<line>` anchors in the committed documentation (Issue #1205).
 *
 * Three consecutive per-PR review rounds (#901, #1194, #1195) paid for line anchors that an
 * extraction had moved. `docs/KOTLIN_STYLE_GUIDE.md` ("Code Comments That Reference Other Code")
 * now says to reference code by symbol, never by line; this test is the mechanical belt under that
 * rule. Like every tripwire in this repository it fails and never fixes: the author retargets the
 * anchor to a symbol (preferred) or corrects the line by hand.
 *
 * ## Scope
 *
 * Every `*.md` file under `docs/`, recursively. The set is deliberately the whole directory, not
 * just `goal9b-demands/` and `PATH_*_ARCHITECTURE.md`: anchors rotted in the older audit, decision
 * and spec pages just as fast, and a narrow scope would leave exactly the pages nobody re-reads.
 * Only `.kt` anchors are checked (`.kts`, `.java` and bare `:123` fragments are not). Pages that
 * cite another repository's file (the kDisco review) must say "line N" in prose instead of using the
 * anchor shape, because a file outside this repository can never be resolved.
 *
 * ## What is checked
 *
 * 1. **The file exists.** The name is resolved over every `*.kt` file in the repository (build,
 *    `.git`, `.gradle`, `.kotlin`, `.idea` and `.worktrees` directories are not searched). A
 *    qualified anchor (`core/.../Foo.kt:12`) is resolved by its path suffix, where everything up
 *    to the last `...` is ignored. A name that matches several files is reported as ambiguous;
 *    qualify it with a path.
 * 2. **The file is long enough.** The highest line of the anchor (`Foo.kt:12-30` -> 30) must not
 *    exceed the file's line count.
 * 3. **The symbol is still near the anchor -- only where that is robust.** When a documentation
 *    line holds exactly ONE anchor and exactly ONE backticked identifier (a span of one plain
 *    identifier, optionally followed by `()`), that identifier must occur within three lines of the
 *    anchored range. Any other shape (several anchors, several identifiers, none) is skipped: the
 *    sentence then does not say which symbol the anchor is about, and guessing would produce false
 *    alarms that teach people to ignore the failure.
 *
 * The check is skipped (not failed) when `docs/` is not on disk -- the Docker test stage copies only
 * the module `src/` directories, so there is nothing to scan there; every checkout (CI included) has
 * it. The test is not vacuous: it fails when fewer than [MIN_ANCHORS] anchors are found, and the
 * checker itself is proven on a synthetic tree by the tests below.
 */
class DocLineAnchorTripwireTest {
	@TempDir
	lateinit var tmp: File

	@Test
	fun `no committed doc carries a stale kt line anchor`() {
		val root = findRepositoryRoot()
		assumeTrue(root != null && File(root, "docs").isDirectory, "docs/ is not on disk (Docker test stage)")
		root!!
		val checker = AnchorChecker(root)

		val docs = File(root, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
		val result = checker.checkAll(docs)

		assertThat(result.anchorCount).isGreaterThanOrEqualTo(MIN_ANCHORS)
		assertThat(result.violations.map { it.render() }).isEmpty()
	}

	@Test
	fun `a missing file, a short file and an ambiguous name are all reported`() {
		write("a/One.kt", "line1\nline2\nline3\n")
		write("a/Twin.kt", "x\n")
		write("b/Twin.kt", "y\n")
		write(
			"docs/page.md",
			"ok `One.kt:3` fine\ngone `Missing.kt:1`\nshort `One.kt:4`\nrange `One.kt:2-9`\nambiguous `Twin.kt:1`\n"
		)

		val result = AnchorChecker(tmp).checkAll(listOf(File(tmp, "docs/page.md")))

		assertThat(result.anchorCount).isEqualTo(5)
		assertThat(result.violations).hasSize(4)
		val rendered = result.violations.map { it.render() }
		assertThat(rendered[0]).contains("page.md:2", "Missing.kt:1", "no such file")
		assertThat(rendered[1]).contains("page.md:3", "One.kt:4", "only 3 lines")
		assertThat(rendered[2]).contains("page.md:4", "One.kt:2-9", "only 3 lines")
		assertThat(rendered[3]).contains("page.md:5", "Twin.kt:1", "ambiguous", "a/Twin.kt", "b/Twin.kt")
	}

	@Test
	fun `a qualified anchor resolves by its path suffix and an elided path by its tail`() {
		write("a/Twin.kt", "x\ny\n")
		write("b/Twin.kt", "x\n")
		write("docs/page.md", "`b/Twin.kt:1` and `a/Twin.kt:2` and `mod/.../b/Twin.kt:1` and `b/Twin.kt:2`\n")

		val result = AnchorChecker(tmp).checkAll(listOf(File(tmp, "docs/page.md")))

		assertThat(result.anchorCount).isEqualTo(4)
		assertThat(result.violations).hasSize(1)
		assertThat(result.violations.single().render()).contains("b/Twin.kt:2", "only 1 lines")
	}

	@Test
	fun `a symbol is checked only when the line names exactly one anchor and one identifier`() {
		write("Foo.kt", (1..20).joinToString("\n") { if (it == 10) "fun target()" else "// filler $it" } + "\n")
		write(
			"docs/page.md",
			listOf(
				"near `target` is at `Foo.kt:12`", // within +-3 of line 10 -> ok
				"moved `target` is at `Foo.kt:16`", // 6 lines away -> reported
				"range `target` is at `Foo.kt:14-18`", // range starts 4 below -> reported
				"call form `target()` is at `Foo.kt:9`", // () accepted -> ok
				"two identifiers `target` and `other` at `Foo.kt:18`", // skipped
				"two anchors `target` at `Foo.kt:18` and `Foo.kt:19`", // skipped
				"no identifier at `Foo.kt:18`", // skipped
				"a phrase `not an identifier` at `Foo.kt:18`" // not an identifier span -> skipped
			).joinToString("\n") + "\n"
		)

		val result = AnchorChecker(tmp).checkAll(listOf(File(tmp, "docs/page.md")))

		assertThat(result.violations.map { it.render() }).hasSize(2)
		val rendered = result.violations.map { it.render() }
		assertThat(rendered[0]).contains("page.md:2", "Foo.kt:16", "target")
		assertThat(rendered[1]).contains("page.md:3", "Foo.kt:14-18", "target")
	}

	@Test
	fun `the repository root is found from the module working directory`() {
		assertThat(findRepositoryRoot()).isNotNull()
	}

	private fun write(
		path: String,
		content: String
	) {
		val file = File(tmp, path)
		file.parentFile.mkdirs()
		file.writeText(content)
	}

	private fun findRepositoryRoot(): File? =
		generateSequence(File("").absoluteFile) { it.parentFile }
			.firstOrNull { File(it, "settings.gradle.kts").isFile }

	/** One finding: where the anchor sits, the anchor text and why it is rejected. */
	private data class Violation(
		val doc: File,
		val docRoot: File,
		val docLine: Int,
		val anchor: String,
		val reason: String
	) {
		fun render(): String = "${doc.relativeTo(docRoot).path}:$docLine: `$anchor` -> $reason"
	}

	private data class CheckResult(
		val anchorCount: Int,
		val violations: List<Violation>
	)

	/** Resolves and verifies the anchors of [docs] against the `*.kt` files below [root]. */
	private class AnchorChecker(
		private val root: File
	) {
		private val ktFiles: Map<String, List<String>> by lazy {
			val byName = mutableMapOf<String, MutableList<String>>()
			root
				.walkTopDown()
				.onEnter { it.name !in SKIPPED_DIRECTORIES }
				.filter { it.isFile && it.extension == "kt" }
				.forEach { byName.getOrPut(it.name) { mutableListOf() }.add(it.relativeTo(root).invariantSeparatorsPath) }
			byName.mapValues { (_, paths) -> paths.sorted() }
		}

		private val lineCounts = mutableMapOf<String, List<String>>()

		fun checkAll(docs: List<File>): CheckResult {
			var anchors = 0
			val violations = mutableListOf<Violation>()
			for (doc in docs.sortedBy { it.path }) {
				doc.readLines().forEachIndexed { index, line ->
					val found = ANCHOR.findAll(line).toList()
					anchors += found.size
					for (match in found) {
						check(doc, index + 1, line, match, found.size)?.let { violations += it }
					}
				}
			}
			return CheckResult(anchors, violations)
		}

		private fun check(
			doc: File,
			docLine: Int,
			line: String,
			match: MatchResult,
			anchorsOnLine: Int
		): Violation? {
			val anchor = match.value

			fun violation(reason: String) = Violation(doc, root, docLine, anchor, reason)

			val qualifier = match.groupValues[1]
			val first = match.groupValues[2].toInt()
			val last = match.groups[3]?.value?.toInt() ?: first
			val tail =
				qualifier
					.split('/')
					.dropWhile { it != "..." }
					.drop(1)
					.ifEmpty { qualifier.split('/') }
			val name = tail.last()
			val candidates =
				ktFiles[name].orEmpty().filter { path ->
					tail.size == 1 || path == tail.joinToString("/") || path.endsWith("/" + tail.joinToString("/"))
				}
			return when {
				candidates.isEmpty() -> {
					violation("no such file `$qualifier` in the repository (retarget to a symbol)")
				}

				candidates.size > 1 -> {
					violation(
						"ambiguous: ${candidates.size} files match (${candidates.joinToString()}); " +
							"qualify the anchor with a path"
					)
				}

				else -> {
					val source = lineCounts.getOrPut(candidates.single()) { File(root, candidates.single()).readLines() }
					when {
						last > source.size -> {
							violation("the file has only ${source.size} lines (retarget to a symbol)")
						}

						else -> {
							symbolProblem(line, anchorsOnLine, source, first, last)?.let { violation(it) }
						}
					}
				}
			}
		}

		private fun symbolProblem(
			line: String,
			anchorsOnLine: Int,
			source: List<String>,
			first: Int,
			last: Int
		): String? {
			val identifiers = IDENTIFIER_SPAN.findAll(line).map { it.groupValues[1] }.toList()
			if (anchorsOnLine != 1 || identifiers.size != 1) return null
			val symbol = identifiers.single()
			val from = (first - CONTEXT_LINES).coerceAtLeast(1)
			val to = (last + CONTEXT_LINES).coerceAtMost(source.size)
			val near = (from..to).any { symbol in source[it - 1] }
			return if (near) {
				null
			} else {
				"`$symbol` does not occur within $CONTEXT_LINES lines of the anchored range (retarget to the symbol)"
			}
		}
	}

	private companion object {
		/** The tripwire must see at least this many anchors, or it is scanning the wrong place. */
		const val MIN_ANCHORS = 15

		/** Lines either side of an anchored range in which its symbol must still occur. */
		const val CONTEXT_LINES = 3

		val SKIPPED_DIRECTORIES = setOf("build", ".git", ".gradle", ".kotlin", ".idea", ".worktrees", "node_modules")

		/** `[path/]Name.kt:12` or `[path/]Name.kt:12-30`; group 1 = path and name, 2 = first line, 3 = last line. */
		val ANCHOR = Regex("""((?:[A-Za-z0-9_.\-]+/)*[A-Za-z0-9_\-]+\.kt):(\d+)(?:[-–](\d+))?(?![0-9A-Za-z])""")

		/** A backticked span holding one plain identifier, optionally followed by `()`. */
		val IDENTIFIER_SPAN = Regex("""`([A-Za-z_][A-Za-z0-9_]*)(?:\(\))?`""")
	}
}
