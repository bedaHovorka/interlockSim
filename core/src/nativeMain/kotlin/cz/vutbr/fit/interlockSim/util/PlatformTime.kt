package cz.vutbr.fit.interlockSim.util

import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillisKMP(): Long = memScoped {
	val ts = alloc<timespec>()
	val rc = clock_gettime(CLOCK_REALTIME, ts.ptr)
	if (rc != 0) error("clock_gettime failed with code $rc")
	ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
