package cz.vutbr.fit.interlockSim.util

/** Returns the current time in milliseconds since the Unix epoch. Platform-specific. */
expect fun currentTimeMillisKMP(): Long

/**
 * Returns a monotonic clock reading in nanoseconds, for measuring elapsed wall time; only the
 * difference of two readings has a meaning. Platform-specific: `System.nanoTime()` on the JVM,
 * `clock_gettime(CLOCK_MONOTONIC)` on native. Unlike [currentTimeMillisKMP] it never jumps when the
 * system clock is set.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
expect fun monotonicNanosKMP(): Long
