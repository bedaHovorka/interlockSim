package cz.vutbr.fit.interlockSim.util

/**
 * Returns an identity-based hash code for the given object as a string (for debug logging).
 *
 * The returned value is identity-based (not derived from equals/hashCode). Two distinct objects
 * with equal content will typically produce different codes, but collisions are possible.
 *
 * - **JVM:** delegates to [System.identityHashCode]
 * - **Native:** delegates to [kotlin.native.identityHashCode]
 *
 * **Do not use this as a unique identifier** (e.g. as a map/scope key). Identity hash codes live
 * in a 31-bit space, so collisions become likely at moderate object counts (birthday bound) -
 * this is for human-readable debug logging only. See Issue #757.
 */
expect fun platformIdentityCode(obj: Any): String
