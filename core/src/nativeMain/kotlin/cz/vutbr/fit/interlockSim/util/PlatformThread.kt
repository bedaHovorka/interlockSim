package cz.vutbr.fit.interlockSim.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Native (linuxX64) actual: runs [action] on a dedicated background thread via a
 * single-threaded coroutine dispatcher named [name].
 *
 * Daemon semantics match the JVM actual: a Kotlin/Native process exits when `main`
 * returns regardless of live worker threads, so the driver loop never blocks
 * process shutdown. The dispatcher is intentionally not closed — it lives for the
 * driver's lifetime, exactly like the JVM daemon thread.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
actual fun platformStartDaemonThread(
	name: String,
	action: suspend () -> Unit
) {
	val dispatcher = newSingleThreadContext(name)
	CoroutineScope(dispatcher).launch { action() }
}
