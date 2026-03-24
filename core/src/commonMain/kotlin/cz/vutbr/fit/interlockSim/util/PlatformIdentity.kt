package cz.vutbr.fit.interlockSim.util

/**
 * Returns an identity-based hash code for the given object as a string (for debug logging).
 *
 * The returned value is unique per object **instance**, not per value. Two distinct objects
 * with equal content (e.g., two equal data class instances) will generally return different codes.
 *
 * - **JVM:** delegates to [System.identityHashCode]
 * - **Native:** delegates to [kotlin.native.identityHashCode]
 */
expect fun platformIdentityCode(obj: Any): String
