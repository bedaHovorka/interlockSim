package cz.vutbr.fit.interlockSim.util

actual fun platformIdentityCode(obj: Any): String = System.identityHashCode(obj).toString()
