package cz.vutbr.fit.interlockSim.util

/**
 * Suspends the current thread (or equivalent) for the specified number of milliseconds.
 * KMP expect/actual: JVM implementation uses Thread.sleep; other targets may use platform-specific APIs.
 */
expect fun platformSleep(millis: Long)
