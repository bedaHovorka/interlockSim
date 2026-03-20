package cz.vutbr.fit.interlockSim.util

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

@OptIn(ExperimentalNativeApi::class)
actual fun platformIdentityCode(obj: Any): String = obj.identityHashCode().toString()
