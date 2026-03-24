package cz.vutbr.fit.interlockSim.util

/**
 * Returns an identity-based hash code for the given object as a string (for debug logging).
 *
 * The returned value is identity-based (not derived from equals/hashCode). Two distinct objects
 * with equal content will typically produce different codes, but collisions are possible.
 *
 * - **JVM:** delegates to [System.identityHashCode]
 * - **Native:** delegates to [kotlin.native.identityHashCode]
 */
expect fun platformIdentityCode(obj: Any): String
