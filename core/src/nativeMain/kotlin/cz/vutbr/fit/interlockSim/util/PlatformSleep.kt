package cz.vutbr.fit.interlockSim.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

actual fun platformSleep(millis: Long) {
	runBlocking { delay(millis) }
}
