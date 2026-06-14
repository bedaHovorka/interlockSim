package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Cross-platform tests for [readTextFile], [writeTextFile], and [deleteFile].
 *
 * These tests run on JVM (jvmTest) and linuxX64 (nativeTest) via commonTest.
 */
class PlatformIOTest {
	private val testFiles = mutableListOf<String>()

	@AfterTest
	fun cleanUp() {
		testFiles.forEach { deleteFile(it) }
		testFiles.clear()
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
}
