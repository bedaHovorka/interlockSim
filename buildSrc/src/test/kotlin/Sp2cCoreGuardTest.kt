import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [Sp2cCoreGuard] (Issue #823, SP2c.0), run against throwaway git repositories
 * created in a JUnit [TempDir] — fast and isolated, no Gradle build spin-up needed to exercise
 * the git-diffing logic behind the root `checkCoreUntouchedBySp2c` task.
 */
class Sp2cCoreGuardTest {
	@Test
	fun `fails and lists offending files when a core file changed since baseline`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		repo.writeAndCommit("core/Foo.kt", "class Foo // touched", "touch core/Foo.kt")

		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		val violated = assertInstanceOf(Sp2cCoreGuard.Result.Violated::class.java, result)
		assertEquals(listOf("core/Foo.kt"), violated.offendingFiles)
	}

	@Test
	fun `passes on the baseline commit itself with no core changes`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		// HEAD == baselineRef: no commits since baseline at all.
		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		assertEquals(Sp2cCoreGuard.Result.Passed(baselineRef), result)
	}

	@Test
	fun `passes when only files outside core changed since baseline`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		repo.writeAndCommit("desktop-ui/Bar.kt", "class Bar", "unrelated desktop-ui change")

		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		assertEquals(Sp2cCoreGuard.Result.Passed(baselineRef), result)
	}

	@Test
	fun `passes when the only core change is allowlisted`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		repo.writeAndCommit("core/Foo.kt", "class Foo // touched", "touch core/Foo.kt")

		val result =
			Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = setOf("core/Foo.kt"))

		assertEquals(Sp2cCoreGuard.Result.Passed(baselineRef), result)
	}

	@Test
	fun `skips with a clear reason when baseline is not an ancestor of HEAD`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val unrelatedRef = repo.headRef()

		// Diverge to a completely disconnected history: unrelatedRef is not reachable from
		// the new orphan branch's HEAD, so merge-base --is-ancestor must report "no".
		repo.checkoutOrphan("unrelated-branch")
		repo.writeAndCommit("README.md", "unrelated history", "unrelated root commit")

		val result = Sp2cCoreGuard.evaluate(tempDir, unrelatedRef, allowlistedPaths = emptySet())

		val skipped = assertInstanceOf(Sp2cCoreGuard.Result.Skipped::class.java, result)
		assertTrue(skipped.reason.contains(unrelatedRef)) {
			"expected skip reason to name the baseline ref, was: ${skipped.reason}"
		}
	}

	@Test
	fun `fails and lists a deleted core file since baseline`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		repo.deleteAndCommit("core/Foo.kt", "delete core/Foo.kt")

		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		val violated = assertInstanceOf(Sp2cCoreGuard.Result.Violated::class.java, result)
		assertEquals(listOf("core/Foo.kt"), violated.offendingFiles)
	}

	@Test
	fun `fails and lists a newly added core file since baseline`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("README.md", "readme", "initial non-core commit")
		val baselineRef = repo.headRef()

		repo.writeAndCommit("core/Bar.kt", "class Bar", "add core/Bar.kt")

		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		val violated = assertInstanceOf(Sp2cCoreGuard.Result.Violated::class.java, result)
		assertEquals(listOf("core/Bar.kt"), violated.offendingFiles)
	}

	@Test
	fun `fails and lists every offending core file when multiple changed`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")
		val baselineRef = repo.headRef()

		repo.writeAndCommit("core/Bar.kt", "class Bar", "add core/Bar.kt")
		repo.writeAndCommit("core/Foo.kt", "class Foo // touched", "touch core/Foo.kt")

		val result = Sp2cCoreGuard.evaluate(tempDir, baselineRef, allowlistedPaths = emptySet())

		val violated = assertInstanceOf(Sp2cCoreGuard.Result.Violated::class.java, result)
		// git diff --name-only lists paths in sorted order; compare as a set to stay robust.
		assertEquals(setOf("core/Bar.kt", "core/Foo.kt"), violated.offendingFiles.toSet())
	}

	@Test
	fun `throws when baselineRef does not resolve to a commit`(
		@TempDir tempDir: File,
	) {
		val repo = TestGitRepo(tempDir)
		repo.writeAndCommit("core/Foo.kt", "class Foo", "initial commit")

		// A malformed/non-existent ref makes `git merge-base --is-ancestor` exit 128 (an error,
		// not 1 = not-ancestor). The guard treats this as misconfiguration, not a skip: it fails
		// the build loudly via check() so a broken baseline .properties cannot pass silently.
		val ex = assertThrows(IllegalStateException::class.java) {
			Sp2cCoreGuard.evaluate(tempDir, "not-a-valid-object", allowlistedPaths = emptySet())
		}
		assertTrue(ex.message!!.contains("merge-base") && ex.message!!.contains("128")) {
			"expected failure to come from the merge-base check with exit 128, was: ${ex.message}"
		}
	}

	@Test
	fun `parseAllowlist trims, drops blanks and comments, and tolerates CRLF`() {
		val parsed = Sp2cCoreGuard.parseAllowlist(
			listOf(
				"core/Foo.kt",
				"  core/Bar.kt  ",
				"",
				"# a comment",
				"   # indented comment",
				"core/Baz.kt\r",
			),
		)

		assertEquals(setOf("core/Foo.kt", "core/Bar.kt", "core/Baz.kt"), parsed)
	}
}
