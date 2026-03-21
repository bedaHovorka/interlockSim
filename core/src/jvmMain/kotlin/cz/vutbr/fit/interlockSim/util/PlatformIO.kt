package cz.vutbr.fit.interlockSim.util

actual fun readTextFile(path: String): String {
	return try {
		java.io.File(path).readText()
	} catch (e: java.io.IOException) {
		throw IllegalStateException("Cannot read file: $path (${e.message})", e)
	}
}

actual fun writeTextFile(path: String, content: String) {
	try {
		java.io.File(path).writeText(content)
	} catch (e: java.io.IOException) {
		throw IllegalStateException("Cannot write file: $path (${e.message})", e)
	}
}

actual fun deleteFile(path: String) {
	java.io.File(path).delete()
}
