package cz.vutbr.fit.interlockSim.util

/**
 * Suspends the current thread (or equivalent) for the specified number of milliseconds.
 * KMP expect/actual: JVM implementation uses Thread.sleep; other targets may use platform-specific APIs.
 */
expect fun platformSleep(millis: Long)

/**
 * Whether this is the platform's thread interrupt (JVM `InterruptedException`; no such type elsewhere): a
 * stop asked for from outside the simulation thread, not a failure of the code it interrupted (Issue #1361).
 */
expect fun Throwable.isPlatformInterrupt(): Boolean
