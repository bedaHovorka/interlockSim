/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat
import platform.posix.umask
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The POSIX side of [writeTextFile] and [createDirectories] that the common tests cannot see
 * (Issue #1327 review round).
 */
class NativePlatformIOTest {
	private val createdDirectories = mutableListOf<String>()

	@AfterTest
	fun cleanUp() {
		createdDirectories.reversed().forEach { deleteFile(it) }
		createdDirectories.clear()
	}

	@Test
	fun `writeTextFile fails when the data cannot be flushed`() {
		// /dev/full accepts the buffered fwrite and fails the flush in fclose with ENOSPC.
		assertFailsWith<IllegalStateException> { writeTextFile("/dev/full", "content") }
	}

	@Test
	fun `createDirectories asks for 0777 and leaves the narrowing to the umask`() {
		val path = "${tempDirectory().trimEnd('/')}/interlockSim-native-mode-${currentTimeMillisKMP()}"
		createdDirectories += path
		val previous = umask(0u)
		try {
			createDirectories(path)
		} finally {
			umask(previous)
		}

		val mode =
			memScoped {
				val status = alloc<stat>()
				check(stat(path, status.ptr) == 0) { "stat $path" }
				status.st_mode and PERMISSION_BITS
			}
		assertThat(mode).isEqualTo(PERMISSION_BITS)
	}

	private companion object {
		/** Octal 0777: the permission bits of a mode. */
		const val PERMISSION_BITS: UInt = 511u
	}
}
