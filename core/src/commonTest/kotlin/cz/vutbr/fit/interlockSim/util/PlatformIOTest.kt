package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Cross-platform tests for [readTextFile], [writeTextFile], [deleteFile], [createDirectories] and
 * [directoryExists].
 *
 * These tests run on JVM (jvmTest) and linuxX64 (nativeTest) via commonTest.
 */
class PlatformIOTest {
	private val testFiles = mutableListOf<String>()

	/**
	 * Directories a test may create, parents first; removed deepest first with [deleteFile] (POSIX
	 * `remove` and `Files.deleteIfExists` both remove an empty directory).
	 */
	private val testDirectories = mutableListOf<String>()

	@AfterTest
	fun cleanUp() {
		testFiles.forEach { deleteFile(it) }
		testFiles.clear()
		testDirectories.reversed().forEach { deleteFile(it) }
		testDirectories.clear()
	}

	/** A fresh directory path [depth] levels below a new temp directory, every level registered for clean-up. */
	private fun tempDirectoryPath(
		suffix: String,
		depth: Int = 1
	): String {
		val base = "${tempDirectory().trimEnd('/', '\\')}/interlockSim-platform-io-dir-$suffix-${currentTimeMillisKMP()}"
		var path = base
		testDirectories += path
		repeat(depth - 1) { level ->
			path = "$path/level$level"
			testDirectories += path
		}
		return path
	}

	private fun tempPath(suffix: String): String {
		val dir = tempDirectory().trimEnd('/', '\\')
		val path = "$dir/interlockSim-platform-io-$suffix-${currentTimeMillisKMP()}.txt"
		testFiles += path
		return path
	}

	@Test
	fun `writeTextFile then readTextFile round-trips content`() {
		val path = tempPath("basic")
		val content = "hello, platform I/O!"
		writeTextFile(path, content)
		assertThat(readTextFile(path)).isEqualTo(content)
	}

	@Test
	fun `writeTextFile then readTextFile round-trips multiline content`() {
		val path = tempPath("multi")
		val content = "line1\nline2\nline3\n"
		writeTextFile(path, content)
		assertThat(readTextFile(path)).isEqualTo(content)
	}

	@Test
	fun `writeTextFile then readTextFile round-trips empty content`() {
		val path = tempPath("empty")
		writeTextFile(path, "")
		assertThat(readTextFile(path)).isEqualTo("")
	}

	@Test
	fun `writeTextFile overwrites existing content`() {
		val path = tempPath("overwrite")
		writeTextFile(path, "original content")
		writeTextFile(path, "new content")
		assertThat(readTextFile(path)).isEqualTo("new content")
	}

	@Test
	fun `readTextFile throws for non-existent file`() {
		val dir = tempDirectory().trimEnd('/', '\\')
		val path = "$dir/interlockSim-does-not-exist-${currentTimeMillisKMP()}.txt"
		assertFailsWith<IllegalStateException> { readTextFile(path) }
	}

	@Test
	fun `deleteFile removes file so it no longer exists`() {
		val path = tempPath("delete")
		writeTextFile(path, "some content")
		assertThat(fileExists(path)).isEqualTo(true)
		deleteFile(path)
		assertThat(fileExists(path)).isEqualTo(false)
		assertFailsWith<IllegalStateException> { readTextFile(path) }
	}

	@Test
	fun `fileExists returns false for non-existent path`() {
		val dir = tempDirectory().trimEnd('/', '\\')
		val path = "$dir/interlockSim-does-not-exist-${currentTimeMillisKMP()}.txt"
		assertThat(fileExists(path)).isEqualTo(false)
	}

	@Test
	fun `fileExists returns true for existing file`() {
		val path = tempPath("exists")
		writeTextFile(path, "content")
		assertThat(fileExists(path)).isEqualTo(true)
	}

	@Test
	fun `writeTextFile and readTextFile work with large content`() {
		val path = tempPath("large")
		val content = "A".repeat(1_500_000)
		writeTextFile(path, content)
		assertThat(readTextFile(path)).isEqualTo(content)
	}

	@Test
	fun `createDirectories creates every missing parent`() {
		val path = tempDirectoryPath("nested", depth = 3)
		assertThat(directoryExists(path)).isEqualTo(false)

		createDirectories(path)

		assertThat(directoryExists(path)).isEqualTo(true)
	}

	@Test
	fun `createDirectories accepts a directory that already exists`() {
		val path = tempDirectoryPath("existing")
		createDirectories(path)

		createDirectories(path)

		assertThat(directoryExists(path)).isEqualTo(true)
	}

	@Test
	fun `a file written into a created directory can be read back`() {
		val dir = tempDirectoryPath("with-file", depth = 2)
		createDirectories(dir)
		val path = "$dir/run.json"
		testFiles += path

		writeTextFile(path, "{}")

		assertThat(readTextFile(path)).isEqualTo("{}")
	}

	@Test
	fun `directoryExists is false for a regular file and for a missing path`() {
		val file = tempPath("not-a-dir")
		writeTextFile(file, "content")

		assertThat(directoryExists(file)).isEqualTo(false)
		assertThat(directoryExists("$file-missing")).isEqualTo(false)
	}

	@Test
	fun `createDirectories fails when a path component is a regular file`() {
		val file = tempPath("blocking-file")
		writeTextFile(file, "content")

		assertFailsWith<IllegalStateException> { createDirectories("$file/child") }
	}

	@Test
	fun `renameFile moves the content to the new path`() {
		val from = tempPath("rename-from")
		val to = tempPath("rename-to")
		writeTextFile(from, "moved")

		renameFile(from, to)

		assertThat(fileExists(from)).isEqualTo(false)
		assertThat(readTextFile(to)).isEqualTo("moved")
	}

	@Test
	fun `renameFile replaces an existing target`() {
		val from = tempPath("replace-from")
		val to = tempPath("replace-to")
		writeTextFile(to, "old")
		writeTextFile(from, "new")

		renameFile(from, to)

		assertThat(readTextFile(to)).isEqualTo("new")
	}

	@Test
	fun `renameFile fails for a missing source`() {
		val from = tempPath("rename-missing")

		assertFailsWith<IllegalStateException> { renameFile(from, "$from-target") }
	}
}
