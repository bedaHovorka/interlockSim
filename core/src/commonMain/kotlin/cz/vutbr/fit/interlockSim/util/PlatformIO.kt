package cz.vutbr.fit.interlockSim.util

/**
 * Reads the entire contents of a text file as a UTF-8 String.
 *
 * KMP expect/actual: JVM uses [java.io.File]; native uses POSIX fopen/fread.
 *
 * @param path Absolute or relative file path
 * @return File contents as a UTF-8 string
 * @throws IllegalStateException if the file cannot be opened or read
 */
expect fun readTextFile(path: String): String

/**
 * Writes a String to a text file in UTF-8, overwriting any existing content.
 *
 * KMP expect/actual: JVM uses [java.io.File]; native uses POSIX fopen/fwrite.
 *
 * @param path Absolute or relative file path
 * @param content Content to write
 * @throws IllegalStateException if the file cannot be written
 */
expect fun writeTextFile(
	path: String,
	content: String
)

/**
 * Deletes the file at the given path.
 *
 * KMP expect/actual: JVM uses [java.io.File.delete]; native uses POSIX remove.
 * No-ops silently if the file does not exist.
 *
 * @param path Absolute or relative file path
 */
expect fun deleteFile(path: String)

/**
 * Checks whether a file exists at the given path.
 *
 * KMP expect/actual: JVM uses [java.io.File.exists]; native uses POSIX access(F_OK).
 *
 * @param path Absolute or relative file path
 * @return true if the file exists, false otherwise
 */
expect fun fileExists(path: String): Boolean

/**
 * Returns the platform-specific temporary directory.
 *
 * KMP expect/actual: JVM uses [java.io.File.tmpDir]; native uses "/tmp".
 *
 * @return Absolute path to a writable temporary directory, without trailing separator.
 */
expect fun tempDirectory(): String
