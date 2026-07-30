import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
}
