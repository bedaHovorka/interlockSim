package cz.vutbr.fit.interlockSim.util

/** Returns the current time in milliseconds since the Unix epoch. Platform-specific. */
expect fun currentTimeMillisKMP(): Long

/** Returns the next scheduled event time for the given Simulation, or Double.MAX_VALUE if none. Platform-specific. */
expect fun getNextScheduledEventTime(sim: cz.hovorka.kdisco.Simulation): Double

