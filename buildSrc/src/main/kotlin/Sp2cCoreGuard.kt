import java.io.File

/**
 * SP2c ":core immutability guard" (Issue #823, #822 constraint C10 / principle P9).
 *
 * Compares the current `HEAD` against a frozen SP2c-start baseline commit and reports any file
 * under `core/` that changed since then, so the multi-week Goal-10 SP2c dispatcher redesign
 * cannot erode the zero-`:core`-changes rule through many small "just this once" edits.
 *
 * Pure, side-effect-free (besides shelling out to `git`) so it can be unit tested with plain
 * JUnit against throwaway git repositories — no Gradle build spin-up required. The root
 * `checkCoreUntouchedBySp2c` task (root build.gradle.kts) is a thin wrapper around [evaluate].
 */
object Sp2cCoreGuard {
	sealed class Result {
		/** No `core/` files changed since [baselineRef] (after allowlist filtering). */
		data class Passed(val baselineRef: String) : Result()

		/** [baselineRef] is not an ancestor of `HEAD` — not a failure, just not applicable here. */
		data class Skipped(val reason: String) : Result()

		/** One or more `core/` files changed since [baselineRef] without an allowlist entry. */
		data class Violated(val baselineRef: String, val offendingFiles: List<String>) : Result()
	}

	/**
	 * @param repoRoot working directory for the `git` subprocess calls (the repository root).
	 * @param baselineRef the frozen SP2c-start commit SHA to compare `HEAD` against.
	 * @param allowlistedPaths repo-relative paths (as they appear in `git diff --name-only`
	 *   output) exempted from the check.
	 * @param corePathPrefix pathspec restricting the diff to the guarded directory; overridable
	 *   only for tests.
	 */
	fun evaluate(
		repoRoot: File,
		baselineRef: String,
		allowlistedPaths: Set<String>,
		corePathPrefix: String = "core/",
	): Result {
		val (ancestorExitCode, ancestorOutput) =
			runGit(repoRoot, "merge-base", "--is-ancestor", baselineRef, "HEAD")
		if (ancestorExitCode == 1) {
			val detail = if (ancestorOutput.isNotBlank()) " (git said: $ancestorOutput)" else ""
			return Result.Skipped(
				"baseline commit $baselineRef is not an ancestor of HEAD on this branch$detail. " +
					"This is expected on branches unrelated to the SP2c redesign; not failing the build.",
			)
		}
		check(ancestorExitCode == 0) {
			"Sp2cCoreGuard: 'git merge-base --is-ancestor' failed (exit $ancestorExitCode): $ancestorOutput"
		}

		val (diffExitCode, diffOutput) =
			runGit(repoRoot, "diff", "--name-only", "$baselineRef...HEAD", "--", corePathPrefix)
		check(diffExitCode == 0) {
			"Sp2cCoreGuard: 'git diff' failed (exit $diffExitCode): $diffOutput"
		}

		val changedFiles =
			diffOutput
				.lineSequence()
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.toList()
		val offendingFiles = changedFiles.filterNot { it in allowlistedPaths }

		return if (offendingFiles.isEmpty()) {
			Result.Passed(baselineRef)
		} else {
			Result.Violated(baselineRef, offendingFiles)
		}
	}

	private fun runGit(
		repoRoot: File,
		vararg args: String,
	): Pair<Int, String> {
		val process =
			ProcessBuilder(listOf("git") + args)
				.directory(repoRoot)
				.redirectErrorStream(true)
				.start()
		val output = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		return exitCode to output.trim()
	}
}
