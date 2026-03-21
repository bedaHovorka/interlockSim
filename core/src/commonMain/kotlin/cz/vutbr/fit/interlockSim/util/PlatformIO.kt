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
expect fun writeTextFile(path: String, content: String)

/**
 * Deletes the file at the given path.
 *
 * KMP expect/actual: JVM uses [java.io.File.delete]; native uses POSIX remove.
 * No-ops silently if the file does not exist.
 *
 * @param path Absolute or relative file path
 */
expect fun deleteFile(path: String)
