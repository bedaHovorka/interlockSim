package cz.vutbr.fit.interlockSim.util

actual fun platformIdentityCode(obj: Any): String = obj.hashCode().toString()
