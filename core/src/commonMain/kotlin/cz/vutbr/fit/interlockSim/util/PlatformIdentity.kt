package cz.vutbr.fit.interlockSim.util

/** Returns a platform-specific identity code for the given object (for debug logging). */
expect fun platformIdentityCode(obj: Any): String
