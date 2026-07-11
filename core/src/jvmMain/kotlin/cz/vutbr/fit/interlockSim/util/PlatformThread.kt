package cz.vutbr.fit.interlockSim.util

import kotlinx.coroutines.runBlocking

actual fun platformStartDaemonThread(
	name: String,
	action: suspend () -> Unit
) {
	val t = Thread { runBlocking { action() } }
	t.isDaemon = true
	t.name = name
	t.start()
}
