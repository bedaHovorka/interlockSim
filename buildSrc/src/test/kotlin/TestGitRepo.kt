import java.io.File

/**
 * Minimal throwaway git repository helper for [Sp2cCoreGuardTest]. Wraps `git init`/`add`/
 * `commit`/`rev-parse`/`checkout --orphan` via [ProcessBuilder] so tests can build small commit
 * histories without shelling out repetitively inline.
 */
class TestGitRepo(private val dir: File) {
	init {
		git("init", "-q")
		git("config", "user.email", "sp2c-guard-test@example.com")
		git("config", "user.name", "Sp2cCoreGuardTest")
	}

	fun writeAndCommit(
		relativePath: String,
		content: String,
		message: String,
	) {
		val file = File(dir, relativePath)
		file.parentFile?.mkdirs()
		file.writeText(content)
		git("add", "-A")
		git("commit", "-q", "-m", message)
	}

	fun headRef(): String = git("rev-parse", "HEAD").trim()

	fun checkoutOrphan(branchName: String) {
		git("checkout", "-q", "--orphan", branchName)
	}

	private fun git(vararg args: String): String {
		val process =
			ProcessBuilder(listOf("git") + args)
				.directory(dir)
				.redirectErrorStream(true)
				.start()
		val output = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		check(exitCode == 0) { "git ${args.joinToString(" ")} failed (exit $exitCode): $output" }
		return output
	}
}
