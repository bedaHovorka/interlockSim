package cz.vutbr.fit.interlockSim.util

actual fun readTextFile(path: String): String =
	try {
		java.io.File(path).readText()
	} catch (e: java.io.IOException) {
		error("Cannot read file: $path (${e.message})")
	}

actual fun writeTextFile(path: String, content: String) {
	try {
		java.io.File(path).writeText(content)
	} catch (e: java.io.IOException) {
		error("Cannot write file: $path (${e.message})")
	}
}

actual fun deleteFile(path: String) {
	java.io.File(path).delete()
}
