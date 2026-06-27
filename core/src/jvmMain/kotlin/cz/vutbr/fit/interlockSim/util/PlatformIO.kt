package cz.vutbr.fit.interlockSim.util

actual fun readTextFile(path: String): String =
	try {
		java.io.File(path).readText()
	} catch (e: java.io.IOException) {
		throw IllegalStateException("Cannot read file: $path (${e.message})", e)
	}

actual fun writeTextFile(
	path: String,
	content: String
) {
	try {
		val file = java.io.File(path)
		file.parentFile?.mkdirs()
		file.writeText(content)
	} catch (e: java.io.IOException) {
		throw IllegalStateException("Cannot write file: $path (${e.message})", e)
	}
}

actual fun deleteFile(path: String) {
	try {
		java.nio.file.Files
			.deleteIfExists(
				java.nio.file.Paths
					.get(path)
			)
	} catch (e: java.io.IOException) {
		throw IllegalStateException("Cannot delete file: $path (${e.message})", e)
	}
}

actual fun fileExists(path: String): Boolean = java.io.File(path).exists()

actual fun tempDirectory(): String = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() } ?: "/tmp"
