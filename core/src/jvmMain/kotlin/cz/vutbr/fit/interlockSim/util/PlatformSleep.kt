package cz.vutbr.fit.interlockSim.util

actual fun platformSleep(millis: Long) {
	try {
		Thread.sleep(millis)
	} catch (_: InterruptedException) {
		Thread.currentThread().interrupt()
	}
}

actual fun Throwable.isPlatformInterrupt(): Boolean = this is InterruptedException
