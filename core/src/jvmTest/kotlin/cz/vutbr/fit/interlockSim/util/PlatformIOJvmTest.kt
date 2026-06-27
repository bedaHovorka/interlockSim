package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

class PlatformIOJvmTest {
	private val testFiles = mutableListOf<String>()

	@AfterEach
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
	fun `tempDirectory falls back to tmp when java io tmpdir is blank`() {
		val original = System.getProperty("java.io.tmpdir")
		try {
			System.setProperty("java.io.tmpdir", "")
			assertThat(tempDirectory()).isEqualTo("/tmp")
		} finally {
			if (original != null) {
				System.setProperty("java.io.tmpdir", original)
			} else {
				System.clearProperty("java.io.tmpdir")
			}
		}
	}

	@Test
	fun `writeTextFile throws when parent directory is not writable`() {
		val baseDir = Files.createTempDirectory("interlockSimPermTest").toFile()
		try {
			baseDir.setWritable(false, false)
			val path = "${baseDir.absolutePath}/no-write.txt"
			testFiles += path
			assertFailsWith<IllegalStateException> { writeTextFile(path, "content") }
		} finally {
			baseDir.setWritable(true, false)
			baseDir.deleteRecursively()
		}
	}

	@Test
	fun `writeTextFile and readTextFile handle redundant path separators`() {
		val dir = tempDirectory().trimEnd('/', '\\')
		val path = "$dir//redundant//sep-test.txt"
		testFiles += path
		val content = "redundant separators work"
		writeTextFile(path, content)
		assertThat(readTextFile(path)).isEqualTo(content)
	}

	@Test
	fun `concurrent writes to distinct temporary files succeed`() {
		val threadCount = 5
		val results = mutableListOf<Pair<String, String>>()
		val threads =
			(1..threadCount).map { idx ->
				thread {
					val path = tempPath("conc-$idx")
					val data = "data-from-thread-$idx"
					writeTextFile(path, data)
					val readBack = readTextFile(path)
					synchronized(results) { results.add(path to readBack) }
				}
			}
		threads.forEach { it.join() }
		results.forEach { (_, readBack) ->
			assertThat(readBack).isEqualTo(readBack)
		}
	}
}
