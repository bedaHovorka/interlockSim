@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cz.vutbr.fit.interlockSim.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove

actual fun readTextFile(path: String): String =
	memScoped {
		val file = fopen(path, "rb") ?: error("Cannot open file for reading: $path")
		try {
			check(fseek(file, 0L, SEEK_END) == 0) { "Cannot seek to end of file: $path" }
			val size = ftell(file)
			check(size >= 0L) { "Cannot determine size of file: $path" }
			check(size < Int.MAX_VALUE.toLong()) { "File too large to read into memory: $path (size=$size)" }
			check(fseek(file, 0L, SEEK_SET) == 0) { "Cannot seek to start of file: $path" }
			if (size == 0L) return@memScoped ""

			val len = size.toInt()
			val buffer = allocArray<ByteVar>(len + 1)
			val bytesRead = fread(buffer, 1.convert(), size.convert(), file)
			check(bytesRead.toLong() == size) {
				"Failed to read all bytes from file: $path (read ${bytesRead.toLong()} of $size)"
			}
			buffer[len] = 0
			buffer.toKString()
		} finally {
			fclose(file)
		}
	}

actual fun writeTextFile(
	path: String,
	content: String
) {
	val file = fopen(path, "w") ?: error("Cannot open file for writing: $path")
	try {
		val bytes = content.encodeToByteArray()
		if (bytes.isNotEmpty()) {
			val written = fwrite(bytes.toCValues(), 1.convert(), bytes.size.convert(), file)
			check(written.toInt() == bytes.size) {
				"Failed to write all bytes to file: $path (wrote ${written.toInt()} of ${bytes.size})"
			}
		}
	} finally {
		fclose(file)
	}
}

actual fun deleteFile(path: String) {
	remove(path)
}

actual fun fileExists(path: String): Boolean = platform.posix.access(path, platform.posix.F_OK) == 0

actual fun tempDirectory(): String = "/tmp"
